package com.everystreet.survey.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room Database for storing survey routes and progress
 */
@Database(
    entities = [SurveyRoute::class, SurveyedStreet::class, SurveyProgress::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(RouteConverters::class)
abstract class SurveyDatabase : RoomDatabase() {
    abstract fun surveyRouteDao(): SurveyRouteDao
    abstract fun surveyedStreetDao(): SurveyedStreetDao
    abstract fun surveyProgressDao(): SurveyProgressDao

    companion object {
        @Volatile
        private var INSTANCE: SurveyDatabase? = null

        fun getDatabase(context: Context): SurveyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SurveyDatabase::class.java,
                    "survey_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * DAO for SurveyRoute operations
 */
@Dao
interface SurveyRouteDao {
    @Query("SELECT * FROM survey_routes ORDER BY createdAt DESC")
    fun getAllRoutes(): Flow<List<SurveyRoute>>

    @Query("SELECT * FROM survey_routes WHERE id = :routeId")
    suspend fun getRouteById(routeId: Long): SurveyRoute?

    @Query("SELECT * FROM survey_routes WHERE id = :routeId")
    fun getRouteByIdFlow(routeId: Long): Flow<SurveyRoute?>

    @Query("SELECT * FROM survey_routes WHERE status = :status")
    fun getRoutesByStatus(status: RouteStatus): Flow<List<SurveyRoute>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: SurveyRoute): Long

    @Update
    suspend fun updateRoute(route: SurveyRoute)

    @Delete
    suspend fun deleteRoute(route: SurveyRoute)

    @Query("DELETE FROM survey_routes WHERE id = :routeId")
    suspend fun deleteRouteById(routeId: Long)

    @Query("UPDATE survey_routes SET status = :status WHERE id = :routeId")
    suspend fun updateRouteStatus(routeId: Long, status: RouteStatus)
}

/**
 * DAO for SurveyedStreet operations
 */
@Dao
interface SurveyedStreetDao {
    @Query("SELECT * FROM surveyed_streets WHERE routeId = :routeId")
    fun getStreetsByRoute(routeId: Long): Flow<List<SurveyedStreet>>

    @Query("SELECT * FROM surveyed_streets WHERE edgeId = :edgeId")
    suspend fun getStreetById(edgeId: Long): SurveyedStreet?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreet(street: SurveyedStreet)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreets(streets: List<SurveyedStreet>)

    @Query("UPDATE surveyed_streets SET surveyCount = surveyCount + 1, surveyedAt = :timestamp WHERE edgeId = :edgeId")
    suspend fun incrementSurveyCount(edgeId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM surveyed_streets WHERE routeId = :routeId")
    suspend fun deleteStreetsByRoute(routeId: Long)

    @Query("SELECT COUNT(*) FROM surveyed_streets WHERE routeId = :routeId")
    suspend fun getCompletedStreetCount(routeId: Long): Int
}

/**
 * DAO for SurveyProgress operations
 */
@Dao
interface SurveyProgressDao {
    @Query("SELECT * FROM survey_progress WHERE routeId = :routeId")
    suspend fun getProgress(routeId: Long): SurveyProgress?

    @Query("SELECT * FROM survey_progress WHERE routeId = :routeId")
    fun getProgressFlow(routeId: Long): Flow<SurveyProgress?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: SurveyProgress)

    @Update
    suspend fun updateProgress(progress: SurveyProgress)

    @Query("UPDATE survey_progress SET currentEdgeIndex = :edgeIndex, completedEdges = :completed, distanceCovered = :distance, lastUpdated = :timestamp WHERE routeId = :routeId")
    suspend fun updateProgressState(
        routeId: Long,
        edgeIndex: Int,
        completed: Int,
        distance: Double,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE survey_progress SET isPaused = :paused, lastUpdated = :timestamp WHERE routeId = :routeId")
    suspend fun setPaused(routeId: Long, paused: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM survey_progress WHERE routeId = :routeId")
    suspend fun deleteProgress(routeId: Long)
}
