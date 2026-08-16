package com.johnny9.calorietracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {
    @Query("SELECT * FROM foods WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeFoods(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods ORDER BY name COLLATE NOCASE")
    suspend fun allFoods(): List<FoodEntity>

    @Query("SELECT * FROM foods WHERE id = :id LIMIT 1")
    suspend fun food(id: String): FoodEntity?

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun foodCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFoods(foods: List<FoodEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFood(food: FoodEntity): Long

    @Upsert
    suspend fun upsertFood(food: FoodEntity)

    @Transaction
    suspend fun saveOnlineFood(food: FoodEntity): Boolean {
        val inserted = insertFood(food) != -1L
        if (!inserted) upsertFood(food)
        return inserted
    }

    @Query("UPDATE foods SET isArchived = 1 WHERE id = :id")
    suspend fun archiveFood(id: String)

    @Query("SELECT * FROM recipes WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY name COLLATE NOCASE")
    suspend fun allRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE id = :id LIMIT 1")
    suspend fun recipe(id: String): RecipeEntity?

    @Query("SELECT * FROM recipe_ingredients ORDER BY recipeId, foodNameSnapshot")
    fun observeRecipeIngredients(): Flow<List<RecipeIngredientEntity>>

    @Query("SELECT * FROM recipe_ingredients ORDER BY recipeId, foodNameSnapshot")
    suspend fun allRecipeIngredients(): List<RecipeIngredientEntity>

    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY foodNameSnapshot")
    suspend fun ingredientsForRecipe(recipeId: String): List<RecipeIngredientEntity>

    @Insert
    suspend fun insertRecipe(recipe: RecipeEntity)

    @Insert
    suspend fun insertRecipeIngredients(ingredients: List<RecipeIngredientEntity>)

    @Query("UPDATE recipes SET isArchived = 1 WHERE id = :id")
    suspend fun archiveRecipe(id: String)

    @Query("SELECT * FROM diary_entries WHERE localDate = :localDate ORDER BY mealGroup, createdAtEpochMs")
    fun observeDiary(localDate: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE localDate BETWEEN :start AND :end ORDER BY localDate, createdAtEpochMs")
    fun observeDiaryRange(start: String, end: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries ORDER BY localDate, createdAtEpochMs")
    suspend fun allDiaryEntries(): List<DiaryEntryEntity>

    @Insert
    suspend fun insertDiaryEntry(entry: DiaryEntryEntity)

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun deleteDiaryEntry(id: String)

    @Query("SELECT * FROM day_states WHERE localDate = :localDate LIMIT 1")
    fun observeDayState(localDate: String): Flow<DayStateEntity?>

    @Query("SELECT * FROM day_states WHERE localDate BETWEEN :start AND :end ORDER BY localDate")
    fun observeDayStatesRange(start: String, end: String): Flow<List<DayStateEntity>>

    @Query("SELECT * FROM day_states ORDER BY localDate")
    suspend fun allDayStates(): List<DayStateEntity>

    @Upsert
    suspend fun upsertDayState(state: DayStateEntity)

    @Query("SELECT * FROM activity_daily WHERE localDate = :localDate LIMIT 1")
    fun observeActivity(localDate: String): Flow<ActivityDailyEntity?>

    @Query("SELECT * FROM activity_daily WHERE localDate = :localDate LIMIT 1")
    suspend fun activity(localDate: String): ActivityDailyEntity?

    @Query("SELECT * FROM activity_daily WHERE localDate BETWEEN :start AND :end ORDER BY localDate")
    fun observeActivityRange(start: String, end: String): Flow<List<ActivityDailyEntity>>

    @Query("SELECT * FROM activity_daily ORDER BY localDate")
    suspend fun allActivity(): List<ActivityDailyEntity>

    @Upsert
    suspend fun upsertActivity(activity: ActivityDailyEntity)

    @Query("SELECT * FROM target_plans WHERE id = 'active' LIMIT 1")
    fun observeTarget(): Flow<TargetPlanEntity?>

    @Query("SELECT * FROM target_plans ORDER BY effectiveFrom")
    suspend fun allTargets(): List<TargetPlanEntity>

    @Upsert
    suspend fun upsertTarget(target: TargetPlanEntity)

    @Query("SELECT * FROM fasting_periods ORDER BY startEpochMs DESC")
    fun observeFasts(): Flow<List<FastingPeriodEntity>>

    @Query("SELECT * FROM fasting_periods ORDER BY startEpochMs")
    suspend fun allFasts(): List<FastingPeriodEntity>

    @Insert
    suspend fun insertFast(fast: FastingPeriodEntity)

    @Query("UPDATE fasting_periods SET endEpochMs = :endEpochMs WHERE id = :id")
    suspend fun endFast(id: String, endEpochMs: Long)
}
