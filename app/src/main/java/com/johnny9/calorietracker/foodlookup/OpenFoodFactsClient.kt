package com.johnny9.calorietracker.foodlookup

import com.johnny9.calorietracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenFoodFactsClient(
    private val transport: JsonHttpTransport = UrlConnectionJsonTransport(),
    appVersion: String = BuildConfig.VERSION_NAME,
    contactEmail: String = BuildConfig.OPEN_FOOD_FACTS_CONTACT_EMAIL,
    searchIntervalMs: Long = 6_000,
    productIntervalMs: Long = 4_000,
) : FoodLookup {
    private val normalizedContactEmail = contactEmail.trim()
    private val contactIsConfigured = normalizedContactEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
    private val userAgent = "CalorieTracker/${appVersion.take(40)} ($normalizedContactEmail)"
    private val searchLimiter = MinimumIntervalLimiter(searchIntervalMs)
    private val productLimiter = MinimumIntervalLimiter(productIntervalMs)

    override val isAvailable: Boolean get() = contactIsConfigured

    override suspend fun search(query: String): List<OnlineFoodCandidate> {
        ensureConfigured()
        val body = OpenFoodFactsParser.searchRequestBody(query)
        searchLimiter.awaitTurn()
        val response = transport.execute(
            JsonHttpRequest(
                method = "POST",
                url = "https://search.openfoodfacts.org/search",
                headers = standardHeaders + ("Content-Type" to "application/json"),
                body = body,
            ),
        )
        return OpenFoodFactsParser.parseSearchResponse(response)
    }

    override suspend fun product(barcode: String): OnlineFoodProduct {
        ensureConfigured()
        val normalized = OpenFoodFactsParser.normalizeBarcode(barcode)
            ?: throw FoodLookupException("The selected product has an invalid barcode")
        productLimiter.awaitTurn()
        val fields = "code,product_name,brands,serving_size,serving_quantity,serving_quantity_unit,nutrition,rev,last_modified_t,completeness,data_quality_errors_tags,data_quality_warnings_tags"
        val response = transport.execute(
            JsonHttpRequest(
                method = "GET",
                url = "https://world.openfoodfacts.org/api/v3.6/product/$normalized.json?fields=$fields",
                headers = standardHeaders,
            ),
        )
        return OpenFoodFactsParser.parseProductResponse(response, normalized)
    }

    private val standardHeaders: Map<String, String>
        get() = mapOf("Accept" to "application/json", "User-Agent" to userAgent)

    private fun ensureConfigured() {
        if (!contactIsConfigured) {
            throw FoodLookupException("Online food search is not configured in this build; local foods are still available")
        }
    }
}

data class JsonHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

interface JsonHttpTransport {
    suspend fun execute(request: JsonHttpRequest): String
}

class UrlConnectionJsonTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maximumResponseBytes: Int = 1_048_576,
) : JsonHttpTransport {
    override suspend fun execute(request: JsonHttpRequest): String = withContext(Dispatchers.IO) {
        require(request.url.startsWith("https://"))
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            when (val status = connection.responseCode) {
                in 200..299 -> connection.inputStream.use { it.readBounded(maximumResponseBytes) }
                404 -> throw FoodLookupException("That packaged food was not found")
                429 -> throw FoodLookupException("Open Food Facts is rate-limiting requests; try again shortly")
                in 300..399 -> throw FoodLookupException("The food service returned an unsafe redirect")
                in 500..599 -> throw FoodLookupException("Open Food Facts is temporarily unavailable")
                else -> throw FoodLookupException("The food service returned HTTP $status")
            }
        } catch (error: FoodLookupException) {
            throw error
        } catch (error: Exception) {
            throw FoodLookupException("Unable to reach Open Food Facts; local foods are still available", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBounded(maximumBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw FoodLookupException("The food service returned too much data")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

private class MinimumIntervalLimiter(private val intervalMs: Long) {
    private val mutex = Mutex()
    private var lastRequestNanos = 0L

    suspend fun awaitTurn() = mutex.withLock {
        if (intervalMs <= 0) return@withLock
        val now = System.nanoTime()
        if (lastRequestNanos != 0L) {
            val elapsedMs = (now - lastRequestNanos).coerceAtLeast(0L) / 1_000_000L
            if (elapsedMs < intervalMs) delay(intervalMs - elapsedMs)
        }
        lastRequestNanos = System.nanoTime()
    }
}
