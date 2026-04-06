package com.lui.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A stored trigger — fires tool chains based on location or time.
 *
 * Geofence: fires when entering/exiting a GPS zone
 * Scheduled: fires at a specific time or after a delay
 */
@Entity(tableName = "triggers")
data class TriggerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,           // "geofence" or "scheduled"

    // Geofence fields
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Float? = null,  // meters
    val transition: String? = null, // "enter" or "exit"
    val placeName: String? = null,

    // Scheduled fields
    val triggerTimeMs: Long? = null,  // epoch millis when it should fire
    val recurring: Boolean = false,   // repeat daily?

    // Action — tool name and params as JSON string
    val toolName: String,
    val toolParams: String = "{}",  // JSON map
    val description: String = "",   // human-readable "Turn on DND when near office"

    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
