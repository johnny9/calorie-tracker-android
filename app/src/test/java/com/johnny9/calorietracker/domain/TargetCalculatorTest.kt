package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetCalculatorTest {
    @Test
    fun `mifflin coefficients and fixed target remain explicit`() {
        val base = TargetInput(
            ageYears = 35,
            heightCm = 175.0,
            weightKg = 80.0,
            equationCoefficient = 5.0,
            activityFactor = 1.2,
            goalAdjustmentKcal = -250.0,
        )
        val result = TargetCalculator.calculate(base)
        assertEquals(1723.75, result.bmrKcal, 0.001)
        assertEquals(1818.5, result.targetKcal, 0.001)

        val alternate = TargetCalculator.calculate(base.copy(equationCoefficient = -161.0, fixedTargetKcal = 2_100.0))
        assertEquals(1557.75, alternate.bmrKcal, 0.001)
        assertEquals(2100.0, alternate.targetKcal, 0.001)
    }

    @Test
    fun `custom bmr overrides equation`() {
        val result = TargetCalculator.calculate(
            TargetInput(40, 180.0, 90.0, 5.0, customBmrKcal = 1_900.0, activityFactor = 1.5, goalAdjustmentKcal = 0.0),
        )
        assertEquals(1900.0, result.bmrKcal, 0.001)
        assertEquals(2850.0, result.targetKcal, 0.001)
    }

    @Test
    fun `percentage macros must total one hundred`() {
        val grams = TargetCalculator.percentageMacroGrams(2_000.0, 30.0, 40.0, 30.0)
        assertEquals(150.0, grams.first, 0.001)
        assertEquals(200.0, grams.second, 0.001)
        assertEquals(66.666, grams.third, 0.01)
        assertThrows(IllegalArgumentException::class.java) {
            TargetCalculator.percentageMacroGrams(2_000.0, 30.0, 30.0, 30.0)
        }
    }
}
