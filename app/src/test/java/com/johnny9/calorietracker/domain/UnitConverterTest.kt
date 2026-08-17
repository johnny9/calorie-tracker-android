package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {
    @Test
    fun `feet and inches convert to canonical centimeters`() {
        assertEquals(177.8, UnitConverter.feetAndInchesToCentimeters(5, 10.0), 0.000_001)

        val display = UnitConverter.centimetersToUsHeight(177.8)
        assertEquals(5, display.feet)
        assertEquals(10.0, display.inches, 0.000_001)
    }

    @Test
    fun `pounds round trip through canonical kilograms`() {
        val kilograms = UnitConverter.poundsToKilograms(180.0)
        assertEquals(81.646_626_6, kilograms, 0.000_001)
        assertEquals(180.0, UnitConverter.kilogramsToPounds(kilograms), 0.000_001)
    }

    @Test
    fun `ounces convert to canonical grams`() {
        assertEquals(28.349_523_125, UnitConverter.ouncesToGrams(1.0), 0.000_001)
        assertEquals(1.0, UnitConverter.gramsToOunces(28.349_523_125), 0.000_001)
    }

    @Test
    fun `unknown stored unit system falls back to metric`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStorage(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromStorage("UNKNOWN"))
        assertEquals(UnitSystem.US, UnitSystem.fromStorage("US"))
    }
}
