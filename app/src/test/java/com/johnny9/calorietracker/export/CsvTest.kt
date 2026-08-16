package com.johnny9.calorietracker.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun `csv quotes commas quotes and newlines`() {
        assertEquals("\"alpha,beta\",\"say \"\"hi\"\"\",\"line1\nline2\"\r\n", Csv.row(listOf("alpha,beta", "say \"hi\"", "line1\nline2")))
    }

    @Test
    fun `csv neutralizes spreadsheet formulas in user text`() {
        assertEquals("\"'=2+2\",\"'+cmd\",\"'@value\"\r\n", Csv.row(listOf("=2+2", "+cmd", "@value")))
    }

    @Test
    fun `csv leaves negative numeric values numeric`() {
        assertEquals("\"-250.0\",\"'-user note\"\r\n", Csv.row(listOf(-250.0, "-user note")))
    }
}
