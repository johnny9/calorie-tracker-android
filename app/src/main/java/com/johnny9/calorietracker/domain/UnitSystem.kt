package com.johnny9.calorietracker.domain

import kotlin.math.floor

enum class UnitSystem {
    METRIC,
    US;

    companion object {
        fun fromStorage(value: String?): UnitSystem = entries.firstOrNull { it.name == value } ?: METRIC
    }
}

data class UsHeight(
    val feet: Int,
    val inches: Double,
)

object UnitConverter {
    const val CENTIMETERS_PER_INCH = 2.54
    const val KILOGRAMS_PER_POUND = 0.45359237
    const val GRAMS_PER_OUNCE = 28.349523125

    fun feetAndInchesToCentimeters(feet: Int, inches: Double): Double =
        (feet * 12.0 + inches) * CENTIMETERS_PER_INCH

    fun centimetersToUsHeight(centimeters: Double): UsHeight {
        val totalInches = centimeters / CENTIMETERS_PER_INCH
        val feet = floor(totalInches / 12.0).toInt()
        return UsHeight(feet, totalInches - feet * 12.0)
    }

    fun poundsToKilograms(pounds: Double): Double = pounds * KILOGRAMS_PER_POUND

    fun kilogramsToPounds(kilograms: Double): Double = kilograms / KILOGRAMS_PER_POUND

    fun ouncesToGrams(ounces: Double): Double = ounces * GRAMS_PER_OUNCE

    fun gramsToOunces(grams: Double): Double = grams / GRAMS_PER_OUNCE
}
