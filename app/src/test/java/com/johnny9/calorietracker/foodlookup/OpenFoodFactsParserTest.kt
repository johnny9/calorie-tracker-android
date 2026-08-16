package com.johnny9.calorietracker.foodlookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsParserTest {
    @Test
    fun parsesChompsSearchResultsWithoutInventingMissingPreviewValues() {
        val results = OpenFoodFactsParser.parseSearchResponse(
            """
            {
              "hits": [
                {
                  "code": "0856584004190",
                  "brands": ["Chomps"],
                  "product_name": "Original Beef Sticks",
                  "nutriments": {
                    "energy-kcal_100g": 312.5,
                    "proteins_100g": 31.25,
                    "fat_100g": 21.875
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals("0856584004190", results.single().barcode)
        assertEquals("Chomps", results.single().brand)
        assertNull(results.single().carbsPer100g)
    }

    @Test
    fun normalizesOneHundredGramNutritionToTheDeclaredServing() {
        val product = OpenFoodFactsParser.parseProductResponse(chompsProduct(), "0856584004190")

        assertEquals("Original Beef Sticks", product.name)
        assertEquals("1 portion (32 g)", product.servingLabel)
        assertEquals(32.0, product.servingQuantity, 0.0)
        assertEquals(100.0, product.calories, 0.0001)
        assertEquals(10.0, product.protein, 0.0001)
        assertEquals(0.0, product.carbs, 0.0)
        assertEquals(7.0, product.fat, 0.0001)
        assertEquals(0.0, product.fiber, 0.0)
        assertEquals("25", product.sourceRevision)
        assertEquals(1_769_215_582_000L, product.sourceUpdatedAtEpochMs)
        assertEquals(1, product.warningCount)
    }

    @Test
    fun convertsKilojoulesOnlyWhenExplicitlyLabelled() {
        val payload = chompsProduct()
            .replace("\"energy-kcal\": {\"value\": 312.5, \"unit\": \"kcal\"},", "\"energy-kj\": {\"value\": 418.4, \"unit\": \"kJ\"},")
            .replace("\"serving_quantity\": 32", "\"serving_quantity\": 100")

        val product = OpenFoodFactsParser.parseProductResponse(payload, "0856584004190")

        assertEquals(100.0, product.calories, 0.0001)
    }

    @Test
    fun rejectsIncompleteOrQualityFlaggedNutritionInsteadOfUsingZero() {
        val missingFiber = chompsProduct().replace("\"fiber\"", "\"missing_fiber\"")
        val qualityError = chompsProduct().replace(
            "\"data_quality_errors_tags\": []",
            "\"data_quality_errors_tags\": [\"en:nutrition-value-very-high\"]",
        )
        val impossibleFat = chompsProduct().replace("\"value\": 21.875", "\"value\": 121.875")
        val mixedBasis = chompsProduct().replace("\"per\": \"100g\"", "\"per\": \"serving\"")

        assertThrows(FoodLookupException::class.java) {
            OpenFoodFactsParser.parseProductResponse(missingFiber, "0856584004190")
        }
        assertThrows(FoodLookupException::class.java) {
            OpenFoodFactsParser.parseProductResponse(qualityError, "0856584004190")
        }
        assertThrows(FoodLookupException::class.java) {
            OpenFoodFactsParser.parseProductResponse(impossibleFat, "0856584004190")
        }
        assertThrows(FoodLookupException::class.java) {
            OpenFoodFactsParser.parseProductResponse(mixedBasis, "0856584004190")
        }
    }

    @Test
    fun validatesQueriesAndPreservesLeadingZeroBarcodes() {
        assertEquals("0856584004190", OpenFoodFactsParser.normalizeBarcode("0856584004190"))
        assertEquals("0856584004190", OpenFoodFactsParser.normalizeBarcode("856584004190"))
        assertEquals("0049000028911", OpenFoodFactsParser.normalizeBarcode("00049000028911"))
        assertNull(OpenFoodFactsParser.normalizeBarcode("856-584-004190"))
        assertEquals("Chomps beef stick", OpenFoodFactsParser.normalizeQuery("  Chomps   beef stick "))
        assertTrue(OpenFoodFactsParser.searchRequestBody("Chomps").contains("\"q\":\"Chomps\""))
    }

    @Test
    fun acceptsTheNormalizedResponseForALeadingZeroGtinFourteenAlias() {
        val normalizedCode = "0049000028911"
        val payload = chompsProduct().replace("0856584004190", normalizedCode)

        val product = OpenFoodFactsParser.parseProductResponse(payload, "00049000028911")

        assertEquals(normalizedCode, product.barcode)
    }

    private fun chompsProduct() =
        """
        {
          "code": "0856584004190",
          "status": "success",
          "product": {
            "code": "0856584004190",
            "product_name": "Original Beef Sticks",
            "brands": "CHOMPS",
            "serving_quantity": 32,
            "serving_quantity_unit": "g",
            "serving_size": "1 portion (32 g)",
            "rev": 25,
            "last_modified_t": 1769215582,
            "completeness": 0.5875,
            "data_quality_errors_tags": [],
            "data_quality_warnings_tags": ["en:serving-quantity-defined-but-quantity-undefined"],
            "nutrition": {
              "aggregated_set": {
                "per": "100g",
                "preparation": "as_sold",
                "nutrients": {
                  "energy-kcal": {"value": 312.5, "unit": "kcal"},
                  "proteins": {"value": 31.25, "unit": "g"},
                  "carbohydrates": {"value": 0, "unit": "g"},
                  "fat": {"value": 21.875, "unit": "g"},
                  "fiber": {"value": 0, "unit": "g"}
                }
              }
            }
          }
        }
        """.trimIndent()
}
