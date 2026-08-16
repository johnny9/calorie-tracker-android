package com.johnny9.calorietracker.ui

import com.johnny9.calorietracker.data.BundledFoods
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTest {
    @Test
    fun matchesAQueryAcrossBrandAndProductName() {
        val chomps = BundledFoods.rows(0L).single { it.brand == "Chomps" }

        assertTrue(chomps.matchesQuery("chomps beef stick"))
        assertTrue(chomps.matchesQuery("original chomps"))
        assertFalse(chomps.matchesQuery("chomps turkey"))
    }
}
