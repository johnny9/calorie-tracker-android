package com.johnny9.calorietracker.data.usda

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.LinkedHashMap

class UsdaCatalogStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val installer = UsdaPackInstaller(appContext)
    private val lock = Any()
    private var opened: OpenCatalog? = null
    private var bundledInstallAttempted = false
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private var cachedBlockBytes = 0

    suspend fun search(rawQuery: String, limit: Int = 20): UsdaCatalogSearchResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val catalog = openCatalog() ?: return@synchronized UsdaCatalogSearchResult(source = null)
            try {
                val normalizedGtin = UsdaPackFormat.normalizeGtin(rawQuery)
                val foods = if (normalizedGtin != null && rawQuery.trim().all(Char::isDigit)) {
                    catalog.database.rawQuery(GTIN_SQL, arrayOf(normalizedGtin)).use(::readSummaries)
                } else {
                    val query = UsdaPackFormat.ftsQuery(rawQuery) ?: return@synchronized UsdaCatalogSearchResult(catalog.source)
                    catalog.database.rawQuery(NAME_SQL, arrayOf(query, limit.coerceIn(1, 50).toString())).use(::readSummaries)
                }
                UsdaCatalogSearchResult(catalog.source, foods)
            } catch (error: Exception) {
                Log.w(TAG, "USDA catalog search failed", error)
                UsdaCatalogSearchResult(catalog.source, error = "The offline USDA catalog could not be searched")
            }
        }
    }

    suspend fun food(fdcId: Long): UsdaFoodRecord = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val catalog = openCatalog() ?: throw UsdaCatalogException("No offline USDA catalog is installed")
            val summary = catalog.database.rawQuery(FOOD_SQL, arrayOf(fdcId.toString())).use { rows ->
                readSummaries(rows).singleOrNull()
            } ?: throw UsdaCatalogException("That USDA food is no longer in the installed catalog")
            val block = loadBlock(catalog.database, summary.blockId)
            UsdaPackFormat.parseRecord(block, summary)
        }
    }

    /** Installer hook for a future Storage Access Framework replacement flow. */
    suspend fun installReplacement(packJar: InputStream): UsdaCatalogSource = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val previous = installer.activeDatabase()
            val installed = try {
                installer.installSignedJar(packJar, ::validateDatabase)
            } catch (error: Exception) {
                throw if (error is UsdaCatalogException) error else UsdaCatalogException("Unable to install USDA catalog", error)
            }
            closeOpened()
            try {
                openFile(installed).also {
                    opened = it
                    onCatalogOpened(installed)
                }.source
            } catch (error: Exception) {
                closeOpened()
                installer.activate(previous)
                if (previous != null && previous.isFile) opened = runCatching { openFile(previous) }.getOrNull()
                throw UsdaCatalogException("The replacement USDA catalog could not be opened", error)
            }
        }
    }

    override fun close() {
        synchronized(lock) { closeOpened() }
    }

    private fun openCatalog(): OpenCatalog? {
        opened?.let { return it }
        var file: File? = null
        if (!bundledInstallAttempted) {
            bundledInstallAttempted = true
            file = try {
                installer.installBundledIfNeeded(validator = ::validateDatabase)
            } catch (error: Exception) {
                Log.w(TAG, "Bundled USDA catalog was rejected", error)
                installer.activeDatabase()
            }
        }
        if (file == null) file = installer.activeDatabase()
        if (file == null) return null
        return try {
            openFile(file).also {
                opened = it
                onCatalogOpened(file)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Installed USDA catalog was rejected", error)
            installer.activate(null)
            val recovered = try {
                installer.installBundledIfNeeded(force = true, validator = ::validateDatabase)
            } catch (recoveryError: Exception) {
                Log.w(TAG, "Bundled USDA catalog recovery failed", recoveryError)
                null
            }
            recovered?.let { recoveredFile ->
                runCatching {
                    openFile(recoveredFile).also { catalog ->
                        opened = catalog
                        onCatalogOpened(recoveredFile)
                    }
                }.getOrNull()
            }
        }
    }

    private fun onCatalogOpened(active: File) {
        val retained = runCatching { installer.pruneInactiveCatalogs(active) }
            .onFailure { Log.w(TAG, "Unable to prune inactive USDA catalogs", it) }
            .getOrDefault(emptyList())
        if (retained.isNotEmpty()) {
            Log.w(TAG, "Unable to remove ${retained.size} inactive USDA catalog file(s)")
        }
    }

    private fun openFile(file: File): OpenCatalog {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            val source = readSource(database)
            return OpenCatalog(file, database, source)
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    private fun validateDatabase(file: File, manifest: UsdaPackManifest) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            val required = setOf("catalog_metadata", "catalog_food", "catalog_food_fts", "catalog_gtin", "catalog_payload_block")
            val present = database.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            if (!present.containsAll(required)) throw UsdaCatalogException("USDA catalog database is missing required tables")
            REQUIRED_COLUMNS.forEach { (table, expected) ->
                val columns = database.rawQuery("PRAGMA table_info($table)", null).use { rows ->
                    buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
                }
                if (!columns.containsAll(expected)) throw UsdaCatalogException("USDA catalog table $table has an unsupported layout")
            }
            readSource(database).requireMatches(manifest.source)
            val integrity = database.rawQuery("PRAGMA quick_check(1)", null).use { rows ->
                if (rows.moveToFirst()) rows.getString(0) else null
            }
            if (integrity != "ok") throw UsdaCatalogException("USDA catalog database failed its integrity check")
            val invalidBlocks = database.rawQuery(
                "SELECT COUNT(*) FROM catalog_payload_block WHERE codec != 'zlib' OR uncompressed_size <= 0 OR uncompressed_size > ?",
                arrayOf(UsdaPackFormat.MAX_BLOCK_BYTES.toString()),
            ).use { rows -> rows.moveToFirst(); rows.getLong(0) }
            if (invalidBlocks != 0L) throw UsdaCatalogException("USDA catalog contains invalid payload blocks")
            val invalidLocators = database.rawQuery(
                """
                SELECT COUNT(*) FROM catalog_food f
                LEFT JOIN catalog_payload_block b ON b.block_id = f.block_id
                WHERE b.block_id IS NULL OR f.record_offset < 0 OR f.record_length <= 0
                   OR f.record_length > ? OR f.record_offset + f.record_length > b.uncompressed_size
                """.trimIndent(),
                arrayOf(UsdaPackFormat.MAX_RECORD_BYTES.toString()),
            ).use { rows -> rows.moveToFirst(); rows.getLong(0) }
            if (invalidLocators != 0L) throw UsdaCatalogException("USDA catalog contains invalid food locators")
            database.rawQuery(NAME_SQL, arrayOf("catalogvalidationtoken*", "1")).use { it.count }
            database.rawQuery(GTIN_SQL, arrayOf("0000000000000")).use { it.count }
        } finally {
            database.close()
        }
    }

    private fun readSource(database: SQLiteDatabase): UsdaCatalogSource {
        val metadata = database.rawQuery("SELECT key, value FROM catalog_metadata", null).use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
        }
        if (metadata["schema_version"] != UsdaPackFormat.SCHEMA_VERSION) {
            throw UsdaCatalogException("USDA catalog schema is unsupported")
        }
        fun required(key: String): String = metadata[key]?.trim()?.takeIf(String::isNotEmpty)
            ?: throw UsdaCatalogException("USDA catalog metadata is missing $key")
        return UsdaCatalogSource(
            packId = required("pack_id"),
            releaseId = required("release_id"),
            releaseDate = required("release_date"),
            sourceUrl = required("source_url"),
            license = required("license"),
            attribution = required("attribution"),
        )
    }

    private fun loadBlock(database: SQLiteDatabase, blockId: Long): ByteArray {
        blocks[blockId]?.let { return it }
        val decoded = database.rawQuery(
            "SELECT codec, uncompressed_size, sha256, payload FROM catalog_payload_block WHERE block_id = ?",
            arrayOf(blockId.toString()),
        ).use { rows ->
            if (!rows.moveToFirst()) throw UsdaCatalogException("USDA food payload block is missing")
            UsdaPackFormat.decodeBlock(
                codec = rows.getString(0),
                compressed = rows.getBlob(3),
                expectedSize = rows.getInt(1),
                expectedSha256 = rows.getString(2),
            )
        }
        blocks[blockId] = decoded
        cachedBlockBytes += decoded.size
        trimCache()
        return decoded
    }

    private fun trimCache() {
        val iterator = blocks.entries.iterator()
        while (cachedBlockBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
            cachedBlockBytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    private fun closeOpened() {
        opened?.database?.close()
        opened = null
        blocks.clear()
        cachedBlockBytes = 0
    }

    private fun readSummaries(rows: Cursor): List<UsdaFoodSummary> = buildList {
        while (rows.moveToNext()) {
            add(
                UsdaFoodSummary(
                    rowId = rows.getLong(0),
                    fdcId = rows.getLong(1),
                    name = rows.getString(2),
                    brand = rows.nullableString(3),
                    gtin = rows.nullableString(4),
                    servingLabel = rows.getString(5),
                    calories = rows.nullableDouble(6),
                    protein = rows.nullableDouble(7),
                    carbs = rows.nullableDouble(8),
                    fat = rows.nullableDouble(9),
                    fiber = rows.nullableDouble(10),
                    blockId = rows.getLong(11),
                    recordOffset = rows.getInt(12),
                    recordLength = rows.getInt(13),
                ),
            )
        }
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private data class OpenCatalog(val file: File, val database: SQLiteDatabase, val source: UsdaCatalogSource)

    companion object {
        private const val TAG = "UsdaCatalog"
        private const val MAX_CACHE_BYTES = 8 * 1_024 * 1_024
        private const val COLUMNS = "f.rowid,f.fdc_id,f.name,f.brand,f.gtin,f.serving_label,f.calories_kcal,f.protein_g,f.carbs_g,f.fat_g,f.fiber_g,f.block_id,f.record_offset,f.record_length"
        private const val GTIN_SQL = "SELECT $COLUMNS FROM catalog_gtin g JOIN catalog_food f ON f.rowid = g.food_rowid WHERE g.gtin = ? LIMIT 1"
        private const val NAME_SQL = "SELECT $COLUMNS FROM catalog_food_fts JOIN catalog_food f ON f.rowid = catalog_food_fts.rowid WHERE catalog_food_fts MATCH ? LIMIT ?"
        private const val FOOD_SQL = "SELECT $COLUMNS FROM catalog_food f WHERE f.fdc_id = ? LIMIT 1"
        private val REQUIRED_COLUMNS = mapOf(
            "catalog_metadata" to setOf("key", "value"),
            "catalog_food" to setOf(
                "fdc_id", "name", "brand", "gtin", "serving_label",
                "calories_kcal", "protein_g", "carbs_g", "fat_g", "fiber_g",
                "block_id", "record_offset", "record_length",
            ),
            "catalog_gtin" to setOf("gtin", "food_rowid"),
            "catalog_payload_block" to setOf("block_id", "codec", "uncompressed_size", "record_count", "sha256", "payload"),
        )
    }
}
