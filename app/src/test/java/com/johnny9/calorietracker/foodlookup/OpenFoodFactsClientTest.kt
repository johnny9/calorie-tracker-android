package com.johnny9.calorietracker.foodlookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsClientTest {
    @Test
    fun sendsOnlyExplicitFoodLookupDataAndIdentifiesTheApplication() = runBlocking {
        val transport = RecordingTransport(
            responses = ArrayDeque(
                listOf(
                    """{"hits":[]}""",
                    productPayload,
                ),
            ),
        )
        val client = OpenFoodFactsClient(
            transport = transport,
            appVersion = "test",
            contactEmail = "calorie-tracker-tests@example.com",
            searchIntervalMs = 0,
            productIntervalMs = 0,
        )

        client.search("Chomps")
        client.product("0856584004190")

        val search = transport.requests[0]
        assertEquals("POST", search.method)
        assertEquals("https://search.openfoodfacts.org/search", search.url)
        assertTrue(search.body.orEmpty().contains("Chomps"))
        assertFalse(search.body.orEmpty().contains("diary", ignoreCase = true))
        assertEquals("CalorieTracker/test (calorie-tracker-tests@example.com)", search.headers.getValue("User-Agent"))

        val product = transport.requests[1]
        assertEquals("GET", product.method)
        assertTrue(product.url.contains("/0856584004190.json"))
        assertEquals(null, product.body)
        assertFalse(product.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun refusesNetworkRequestsWithoutAProviderContact() = runBlocking {
        val transport = RecordingTransport(ArrayDeque())
        val client = OpenFoodFactsClient(
            transport = transport,
            appVersion = "test",
            contactEmail = "",
            searchIntervalMs = 0,
            productIntervalMs = 0,
        )

        val error = runCatching { client.search("Chomps") }.exceptionOrNull()

        assertTrue(error is FoodLookupException)
        assertTrue(transport.requests.isEmpty())
    }

    private class RecordingTransport(private val responses: ArrayDeque<String>) : JsonHttpTransport {
        val requests = mutableListOf<JsonHttpRequest>()

        override suspend fun execute(request: JsonHttpRequest): String {
            requests += request
            return responses.removeFirst()
        }
    }

    private val productPayload =
        """
        {
          "code":"0856584004190",
          "status":"success",
          "product":{
            "code":"0856584004190",
            "product_name":"Original Beef Sticks",
            "brands":"CHOMPS",
            "serving_quantity":32,
            "serving_quantity_unit":"g",
            "serving_size":"1 portion (32 g)",
            "data_quality_errors_tags":[],
            "data_quality_warnings_tags":[],
            "nutrition":{"aggregated_set":{"per":"100g","preparation":"as_sold","nutrients":{
              "energy-kcal":{"value":312.5,"unit":"kcal"},
              "proteins":{"value":31.25,"unit":"g"},
              "carbohydrates":{"value":0,"unit":"g"},
              "fat":{"value":21.875,"unit":"g"},
              "fiber":{"value":0,"unit":"g"}
            }}}
          }
        }
        """.trimIndent()
}
