package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodNamesTest {
    @Test
    fun removesAnExactDuplicateBrandFromEitherEnd() {
        assertEquals("Original Beef Stick", cleanFoodName("Chomps Original Beef Stick", "Chomps"))
        assertEquals("Original Beef Stick", cleanFoodName("CHOMPS® — Original Beef Stick", "Chomps"))
        assertEquals("Original Beef Stick", cleanFoodName("Original Beef Stick - Chomps", "Chomps"))
    }

    @Test
    fun preservesPartialMatchesAndBrandOnlyTitles() {
        assertEquals("Goat Cheese", cleanFoodName("Goat Cheese", "Go"))
        assertEquals("Chomps", cleanFoodName("Chomps", "Chomps"))
        assertEquals("Lime Sparkling Water", cleanFoodName("  Lime   Sparkling Water  ", "Bubbles"))
    }
}
