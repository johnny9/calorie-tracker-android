package com.johnny9.calorietracker

import android.app.Application
import com.johnny9.calorietracker.data.TrackerDatabase
import com.johnny9.calorietracker.data.TrackerRepository
import com.johnny9.calorietracker.data.usda.UsdaCatalogStore
import com.johnny9.calorietracker.foodlookup.OpenFoodFactsClient

class CalorieTrackerApplication : Application() {
    val database by lazy { TrackerDatabase.get(this) }
    val repository by lazy { TrackerRepository(database) }
    val foodLookup by lazy { OpenFoodFactsClient() }
    val usdaCatalog by lazy { UsdaCatalogStore(this) }
}
