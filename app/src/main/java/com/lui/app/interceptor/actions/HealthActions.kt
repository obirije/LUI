package com.lui.app.interceptor.actions

import android.content.Context
import com.lui.app.data.HealthReadingEntity
import com.lui.app.data.LuiDatabase
import com.lui.app.health.ColmiRingService
import com.lui.app.helper.LuiLogger

/**
 * Health ring tool actions — reads data from connected Colmi ring.
 * All blocking waits run on IO dispatcher to avoid ANR.
 */
object HealthActions {

    private var ringService: ColmiRingService? = null
    private var dbInitialized = false

    fun getRingService(context: Context): ColmiRingService {
        if (ringService == null) {
            ringService = ColmiRingService(context.applicationContext)
        }
        if (!dbInitialized) {
            dbInitialized = true
            val dao = LuiDatabase.getInstance(context.applicationContext).healthReadingDao()
            ringService!!.onReading = { metric, value ->
                try {
                    dao.insert(HealthReadingEntity(metric = metric, value = value))
                } catch (e: Exception) {
                    LuiLogger.e("Health", "Failed to persist reading: ${e.message}")
                }
            }
        }
        return ringService!!
    }

    fun getHeartRate(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestManualHeartRate()

        // Non-blocking wait — runs on IO thread via ActionExecutor's Dispatchers.IO
        val previousHr = ring.heartRate.value
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 12000) {
            val hr = ring.heartRate.value
            if (hr > 0 && hr != previousHr) {
                return ActionResult.Success("Heart rate: $hr BPM")
            }
            Thread.sleep(300)
        }

        val lastHr = ring.heartRate.value
        return if (lastHr > 0) {
            ActionResult.Success("Heart rate: $lastHr BPM (last reading)")
        } else {
            ActionResult.Failure("Couldn't read heart rate. Make sure the ring is worn snugly.")
        }
    }

    fun getRingBattery(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected.")
        }

        ring.requestBattery()
        Thread.sleep(1000)

        val level = ring.batteryLevel.value
        return if (level >= 0) {
            ActionResult.Success("Ring battery: $level%")
        } else {
            ActionResult.Failure("Couldn't read ring battery.")
        }
    }

    fun getHealthSummary(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        // Request battery first
        ring.requestBattery()
        Thread.sleep(800)

        // Request heart rate
        ring.requestManualHeartRate()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 12000) {
            if (ring.heartRate.value > 0) break
            Thread.sleep(300)
        }

        val sb = StringBuilder()
        val name = ring.deviceName.value
        val battery = ring.batteryLevel.value
        val hr = ring.heartRate.value

        sb.appendLine("Ring: $name")
        if (battery >= 0) sb.appendLine("Ring Battery: $battery%")
        if (hr > 0) {
            sb.appendLine("Heart Rate: $hr BPM")
            val zone = when {
                hr < 60 -> "resting (low)"
                hr < 100 -> "normal"
                hr < 140 -> "elevated"
                else -> "high"
            }
            sb.appendLine("Zone: $zone")
        } else {
            sb.appendLine("Heart Rate: couldn't measure — ensure ring is worn snugly")
        }

        // Include any cached metrics from prior syncs
        val spO2 = ring.spO2.value
        if (spO2 > 0) sb.appendLine("SpO2: $spO2%")
        val stress = ring.stress.value
        if (stress > 0) sb.appendLine("Stress: $stress")
        val hrvVal = ring.hrv.value
        if (hrvVal > 0) sb.appendLine("HRV: $hrvVal ms")
        val temp = ring.temperature.value
        if (temp > 0f) sb.appendLine("Temperature: ${"%.1f".format(temp)}°C")
        val steps = ring.steps.value
        if (steps >= 0) sb.appendLine("Steps: $steps")

        return ActionResult.Success(sb.toString().trim())
    }

    fun getRingStatus(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestBattery()
        Thread.sleep(800)

        val name = ring.deviceName.value
        val battery = ring.batteryLevel.value
        val hr = ring.heartRate.value

        val sb = StringBuilder("Ring: $name, connected\n")
        if (battery >= 0) sb.appendLine("Battery: $battery%")
        if (hr > 0) sb.appendLine("Last heart rate: $hr BPM")

        return ActionResult.Success(sb.toString().trim())
    }

    fun getRingCapabilities(context: Context): ActionResult {
        val ring = getRingService(context)
        val connected = ring.isConnected
        val name = if (connected) ring.deviceName.value else "not connected"

        val sb = StringBuilder()
        sb.appendLine("Health Ring: $name")
        sb.appendLine("")
        sb.appendLine("Available vitals:")
        sb.appendLine("- Heart rate (real-time + history)")
        sb.appendLine("- SpO2 / blood oxygen")
        sb.appendLine("- Sleep tracking (deep/light/REM/awake stages)")
        sb.appendLine("- Steps, calories, distance")
        sb.appendLine("- HRV (heart rate variability)")
        sb.appendLine("- Stress level")
        sb.appendLine("- Body temperature (R09+)")
        sb.appendLine("")
        sb.appendLine("Commands you can use:")
        sb.appendLine("- \"What's my heart rate?\"")
        sb.appendLine("- \"What's my blood oxygen?\" / \"SpO2\"")
        sb.appendLine("- \"How did I sleep?\"")
        sb.appendLine("- \"Steps from ring\"")
        sb.appendLine("- \"Stress level\"")
        sb.appendLine("- \"HRV\" / \"Heart rate variability\"")
        sb.appendLine("- \"Body temperature\"")
        sb.appendLine("- \"Health summary\" / \"Check my vitals\"")
        sb.appendLine("- \"Ring battery\"")
        sb.appendLine("- \"Find my ring\"")
        if (!connected) {
            sb.appendLine("")
            sb.appendLine("Connect your ring in Connection Hub first.")
        }

        return ActionResult.Success(sb.toString().trim())
    }

    fun findRing(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected.")
        }
        ring.findDevice()
        return ActionResult.Success("Ring is vibrating — check nearby.")
    }

    fun getSpO2(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestSpO2()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 25000) {
            val v = ring.spO2.value
            if (v > 0) {
                val status = when {
                    v >= 95 -> "normal"
                    v >= 90 -> "low"
                    else -> "critically low"
                }
                return ActionResult.Success("Blood oxygen (SpO2): $v% — $status")
            }
            Thread.sleep(300)
        }
        return ActionResult.Failure("No SpO2 data available. The ring measures SpO2 periodically — try again after wearing it for a while.")
    }

    fun getSleep(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestSleep()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 10000) {
            val sleep = ring.sleepData.value
            if (sleep.totalMinutes > 0) {
                val hours = sleep.totalMinutes / 60
                val mins = sleep.totalMinutes % 60
                val sb = StringBuilder()
                sb.appendLine("Last night's sleep: ${hours}h ${mins}m")
                if (sleep.deepMinutes > 0) sb.appendLine("Deep sleep: ${sleep.deepMinutes / 60}h ${sleep.deepMinutes % 60}m")
                if (sleep.lightMinutes > 0) sb.appendLine("Light sleep: ${sleep.lightMinutes / 60}h ${sleep.lightMinutes % 60}m")
                if (sleep.remMinutes > 0) sb.appendLine("REM sleep: ${sleep.remMinutes / 60}h ${sleep.remMinutes % 60}m")
                if (sleep.awakeMinutes > 0) sb.appendLine("Awake: ${sleep.awakeMinutes}m")
                return ActionResult.Success(sb.toString().trim())
            }
            Thread.sleep(300)
        }
        return ActionResult.Failure("No sleep data available. Wear the ring overnight and sync in the morning.")
    }

    fun getActivity(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestActivity()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 8000) {
            val s = ring.steps.value
            if (s >= 0) {
                val sb = StringBuilder("Steps: $s")
                val c = ring.calories.value
                if (c > 0) sb.append(" | Calories: $c kcal")
                return ActionResult.Success(sb.toString())
            }
            Thread.sleep(300)
        }
        return ActionResult.Failure("No activity data available from ring.")
    }

    fun getStress(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestStress()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 30000) {
            val v = ring.stress.value
            if (v > 0) {
                val level = when {
                    v < 30 -> "relaxed"
                    v < 60 -> "normal"
                    v < 80 -> "moderate"
                    else -> "high"
                }
                return ActionResult.Success("Stress level: $v ($level)")
            }
            Thread.sleep(300)
        }
        return ActionResult.Failure("No stress data available. The ring measures stress periodically — try again later.")
    }

    fun getHrv(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        ring.requestHrv()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 25000) {
            val v = ring.hrv.value
            if (v > 0) {
                val status = when {
                    v < 20 -> "low (may indicate stress or fatigue)"
                    v < 50 -> "normal"
                    else -> "good (strong recovery)"
                }
                return ActionResult.Success("HRV: $v ms — $status")
            }
            Thread.sleep(300)
        }
        return ActionResult.Failure("No HRV data available. The ring measures HRV periodically — try again later.")
    }

    fun getTemperature(context: Context): ActionResult {
        val ring = getRingService(context)
        if (!ring.isConnected) {
            return ActionResult.Failure("Health ring not connected. Connect it in Connection Hub.")
        }

        val previous = ring.temperature.value
        ring.requestTemperature()
        val startTime = System.currentTimeMillis()
        var retried = false
        while (System.currentTimeMillis() - startTime < 25000) {
            val v = ring.temperature.value
            if (v > 0f && v != previous) {
                val status = when {
                    v < 36.1f -> "below normal"
                    v <= 37.2f -> "normal"
                    v <= 38.0f -> "slightly elevated"
                    else -> "fever"
                }
                return ActionResult.Success("Body temperature: ${"%.1f".format(v)}°C — $status")
            }
            // Retry once at the halfway point if nothing has come in
            if (!retried && System.currentTimeMillis() - startTime > 12000) {
                retried = true
                LuiLogger.d("Health", "Temperature timed out, retrying...")
                ring.requestTemperature()
            }
            Thread.sleep(300)
        }

        // Fall back to most recent cached value if any
        val cached = ring.temperature.value
        return if (cached > 0f) {
            ActionResult.Success("Body temperature: ${"%.1f".format(cached)}°C (last reading)")
        } else {
            ActionResult.Failure("Couldn't read temperature. Make sure the ring is worn snugly and try again.")
        }
    }

    fun getHealthTrend(context: Context, metric: String, hours: Int): ActionResult {
        val dao = LuiDatabase.getInstance(context.applicationContext).healthReadingDao()
        val since = System.currentTimeMillis() - (hours * 3600 * 1000L)

        val validMetrics = setOf(
            "heart_rate", "spo2", "stress", "hrv", "temperature", "steps",
            "sleep_total", "sleep_deep", "sleep_light", "sleep_rem", "sleep_awake"
        )
        val resolvedMetric = when {
            metric.contains("heart", true) || metric.contains("hr", true) || metric.contains("pulse", true) -> "heart_rate"
            metric.contains("spo2", true) || metric.contains("oxygen", true) -> "spo2"
            metric.contains("stress", true) -> "stress"
            metric.contains("hrv", true) || metric.contains("variab", true) -> "hrv"
            metric.contains("temp", true) -> "temperature"
            metric.contains("step", true) || metric.contains("activ", true) -> "steps"
            metric.contains("deep", true) -> "sleep_deep"
            metric.contains("light", true) && metric.contains("sleep", true) -> "sleep_light"
            metric.contains("rem", true) -> "sleep_rem"
            metric.contains("awake", true) -> "sleep_awake"
            metric.contains("sleep", true) -> "sleep_total"
            metric in validMetrics -> metric
            else -> return ActionResult.Failure("Unknown metric: $metric. Available: heart_rate, spo2, stress, hrv, temperature, steps, sleep_total/deep/light/rem/awake.")
        }

        val count = dao.getCount(resolvedMetric, since)
        if (count == 0) {
            return ActionResult.Failure("No $resolvedMetric data in the last $hours hours. The ring needs to be connected and syncing.")
        }

        val avg = dao.getAverage(resolvedMetric, since) ?: 0f
        val min = dao.getMin(resolvedMetric, since) ?: 0f
        val max = dao.getMax(resolvedMetric, since) ?: 0f
        val latest = dao.getLatest(resolvedMetric)

        val unit = when (resolvedMetric) {
            "heart_rate" -> "BPM"
            "spo2" -> "%"
            "stress" -> ""
            "hrv" -> "ms"
            "temperature" -> "°C"
            "steps" -> "steps"
            else -> ""
        }

        val label = when (resolvedMetric) {
            "heart_rate" -> "Heart Rate"
            "spo2" -> "SpO2"
            "stress" -> "Stress"
            "hrv" -> "HRV"
            "temperature" -> "Temperature"
            "steps" -> "Steps"
            else -> resolvedMetric
        }

        val sb = StringBuilder()
        sb.appendLine("$label trend (last $hours hours, $count readings):")
        if (resolvedMetric == "temperature") {
            sb.appendLine("Average: ${"%.1f".format(avg)}$unit")
            sb.appendLine("Range: ${"%.1f".format(min)} – ${"%.1f".format(max)}$unit")
        } else {
            sb.appendLine("Average: ${avg.toInt()} $unit")
            sb.appendLine("Range: ${min.toInt()} – ${max.toInt()} $unit")
        }
        if (latest != null) {
            val ago = (System.currentTimeMillis() - latest.timestamp) / 60000
            if (resolvedMetric == "temperature") {
                sb.appendLine("Latest: ${"%.1f".format(latest.value)}$unit (${ago}min ago)")
            } else {
                sb.appendLine("Latest: ${latest.value.toInt()} $unit (${ago}min ago)")
            }
        }

        return ActionResult.Success(sb.toString().trim())
    }
}
