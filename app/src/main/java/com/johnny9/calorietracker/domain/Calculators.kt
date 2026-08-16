package com.johnny9.calorietracker.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToLong

object TargetCalculator {
    fun calculate(input: TargetInput): TargetResult {
        require(input.ageYears in 13..120) { "Age must be between 13 and 120" }
        require(input.heightCm in 100.0..250.0) { "Height must be between 100 and 250 cm" }
        require(input.weightKg in 25.0..400.0) { "Weight must be between 25 and 400 kg" }
        require(input.activityFactor in 1.0..2.5) { "Activity factor must be between 1.0 and 2.5" }

        val bmi = input.weightKg / ((input.heightCm / 100.0) * (input.heightCm / 100.0))
        val calculatedBmr = 10.0 * input.weightKg +
            6.25 * input.heightCm -
            5.0 * input.ageYears +
            input.equationCoefficient
        val bmr = input.customBmrKcal ?: calculatedBmr
        require(bmr > 0) { "BMR must be positive" }

        val target = input.fixedTargetKcal ?: (bmr * input.activityFactor + input.goalAdjustmentKcal)
        require(target > 0) { "Calorie target must be positive" }
        return TargetResult(bmi, bmr, target)
    }

    fun percentageMacroGrams(targetKcal: Double, proteinPercent: Double, carbsPercent: Double, fatPercent: Double): Triple<Double, Double, Double> {
        require(kotlin.math.abs(proteinPercent + carbsPercent + fatPercent - 100.0) < 0.01) {
            "Macro percentages must total 100"
        }
        return Triple(
            targetKcal * proteinPercent / 100.0 / 4.0,
            targetKcal * carbsPercent / 100.0 / 4.0,
            targetKcal * fatPercent / 100.0 / 9.0,
        )
    }
}

object RollingWindowCalculator {
    fun calculate(
        points: List<DailyPoint>,
        endDate: LocalDate,
        trackingStart: LocalDate,
        windowDays: Int,
    ): RollingResult {
        require(windowDays in 1..365)
        val requestedStart = endDate.minusDays((windowDays - 1).toLong())
        val windowStart = maxOf(trackingStart, requestedStart)
        if (windowStart > endDate) {
            return RollingResult(null, 0, 0, 0, windowDays)
        }

        val byDate = points.associateBy { it.date }
        val elapsed = (endDate.toEpochDay() - windowStart.toEpochDay() + 1).toInt()
        val eligible = (0 until elapsed).mapNotNull { offset ->
            byDate[windowStart.plusDays(offset.toLong())]?.takeIf(DailyPoint::isEligible)
        }
        val total = eligible.sumOf { requireNotNull(it.netMilliKcal) }
        val average = if (eligible.isEmpty()) null else (total.toDouble() / eligible.size).roundToLong()
        return RollingResult(average, total, eligible.size, elapsed, windowDays)
    }
}
