package com.lui.app.scenarios

import android.content.Context
import com.lui.app.data.ChatMessage
import com.lui.app.data.LuiDatabase
import com.lui.app.helper.LuiLogger
import com.lui.app.interceptor.actions.HealthActions
import com.lui.app.triggers.TriggerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * Central controller for LUI's proactive wellbeing behaviour.
 *
 * Four scenarios hang off this object:
 *   1. Acute stress recovery — live observer on [com.lui.app.health.ColmiRingService.stress]
 *   2. Morning briefing       — daily recurring trigger at 07:00 local
 *   3. Pre-meeting readiness  — one-shot trigger 10 min before each calendar event
 *   4. Weekly stress patterns — daily recurring trigger at 18:00 local with 7-day cooldown in the tool
 *
 * [start] is idempotent — safe to call on every LuiViewModel construction. It
 * attaches the stress observer to the supplied scope and (re)creates the
 * scheduled TriggerEntity rows if they're missing. Messages produced by any
 * scenario land on [ProactiveBus], which LuiViewModel forwards to the canvas.
 */
object ProactiveScenarios {

    private const val TAG = "Scenarios"

    // SharedPreferences keys (persist across process death)
    private const val PREFS = "lui_scenarios"
    private const val KEY_LAST_STRESS_ALERT = "last_stress_alert_at"
    private const val KEY_LAST_PATTERN_AT = "last_stress_pattern_at"

    // Thresholds / cooldowns
    private const val STRESS_HIGH_THRESHOLD = 75
    private const val STRESS_REQUIRED_CONSECUTIVE = 3           // ~45 min at 15-min sync cycle
    private const val STRESS_ALERT_COOLDOWN_MS = 2 * 60 * 60 * 1000L
    private const val PATTERN_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000L

    private var consecutiveHighStress = 0

    // Hook from the VM: true while user is talking — don't interrupt.
    @Volatile var conversationModeProvider: (() -> Boolean)? = null

    fun start(context: Context, scope: CoroutineScope) {
        observeStress(context, scope)

        scope.launch(Dispatchers.IO) {
            try {
                ensureDailySchedules(context.applicationContext)
                rescheduleTodayPreMeetings(context.applicationContext)
            } catch (e: Exception) {
                LuiLogger.e(TAG, "ensureSchedules failed: ${e.message}", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Scenario 1 — Acute stress recovery
    // ────────────────────────────────────────────────────────────────

    private fun observeStress(context: Context, scope: CoroutineScope) {
        scope.launch {
            try {
                val ring = HealthActions.getRingService(context)
                ring.stress.collect { level ->
                    if (level > 0) onStressReading(context, ring, level)
                }
            } catch (e: Exception) {
                LuiLogger.e(TAG, "Stress observer failed: ${e.message}", e)
            }
        }
    }

    private fun onStressReading(
        context: Context,
        ring: com.lui.app.health.ColmiRingService,
        level: Int
    ) {
        if (level >= STRESS_HIGH_THRESHOLD) consecutiveHighStress += 1
        else { consecutiveHighStress = 0; return }

        if (consecutiveHighStress < STRESS_REQUIRED_CONSECUTIVE) return
        if (conversationModeProvider?.invoke() == true) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_STRESS_ALERT, 0L)
        if (now - last < STRESS_ALERT_COOLDOWN_MS) return

        prefs.edit().putLong(KEY_LAST_STRESS_ALERT, now).apply()
        consecutiveHighStress = 0

        val text = composeAcuteStressMessage(context, ring, level)
        ProactiveBus.emit(ChatMessage(text = text, sender = ChatMessage.Sender.LUI))
        LuiLogger.i(TAG, "Acute stress alert fired at level $level")
    }

    /** Chooses between "reschedule next meeting" and "start wellness mode"
     *  based on whether there's a calendar event in the next 20 minutes. */
    private fun composeAcuteStressMessage(
        context: Context,
        ring: com.lui.app.health.ColmiRingService,
        level: Int
    ): String {
        val hrv = ring.hrv.value.let { if (it > 0) ", HRV ${it}ms" else "" }
        val now = System.currentTimeMillis()
        val imminent = try {
            ScenarioActions.fetchUpcomingEvents(context, now, now + 20 * 60 * 1000)
                .firstOrNull { it.startMs > now }
        } catch (_: Exception) { null }

        return if (imminent != null) {
            val mins = ((imminent.startMs - now) / 60000).coerceAtLeast(1)
            "Your stress has climbed to $level$hrv and you've got '${imminent.title}' in $mins min. " +
                "Want me to flag it as low-energy and draft a reschedule? Otherwise say 'start wellness mode' and I'll carry the next 10 minutes for you."
        } else {
            "Your stress has sat around $level$hrv for a while. Say 'start wellness mode' and I'll mute notifications, play something calming, and dim the screen."
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Scenario 4 — Weekly pattern cooldown
    // ────────────────────────────────────────────────────────────────

    /** Called by the pattern-detection tool at execution time to decide
     *  whether to actually run or return a no-op. The tool itself lives
     *  in [ScenarioActions.detectStressPatterns]; cooldown is tracked
     *  here so it survives tool re-entry. */
    fun patternCheckAllowed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_PATTERN_AT, 0L)
        return (now - last) >= PATTERN_COOLDOWN_MS
    }

    fun markPatternCheckRan(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_PATTERN_AT, System.currentTimeMillis()).apply()
    }

    // ────────────────────────────────────────────────────────────────
    //  Scheduling — morning, weekly, pre-meeting
    // ────────────────────────────────────────────────────────────────

    private suspend fun ensureDailySchedules(context: Context) {
        val dao = LuiDatabase.getInstance(context).triggerDao()

        if (dao.search(NAME_MORNING).isEmpty()) {
            TriggerManager.createScheduled(
                context,
                name = NAME_MORNING,
                triggerTimeMs = nextOccurrence(7, 30),
                recurring = true,
                toolName = "morning_briefing",
                description = "Daily morning briefing"
            )
        }
        if (dao.search(NAME_WEEKLY).isEmpty()) {
            TriggerManager.createScheduled(
                context,
                name = NAME_WEEKLY,
                triggerTimeMs = nextOccurrence(18, 0),
                recurring = true,
                toolName = "detect_stress_patterns",
                description = "Weekly stress pattern check"
            )
        }
    }

    private suspend fun rescheduleTodayPreMeetings(context: Context) {
        val dao = LuiDatabase.getInstance(context).triggerDao()
        for (old in dao.search("pre_meeting_")) {
            TriggerManager.deleteTrigger(context, old.id)
        }

        val now = System.currentTimeMillis()
        val events = ScenarioActions.fetchUpcomingEvents(context, now, now + 24 * 3600_000L)
        for (e in events) {
            val fireAt = e.startMs - 10 * 60_000L
            if (fireAt < now + 60_000L) continue            // too imminent — skip
            if ((e.endMs - e.startMs) < 20 * 60_000L) continue // stand-up < 20min isn't worth a ping

            val params = JSONObject().put("event_title", e.title).toString()
            TriggerManager.createScheduled(
                context,
                name = "pre_meeting_${e.startMs}",
                triggerTimeMs = fireAt,
                recurring = false,
                toolName = "pre_meeting_check",
                toolParams = params,
                description = "Pre-meeting check for ${e.title}"
            )
        }
        LuiLogger.i(TAG, "Scheduled ${events.size} pre-meeting checks for the next 24h")
    }

    private const val NAME_MORNING = "scenario_morning_briefing"
    private const val NAME_WEEKLY = "scenario_weekly_pattern"

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }
}
