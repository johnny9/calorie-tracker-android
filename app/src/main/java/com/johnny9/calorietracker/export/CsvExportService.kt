package com.johnny9.calorietracker.export

import android.content.Context
import android.net.Uri
import com.johnny9.calorietracker.data.ActivityDailyEntity
import com.johnny9.calorietracker.data.DayStateEntity
import com.johnny9.calorietracker.data.DiaryEntryEntity
import com.johnny9.calorietracker.data.TargetPlanEntity
import com.johnny9.calorietracker.data.TrackerRepository
import com.johnny9.calorietracker.domain.DayCompleteness
import com.johnny9.calorietracker.domain.fromMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CsvExportService(
    private val context: Context,
    private val repository: TrackerRepository,
) {
    suspend fun export(destination: Uri) = withContext(Dispatchers.IO) {
        val dao = repository.daoForExport()
        val foods = dao.allFoods()
        val recipes = dao.allRecipes()
        val ingredients = dao.allRecipeIngredients()
        val diary = dao.allDiaryEntries()
        val states = dao.allDayStates()
        val activity = dao.allActivity()
        val targets = dao.allTargets()
        val fasts = dao.allFasts()

        val output = requireNotNull(context.contentResolver.openOutputStream(destination)) {
            "Unable to open the selected export destination"
        }
        output.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.csv("manifest.csv", listOf("schema_version", "generated_at_utc"), listOf(listOf("1", Instant.now().toString())))
                zip.csv(
                    "foods.csv",
                    listOf("schema_version", "id", "name", "brand", "serving_label", "serving_grams", "calories_kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "source", "source_id", "archived", "user_created"),
                    foods.map { listOf("1", it.id, it.name, it.brand, it.servingLabel, it.servingGramsMilli?.fromMilli(), it.caloriesMilliKcal.fromMilli(), it.proteinMilliGram.fromMilli(), it.carbsMilliGram.fromMilli(), it.fatMilliGram.fromMilli(), it.fiberMilliGram.fromMilli(), it.source, it.sourceId, it.isArchived, it.isUserCreated) },
                )
                zip.csv(
                    "recipes.csv",
                    listOf("schema_version", "id", "name", "servings", "notes", "archived", "created_at_epoch_ms"),
                    recipes.map { listOf("1", it.id, it.name, it.servings, it.notes, it.isArchived, it.createdAtEpochMs) },
                )
                zip.csv(
                    "recipe_ingredients.csv",
                    listOf("schema_version", "id", "recipe_id", "food_id", "food_name_snapshot", "quantity", "calories_kcal_per_serving", "protein_g_per_serving", "carbs_g_per_serving", "fat_g_per_serving", "fiber_g_per_serving"),
                    ingredients.map { listOf("1", it.id, it.recipeId, it.foodId, it.foodNameSnapshot, it.quantity, it.caloriesMilliKcalPerServing.fromMilli(), it.proteinMilliGramPerServing.fromMilli(), it.carbsMilliGramPerServing.fromMilli(), it.fatMilliGramPerServing.fromMilli(), it.fiberMilliGramPerServing.fromMilli()) },
                )
                zip.csv(
                    "diary_entries.csv",
                    listOf("schema_version", "id", "local_date", "meal_group", "source_type", "source_id", "name_snapshot", "serving_snapshot", "quantity", "calories_kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "created_at_epoch_ms"),
                    diary.map { listOf("1", it.id, it.localDate, it.mealGroup, it.sourceType, it.sourceId, it.nameSnapshot, it.servingLabelSnapshot, it.quantity, it.caloriesMilliKcal.fromMilli(), it.proteinMilliGram.fromMilli(), it.carbsMilliGram.fromMilli(), it.fatMilliGram.fromMilli(), it.fiberMilliGram.fromMilli(), it.createdAtEpochMs) },
                )
                zip.csv(
                    "day_states.csv",
                    listOf("schema_version", "local_date", "complete", "intentional_zero", "updated_at_epoch_ms"),
                    states.map { listOf("1", it.localDate, it.isComplete, it.intentionalZero, it.updatedAtEpochMs) },
                )
                zip.csv(
                    "activity_daily.csv",
                    listOf("schema_version", "local_date", "active_kcal", "source", "known", "stale", "synced_at_epoch_ms"),
                    activity.map { listOf("1", it.localDate, it.activeCaloriesMilliKcal.fromMilli(), it.source, it.isKnown, it.isStale, it.syncedAtEpochMs) },
                )
                zip.csv(
                    "targets.csv",
                    listOf("schema_version", "id", "effective_from", "tracking_start", "home_time_zone", "age_years", "height_cm", "weight_kg", "equation_coefficient", "custom_bmr_kcal", "target_mode", "fixed_target_kcal", "activity_factor", "goal_adjustment_kcal", "target_kcal", "macro_mode", "protein_target", "carbs_target", "fat_target", "health_connect"),
                    targets.map { listOf("1", it.id, it.effectiveFrom, it.trackingStartDate, it.homeTimeZoneId, it.ageYears, it.heightMilliCm.fromMilli(), it.weightMilliKg.fromMilli(), it.equationCoefficient, it.customBmrMilliKcal?.fromMilli(), it.targetMode, it.fixedTargetMilliKcal?.fromMilli(), it.activityFactor, it.goalAdjustmentMilliKcal.fromMilli(), it.targetCaloriesMilliKcal.fromMilli(), it.macroMode, it.proteinTarget, it.carbsTarget, it.fatTarget, it.useHealthConnect) },
                )
                zip.csv(
                    "fasting_periods.csv",
                    listOf("schema_version", "id", "start_epoch_ms", "planned_end_epoch_ms", "end_epoch_ms", "note"),
                    fasts.map { listOf("1", it.id, it.startEpochMs, it.plannedEndEpochMs, it.endEpochMs, it.note) },
                )
                zip.csv(
                    "daily_summaries.csv",
                    listOf("schema_version", "local_date", "intake_kcal", "active_kcal", "net_kcal", "completeness"),
                    dailySummaryRows(diary, states, activity, targets.lastOrNull()),
                )
            }
        }
    }

    private fun dailySummaryRows(
        diary: List<DiaryEntryEntity>,
        states: List<DayStateEntity>,
        activity: List<ActivityDailyEntity>,
        target: TargetPlanEntity?,
    ): List<List<Any?>> {
        val dates = (diary.map { it.localDate } + states.map { it.localDate } + activity.map { it.localDate }).distinct().sorted()
        val stateByDate = states.associateBy { it.localDate }
        val activityByDate = activity.associateBy { it.localDate }
        return dates.map { date ->
            val intake = diary.filter { it.localDate == date }.sumOf { it.caloriesMilliKcal }
            val activeRow = activityByDate[date]
            val dayState = stateByDate[date]
            val activityKnown = target?.useHealthConnect != true || (activeRow?.isKnown == true && !activeRow.isStale)
            val active = when {
                target?.useHealthConnect != true -> 0L
                activityKnown -> activeRow?.activeCaloriesMilliKcal
                else -> null
            }
            val completeness = when {
                dayState?.isComplete == true && activityKnown && dayState.intentionalZero && intake == 0L -> DayCompleteness.FASTED_ZERO
                dayState?.isComplete == true && activityKnown -> DayCompleteness.COMPLETE
                intake != 0L || dayState != null || activeRow != null -> DayCompleteness.PARTIAL
                else -> DayCompleteness.MISSING
            }
            listOf("1", date, intake.fromMilli(), active?.fromMilli(), active?.let { (intake - it).fromMilli() }, completeness.name)
        }
    }
}

object Csv {
    fun row(values: List<Any?>): String = values.joinToString(",") { value ->
        val raw = value?.toString().orEmpty()
        val safe = if (value is String && raw.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
        "\"${safe.replace("\"", "\"\"")}\""
    } + "\r\n"
}

private fun ZipOutputStream.csv(name: String, headers: List<String>, rows: List<List<Any?>>) {
    putNextEntry(ZipEntry(name))
    val writer = OutputStreamWriter(this, Charsets.UTF_8)
    writer.write(Csv.row(headers))
    rows.forEach { writer.write(Csv.row(it)) }
    writer.flush()
    closeEntry()
}
