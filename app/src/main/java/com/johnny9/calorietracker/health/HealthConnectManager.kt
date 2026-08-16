package com.johnny9.calorietracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.johnny9.calorietracker.data.TrackerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

enum class HealthAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

data class HealthConnectState(
    val availability: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val hasPermission: Boolean = false,
    val message: String = "Health Connect status has not been checked",
)

class HealthConnectManager(
    private val context: Context,
    private val repository: TrackerRepository,
) {
    private val mutableState = MutableStateFlow(HealthConnectState())
    val state: StateFlow<HealthConnectState> = mutableState.asStateFlow()

    suspend fun refreshStatus() {
        val availability = availability()
        val granted = if (availability == HealthAvailability.AVAILABLE) {
            runCatching { client().permissionController.getGrantedPermissions().contains(ACTIVE_CALORIES_PERMISSION) }
                .getOrDefault(false)
        } else {
            false
        }
        mutableState.value = HealthConnectState(
            availability = availability,
            hasPermission = granted,
            message = when {
                availability == HealthAvailability.UPDATE_REQUIRED -> "Install or update Health Connect to import activity"
                availability == HealthAvailability.UNAVAILABLE -> "Health Connect is unavailable on this device"
                granted -> "Active-calorie access granted"
                else -> "Permission is optional; food tracking works without it"
            },
        )
    }

    suspend fun sync(date: LocalDate, zoneId: ZoneId): Result<Double> {
        refreshStatus()
        if (!state.value.hasPermission) return Result.failure(IllegalStateException("Health Connect permission is not granted"))
        return runCatching {
            val start = date.atStartOfDay(zoneId).toInstant()
            val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val metric = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
            val result = client().aggregate(
                AggregateRequest(
                    metrics = setOf(metric),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            // A successful aggregate with no contributing record is Health
            // Connect's known-zero result for this interval.
            val calories = result[metric]?.inKilocalories ?: 0.0
            repository.upsertActivity(date, calories, "HEALTH_CONNECT", known = true)
            calories
        }.onFailure {
            repository.markActivitySyncFailed(date)
            mutableState.value = state.value.copy(message = "Activity sync failed: ${it.message ?: "unknown error"}")
        }
    }

    private fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.UNAVAILABLE
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    companion object {
        val ACTIVE_CALORIES_PERMISSION: String =
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val REQUIRED_PERMISSIONS: Set<String> = setOf(ACTIVE_CALORIES_PERMISSION)
    }
}
