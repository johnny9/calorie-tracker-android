package com.johnny9.calorietracker.foodlookup

data class OnlineFoodCandidate(
    val barcode: String,
    val name: String,
    val brand: String?,
    val caloriesPer100g: Double?,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
)

data class OnlineFoodProduct(
    val barcode: String,
    val name: String,
    val brand: String?,
    val servingLabel: String,
    val servingQuantity: Double,
    val servingUnit: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double,
    val sourceRevision: String?,
    val sourceUpdatedAtEpochMs: Long?,
    val completeness: Double?,
    val warningCount: Int,
)

interface FoodLookup {
    val isAvailable: Boolean
    suspend fun search(query: String): List<OnlineFoodCandidate>
    suspend fun product(barcode: String): OnlineFoodProduct
}

class FoodLookupException(message: String, cause: Throwable? = null) : Exception(message, cause)
