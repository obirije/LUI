package com.lui.app.triggers

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lui.app.BuildConfig
import com.lui.app.data.LuiDatabase
import com.lui.app.data.TriggerEntity
import com.lui.app.helper.LuiLogger

/**
 * Manages geofence and scheduled triggers.
 * Geofences use Google Play Services GeofencingClient.
 * Scheduled triggers use AlarmManager for exact timing.
 */
object TriggerManager {

    private const val TAG = "TriggerManager"

    // ── Create triggers ──

    suspend fun createGeofence(
        context: Context,
        name: String,
        latitude: Double,
        longitude: Double,
        radius: Float = 200f,
        transition: String = "enter",
        toolName: String,
        toolParams: String = "{}",
        description: String = ""
    ): Result<Long> {
        val dao = LuiDatabase.getInstance(context).triggerDao()
        val trigger = TriggerEntity(
            name = name,
            type = "geofence",
            latitude = latitude,
            longitude = longitude,
            radius = radius,
            transition = transition,
            toolName = toolName,
            toolParams = toolParams,
            description = description.ifBlank { "$toolName when ${transition}ing $name" }
        )
        val id = dao.insert(trigger)
        LuiLogger.i(TAG, "Created geofence trigger #$id: $name ($latitude,$longitude) r=${radius}m $transition → $toolName")

        registerGeofence(context, trigger.copy(id = id))
        return Result.success(id)
    }

    suspend fun createScheduled(
        context: Context,
        name: String,
        triggerTimeMs: Long,
        recurring: Boolean = false,
        toolName: String,
        toolParams: String = "{}",
        description: String = ""
    ): Result<Long> {
        val dao = LuiDatabase.getInstance(context).triggerDao()
        val trigger = TriggerEntity(
            name = name,
            type = "scheduled",
            triggerTimeMs = triggerTimeMs,
            recurring = recurring,
            toolName = toolName,
            toolParams = toolParams,
            description = description.ifBlank { "$toolName at scheduled time" }
        )
        val id = dao.insert(trigger)
        LuiLogger.i(TAG, "Created scheduled trigger #$id: $name at ${triggerTimeMs} → $toolName")

        scheduleAlarm(context, trigger.copy(id = id))
        return Result.success(id)
    }

    // ── List / Delete ──

    suspend fun listTriggers(context: Context): List<TriggerEntity> {
        return LuiDatabase.getInstance(context).triggerDao().getAll()
    }

    suspend fun deleteTrigger(context: Context, id: Long): Boolean {
        val dao = LuiDatabase.getInstance(context).triggerDao()
        val trigger = dao.getById(id) ?: return false

        if (trigger.type == "geofence") {
            unregisterGeofence(context, trigger)
        } else {
            cancelAlarm(context, trigger)
        }

        dao.deleteById(id)
        LuiLogger.i(TAG, "Deleted trigger #$id: ${trigger.name}")
        return true
    }

    suspend fun deleteTriggerByName(context: Context, name: String): Boolean {
        val dao = LuiDatabase.getInstance(context).triggerDao()
        val triggers = dao.search(name)
        if (triggers.isEmpty()) return false
        for (t in triggers) {
            deleteTrigger(context, t.id)
        }
        return true
    }

    // ── Re-register all on boot / app start ──

    suspend fun reregisterAll(context: Context) {
        val dao = LuiDatabase.getInstance(context).triggerDao()

        val geofences = dao.getActiveGeofences()
        for (g in geofences) registerGeofence(context, g)
        LuiLogger.i(TAG, "Re-registered ${geofences.size} geofences")

        val scheduled = dao.getActiveScheduled()
        val now = System.currentTimeMillis()
        for (s in scheduled) {
            val time = s.triggerTimeMs ?: continue
            if (time > now) {
                scheduleAlarm(context, s)
            } else if (!s.recurring) {
                // Expired non-recurring — clean up
                dao.deleteById(s.id)
            }
        }
        LuiLogger.i(TAG, "Re-registered ${scheduled.size} scheduled triggers")
    }

    // ── Geofence registration ──

    @Suppress("MissingPermission")
    private fun registerGeofence(context: Context, trigger: TriggerEntity) {
        if (!BuildConfig.HAS_PLAY_SERVICES) {
            LuiLogger.w(TAG, "Geofencing not available (no Play Services in this build)")
            return
        }
        if (!hasLocationPermission(context)) {
            LuiLogger.w(TAG, "No location permission for geofence ${trigger.name}")
            return
        }

        val lat = trigger.latitude ?: return
        val lng = trigger.longitude ?: return
        val radius = trigger.radius ?: 200f

        try {
            // Use reflection-safe approach — these classes don't exist in F-Droid builds
            GeofenceHelper.register(context, trigger, lat, lng, radius)
            LuiLogger.i(TAG, "Registered geofence: ${trigger.name} at $lat,$lng r=$radius")
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to register geofence: ${e.message}", e)
        }
    }

    private fun unregisterGeofence(context: Context, trigger: TriggerEntity) {
        if (!BuildConfig.HAS_PLAY_SERVICES) return
        try {
            GeofenceHelper.unregister(context, trigger)
            LuiLogger.i(TAG, "Unregistered geofence: ${trigger.name}")
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to unregister geofence: ${e.message}", e)
        }
    }

    // ── Alarm scheduling ──

    private fun scheduleAlarm(context: Context, trigger: TriggerEntity) {
        val time = trigger.triggerTimeMs ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = "com.lui.app.TRIGGER_FIRED"
            putExtra("trigger_id", trigger.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, (10000 + trigger.id).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Fallback to inexact alarm
                alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                LuiLogger.w(TAG, "Scheduled inexact alarm for ${trigger.name} (exact alarms not permitted)")
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent)
                LuiLogger.i(TAG, "Scheduled exact alarm for ${trigger.name} at $time")
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to schedule alarm: ${e.message}", e)
        }
    }

    private fun cancelAlarm(context: Context, trigger: TriggerEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = "com.lui.app.TRIGGER_FIRED"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, (10000 + trigger.id).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        alarmManager.cancel(pendingIntent)
        LuiLogger.i(TAG, "Cancelled alarm for ${trigger.name}")
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        return fine && background
    }
}
