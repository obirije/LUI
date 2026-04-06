package com.lui.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers all geofences and scheduled alarms after device boot.
 * Geofences are lost when the device restarts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LuiLogger.i("BootReceiver", "Device booted — re-registering triggers")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    TriggerManager.reregisterAll(context)
                } catch (e: Exception) {
                    LuiLogger.e("BootReceiver", "Failed to re-register triggers: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
