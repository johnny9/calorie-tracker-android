package com.johnny9.calorietracker.domain

import java.time.LocalDate
import kotlin.math.roundToLong

const val MILLI = 1_000.0

fun Double.toMilli(): Long = (this * MILLI).roundToLong()
fun Long.fromMilli(): Double = this / MILLI

data class Nutrients(
    val caloriesMilliKcal: Long = 0,
    val proteinMilliGram: Long = 0,
    val carbsMilliGram: Long = 0,
    val fatMilliGram: Long = 0,
    val fiberMilliGram: Long = 0,
) {
    operator fun plus(other: Nutrients) = Nutrients(
        caloriesMilliKcal + other.caloriesMilliKcal,
        proteinMilliGram + other.proteinMilliGram,
        carbsMilliGram + other.carbsMilliGram,
        fatMilliGram + other.fatMilliGram,
        fiberMilliGram + other.fiberMilliGram,
    )

    operator fun times(quantity: Double) = Nutrients(
        (caloriesMilliKcal * quantity).roundToLong(),
        (proteinMilliGram * quantity).roundToLong(),
        (carbsMilliGram * quantity).roundToLong(),
        (fatMilliGram * quantity).roundToLong(),
        (fiberMilliGram * quantity).roundToLong(),
    )

    operator fun div(divisor: Double): Nutrients = times(1.0 / divisor)
}

enum class DayCompleteness { MISSING, PARTIAL, COMPLETE, FASTED_ZERO }

data class DailyPoint(
    val date: LocalDate,
    val intakeMilliKcal: Long,
    val activeMilliKcal: Long,
    val completeness: DayCompleteness,
) {
    val netMilliKcal: Long get() = intakeMilliKcal - activeMilliKcal
    val isEligible: Boolean
        get() = completeness == DayCompleteness.COMPLETE || completeness == DayCompleteness.FASTED_ZERO
}

data class RollingResult(
    val averageNetMilliKcal: Long?,
    val knownTotalNetMilliKcal: Long,
    val eligibleDays: Int,
    val elapsedDays: Int,
    val requestedWindowDays: Int,
)

data class TargetInput(
    val ageYears: Int,
    val heightCm: Double,
    val weightKg: Double,
    val equationCoefficient: Double,
    val customBmrKcal: Double? = null,
    val fixedTargetKcal: Double? = null,
    val activityFactor: Double,
    val goalAdjustmentKcal: Double,
)

data class TargetResult(
    val bmi: Double,
    val bmrKcal: Double,
    val targetKcal: Double,
)

data class RecipeSummary(
    val id: String,
    val name: String,
    val servings: Double,
    val nutrientsPerServing: Nutrients,
    val ingredientCount: Int,
)
