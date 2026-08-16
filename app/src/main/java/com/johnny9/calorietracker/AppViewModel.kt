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
import com.johnny9.calorietracker.data.usda.UsdaCatalogSource
import com.johnny9.calorietracker.data.usda.UsdaFoodSummary
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
import com.johnny9.calorietracker.foodlookup.OnlineFoodCandidate
import com.johnny9.calorietracker.health.HealthConnectManager
import com.johnny9.calorietracker.health.HealthConnectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val activeMilliKcal: Long? = 0,
    val targetMilliKcal: Long = 0,
    val isDayComplete: Boolean = false,
    val completeness: DayCompleteness = DayCompleteness.MISSING,
    val macroGoals: MacroGoals = MacroGoals(),
    val targetConfigured: Boolean = false,
) {
    val intakeMilliKcal get() = nutrients.caloriesMilliKcal
    val netMilliKcal get() = activeMilliKcal?.let { intakeMilliKcal - it }
    val remainingMilliKcal get() = targetMilliKcal - intakeMilliKcal
}

data class TrendsUiState(
    val lastSeven: List<DailyPoint> = emptyList(),
    val rolling: RollingResult = RollingResult(null, 0, 0, 0, 7),
    val windowDays: Int = 7,
)

data class OnlineFoodSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<OnlineFoodCandidate> = emptyList(),
    val importingBarcode: String? = null,
    val error: String? = null,
)

data class UsdaFoodSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val source: UsdaCatalogSource? = null,
    val results: List<UsdaFoodSummary> = emptyList(),
    val importingFdcId: Long? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CalorieTrackerApplication
    private val repository = app.repository
    private val foodLookup = app.foodLookup
    private val usdaCatalog = app.usdaCatalog
    private val exporter = CsvExportService(application, repository)
    private val healthManager = HealthConnectManager(application, repository)

    val selectedDate = MutableStateFlow(LocalDate.now())
    val rollingWindowDays = MutableStateFlow(7)
    val message = MutableStateFlow<String?>(null)
    val isWorking = MutableStateFlow(false)
    val onlineFoodSearch = MutableStateFlow(OnlineFoodSearchUiState())
    val usdaFoodSearch = MutableStateFlow(UsdaFoodSearchUiState())
    val onlineFoodLookupAvailable: Boolean get() = foodLookup.isAvailable
    private var onlineSearchJob: Job? = null
    private var usdaSearchJob: Job? = null
    private var usdaImportJob: Job? = null

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
            activeMilliKcal = when {
                plan?.useHealthConnect != true -> 0
                activityKnown -> activityRow?.activeCaloriesMilliKcal
                else -> null
            },
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
                activeMilliKcal = when {
                    plan?.useHealthConnect != true -> 0
                    activityKnown -> activityRow?.activeCaloriesMilliKcal
                    else -> null
                },
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

    fun searchOnlineFoods(rawQuery: String) {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ")
        onlineSearchJob?.cancel()
        onlineSearchJob = viewModelScope.launch {
            onlineFoodSearch.value = OnlineFoodSearchUiState(query = query, isSearching = true)
            try {
                val results = foodLookup.search(query)
                onlineFoodSearch.value = OnlineFoodSearchUiState(query = query, results = results)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onlineFoodSearch.value = OnlineFoodSearchUiState(
                    query = query,
                    error = error.message ?: "Online food search failed",
                )
            }
        }
    }

    fun searchUsdaFoods(rawQuery: String) {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ").take(80)
        usdaSearchJob?.cancel()
        if (query.length < 2) {
            usdaFoodSearch.value = UsdaFoodSearchUiState(query = query)
            return
        }
        usdaSearchJob = viewModelScope.launch {
            usdaFoodSearch.value = UsdaFoodSearchUiState(query = query, isSearching = true)
            try {
                delay(150)
                val result = usdaCatalog.search(query)
                usdaFoodSearch.value = UsdaFoodSearchUiState(
                    query = query,
                    source = result.source,
                    results = result.foods,
                    error = result.error,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                usdaFoodSearch.value = UsdaFoodSearchUiState(
                    query = query,
                    error = error.message ?: "The offline USDA catalog could not be searched",
                )
            }
        }
    }

    fun saveUsdaFood(fdcId: Long) = importUsdaFood(fdcId, "Food saved for offline use") { _, _ -> }

    fun logUsdaFood(fdcId: Long, meal: String, quantity: Double) = importUsdaFood(fdcId, "Food logged") { foodId, _ ->
        repository.logFood(selectedDate.value, meal, foodId, quantity)
    }

    private fun importUsdaFood(
        fdcId: Long,
        success: String,
        afterCache: suspend (foodId: String, inserted: Boolean) -> Unit,
    ) {
        if (usdaImportJob?.isActive == true) return
        usdaImportJob = viewModelScope.launch {
            usdaFoodSearch.value = usdaFoodSearch.value.copy(importingFdcId = fdcId, error = null)
            try {
                val record = usdaCatalog.food(fdcId)
                val inserted = repository.cacheUsdaFood(record)
                afterCache("usda:$fdcId", inserted)
                message.value = if (success == "Food saved for offline use" && !inserted) {
                    "Saved food refreshed for offline use"
                } else {
                    success
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = error.message ?: "Unable to import that USDA food"
                usdaFoodSearch.value = usdaFoodSearch.value.copy(error = detail)
                message.value = detail
            } finally {
                usdaFoodSearch.value = usdaFoodSearch.value.copy(importingFdcId = null)
            }
        }
    }

    fun saveOnlineFood(barcode: String) {
        if (onlineFoodSearch.value.importingBarcode != null) return
        viewModelScope.launch {
            onlineFoodSearch.value = onlineFoodSearch.value.copy(importingBarcode = barcode, error = null)
            try {
                val product = foodLookup.product(barcode)
                val inserted = repository.cacheOnlineFood(product)
                message.value = if (inserted) "Food saved for offline use" else "Saved food refreshed for offline use"
                onlineFoodSearch.value = onlineFoodSearch.value.copy(importingBarcode = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onlineFoodSearch.value = onlineFoodSearch.value.copy(
                    importingBarcode = null,
                    error = error.message ?: "Unable to save that food",
                )
            }
        }
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

    fun syncHealth() = launchAction("Last 30 days of activity synced") {
        val zone = target.value?.homeTimeZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val end = selectedDate.value
        healthManager.syncRange(end.minusDays(29), end, zone).getOrThrow()
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
