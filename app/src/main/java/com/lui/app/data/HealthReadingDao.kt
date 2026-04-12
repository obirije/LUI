package com.lui.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HealthReadingDao {

    @Insert
    fun insert(reading: HealthReadingEntity)

    /** Get readings for a metric within the last N milliseconds, newest first */
    @Query("SELECT * FROM health_readings WHERE metric = :metric AND timestamp > :since ORDER BY timestamp DESC")
    fun getReadingsSince(metric: String, since: Long): List<HealthReadingEntity>

    /** Get the most recent reading for a metric */
    @Query("SELECT * FROM health_readings WHERE metric = :metric ORDER BY timestamp DESC LIMIT 1")
    fun getLatest(metric: String): HealthReadingEntity?

    /** Get average value for a metric within a time range */
    @Query("SELECT AVG(value) FROM health_readings WHERE metric = :metric AND timestamp > :since")
    fun getAverage(metric: String, since: Long): Float?

    /** Get min value for a metric within a time range */
    @Query("SELECT MIN(value) FROM health_readings WHERE metric = :metric AND timestamp > :since")
    fun getMin(metric: String, since: Long): Float?

    /** Get max value for a metric within a time range */
    @Query("SELECT MAX(value) FROM health_readings WHERE metric = :metric AND timestamp > :since")
    fun getMax(metric: String, since: Long): Float?

    /** Get reading count for a metric within a time range */
    @Query("SELECT COUNT(*) FROM health_readings WHERE metric = :metric AND timestamp > :since")
    fun getCount(metric: String, since: Long): Int

    /** Delete readings older than a given timestamp (cleanup) */
    @Query("DELETE FROM health_readings WHERE timestamp < :before")
    fun deleteOlderThan(before: Long)
}
