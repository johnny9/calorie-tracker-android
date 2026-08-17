package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `editing serving quantity rescales the frozen nutrient snapshot`() {
        val logged = Nutrients(
            caloriesMilliKcal = 375_000,
            proteinMilliGram = 22_500,
            carbsMilliGram = 45_000,
            fatMilliGram = 12_000,
            fiberMilliGram = 3_000,
        )

        val edited = logged.rescaleQuantity(previousQuantity = 1.5, newQuantity = 0.5)

        assertEquals(125_000, edited.caloriesMilliKcal)
        assertEquals(7_500, edited.proteinMilliGram)
        assertEquals(15_000, edited.carbsMilliGram)
        assertEquals(4_000, edited.fatMilliGram)
        assertEquals(1_000, edited.fiberMilliGram)
    }

    @Test
    fun `serving quantity edits reject invalid values`() {
        val logged = Nutrients(caloriesMilliKcal = 100_000)

        assertThrows(IllegalArgumentException::class.java) {
            logged.rescaleQuantity(previousQuantity = 1.0, newQuantity = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            logged.rescaleQuantity(previousQuantity = 1.0, newQuantity = Double.POSITIVE_INFINITY)
        }
    }
}
