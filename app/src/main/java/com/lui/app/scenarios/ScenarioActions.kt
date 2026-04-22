package com.lui.app.scenarios

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lui.app.data.LuiDatabase
import com.lui.app.helper.LuiLogger
import com.lui.app.interceptor.actions.ActionResult
import com.lui.app.interceptor.actions.HealthActions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tool handlers for proactive wellbeing scenarios.
 *
 * Each function is callable both by the LLM (via [ActionExecutor]) and
 * internally by [ProactiveScenarios] when a scheduled trigger fires. The
 * morning briefing, stress-pattern detector, and pre-meeting check all
 * return human-readable [ActionResult] strings — the trigger receiver
 * forwards the success text to [ProactiveBus] so it lands on the canvas.
 */
object ScenarioActions {

    // ────────────────────────────────────────────────────────────────
    //  Morning briefing
    // ────────────────────────────────────────────────────────────────

    /** Fires daily after wake. Composes:
     *   - sleep recap (duration + quality)
     *   - today's calendar (flagging early / back-to-back events)
     *   - gentle reschedule offer when last night was rough.
     * Falls back gracefully when the ring isn't connected or sleep data
     * wasn't captured. */
    fun morningBriefing(context: Context): ActionResult {
        val sb = StringBuilder("Good morning.\n\n")

        // Sleep recap — prefer live ring data, fall back to cached reading
        val sleepText = composeSleepRecap(context)
        sb.appendLine(sleepText)

        // Today's calendar
        val events = fetchTodayEvents(context)
        if (events.isEmpty()) {
            sb.appendLine()
            sb.append("Nothing on the calendar today.")
            return ActionResult.Success(sb.toString())
        }

        sb.appendLine()
        sb.appendLine("Today's schedule:")
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        for (e in events.take(6)) {
            sb.append("  ${fmt.format(Date(e.startMs))} — ${e.title}")
            sb.appendLine()
        }

        // Rough-night flag on early meetings
        val wasRough = isRoughNight(context)
        val earlyCutoff = todayAtHour(10) // meetings before 10am
        val earlyMeetings = events.filter { it.startMs < earlyCutoff }
        if (wasRough && earlyMeetings.isNotEmpty()) {
            val first = earlyMeetings.first()
            val firstTime = fmt.format(Date(first.startMs))
            sb.appendLine()
            sb.append("That's a short night heading into $firstTime. Reply 'reschedule ${first.title.take(40)}' and I'll propose a later slot.")
        }

        return ActionResult.Success(sb.toString().trim())
    }

    private fun composeSleepRecap(context: Context): String {
        return try {
            val ring = HealthActions.getRingService(context)
            val sleep = ring.sleepData.value
            if (sleep.totalMinutes > 0) {
                val hours = sleep.totalMinutes / 60
                val mins = sleep.totalMinutes % 60
                val deepPct = sleep.deepMinutes * 100f / sleep.totalMinutes
                val remPct = sleep.remMinutes * 100f / sleep.totalMinutes
                val quality = ((deepPct * 2 + remPct * 1.5f).coerceAtMost(100f)).toInt()
                val label = when {
                    sleep.totalMinutes < 360 -> "short"
                    quality < 45 -> "restless"
                    quality >= 75 -> "solid"
                    else -> "decent"
                }
                "Sleep: ${hours}h ${mins}m, ${label} night (quality $quality/100, deep ${"%.0f".format(deepPct)}%)."
            } else {
                "Sleep: no ring data for last night."
            }
        } catch (e: Exception) {
            LuiLogger.e("Scenarios", "Sleep recap failed: ${e.message}")
            "Sleep: no ring data for last night."
        }
    }

    /** Rough night = less than 6h total OR deep sleep below 12%. */
    private fun isRoughNight(context: Context): Boolean {
        return try {
            val sleep = HealthActions.getRingService(context).sleepData.value
            if (sleep.totalMinutes <= 0) return false
            val deepPct = sleep.deepMinutes * 100f / sleep.totalMinutes
            sleep.totalMinutes < 360 || deepPct < 12f
        } catch (_: Exception) { false }
    }

    // ────────────────────────────────────────────────────────────────
    //  Weekly stress pattern detection
    // ────────────────────────────────────────────────────────────────

    /** Looks for recurring stress peaks in the last 21 days, bucketed by
     *  (weekday, hour). A bucket qualifies as a "pattern" when it has at
     *  least 3 samples and average stress ≥70. Returns up to three
     *  strongest patterns with a suggested mitigation. */
    fun detectStressPatterns(context: Context): ActionResult {
        return try {
            val dao = LuiDatabase.getInstance(context.applicationContext).healthReadingDao()
            val since = System.currentTimeMillis() - 21L * 24 * 3600 * 1000
            val readings = dao.getReadingsSince("stress", since)
            if (readings.size < 10) {
                return ActionResult.Success(
                    "Not enough stress history yet — wear the ring for a couple weeks and I'll spot the rhythms."
                )
            }

            data class Bucket(val weekday: Int, val hour: Int, val values: MutableList<Float> = mutableListOf())
            val buckets = mutableMapOf<Pair<Int, Int>, Bucket>()
            val cal = Calendar.getInstance()
            for (r in readings) {
                cal.timeInMillis = r.timestamp
                val key = cal.get(Calendar.DAY_OF_WEEK) to cal.get(Calendar.HOUR_OF_DAY)
                buckets.getOrPut(key) { Bucket(key.first, key.second) }.values.add(r.value)
            }

            val patterns = buckets.values
                .filter { it.values.size >= 3 && it.values.average() >= 70.0 }
                .sortedByDescending { it.values.average() }
                .take(3)

            if (patterns.isEmpty()) {
                return ActionResult.Success(
                    "No recurring stress patterns over the last three weeks. Things look well-distributed."
                )
            }

            val sb = StringBuilder("Patterns I noticed over the last three weeks:\n")
            for (p in patterns) {
                val dayName = weekdayName(p.weekday)
                val hourLabel = formatHour(p.hour)
                val avg = p.values.average().toInt()
                sb.append("  • $dayName around $hourLabel — avg stress $avg (${p.values.size} samples)\n")
            }
            val top = patterns.first()
            val topDay = weekdayName(top.weekday)
            val topHour = formatHour((top.hour - 1).coerceAtLeast(6))
            sb.append("\nWant me to block $topHour on $topDay as a walk or breather? Reply 'yes, book it'.")
            ActionResult.Success(sb.toString().trim())
        } catch (e: Exception) {
            LuiLogger.e("Scenarios", "Pattern detection failed: ${e.message}")
            ActionResult.Failure("Couldn't analyze stress patterns: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Pre-meeting readiness
    // ────────────────────────────────────────────────────────────────

    /** Fires ~10 min before a calendar event. Reads current stress + HRV;
     *  if either looks off, offers a quick breath reset. Called via a
     *  scheduled trigger so params carry the event title. */
    fun preMeetingCheck(context: Context, eventTitle: String): ActionResult {
        val ring = try { HealthActions.getRingService(context) } catch (_: Exception) { null }
        val stress = ring?.stress?.value ?: -1
        val hrv = ring?.hrv?.value ?: -1

        val title = eventTitle.ifBlank { "your next meeting" }
        val elevated = stress in 61..100 || (hrv in 1..24)
        return if (elevated) {
            val bits = buildList {
                if (stress > 60) add("stress at $stress")
                if (hrv in 1..24) add("HRV low (${hrv}ms)")
            }
            val readingPart = if (bits.isEmpty()) "" else " (${bits.joinToString(", ")})"
            ActionResult.Success(
                "Meeting in 10 — $title$readingPart. Want a 4-minute breath reset? Reply 'yes' and I'll start wellness mode."
            )
        } else {
            ActionResult.Success(
                "Meeting in 10 — $title. Vitals look steady. You're good."
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    data class EventRow(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean)

    /** Returns today's calendar events from the device provider. Empty on
     *  permission denied — callers should handle that shape naturally. */
    fun fetchTodayEvents(context: Context): List<EventRow> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) return emptyList()

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val dayStart = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        val dayEnd = cal.timeInMillis

        return queryEvents(context, dayStart, dayEnd)
    }

    /** Events starting between [fromMs] and [toMs] (inclusive). Used by
     *  the pre-meeting scheduler to find upcoming events in the next N
     *  hours. */
    fun fetchUpcomingEvents(context: Context, fromMs: Long, toMs: Long): List<EventRow> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) return emptyList()
        return queryEvents(context, fromMs, toMs)
    }

    private fun queryEvents(context: Context, fromMs: Long, toMs: Long): List<EventRow> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(fromMs.toString(), toMs.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val out = mutableListOf<EventRow>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args, sortOrder
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "Untitled"
                    val start = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (!allDay) out.add(EventRow(title, start, end, false))
                }
            }
        } catch (e: Exception) {
            LuiLogger.e("Scenarios", "Calendar query failed: ${e.message}")
        }
        return out
    }

    private fun todayAtHour(hour: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        return c.timeInMillis
    }

    private fun weekdayName(day: Int): String = when (day) {
        Calendar.SUNDAY -> "Sunday"
        Calendar.MONDAY -> "Monday"
        Calendar.TUESDAY -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY -> "Thursday"
        Calendar.FRIDAY -> "Friday"
        Calendar.SATURDAY -> "Saturday"
        else -> "?"
    }

    private fun formatHour(hour24: Int): String {
        val h = ((hour24 + 11) % 12) + 1
        val suffix = if (hour24 < 12) "am" else "pm"
        return "$h$suffix"
    }
}
