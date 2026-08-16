package com.johnny9.calorietracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
            }
        }

        internal val MIGRATION_1_2_STATEMENTS = listOf(
            "ALTER TABLE foods ADD COLUMN sourceRevision TEXT",
            "ALTER TABLE foods ADD COLUMN sourceUpdatedAtEpochMs INTEGER",
            "ALTER TABLE foods ADD COLUMN sourceCompleteness REAL",
            "ALTER TABLE foods ADD COLUMN sourceWarningCount INTEGER",
            "ALTER TABLE foods ADD COLUMN dataQuality TEXT NOT NULL DEFAULT 'UNSPECIFIED'",
            """
            UPDATE foods
            SET dataQuality = CASE source
                WHEN 'USDA_REFERENCE' THEN 'REFERENCE'
                WHEN 'USER_CUSTOM' THEN 'USER_ENTERED'
                ELSE 'UNSPECIFIED'
            END
            """.trimIndent(),
        )
    }
}
