package com.lui.app.triggers

import android.content.Context
import com.lui.app.data.TriggerEntity

/**
 * F-Droid stub — no Google Play Services available.
 * Geofencing is disabled. Scheduled triggers still work via AlarmManager.
 */
object GeofenceHelper {
    fun register(context: Context, trigger: TriggerEntity, lat: Double, lng: Double, radius: Float) {
        // No-op: Play Services not available
    }

    fun unregister(context: Context, trigger: TriggerEntity) {
        // No-op
    }
}
