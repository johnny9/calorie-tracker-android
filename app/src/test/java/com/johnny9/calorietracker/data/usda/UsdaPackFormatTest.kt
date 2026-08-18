package com.johnny9.calorietracker.data.usda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class UsdaPackFormatTest {
    @Test
    fun normalizesSearchTermsAndCanonicalGtins() {
        assertEquals("peanut* AND butter*", UsdaPackFormat.ftsQuery(" Peanut, BUTTER! "))
        assertEquals("coffee*", UsdaPackFormat.ftsQuery("a coffee"))
        assertNull(UsdaPackFormat.ftsQuery("a !"))

        assertEquals("0012345678905", UsdaPackFormat.normalizeGtin("00012345678905"))
        assertEquals("0123456789012", UsdaPackFormat.normalizeGtin("123456789012"))
        assertEquals("12345678", UsdaPackFormat.normalizeGtin("12345678"))
        assertNull(UsdaPackFormat.normalizeGtin("1234-5678"))
    }

    @Test
    fun presentsTheBrandSeparatelyFromTheProductName() {
        val food = summary(
            fdcId = 1,
            length = 1,
            name = "Chomps Original Beef Stick",
            brand = "Chomps",
        )

        assertEquals("Original Beef Stick", food.displayName)
        assertEquals("Chomps Original Beef Stick", food.name)
        assertEquals("Chomps", food.brand)
    }

    @Test
    fun inflatesOnlyTheDeclaredVerifiedBlock() {
        val plain = "first canonical record\nsecond canonical record".encodeToByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(plain) }
        }.toByteArray()

        assertEquals(
            plain.toList(),
            UsdaPackFormat.decodeBlock("zlib", compressed, plain.size, UsdaPackFormat.sha256(plain)).toList(),
        )
        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.decodeBlock("zlib", compressed, plain.size, "0".repeat(64))
        }
        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.decodeBlock("zlib", compressed, plain.size - 1, UsdaPackFormat.sha256(plain))
        }
        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.decodeBlock("zstd", compressed, plain.size, UsdaPackFormat.sha256(plain))
        }
    }

    @Test
    fun parsesNativeUsdaFieldsFromTheIndexedRecordSlice() {
        val prefix = "ignored".encodeToByteArray()
        val json = """
            {"fdcId":12345,"dataType":"Branded","servingSize":30.0,"servingSizeUnit":"GRM","publicationDate":"2025-02-01","foodUpdateLog":[{"field":"description","oldValue":"A","newValue":"B"}]}
        """.trimIndent().encodeToByteArray()
        val block = prefix + json + "trailing".encodeToByteArray()
        val record = UsdaPackFormat.parseRecord(
            block,
            summary(
                fdcId = 12345,
                blockId = 9,
                offset = prefix.size,
                length = json.size,
            ),
        )

        assertEquals("Branded", record.dataType)
        assertEquals(30.0, record.servingGrams!!, 0.0)
        assertEquals("2025-02-01", record.sourceRevision)
        assertEquals(1_738_368_000_000L, record.sourceUpdatedAtEpochMs)
    }

    @Test
    fun derivesHundredGramServingAndRejectsMismatchedOrUnsafeLocators() {
        val json = """
            {"fdcId":77,"dataType":"Foundation","foodPortions":[{"gramWeight":42.5}],"modifiedDate":"4/26/2020"}
        """.trimIndent().encodeToByteArray()
        val record = UsdaPackFormat.parseRecord(json, summary(fdcId = 77, length = json.size, servingLabel = "100 g"))
        assertEquals(100.0, record.servingGrams!!, 0.0)
        assertEquals(1_587_859_200_000L, record.sourceUpdatedAtEpochMs)

        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.parseRecord(json, summary(fdcId = 78, length = json.size))
        }
        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.parseRecord(json, summary(fdcId = 77, offset = json.size, length = 1))
        }
        assertThrows(UsdaCatalogException::class.java) {
            UsdaPackFormat.parseRecord(json, summary(fdcId = 77, length = UsdaPackFormat.MAX_RECORD_BYTES + 1))
        }
    }

    @Test
    fun rejectsNutritionThatWouldOverflowTheUserDatabaseUnits() {
        assertEquals(true, summary(fdcId = 1, length = 1).hasImportableNutrition)
        assertEquals(false, summary(fdcId = 1, length = 1, calories = Double.POSITIVE_INFINITY).hasImportableNutrition)
        assertEquals(false, summary(fdcId = 1, length = 1, calories = 100_000.1).hasImportableNutrition)
    }

    private fun summary(
        fdcId: Long,
        blockId: Long = 1,
        offset: Int = 0,
        length: Int,
        servingLabel: String = "1 serving",
        calories: Double = 100.0,
        name: String = "Test food",
        brand: String? = null,
    ) = UsdaFoodSummary(
        rowId = 1,
        fdcId = fdcId,
        name = name,
        brand = brand,
        gtin = null,
        servingLabel = servingLabel,
        calories = calories,
        protein = 2.0,
        carbs = 20.0,
        fat = 1.0,
        fiber = null,
        blockId = blockId,
        recordOffset = offset,
        recordLength = length,
    )
}
