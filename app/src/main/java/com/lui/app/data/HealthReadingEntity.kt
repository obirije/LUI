package com.lui.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "health_readings", indices = [Index("metric", "timestamp")])
data class HealthReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,      // "heart_rate", "spo2", "stress", "hrv", "temperature", "steps"
    val value: Float,
    val timestamp: Long = System.currentTimeMillis()
)
