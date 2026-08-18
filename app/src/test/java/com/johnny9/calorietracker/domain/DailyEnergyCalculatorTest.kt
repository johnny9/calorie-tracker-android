package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyEnergyCalculatorTest {
    @Test
    fun `device resting calories take precedence over app BMR`() {
        val result = DailyEnergyCalculator.calculate(
            intakeMilliKcal = 2_000_000,
            activeMilliKcal = 400_000,
            healthConnectRestingMilliKcal = 1_700_000,
            appBmrMilliKcal = 1_500_000,
        )

        assertEquals(1_700_000L, result.restingMilliKcal)
        assertEquals(RestingCaloriesSource.HEALTH_CONNECT, result.restingSource)
        assertEquals(2_100_000L, result.totalBurnMilliKcal)
        assertEquals(-100_000L, result.energyBalanceMilliKcal)
    }

    @Test
    fun `app BMR is used when device resting calories are missing or invalid`() {
        val missing = DailyEnergyCalculator.calculate(2_000_000, 300_000, null, 1_600_000)
        val zero = DailyEnergyCalculator.calculate(2_000_000, 300_000, 0, 1_600_000)

        assertEquals(1_600_000L, missing.restingMilliKcal)
        assertEquals(RestingCaloriesSource.APP_BMR, missing.restingSource)
        assertEquals(missing, zero)
    }

    @Test
    fun `total burn stays unavailable when active calories are unknown`() {
        val result = DailyEnergyCalculator.calculate(2_000_000, null, 1_700_000, 1_500_000)

        assertEquals(1_700_000L, result.restingMilliKcal)
        assertNull(result.totalBurnMilliKcal)
        assertNull(result.energyBalanceMilliKcal)
    }

    @Test
    fun `resting and balance stay unavailable without either source`() {
        val result = DailyEnergyCalculator.calculate(2_000_000, 300_000, null, null)

        assertNull(result.restingMilliKcal)
        assertEquals(RestingCaloriesSource.UNAVAILABLE, result.restingSource)
        assertNull(result.totalBurnMilliKcal)
        assertNull(result.energyBalanceMilliKcal)
    }
}
