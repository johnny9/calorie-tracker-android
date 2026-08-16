package com.johnny9.calorietracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FoodEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        DiaryEntryEntity::class,
        DayStateEntity::class,
        ActivityDailyEntity::class,
        TargetPlanEntity::class,
        FastingPeriodEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao

    companion object {
        @Volatile private var instance: TrackerDatabase? = null

        fun get(context: Context): TrackerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TrackerDatabase::class.java,
                "calorie-tracker.db",
            ).build().also { instance = it }
        }
    }
}
