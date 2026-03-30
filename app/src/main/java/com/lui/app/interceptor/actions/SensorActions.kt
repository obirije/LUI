package com.lui.app.interceptor.actions

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SensorActions {

    // Dedicated thread with a Looper for sensor callbacks
    private val sensorThread = HandlerThread("LuiSensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private fun <T> readSensor(context: Context, sensorType: Int, sensorName: String, transform: (SensorEvent) -> T): Pair<T?, String?> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(sensorType)
            ?: return Pair(null, "This device doesn't have a $sensorName sensor.")

        val latch = CountDownLatch(1)
        var result: T? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && result == null) {
                    result = transform(event)
                    latch.countDown()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        latch.await(5, TimeUnit.SECONDS)
        sm.unregisterListener(listener)

        return if (result != null) Pair(result, null) else Pair(null, "Sensor didn't respond in time.")
    }

    fun getSteps(context: Context): ActionResult {
        // Try step counter first (cumulative), then check if sensor exists at all
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val hasDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

        if (!hasCounter && !hasDetector) {
            return ActionResult.Failure("This device doesn't have a step counter sensor.")
        }

        if (hasCounter) {
            val (steps, error) = readSensor(context, Sensor.TYPE_STEP_COUNTER, "step counter") { it.values[0] }
            if (steps != null) return ActionResult.Success("Steps since last reboot: ${steps.toInt()}.")
        }

        return ActionResult.Failure("Step counter sensor exists but isn't responding. Make sure activity recognition permission is granted and try walking a few steps first.")
    }

    fun getProximity(context: Context): ActionResult {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            ?: return ActionResult.Failure("No proximity sensor on this device.")
        val maxRange = sensor.maximumRange

        val (distance, error) = readSensor(context, Sensor.TYPE_PROXIMITY, "proximity") { it.values[0] }
        if (error != null) return ActionResult.Failure(error)

        val status = if (distance!! < maxRange) "Something is near the sensor (face-down or in pocket)." else "Nothing near the sensor (phone is face-up)."
        return ActionResult.Success(status)
    }

    fun getLight(context: Context): ActionResult {
        val (lux, error) = readSensor(context, Sensor.TYPE_LIGHT, "ambient light") { it.values[0] }
        if (error != null) return ActionResult.Failure(error)

        val desc = when {
            lux!! < 10 -> "Very dark"
            lux < 50 -> "Dim"
            lux < 200 -> "Indoor lighting"
            lux < 1000 -> "Bright indoor"
            lux < 10000 -> "Overcast daylight"
            else -> "Direct sunlight"
        }
        return ActionResult.Success("Ambient light: ${lux.toInt()} lux ($desc).")
    }
}
