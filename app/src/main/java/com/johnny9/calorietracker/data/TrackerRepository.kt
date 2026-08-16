package com.johnny9.calorietracker.data

import androidx.room.withTransaction
import com.johnny9.calorietracker.domain.Nutrients
import com.johnny9.calorietracker.domain.RecipeSummary
import com.johnny9.calorietracker.domain.TargetCalculator
import com.johnny9.calorietracker.domain.TargetInput
import com.johnny9.calorietracker.domain.toMilli
import com.johnny9.calorietracker.data.usda.UsdaFoodRecord
import com.johnny9.calorietracker.foodlookup.OnlineFoodProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class TrackerRepository(private val database: TrackerDatabase) {
    private val dao = database.trackerDao()

    val foods: Flow<List<FoodEntity>> = dao.observeFoods()
    val recipes: Flow<List<RecipeSummary>> = combine(
        dao.observeRecipes(),
        dao.observeRecipeIngredients(),
    ) { recipes, ingredients ->
        recipes.map { recipe ->
            val rows = ingredients.filter { it.recipeId == recipe.id }
            val total = rows.fold(Nutrients()) { accumulator, row ->
                accumulator + row.nutrientsPerServing() * row.quantity
            }
            RecipeSummary(
                id = recipe.id,
                name = recipe.name,
                servings = recipe.servings,
                nutrientsPerServing = total / recipe.servings,
                ingredientCount = rows.size,
            )
        }
    }
    val target: Flow<TargetPlanEntity?> = dao.observeTarget()
    val fasts: Flow<List<FastingPeriodEntity>> = dao.observeFasts()

    suspend fun initialize() {
        val now = System.currentTimeMillis()
        dao.insertFoods(BundledFoods.rows(now))
        if (dao.allTargets().isEmpty()) {
            val today = LocalDate.now()
            dao.upsertTarget(
                TargetPlanEntity(
                    effectiveFrom = today.toString(),
                    trackingStartDate = today.toString(),
                    homeTimeZoneId = ZoneId.systemDefault().id,
                    isConfigured = false,
                    ageYears = 35,
                    heightMilliCm = 170.0.toMilli(),
                    weightMilliKg = 70.0.toMilli(),
                    equationCoefficient = -161.0,
                    targetMode = "FIXED",
                    fixedTargetMilliKcal = 2_000.0.toMilli(),
                    activityFactor = 1.2,
                    goalAdjustmentMilliKcal = 0,
                    targetCaloriesMilliKcal = 2_000.0.toMilli(),
                    macroMode = "PERCENT",
                    proteinTarget = 30.0,
                    carbsTarget = 40.0,
                    fatTarget = 30.0,
                    useHealthConnect = false,
                    updatedAtEpochMs = now,
                ),
            )
        }
    }

    fun observeDiary(date: LocalDate) = dao.observeDiary(date.toString())
    fun observeDayState(date: LocalDate) = dao.observeDayState(date.toString())
    fun observeActivity(date: LocalDate) = dao.observeActivity(date.toString())
    fun observeDiaryRange(start: LocalDate, end: LocalDate) = dao.observeDiaryRange(start.toString(), end.toString())
    fun observeDayStatesRange(start: LocalDate, end: LocalDate) = dao.observeDayStatesRange(start.toString(), end.toString())
    fun observeActivityRange(start: LocalDate, end: LocalDate) = dao.observeActivityRange(start.toString(), end.toString())

    suspend fun createCustomFood(
        name: String,
        servingLabel: String,
        servingGrams: Double?,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double,
    ) {
        require(name.isNotBlank()) { "Food name is required" }
        require(servingLabel.isNotBlank()) { "Serving label is required" }
        require(listOf(calories, protein, carbs, fat, fiber).all { it >= 0 }) { "Nutrition values cannot be negative" }
        dao.upsertFood(
            FoodEntity(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                servingLabel = servingLabel.trim(),
                servingGramsMilli = servingGrams?.takeIf { it > 0 }?.toMilli(),
                caloriesMilliKcal = calories.toMilli(),
                proteinMilliGram = protein.toMilli(),
                carbsMilliGram = carbs.toMilli(),
                fatMilliGram = fat.toMilli(),
                fiberMilliGram = fiber.toMilli(),
                source = "USER_CUSTOM",
                dataQuality = "USER_ENTERED",
                isUserCreated = true,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun archiveFood(id: String) = dao.archiveFood(id)
    suspend fun archiveRecipe(id: String) = dao.archiveRecipe(id)

    suspend fun cacheOnlineFood(product: OnlineFoodProduct): Boolean {
        val row = FoodEntity(
            id = "off:${product.barcode}",
            name = product.name,
            brand = product.brand,
            servingLabel = product.servingLabel,
            servingGramsMilli = product.servingQuantity.takeIf { product.servingUnit == "g" }?.toMilli(),
            caloriesMilliKcal = product.calories.toMilli(),
            proteinMilliGram = product.protein.toMilli(),
            carbsMilliGram = product.carbs.toMilli(),
            fatMilliGram = product.fat.toMilli(),
            fiberMilliGram = product.fiber.toMilli(),
            source = "OPEN_FOOD_FACTS",
            sourceId = product.barcode,
            sourceRevision = product.sourceRevision,
            sourceUpdatedAtEpochMs = product.sourceUpdatedAtEpochMs,
            sourceCompleteness = product.completeness,
            sourceWarningCount = product.warningCount,
            dataQuality = if (product.warningCount == 0) "COMMUNITY_NO_REPORTED_ERRORS" else "COMMUNITY_WITH_WARNINGS",
            isUserCreated = false,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        return dao.saveOnlineFood(row)
    }

    suspend fun cacheUsdaFood(record: UsdaFoodRecord): Boolean {
        val summary = record.summary
        require(summary.hasImportableNutrition) { "That USDA record has missing or invalid nutrition values" }
        val dataType = record.dataType.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .take(32)
            .ifEmpty { "UNKNOWN" }
        val presentNutrients = listOf(summary.calories, summary.protein, summary.carbs, summary.fat, summary.fiber).count { it != null }
        val row = FoodEntity(
            id = "usda:${summary.fdcId}",
            name = summary.name,
            brand = summary.brand,
            servingLabel = summary.servingLabel,
            servingGramsMilli = record.servingGrams?.toMilli(),
            caloriesMilliKcal = requireNotNull(summary.calories).toMilli(),
            proteinMilliGram = requireNotNull(summary.protein).toMilli(),
            carbsMilliGram = requireNotNull(summary.carbs).toMilli(),
            fatMilliGram = requireNotNull(summary.fat).toMilli(),
            fiberMilliGram = (summary.fiber ?: 0.0).toMilli(),
            source = "USDA_FDC_$dataType",
            sourceId = summary.fdcId.toString(),
            sourceRevision = record.sourceRevision,
            sourceUpdatedAtEpochMs = record.sourceUpdatedAtEpochMs,
            sourceCompleteness = presentNutrients / 5.0,
            sourceWarningCount = if (summary.hasCompleteNutrition) 0 else 1,
            dataQuality = if (dataType == "BRANDED") "MANUFACTURER_LABEL" else "USDA_REFERENCE",
            isUserCreated = false,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        return dao.saveOnlineFood(row)
    }

    suspend fun createRecipe(name: String, servings: Double, foodQuantities: Map<String, Double>) {
        require(name.isNotBlank()) { "Recipe name is required" }
        require(servings > 0) { "Recipe servings must be positive" }
        val selected = foodQuantities.filterValues { it > 0 }
        require(selected.isNotEmpty()) { "Choose at least one ingredient" }
        val recipeId = UUID.randomUUID().toString()
        val rows = selected.map { (foodId, quantity) ->
            val food = requireNotNull(dao.food(foodId)) { "Ingredient no longer exists" }
            RecipeIngredientEntity(
                id = UUID.randomUUID().toString(),
                recipeId = recipeId,
                foodId = food.id,
                foodNameSnapshot = food.name,
                quantity = quantity,
                caloriesMilliKcalPerServing = food.caloriesMilliKcal,
                proteinMilliGramPerServing = food.proteinMilliGram,
                carbsMilliGramPerServing = food.carbsMilliGram,
                fatMilliGramPerServing = food.fatMilliGram,
                fiberMilliGramPerServing = food.fiberMilliGram,
            )
        }
        database.withTransaction {
            dao.insertRecipe(
                RecipeEntity(
                    id = recipeId,
                    name = name.trim(),
                    servings = servings,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            dao.insertRecipeIngredients(rows)
        }
    }

    suspend fun logFood(date: LocalDate, mealGroup: String, foodId: String, quantity: Double) {
        require(quantity > 0)
        val food = requireNotNull(dao.food(foodId))
        val nutrients = food.nutrients() * quantity
        insertDiary(date, mealGroup, "FOOD", food.id, food.name, food.servingLabel, quantity, nutrients)
    }

    suspend fun logRecipe(date: LocalDate, mealGroup: String, recipeId: String, quantity: Double) {
        require(quantity > 0)
        val recipe = requireNotNull(dao.recipe(recipeId))
        val total = dao.ingredientsForRecipe(recipeId).fold(Nutrients()) { accumulator, row ->
            accumulator + row.nutrientsPerServing() * row.quantity
        }
        val nutrients = (total / recipe.servings) * quantity
        insertDiary(date, mealGroup, "RECIPE", recipe.id, recipe.name, "1 serving", quantity, nutrients)
    }

    private suspend fun insertDiary(
        date: LocalDate,
        mealGroup: String,
        sourceType: String,
        sourceId: String,
        name: String,
        serving: String,
        quantity: Double,
        nutrients: Nutrients,
    ) {
        dao.insertDiaryEntry(
            DiaryEntryEntity(
                id = UUID.randomUUID().toString(),
                localDate = date.toString(),
                mealGroup = mealGroup,
                sourceType = sourceType,
                sourceId = sourceId,
                nameSnapshot = name,
                servingLabelSnapshot = serving,
                quantity = quantity,
                caloriesMilliKcal = nutrients.caloriesMilliKcal,
                proteinMilliGram = nutrients.proteinMilliGram,
                carbsMilliGram = nutrients.carbsMilliGram,
                fatMilliGram = nutrients.fatMilliGram,
                fiberMilliGram = nutrients.fiberMilliGram,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteDiaryEntry(id: String) = dao.deleteDiaryEntry(id)

    suspend fun setDayComplete(date: LocalDate, complete: Boolean, intentionalZero: Boolean = false) {
        dao.upsertDayState(
            DayStateEntity(
                localDate = date.toString(),
                isComplete = complete,
                intentionalZero = complete && intentionalZero,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun upsertActivity(date: LocalDate, activeCaloriesKcal: Double, source: String, known: Boolean, stale: Boolean = false) {
        dao.upsertActivity(
            ActivityDailyEntity(
                localDate = date.toString(),
                activeCaloriesMilliKcal = activeCaloriesKcal.toMilli(),
                source = source,
                isKnown = known,
                isStale = stale,
                syncedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markActivitySyncFailed(date: LocalDate) {
        val existing = dao.activity(date.toString())
        dao.upsertActivity(
            existing?.copy(isStale = true) ?: ActivityDailyEntity(
                localDate = date.toString(),
                activeCaloriesMilliKcal = 0,
                source = "HEALTH_CONNECT",
                isKnown = false,
                isStale = true,
                syncedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveTarget(
        input: TargetInput,
        fixedMode: Boolean,
        macroMode: String,
        proteinTarget: Double,
        carbsTarget: Double,
        fatTarget: Double,
        useHealthConnect: Boolean,
        existingTrackingStart: LocalDate?,
        zoneId: ZoneId,
    ): TargetPlanEntity {
        require(macroMode == "PERCENT" || macroMode == "GRAMS")
        if (macroMode == "PERCENT") {
            TargetCalculator.percentageMacroGrams(input.fixedTargetKcal ?: TargetCalculator.calculate(input).targetKcal, proteinTarget, carbsTarget, fatTarget)
        } else {
            require(listOf(proteinTarget, carbsTarget, fatTarget).all { it >= 0 })
        }
        val result = TargetCalculator.calculate(input)
        val now = System.currentTimeMillis()
        val target = TargetPlanEntity(
            effectiveFrom = LocalDate.now(zoneId).toString(),
            trackingStartDate = (existingTrackingStart ?: LocalDate.now(zoneId)).toString(),
            homeTimeZoneId = zoneId.id,
            isConfigured = true,
            ageYears = input.ageYears,
            heightMilliCm = input.heightCm.toMilli(),
            weightMilliKg = input.weightKg.toMilli(),
            equationCoefficient = input.equationCoefficient,
            customBmrMilliKcal = input.customBmrKcal?.toMilli(),
            targetMode = if (fixedMode) "FIXED" else "CALCULATED",
            fixedTargetMilliKcal = input.fixedTargetKcal?.toMilli(),
            activityFactor = input.activityFactor,
            goalAdjustmentMilliKcal = input.goalAdjustmentKcal.toMilli(),
            targetCaloriesMilliKcal = result.targetKcal.toMilli(),
            macroMode = macroMode,
            proteinTarget = proteinTarget,
            carbsTarget = carbsTarget,
            fatTarget = fatTarget,
            useHealthConnect = useHealthConnect,
            updatedAtEpochMs = now,
        )
        dao.upsertTarget(target)
        return target
    }

    suspend fun startFast(hours: Int?) {
        require(hours == null || hours > 0)
        val now = System.currentTimeMillis()
        dao.insertFast(
            FastingPeriodEntity(
                id = UUID.randomUUID().toString(),
                startEpochMs = now,
                plannedEndEpochMs = hours?.let { now + it * 3_600_000L },
            ),
        )
    }

    suspend fun endFast(id: String) = dao.endFast(id, System.currentTimeMillis())

    fun daoForExport(): TrackerDao = dao
}

fun FoodEntity.nutrients() = Nutrients(
    caloriesMilliKcal,
    proteinMilliGram,
    carbsMilliGram,
    fatMilliGram,
    fiberMilliGram,
)

fun RecipeIngredientEntity.nutrientsPerServing() = Nutrients(
    caloriesMilliKcalPerServing,
    proteinMilliGramPerServing,
    carbsMilliGramPerServing,
    fatMilliGramPerServing,
    fiberMilliGramPerServing,
)
