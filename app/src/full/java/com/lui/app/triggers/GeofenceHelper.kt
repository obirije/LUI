package com.lui.app.triggers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.lui.app.data.TriggerEntity

@Suppress("MissingPermission")
object GeofenceHelper {
    fun register(context: Context, trigger: TriggerEntity, lat: Double, lng: Double, radius: Float) {
        val transitionType = when (trigger.transition) {
            "exit" -> Geofence.GEOFENCE_TRANSITION_EXIT
            else -> Geofence.GEOFENCE_TRANSITION_ENTER
        }
        val geofence = Geofence.Builder()
            .setRequestId("lui_trigger_${trigger.id}")
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionType)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = "com.lui.app.TRIGGER_FIRED"
            putExtra("trigger_id", trigger.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, trigger.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent)
    }

    fun unregister(context: Context, trigger: TriggerEntity) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(listOf("lui_trigger_${trigger.id}"))
    }
}
