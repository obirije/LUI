package com.lui.app.interceptor.actions

import android.content.Context
import com.lui.app.health.ColmiRingService
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Health ring tool actions — reads data from connected Colmi ring.
 * All blocking waits run on IO dispatcher to avoid ANR.
 */
object HealthActions {

    private var ringService: ColmiRingService? = null

    fun getRingService(context: Context): ColmiRingService {
        if (ringService == null) {
            ringService = ColmiRingService(context.applicationContext)
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

        // Available vitals from R09
        sb.appendLine("")
        sb.appendLine("Available vitals: heart rate, SpO2 (blood oxygen), sleep stages, steps, HRV, stress, body temperature")

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
}
