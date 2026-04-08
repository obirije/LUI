package com.lui.app.interceptor.actions

import android.content.Context
import com.lui.app.BuildConfig
import com.lui.app.helper.LuiLogger
import com.lui.app.triggers.TriggerManager
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TriggerActions {

    /**
     * Create a geofence trigger.
     * place: human name or "lat,lng"
     * trigger: "enter" or "exit"
     * action: tool name to execute
     * actionParams: JSON string of tool params
     */
    fun createGeofence(
        context: Context,
        place: String,
        latitude: Double,
        longitude: Double,
        trigger: String = "enter",
        action: String,
        actionParams: String = "{}",
        radius: Float = 200f
    ): ActionResult {
        if (!BuildConfig.HAS_PLAY_SERVICES) {
            return ActionResult.Failure("Geofencing isn't available in this build (no Play Services). Scheduled triggers still work — try schedule_action instead.")
        }

        // Check background location permission — required for geofencing on Android 10+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val bgGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                // Open app permission settings so user can grant "Allow all the time"
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
                return ActionResult.Failure("Geofencing needs background location permission. I've opened Settings — tap Permissions → Location → Allow all the time. Then try again.")
            }
        }

        return try {
            val result = runBlocking {
                TriggerManager.createGeofence(
                    context, name = place,
                    latitude = latitude, longitude = longitude,
                    radius = radius, transition = trigger,
                    toolName = action, toolParams = actionParams,
                    description = "$action when ${trigger}ing $place"
                )
            }
            val id = result.getOrThrow()
            ActionResult.Success("Geofence set: $action will run when you ${trigger} $place (${radius.toInt()}m radius). Trigger #$id.")
        } catch (e: Exception) {
            LuiLogger.e("TriggerActions", "Create geofence failed: ${e.message}", e)
            ActionResult.Failure("Couldn't create geofence: ${e.message}")
        }
    }

    /**
     * Create a scheduled trigger.
     * time: "5 minutes", "30 seconds", "6:32pm", "18:32", "2027-06-05 14:00"
     * action: tool name to execute
     * actionParams: JSON string of tool params
     */
    fun createScheduled(
        context: Context,
        time: String,
        action: String,
        actionParams: String = "{}",
        recurring: Boolean = false
    ): ActionResult {
        val triggerTimeMs = parseTime(time)
            ?: return ActionResult.Failure("Couldn't understand the time: \"$time\". Try \"5 minutes\", \"6:32pm\", or \"18:30\".")

        if (triggerTimeMs <= System.currentTimeMillis()) {
            return ActionResult.Failure("That time is in the past.")
        }

        return try {
            val result = runBlocking {
                TriggerManager.createScheduled(
                    context, name = "$action at $time",
                    triggerTimeMs = triggerTimeMs,
                    recurring = recurring,
                    toolName = action, toolParams = actionParams,
                    description = "$action at $time${if (recurring) " (daily)" else ""}"
                )
            }
            val id = result.getOrThrow()
            val formattedTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerTimeMs))
            ActionResult.Success("Scheduled: $action will run at $formattedTime. Trigger #$id.")
        } catch (e: Exception) {
            LuiLogger.e("TriggerActions", "Create scheduled failed: ${e.message}", e)
            ActionResult.Failure("Couldn't create schedule: ${e.message}")
        }
    }

    /**
     * List all active triggers.
     */
    fun listTriggers(context: Context): ActionResult {
        return try {
            val triggers = runBlocking { TriggerManager.listTriggers(context) }
            if (triggers.isEmpty()) {
                return ActionResult.Success("No active triggers.")
            }
            val sb = StringBuilder("${triggers.size} trigger(s):\n")
            val timeFormat = SimpleDateFormat("h:mm a, MMM d", Locale.getDefault())
            for (t in triggers) {
                val status = if (t.enabled) "active" else "disabled"
                when (t.type) {
                    "geofence" -> {
                        sb.appendLine("- #${t.id} [geofence] ${t.name}: ${t.toolName} on ${t.transition} ($status)")
                    }
                    "scheduled" -> {
                        val time = t.triggerTimeMs?.let { timeFormat.format(Date(it)) } ?: "unknown"
                        sb.appendLine("- #${t.id} [scheduled] ${t.toolName} at $time${if (t.recurring) " (daily)" else ""} ($status)")
                    }
                }
            }
            ActionResult.Success(sb.toString().trim())
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't list triggers: ${e.message}")
        }
    }

    /**
     * Delete a trigger by ID or name.
     */
    fun deleteTrigger(context: Context, target: String): ActionResult {
        return try {
            val id = target.toLongOrNull()
            val deleted = runBlocking {
                if (id != null) {
                    TriggerManager.deleteTrigger(context, id)
                } else {
                    TriggerManager.deleteTriggerByName(context, target)
                }
            }
            if (deleted) ActionResult.Success("Trigger deleted.")
            else ActionResult.Failure("Trigger not found: $target")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't delete trigger: ${e.message}")
        }
    }

    // ── Time parsing ──

    private fun parseTime(input: String): Long? {
        val lower = input.lowercase().trim()

        // Relative: "5 minutes", "30 seconds", "2 hours"
        Regex("(\\d+)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)").find(lower)?.let {
            val amount = it.groupValues[1].toLongOrNull() ?: return null
            val unit = it.groupValues[2]
            val ms = when {
                unit.startsWith("s") -> amount * 1000
                unit.startsWith("m") -> amount * 60 * 1000
                unit.startsWith("h") -> amount * 3600 * 1000
                else -> return null
            }
            return System.currentTimeMillis() + ms
        }

        // Absolute: "6:32pm", "18:30", "6:32 PM"
        for (pattern in listOf("h:mm a", "h:mma", "HH:mm", "H:mm")) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val parsed = sdf.parse(lower) ?: continue
                val cal = Calendar.getInstance()
                val parsedCal = Calendar.getInstance().apply { time = parsed }
                cal.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                // If the time is in the past today, schedule for tomorrow
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            } catch (_: Exception) {}
        }

        return null
    }
}
