package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NutrientsTest {
    @Test
    fun `serving multiplication retains milli-unit precision`() {
        val serving = Nutrients(
            caloriesMilliKcal = 72_250,
            proteinMilliGram = 6_300,
            carbsMilliGram = 400,
            fatMilliGram = 4_800,
        )
        val total = serving * 1.5
        assertEquals(108_375, total.caloriesMilliKcal)
        assertEquals(9_450, total.proteinMilliGram)
    }
}
