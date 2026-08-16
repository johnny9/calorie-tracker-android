package com.johnny9.calorietracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RollingWindowCalculatorTest {
    private val day1 = LocalDate.of(2026, 8, 1)

    @Test
    fun `first tracked day divides by one instead of full requested window`() {
        val result = RollingWindowCalculator.calculate(
            points = listOf(DailyPoint(day1, 2_000_000, 200_000, DayCompleteness.COMPLETE)),
            endDate = day1,
            trackingStart = day1,
            windowDays = 7,
        )
        assertEquals(1_800_000L, result.averageNetMilliKcal)
        assertEquals(1, result.eligibleDays)
        assertEquals(1, result.elapsedDays)
    }

    @Test
    fun `missing and partial days are gaps while explicit zero counts`() {
        val points = listOf(
            DailyPoint(day1, 1_800_000, 0, DayCompleteness.COMPLETE),
            DailyPoint(day1.plusDays(1), 0, 0, DayCompleteness.MISSING),
            DailyPoint(day1.plusDays(2), 1_200_000, 0, DayCompleteness.COMPLETE),
            DailyPoint(day1.plusDays(3), 0, 0, DayCompleteness.FASTED_ZERO),
        )
        val full = RollingWindowCalculator.calculate(points, day1.plusDays(3), day1, 7)
        assertEquals(1_000_000L, full.averageNetMilliKcal)
        assertEquals(3, full.eligibleDays)
        assertEquals(4, full.elapsedDays)

        val threeDay = RollingWindowCalculator.calculate(points, day1.plusDays(3), day1, 3)
        assertEquals(600_000L, threeDay.averageNetMilliKcal)
        assertEquals(2, threeDay.eligibleDays)
    }

    @Test
    fun `no eligible observations returns no average`() {
        val result = RollingWindowCalculator.calculate(
            listOf(DailyPoint(day1, 500_000, 0, DayCompleteness.PARTIAL)),
            day1,
            day1,
            7,
        )
        assertNull(result.averageNetMilliKcal)
        assertEquals(0L, result.knownTotalNetMilliKcal)
    }
}
