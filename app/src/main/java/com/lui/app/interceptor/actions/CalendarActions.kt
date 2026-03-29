package com.lui.app.interceptor.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

object CalendarActions {

    fun createEvent(context: Context, title: String, dateStr: String, timeStr: String): ActionResult {
        // Try using intent first — works without permission
        return try {
            val cal = parseDateTime(dateStr, timeStr)
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 3600000) // 1 hour
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Creating event \"$title\".")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't create event: ${e.message}")
        }
    }

    private fun parseDateTime(dateStr: String, timeStr: String): Calendar {
        val cal = Calendar.getInstance()
        val dateLower = dateStr.lowercase().trim()

        // Parse date
        when {
            dateLower == "today" || dateLower.isBlank() -> {} // already today
            dateLower == "tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            dateLower.matches(Regex("\\d{1,2}/\\d{1,2}")) -> {
                val parts = dateLower.split("/")
                cal.set(Calendar.MONTH, parts[0].toInt() - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[1].toInt())
            }
            dateLower.startsWith("next ") -> {
                val dayName = dateLower.removePrefix("next ").trim()
                val targetDay = dayNameToCalendarDay(dayName)
                if (targetDay != null) {
                    val currentDay = cal.get(Calendar.DAY_OF_WEEK)
                    var daysAhead = targetDay - currentDay
                    if (daysAhead <= 0) daysAhead += 7
                    cal.add(Calendar.DAY_OF_YEAR, daysAhead)
                }
            }
        }

        // Parse time
        val timeLower = timeStr.lowercase().trim().replace(".", ":")
        val isPm = timeLower.contains("pm")
        val isAm = timeLower.contains("am")
        val digits = timeLower.replace(Regex("[^0-9:]"), "")
        val timeParts = if (digits.contains(":")) digits.split(":") else listOf(digits, "0")

        var hour = timeParts[0].toIntOrNull() ?: 9
        val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)

        return cal
    }

    private fun dayNameToCalendarDay(name: String): Int? {
        return when (name) {
            "sunday", "sun" -> Calendar.SUNDAY
            "monday", "mon" -> Calendar.MONDAY
            "tuesday", "tue", "tues" -> Calendar.TUESDAY
            "wednesday", "wed" -> Calendar.WEDNESDAY
            "thursday", "thu", "thur", "thurs" -> Calendar.THURSDAY
            "friday", "fri" -> Calendar.FRIDAY
            "saturday", "sat" -> Calendar.SATURDAY
            else -> null
        }
    }
}
