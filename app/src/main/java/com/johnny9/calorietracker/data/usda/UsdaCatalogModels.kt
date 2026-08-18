package com.johnny9.calorietracker.data.usda

import com.johnny9.calorietracker.domain.cleanFoodName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.InflaterInputStream

data class UsdaCatalogSource(
    val packId: String,
    val releaseId: String,
    val releaseDate: String,
    val sourceUrl: String,
    val license: String,
    val attribution: String,
)

internal data class UsdaPackManifest(
    val schemaVersion: String,
    val databaseEntry: String,
    val databaseSha256: String,
    val databaseSizeBytes: Long,
    val source: UsdaCatalogSource,
)

internal fun UsdaCatalogSource.requireMatches(expected: UsdaCatalogSource) {
    if (releaseDate != expected.releaseDate) {
        throw UsdaCatalogException("USDA catalog metadata release does not match its signed manifest")
    }
    if (sourceUrl != expected.sourceUrl) {
        throw UsdaCatalogException("USDA catalog metadata source URL does not match its signed manifest")
    }
    if (packId != expected.packId || releaseId != expected.releaseId || license != expected.license || attribution != expected.attribution) {
        throw UsdaCatalogException("USDA catalog provenance does not match its signed manifest")
    }
}

data class UsdaFoodSummary(
    val rowId: Long,
    val fdcId: Long,
    val name: String,
    val brand: String?,
    val gtin: String?,
    val servingLabel: String,
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val fiber: Double?,
    internal val blockId: Long,
    internal val recordOffset: Int,
    internal val recordLength: Int,
) {
    val displayName: String
        get() = cleanFoodName(name, brand)

    val hasCompleteNutrition: Boolean
        get() = listOf(calories, protein, carbs, fat, fiber).all { it != null }

    val hasRequiredNutrition: Boolean
        get() = listOf(calories, protein, carbs, fat).all { it != null }

    val hasImportableNutrition: Boolean
        get() = calories.isSafeNutrition(MAX_CALORIES) &&
            protein.isSafeNutrition(MAX_NUTRIENT_GRAMS) &&
            carbs.isSafeNutrition(MAX_NUTRIENT_GRAMS) &&
            fat.isSafeNutrition(MAX_NUTRIENT_GRAMS) &&
            (fiber == null || fiber.isSafeNutrition(MAX_NUTRIENT_GRAMS))

    private fun Double?.isSafeNutrition(maximum: Double): Boolean = this != null && isFinite() && this in 0.0..maximum

    private companion object {
        const val MAX_CALORIES = 100_000.0
        const val MAX_NUTRIENT_GRAMS = 100_000.0
    }
}

data class UsdaFoodRecord(
    val summary: UsdaFoodSummary,
    val dataType: String,
    val servingGrams: Double?,
    val sourceRevision: String?,
    val sourceUpdatedAtEpochMs: Long?,
)

data class UsdaCatalogSearchResult(
    val source: UsdaCatalogSource?,
    val foods: List<UsdaFoodSummary> = emptyList(),
    val error: String? = null,
) {
    val isAvailable: Boolean get() = source != null
}

class UsdaCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object UsdaPackFormat {
    const val SCHEMA_VERSION = "1"
    const val DATABASE_ENTRY = "usda-catalog.sqlite"
    const val PACK_MANIFEST_ENTRY = "usda-catalog-manifest.json"
    const val ASSET_PATH = "usda/usda-catalog-pack.jar"
    const val MAX_BLOCK_BYTES = 16 * 1_024 * 1_024
    const val MAX_RECORD_BYTES = MAX_BLOCK_BYTES

    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")
    private val gtinPattern = Regex("[0-9]{8,14}")
    private val json = Json { ignoreUnknownKeys = true }

    fun normalizeGtin(raw: String): String? {
        val value = raw.trim()
        if (!gtinPattern.matches(value)) return null
        return when {
            value.length in 9..12 -> value.padStart(13, '0')
            value.length == 14 && value.startsWith('0') -> value.drop(1)
            else -> value
        }
    }

    fun ftsQuery(raw: String): String? {
        val tokens = tokenPattern.findAll(raw.lowercase(Locale.ROOT))
            .map(MatchResult::value)
            .filter { it.length >= 2 }
            .take(8)
            .toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { "$it*" }
    }

    fun decodeBlock(codec: String, compressed: ByteArray, expectedSize: Int, expectedSha256: String): ByteArray {
        if (codec != "zlib") throw UsdaCatalogException("Unsupported USDA payload codec: $codec")
        if (expectedSize !in 1..MAX_BLOCK_BYTES) throw UsdaCatalogException("USDA payload block has an unsafe size")
        val output = ByteArrayOutputStream(expectedSize)
        try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expectedSize || total > MAX_BLOCK_BYTES) {
                        throw UsdaCatalogException("USDA payload block expanded beyond its declared size")
                    }
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: UsdaCatalogException) {
            throw error
        } catch (error: Exception) {
            throw UsdaCatalogException("Unable to decompress USDA payload block", error)
        }
        val decoded = output.toByteArray()
        if (decoded.size != expectedSize) throw UsdaCatalogException("USDA payload block size does not match its manifest")
        if (!sha256(decoded).equals(expectedSha256, ignoreCase = true)) {
            throw UsdaCatalogException("USDA payload block checksum failed")
        }
        return decoded
    }

    fun parseRecord(block: ByteArray, summary: UsdaFoodSummary): UsdaFoodRecord {
        val offset = summary.recordOffset
        val length = summary.recordLength
        if (offset < 0 || length !in 1..MAX_RECORD_BYTES || offset > block.size - length) {
            throw UsdaCatalogException("USDA food points outside its payload block")
        }
        val payload = try {
            json.parseToJsonElement(block.decodeToString(offset, offset + length)).jsonObject
        } catch (error: Exception) {
            throw UsdaCatalogException("USDA food payload is unreadable", error)
        }
        val payloadFdcId = payload.long("fdcId")
            ?: throw UsdaCatalogException("USDA food payload has no FDC ID")
        if (payloadFdcId != summary.fdcId) throw UsdaCatalogException("USDA food payload ID does not match its index")
        return UsdaFoodRecord(
            summary = summary,
            dataType = payload.text("dataType")?.take(40) ?: "Unknown",
            servingGrams = payload.nativeServingGrams(summary.servingLabel),
            sourceRevision = payload.nativeRevision(),
            sourceUpdatedAtEpochMs = payload.nativeRevision()?.toEpochMillis(),
        )
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.nativeServingGrams(servingLabel: String): Double? {
        val brandedServing = number("servingSize")
            ?.takeIf { text("servingSizeUnit")?.lowercase(Locale.ROOT) in GRAM_UNITS }
            ?.validServingGrams()
        if (brandedServing != null) return brandedServing
        return if (servingLabel.trim().lowercase(Locale.ROOT) in HUNDRED_GRAM_LABELS) 100.0 else null
    }

    private fun JsonObject.nativeRevision(): String? = sequenceOf(
        "modifiedDate",
        "publicationDate",
        "availableDate",
    ).mapNotNull { key -> text(key) }.firstOrNull()?.take(80)

    private fun Double.validServingGrams(): Double? = takeIf { isFinite() && this > 0.0 && this <= 5_000.0 }

    private fun String.toEpochMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(this).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(this, USDA_DATE).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    private val GRAM_UNITS = setOf("g", "grm", "gram", "grams")
    private val HUNDRED_GRAM_LABELS = setOf("100 g", "100 gram", "100 grams")
    private val USDA_DATE = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US)
}
