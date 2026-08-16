package com.johnny9.calorietracker.foodlookup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object OpenFoodFactsParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val whitespace = Regex("\\s+")
    private val barcodePattern = Regex("[0-9]{8,14}")

    fun searchRequestBody(rawQuery: String): String {
        val query = normalizeQuery(rawQuery)
        return buildJsonObject {
            put("q", query)
            put("page", 1)
            put("page_size", 15)
            put(
                "fields",
                buildJsonArray {
                    add(JsonPrimitive("code"))
                    add(JsonPrimitive("product_name"))
                    add(JsonPrimitive("brands"))
                    add(JsonPrimitive("nutriments"))
                },
            )
        }.toString()
    }

    fun parseSearchResponse(payload: String): List<OnlineFoodCandidate> {
        val root = parseObject(payload)
        val hits = root["hits"] as? JsonArray ?: throw FoodLookupException("The food service returned an unexpected search response")
        return hits.mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val barcode = hit.string("code")?.let(::normalizeBarcode) ?: return@mapNotNull null
            val name = hit.localizedText("product_name")?.bounded(160) ?: return@mapNotNull null
            val nutrients = hit["nutriments"] as? JsonObject
            OnlineFoodCandidate(
                barcode = barcode,
                name = name,
                brand = hit.brand()?.bounded(100),
                caloriesPer100g = nutrients.validNumber("energy-kcal_100g", 1_000.0),
                proteinPer100g = nutrients.validNumber("proteins_100g", 100.0),
                carbsPer100g = nutrients.validNumber("carbohydrates_100g", 100.0),
                fatPer100g = nutrients.validNumber("fat_100g", 100.0),
            )
        }.distinctBy(OnlineFoodCandidate::barcode).take(15)
    }

    fun parseProductResponse(payload: String, requestedBarcode: String): OnlineFoodProduct {
        val root = parseObject(payload)
        val apiErrors = root["errors"] as? JsonArray
        if (!apiErrors.isNullOrEmpty()) {
            throw FoodLookupException("Open Food Facts reported an error for this product")
        }
        val status = root.string("status")
        if (status == "failure" || root["product"] !is JsonObject) {
            throw FoodLookupException("That packaged food was not found")
        }
        val product = root["product"]!!.jsonObject
        val barcode = product.string("code")?.let(::normalizeBarcode)
            ?: root.string("code")?.let(::normalizeBarcode)
            ?: throw FoodLookupException("The food service returned a product without a valid barcode")
        val normalizedRequested = normalizeBarcode(requestedBarcode)
            ?: throw FoodLookupException("The selected product has an invalid barcode")
        if (barcode != normalizedRequested) {
            throw FoodLookupException("The food service returned a different barcode than the selected product")
        }

        val name = product.localizedText("product_name")?.bounded(160)
            ?: throw FoodLookupException("The selected product has no name")
        val servingQuantity = product.number("serving_quantity")
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 5_000.0 }
            ?: throw FoodLookupException("This product has no usable serving quantity")
        val servingUnit = product.string("serving_quantity_unit")?.lowercase()
            ?.takeIf { it == "g" }
            ?: throw FoodLookupException("Only products with gram-based servings can currently be imported")

        val qualityErrors = product["data_quality_errors_tags"] as? JsonArray
        if (!qualityErrors.isNullOrEmpty()) {
            throw FoodLookupException("Open Food Facts flags this product's nutrition data as invalid")
        }
        val warningCount = (product["data_quality_warnings_tags"] as? JsonArray)?.size ?: 0

        val nutrition = product["nutrition"] as? JsonObject
            ?: throw FoodLookupException("This product has no nutrition panel")
        val aggregate = nutrition["aggregated_set"] as? JsonObject
            ?: throw FoodLookupException("This product has no normalized nutrition panel")
        if (aggregate.string("per") != "100g") {
            throw FoodLookupException("This product's nutrition basis is not supported")
        }
        val preparation = aggregate.string("preparation")
        if (preparation != null && preparation != "as_sold") {
            throw FoodLookupException("Only as-sold nutrition can be imported")
        }
        val nutrients = aggregate["nutrients"] as? JsonObject
            ?: throw FoodLookupException("This product has no normalized nutrients")

        val caloriesPer100 = nutrients.nutrient("energy-kcal", "kcal", 1_000.0)
            ?: nutrients.nutrient("energy-kj", "kJ", 4_184.0)?.div(4.184)
            ?: throw FoodLookupException("This product is missing energy data")
        val proteinPer100 = nutrients.requiredNutrient("proteins")
        val carbsPer100 = nutrients.requiredNutrient("carbohydrates")
        val fatPer100 = nutrients.requiredNutrient("fat")
        val fiberPer100 = nutrients.requiredNutrient("fiber")
        val factor = servingQuantity / 100.0

        return OnlineFoodProduct(
            barcode = barcode,
            name = name,
            brand = product.brand()?.bounded(100),
            servingLabel = product.string("serving_size")?.bounded(100) ?: "$servingQuantity $servingUnit",
            servingQuantity = servingQuantity,
            servingUnit = servingUnit,
            calories = caloriesPer100 * factor,
            protein = proteinPer100 * factor,
            carbs = carbsPer100 * factor,
            fat = fatPer100 * factor,
            fiber = fiberPer100 * factor,
            sourceRevision = product["rev"]?.jsonPrimitive?.contentOrNull?.bounded(40),
            sourceUpdatedAtEpochMs = product.number("last_modified_t")
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE / 1_000.0 }
                ?.times(1_000.0)
                ?.toLong(),
            completeness = product.number("completeness")?.takeIf { it in 0.0..1.0 },
            warningCount = warningCount,
        )
    }

    fun normalizeQuery(rawQuery: String): String {
        val query = rawQuery.trim().replace(whitespace, " ")
        if (query.length !in 2..80) throw FoodLookupException("Enter between 2 and 80 characters")
        return query
    }

    fun normalizeBarcode(rawBarcode: String): String? {
        val barcode = rawBarcode.trim()
        if (!barcodePattern.matches(barcode)) return null
        return when {
            barcode.length in 9..12 -> barcode.padStart(13, '0')
            barcode.length == 14 && barcode.startsWith('0') -> barcode.drop(1)
            else -> barcode
        }
    }

    private fun parseObject(payload: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (error: Exception) {
        throw FoodLookupException("The food service returned unreadable data", error)
    }

    private fun JsonObject.requiredNutrient(name: String): Double = nutrient(name, "g", 100.0)
        ?: throw FoodLookupException("This product is missing $name data")

    private fun JsonObject.nutrient(name: String, expectedUnit: String, maximum: Double): Double? {
        val node = this[name] as? JsonObject ?: return null
        if (node.string("unit") != expectedUnit) return null
        return node.validNumber("value", maximum)
    }

    private fun JsonObject.brand(): String? = when (val value = this["brands"]) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .take(3)
            .joinToString(", ")
            .takeIf(String::isNotEmpty)
        else -> null
    }

    private fun JsonObject.localizedText(key: String): String? = when (val value = this[key]) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        is JsonObject -> (value["en"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: value.values.asSequence().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.firstOrNull(String::isNotEmpty)
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject?.validNumber(key: String, maximum: Double): Double? = this?.number(key)
        ?.takeIf { it.isFinite() && it >= 0.0 && it <= maximum }

    private fun String.bounded(maximumLength: Int): String = take(maximumLength)
}
