package com.johnny9.calorietracker.data

import com.johnny9.calorietracker.domain.fromMilli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledFoodsTest {
    @Test
    fun includesCurrentManufacturerLabelForChompsOriginalBeef() {
        val chomps = BundledFoods.rows(123L).single { it.brand == "Chomps" }

        assertTrue(chomps.name.contains("Beef Stick"))
        assertEquals(100.0, chomps.caloriesMilliKcal.fromMilli(), 0.0)
        assertEquals(10.0, chomps.proteinMilliGram.fromMilli(), 0.0)
        assertEquals(7.0, chomps.fatMilliGram.fromMilli(), 0.0)
        assertEquals("BRAND_LABEL", chomps.source)
        assertEquals("MANUFACTURER_LABEL", chomps.dataQuality)
    }
}
