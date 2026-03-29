package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object AlarmActions {

    fun setAlarm(context: Context, time: String, label: String = "LUI Alarm"): ActionResult {
        return try {
            val parsed = parseTime(time)
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, parsed.first)
                putExtra(AlarmClock.EXTRA_MINUTES, parsed.second)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val displayTime = String.format("%d:%02d", parsed.first, parsed.second)
            ActionResult.Success("Alarm set for $displayTime.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't set alarm: ${e.message}")
        }
    }

    fun setTimer(context: Context, amount: String, unit: String): ActionResult {
        return try {
            val seconds = when {
                unit.startsWith("sec") -> amount.toInt()
                unit.startsWith("min") -> amount.toInt() * 60
                unit.startsWith("hour") || unit.startsWith("hr") -> amount.toInt() * 3600
                else -> amount.toInt() * 60 // default to minutes
            }
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "LUI Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Timer set for $amount $unit.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't set timer: ${e.message}")
        }
    }

    fun dismissAlarm(context: Context): ActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Dismissing alarm.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't dismiss alarm: ${e.message}")
        }
    }

    fun cancelTimer(context: Context): ActionResult {
        return try {
            // There's no direct cancel intent — dismiss works for active timers
            val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Cancelling timer.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't cancel timer: ${e.message}")
        }
    }

    /**
     * Parse time strings like "7:30", "7:30am", "730", "7.30 pm", "19:30"
     * Returns Pair(hour in 24h, minutes)
     */
    private fun parseTime(time: String): Pair<Int, Int> {
        val cleaned = time.trim().lowercase().replace(".", ":")
        val isPm = cleaned.contains("pm")
        val isAm = cleaned.contains("am")
        val digits = cleaned.replace(Regex("[^0-9:]"), "")

        val parts = if (digits.contains(":")) {
            digits.split(":")
        } else if (digits.length >= 3) {
            // "730" -> "7", "30" or "1930" -> "19", "30"
            if (digits.length <= 3) {
                listOf(digits.substring(0, 1), digits.substring(1))
            } else {
                listOf(digits.substring(0, digits.length - 2), digits.substring(digits.length - 2))
            }
        } else {
            listOf(digits, "0")
        }

        var hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Can't parse hour from: $time")
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        return Pair(hour, minute)
    }
}
