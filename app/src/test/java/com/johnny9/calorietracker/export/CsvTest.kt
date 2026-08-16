package com.johnny9.calorietracker.export

import com.johnny9.calorietracker.data.FoodEntity
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

    @Test
    fun `export source URL preserves Open Food Facts attribution`() {
        val food = FoodEntity(
            id = "off:0856584004190",
            name = "Original Beef Sticks",
            servingLabel = "1 portion (32 g)",
            caloriesMilliKcal = 100_000,
            proteinMilliGram = 10_000,
            carbsMilliGram = 0,
            fatMilliGram = 7_000,
            fiberMilliGram = 0,
            source = "OPEN_FOOD_FACTS",
            sourceId = "0856584004190",
            sourceRevision = "25",
            createdAtEpochMs = 1,
        )

        assertEquals("https://world.openfoodfacts.org/product/0856584004190?rev=25", sourceUrl(food))
        assertEquals("2", EXPORT_SCHEMA_VERSION)
    }
}
