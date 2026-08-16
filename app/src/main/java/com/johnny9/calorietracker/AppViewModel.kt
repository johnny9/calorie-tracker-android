package com.johnny9.calorietracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnny9.calorietracker.data.ActivityDailyEntity
import com.johnny9.calorietracker.data.DayStateEntity
import com.johnny9.calorietracker.data.DiaryEntryEntity
import com.johnny9.calorietracker.data.FastingPeriodEntity
import com.johnny9.calorietracker.data.FoodEntity
import com.johnny9.calorietracker.data.TargetPlanEntity
import com.johnny9.calorietracker.domain.DailyPoint
import com.johnny9.calorietracker.domain.DayCompleteness
import com.johnny9.calorietracker.domain.Nutrients
import com.johnny9.calorietracker.domain.RecipeSummary
import com.johnny9.calorietracker.domain.RollingResult
import com.johnny9.calorietracker.domain.RollingWindowCalculator
import com.johnny9.calorietracker.domain.TargetCalculator
import com.johnny9.calorietracker.domain.TargetInput
import com.johnny9.calorietracker.domain.fromMilli
import com.johnny9.calorietracker.export.CsvExportService
import com.johnny9.calorietracker.health.HealthConnectManager
import com.johnny9.calorietracker.health.HealthConnectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

data class MacroGoals(
    val proteinGram: Double = 0.0,
    val carbsGram: Double = 0.0,
    val fatGram: Double = 0.0,
)

data class TodayUiState(
    val entries: List<DiaryEntryEntity> = emptyList(),
    val nutrients: Nutrients = Nutrients(),
    val activeMilliKcal: Long = 0,
    val targetMilliKcal: Long = 0,
    val isDayComplete: Boolean = false,
    val completeness: DayCompleteness = DayCompleteness.MISSING,
    val macroGoals: MacroGoals = MacroGoals(),
    val targetConfigured: Boolean = false,
) {
    val intakeMilliKcal get() = nutrients.caloriesMilliKcal
    val netMilliKcal get() = intakeMilliKcal - activeMilliKcal
    val remainingMilliKcal get() = targetMilliKcal - intakeMilliKcal
}

data class TrendsUiState(
    val lastSeven: List<DailyPoint> = emptyList(),
    val rolling: RollingResult = RollingResult(null, 0, 0, 0, 7),
    val windowDays: Int = 7,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalorieTrackerApplication
    private val repository = app.repository
    private val exporter = CsvExportService(application, repository)
    private val healthManager = HealthConnectManager(application, repository)

    val selectedDate = MutableStateFlow(LocalDate.now())
    val rollingWindowDays = MutableStateFlow(7)
    val message = MutableStateFlow<String?>(null)
    val isWorking = MutableStateFlow(false)

    val foods: StateFlow<List<FoodEntity>> = repository.foods.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val recipes: StateFlow<List<RecipeSummary>> = repository.recipes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val target: StateFlow<TargetPlanEntity?> = repository.target.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val fasts: StateFlow<List<FastingPeriodEntity>> = repository.fasts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val healthState: StateFlow<HealthConnectState> = healthManager.state

    private val entries = selectedDate.flatMapLatest(repository::observeDiary)
    private val dayState = selectedDate.flatMapLatest(repository::observeDayState)
    private val activity = selectedDate.flatMapLatest(repository::observeActivity)

    val today: StateFlow<TodayUiState> = combine(entries, dayState, activity, target) { rows, state, activityRow, plan ->
        val nutrients = rows.fold(Nutrients()) { sum, row -> sum + row.nutrients() }
        val activityKnown = plan?.useHealthConnect != true || (activityRow?.isKnown == true && !activityRow.isStale)
        val completeness = completeness(rows, state, activityRow, activityKnown)
        TodayUiState(
            entries = rows,
            nutrients = nutrients,
            activeMilliKcal = if (plan?.useHealthConnect == true) activityRow?.activeCaloriesMilliKcal ?: 0 else 0,
            targetMilliKcal = plan?.targetCaloriesMilliKcal ?: 0,
            isDayComplete = state?.isComplete == true,
            completeness = completeness,
            macroGoals = plan?.macroGoals() ?: MacroGoals(),
            targetConfigured = plan?.isConfigured == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    private val trendStartEnd = selectedDate.flatMapLatest { end ->
        val start = end.minusDays(29)
        combine(
            repository.observeDiaryRange(start, end),
            repository.observeDayStatesRange(start, end),
            repository.observeActivityRange(start, end),
        ) { diary, states, activity -> Triple(diary, states, activity) }
    }

    val trends: StateFlow<TrendsUiState> = combine(
        trendStartEnd,
        target,
        selectedDate,
        rollingWindowDays,
    ) { (diary, states, activities), plan, endDate, window ->
        val stateByDate = states.associateBy(DayStateEntity::localDate)
        val activityByDate = activities.associateBy(ActivityDailyEntity::localDate)
        val entriesByDate = diary.groupBy(DiaryEntryEntity::localDate)
        val start = endDate.minusDays(29)
        val points = (0L..29L).map { offset ->
            val date = start.plusDays(offset)
            val rows = entriesByDate[date.toString()].orEmpty()
            val state = stateByDate[date.toString()]
            val activityRow = activityByDate[date.toString()]
            val intake = rows.sumOf(DiaryEntryEntity::caloriesMilliKcal)
            val activityKnown = plan?.useHealthConnect != true || (activityRow?.isKnown == true && !activityRow.isStale)
            DailyPoint(
                date = date,
                intakeMilliKcal = intake,
                activeMilliKcal = if (plan?.useHealthConnect == true) activityRow?.activeCaloriesMilliKcal ?: 0 else 0,
                completeness = completeness(rows, state, activityRow, activityKnown),
            )
        }
        val trackingStart = plan?.trackingStartDate?.let(LocalDate::parse) ?: endDate
        TrendsUiState(
            lastSeven = points.takeLast(7),
            rolling = RollingWindowCalculator.calculate(points, endDate, trackingStart, window),
            windowDays = window,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsUiState())

    init {
        viewModelScope.launch {
            repository.initialize()
            healthManager.refreshStatus()
        }
    }

    fun moveDate(days: Long) {
        selectedDate.value = selectedDate.value.plusDays(days)
    }

    fun setRollingWindow(days: Int) {
        rollingWindowDays.value = days.coerceIn(3, 30)
    }

    fun createFood(
        name: String,
        serving: String,
        grams: Double?,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double,
    ) = launchAction("Custom food saved") {
        repository.createCustomFood(name, serving, grams, calories, protein, carbs, fat, fiber)
    }

    fun createRecipe(name: String, servings: Double, quantities: Map<String, Double>) = launchAction("Recipe saved") {
        repository.createRecipe(name, servings, quantities)
    }

    fun archiveFood(id: String) = launchAction("Food archived") { repository.archiveFood(id) }
    fun archiveRecipe(id: String) = launchAction("Recipe archived") { repository.archiveRecipe(id) }

    fun logFood(foodId: String, meal: String, quantity: Double) = launchAction("Food logged") {
        repository.logFood(selectedDate.value, meal, foodId, quantity)
    }

    fun logRecipe(recipeId: String, meal: String, quantity: Double) = launchAction("Recipe logged") {
        repository.logRecipe(selectedDate.value, meal, recipeId, quantity)
    }

    fun deleteEntry(id: String) = launchAction("Entry removed") { repository.deleteDiaryEntry(id) }

    fun toggleDayComplete() = launchAction(if (today.value.isDayComplete) "Day reopened" else "Day marked complete") {
        val complete = !today.value.isDayComplete
        repository.setDayComplete(
            selectedDate.value,
            complete,
            intentionalZero = complete && today.value.intakeMilliKcal == 0L,
        )
    }

    fun saveTarget(
        input: TargetInput,
        fixedMode: Boolean,
        macroMode: String,
        protein: Double,
        carbs: Double,
        fat: Double,
        useHealthConnect: Boolean,
    ) = launchAction("Targets saved") {
        repository.saveTarget(
            input = input,
            fixedMode = fixedMode,
            macroMode = macroMode,
            proteinTarget = protein,
            carbsTarget = carbs,
            fatTarget = fat,
            useHealthConnect = useHealthConnect,
            existingTrackingStart = target.value?.trackingStartDate?.let(LocalDate::parse),
            zoneId = ZoneId.systemDefault(),
        )
    }

    fun onHealthPermissionResult() = viewModelScope.launch {
        healthManager.refreshStatus()
        if (healthState.value.hasPermission) syncHealth()
    }

    fun syncHealth() = launchAction("Activity synced") {
        val zone = target.value?.homeTimeZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        healthManager.sync(selectedDate.value, zone).getOrThrow()
    }

    fun startFast(hours: Int?) = launchAction("Fast started") { repository.startFast(hours) }
    fun endFast(id: String) = launchAction("Fast ended") { repository.endFast(id) }

    fun export(uri: Uri) = launchAction("Export complete") { exporter.export(uri) }

    fun clearMessage() {
        message.value = null
    }

    private fun launchAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            isWorking.value = true
            message.value = runCatching { block() }.fold(
                onSuccess = { success },
                onFailure = { it.message ?: "Something went wrong" },
            )
            isWorking.value = false
        }
    }
}

private fun DiaryEntryEntity.nutrients() = Nutrients(
    caloriesMilliKcal,
    proteinMilliGram,
    carbsMilliGram,
    fatMilliGram,
    fiberMilliGram,
)

private fun completeness(
    rows: List<DiaryEntryEntity>,
    state: DayStateEntity?,
    activity: ActivityDailyEntity?,
    activityKnown: Boolean,
): DayCompleteness = when {
    state?.isComplete == true && activityKnown && state.intentionalZero && rows.sumOf(DiaryEntryEntity::caloriesMilliKcal) == 0L -> DayCompleteness.FASTED_ZERO
    state?.isComplete == true && activityKnown -> DayCompleteness.COMPLETE
    rows.isNotEmpty() || state != null || activity != null -> DayCompleteness.PARTIAL
    else -> DayCompleteness.MISSING
}

private fun TargetPlanEntity.macroGoals(): MacroGoals {
    if (macroMode == "GRAMS") return MacroGoals(proteinTarget, carbsTarget, fatTarget)
    val (protein, carbs, fat) = TargetCalculator.percentageMacroGrams(
        targetCaloriesMilliKcal.fromMilli(),
        proteinTarget,
        carbsTarget,
        fatTarget,
    )
    return MacroGoals(protein, carbs, fat)
}
