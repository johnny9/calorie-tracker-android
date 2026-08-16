package com.johnny9.calorietracker.data

import com.johnny9.calorietracker.domain.toMilli

/**
 * A deliberately small, versioned starter catalog for clean-install offline use.
 * Generic values are rounded from standard-serving references in USDA FoodData Central.
 * Branded label rows name their manufacturer source explicitly.
 * The UI shows the source so a user can decide whether to use or replace a row.
 */
object BundledFoods {
    const val PACK_VERSION = "usda-common-v1"

    fun rows(now: Long): List<FoodEntity> = listOf(
        food("egg-large", "Egg, whole, large", "1 large", 50.0, 72.0, 6.3, 0.4, 4.8, 0.0, now),
        food("banana-medium", "Banana", "1 medium", 118.0, 105.0, 1.3, 27.0, 0.4, 3.1, now),
        food("apple-medium", "Apple with skin", "1 medium", 182.0, 95.0, 0.5, 25.0, 0.3, 4.4, now),
        food("chicken-breast-3oz", "Chicken breast, roasted", "3 oz", 85.0, 128.0, 26.0, 0.0, 2.7, 0.0, now),
        food("rice-white-cup", "White rice, cooked", "1 cup", 158.0, 205.0, 4.3, 44.5, 0.4, 0.6, now),
        food("oatmeal-cup", "Oatmeal, cooked", "1 cup", 234.0, 154.0, 6.0, 27.0, 3.2, 4.0, now),
        food("milk-whole-cup", "Milk, whole", "1 cup", 244.0, 149.0, 7.7, 11.7, 7.9, 0.0, now),
        food("greek-yogurt-170g", "Greek yogurt, plain, nonfat", "170 g", 170.0, 100.0, 17.0, 6.0, 0.0, 0.0, now),
        food("almonds-ounce", "Almonds", "1 oz", 28.35, 164.0, 6.0, 6.1, 14.2, 3.5, now),
        food("peanut-butter-2tbsp", "Peanut butter, smooth", "2 tbsp", 32.0, 190.0, 7.0, 7.0, 16.0, 2.0, now),
        food("broccoli-cup", "Broccoli, cooked", "1 cup", 156.0, 55.0, 3.7, 11.2, 0.6, 5.1, now),
        food("avocado-half", "Avocado", "1/2 fruit", 68.0, 114.0, 1.3, 6.0, 10.5, 4.6, now),
        food("salmon-3oz", "Salmon, cooked", "3 oz", 85.0, 175.0, 18.8, 0.0, 10.5, 0.0, now),
        food("beef-90-3oz", "Ground beef, 90% lean, cooked", "3 oz", 85.0, 184.0, 22.0, 0.0, 10.0, 0.0, now),
        food("black-beans-cup", "Black beans, cooked", "1 cup", 172.0, 227.0, 15.2, 40.8, 0.9, 15.0, now),
        food("bread-whole-wheat", "Whole-wheat bread", "1 slice", 43.0, 81.0, 4.0, 13.8, 1.1, 1.9, now),
        food("cheddar-ounce", "Cheddar cheese", "1 oz", 28.0, 115.0, 6.5, 0.9, 9.4, 0.0, now),
        food("olive-oil-tbsp", "Olive oil", "1 tbsp", 13.5, 119.0, 0.0, 0.0, 13.5, 0.0, now),
        food("potato-baked", "Potato, baked with skin", "1 medium", 173.0, 161.0, 4.3, 36.6, 0.2, 3.8, now),
        food("orange-medium", "Orange", "1 medium", 131.0, 62.0, 1.2, 15.4, 0.2, 3.1, now),
        brandFood(
            slug = "chomps-original-beef-33g",
            name = "Original Beef Stick",
            brand = "Chomps",
            serving = "1 stick (33 g)",
            grams = 33.0,
            calories = 100.0,
            protein = 10.0,
            carbs = 0.0,
            fat = 7.0,
            fiber = 0.0,
            sourceUrl = "https://chomps.com/collections/chomps/products/gluten-free-snack-beef-jerky-stick-original",
            now = now,
        ),
    )

    private fun food(
        slug: String,
        name: String,
        serving: String,
        grams: Double,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double,
        now: Long,
    ) = FoodEntity(
        id = "seed-$PACK_VERSION-$slug",
        name = name,
        servingLabel = serving,
        servingGramsMilli = grams.toMilli(),
        caloriesMilliKcal = calories.toMilli(),
        proteinMilliGram = protein.toMilli(),
        carbsMilliGram = carbs.toMilli(),
        fatMilliGram = fat.toMilli(),
        fiberMilliGram = fiber.toMilli(),
        source = "USDA_REFERENCE",
        sourceId = PACK_VERSION,
        dataQuality = "REFERENCE",
        isUserCreated = false,
        createdAtEpochMs = now,
    )

    private fun brandFood(
        slug: String,
        name: String,
        brand: String,
        serving: String,
        grams: Double,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double,
        sourceUrl: String,
        now: Long,
    ) = FoodEntity(
        id = "seed-brand-v1-$slug",
        name = name,
        brand = brand,
        servingLabel = serving,
        servingGramsMilli = grams.toMilli(),
        caloriesMilliKcal = calories.toMilli(),
        proteinMilliGram = protein.toMilli(),
        carbsMilliGram = carbs.toMilli(),
        fatMilliGram = fat.toMilli(),
        fiberMilliGram = fiber.toMilli(),
        source = "BRAND_LABEL",
        sourceId = sourceUrl,
        dataQuality = "MANUFACTURER_LABEL",
        isUserCreated = false,
        createdAtEpochMs = now,
    )
}
