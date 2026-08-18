package com.johnny9.calorietracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
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
    val hasRestingPermission: Boolean = false,
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
            runCatching { client().permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val hasActivePermission = ACTIVE_CALORIES_PERMISSION in granted
        val hasRestingPermission = RESTING_CALORIES_PERMISSION in granted
        mutableState.value = HealthConnectState(
            availability = availability,
            hasPermission = hasActivePermission,
            hasRestingPermission = hasRestingPermission,
            message = when {
                availability == HealthAvailability.UPDATE_REQUIRED -> "Install or update Health Connect to import energy data"
                availability == HealthAvailability.UNAVAILABLE -> "Health Connect is unavailable on this device"
                hasActivePermission && hasRestingPermission -> "Active and resting-calorie access granted"
                hasActivePermission -> "Active access granted; app BMR will be used until resting access is granted"
                hasRestingPermission -> "Resting access granted; active-calorie access is still needed"
                else -> "Permission is optional; food tracking works without it"
            },
        )
    }

    suspend fun syncRange(startDate: LocalDate, endDate: LocalDate, zoneId: ZoneId): Result<Int> {
        require(!startDate.isAfter(endDate)) { "Activity sync start must not be after its end" }
        refreshStatus()
        if (!state.value.hasPermission) return Result.failure(IllegalStateException("Health Connect permission is not granted"))
        return runCatching {
            var current = startDate
            var synced = 0
            while (!current.isAfter(endDate)) {
                syncDay(current, zoneId)
                synced += 1
                current = current.plusDays(1)
            }
            synced
        }.onFailure {
            mutableState.value = state.value.copy(message = "Activity sync failed: ${it.message ?: "unknown error"}")
        }
    }

    private suspend fun syncDay(date: LocalDate, zoneId: ZoneId) {
        runCatching {
            val start = date.atStartOfDay(zoneId).toInstant()
            val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val activeMetric = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
            val restingMetric = BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL
            val result = client().aggregate(
                AggregateRequest(
                    metrics = if (state.value.hasRestingPermission) {
                        setOf(activeMetric, restingMetric)
                    } else {
                        setOf(activeMetric)
                    },
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )
            // A successful aggregate with no contributing record is Health
            // Connect's known-zero result for this interval.
            val activeCalories = result[activeMetric]?.inKilocalories ?: 0.0
            // Unlike active energy, an absent basal aggregate is not a known
            // zero. Preserve it as missing so the app can use its BMR fallback.
            val restingCalories = result[restingMetric]
                ?.inKilocalories
                ?.takeIf { it.isFinite() && it > 0.0 }
            repository.upsertActivity(
                date = date,
                activeCaloriesKcal = activeCalories,
                restingCaloriesKcal = restingCalories,
                source = "HEALTH_CONNECT",
                known = true,
            )
        }.onFailure {
            repository.markActivitySyncFailed(date)
        }.getOrThrow()
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
        val RESTING_CALORIES_PERMISSION: String =
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
        val REQUIRED_PERMISSIONS: Set<String> = setOf(ACTIVE_CALORIES_PERMISSION, RESTING_CALORIES_PERMISSION)
    }
}
