package com.johnny9.calorietracker

import android.app.Application
import com.johnny9.calorietracker.data.TrackerDatabase
import com.johnny9.calorietracker.data.TrackerRepository

class CalorieTrackerApplication : Application() {
    val database by lazy { TrackerDatabase.get(this) }
    val repository by lazy { TrackerRepository(database) }
}
