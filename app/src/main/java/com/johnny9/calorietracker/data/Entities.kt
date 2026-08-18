package com.johnny9.calorietracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "foods", indices = [Index("name"), Index("source")])
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String? = null,
    val servingLabel: String,
    val servingGramsMilli: Long? = null,
    val caloriesMilliKcal: Long,
    val proteinMilliGram: Long,
    val carbsMilliGram: Long,
    val fatMilliGram: Long,
    val fiberMilliGram: Long,
    val source: String,
    val sourceId: String? = null,
    val sourceRevision: String? = null,
    val sourceUpdatedAtEpochMs: Long? = null,
    val sourceCompleteness: Double? = null,
    val sourceWarningCount: Int? = null,
    @ColumnInfo(defaultValue = "'UNSPECIFIED'") val dataQuality: String = "UNSPECIFIED",
    val isArchived: Boolean = false,
    val isUserCreated: Boolean = false,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "recipes", indices = [Index("name")])
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servings: Double,
    val notes: String = "",
    val isArchived: Boolean = false,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "recipe_ingredients",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipeId"), Index("foodId")],
)
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val foodId: String,
    val foodNameSnapshot: String,
    val quantity: Double,
    val caloriesMilliKcalPerServing: Long,
    val proteinMilliGramPerServing: Long,
    val carbsMilliGramPerServing: Long,
    val fatMilliGramPerServing: Long,
    val fiberMilliGramPerServing: Long,
)

@Entity(tableName = "diary_entries", indices = [Index("localDate"), Index("sourceId")])
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val mealGroup: String,
    val sourceType: String,
    val sourceId: String,
    val nameSnapshot: String,
    val servingLabelSnapshot: String,
    val quantity: Double,
    val caloriesMilliKcal: Long,
    val proteinMilliGram: Long,
    val carbsMilliGram: Long,
    val fatMilliGram: Long,
    val fiberMilliGram: Long,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "day_states")
data class DayStateEntity(
    @PrimaryKey val localDate: String,
    val isComplete: Boolean,
    val intentionalZero: Boolean = false,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "activity_daily")
data class ActivityDailyEntity(
    @PrimaryKey val localDate: String,
    val activeCaloriesMilliKcal: Long,
    val restingCaloriesMilliKcal: Long? = null,
    val source: String,
    val isKnown: Boolean,
    val isStale: Boolean,
    val syncedAtEpochMs: Long,
)

@Entity(tableName = "target_plans", indices = [Index("effectiveFrom")])
data class TargetPlanEntity(
    @PrimaryKey val id: String = "active",
    val effectiveFrom: String,
    val trackingStartDate: String,
    val homeTimeZoneId: String,
    val isConfigured: Boolean,
    @ColumnInfo(defaultValue = "'METRIC'") val unitSystem: String = "METRIC",
    val ageYears: Int,
    val heightMilliCm: Long,
    val weightMilliKg: Long,
    val equationCoefficient: Double,
    val customBmrMilliKcal: Long? = null,
    val targetMode: String,
    val fixedTargetMilliKcal: Long? = null,
    val activityFactor: Double,
    val goalAdjustmentMilliKcal: Long,
    val targetCaloriesMilliKcal: Long,
    val macroMode: String,
    val proteinTarget: Double,
    val carbsTarget: Double,
    val fatTarget: Double,
    val useHealthConnect: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "fasting_periods", indices = [Index("startEpochMs")])
data class FastingPeriodEntity(
    @PrimaryKey val id: String,
    val startEpochMs: Long,
    val plannedEndEpochMs: Long? = null,
    val endEpochMs: Long? = null,
    val note: String = "",
)
