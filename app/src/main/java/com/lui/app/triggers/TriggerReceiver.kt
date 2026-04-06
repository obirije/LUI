package com.lui.app.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lui.app.data.LuiDatabase
import com.lui.app.data.ToolCall
import com.lui.app.helper.LuiLogger
import com.lui.app.interceptor.ActionExecutor
import com.lui.app.interceptor.actions.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Receives trigger events from GeofencingClient and AlarmManager.
 * Looks up the trigger in the DB and executes the stored tool chain.
 */
class TriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TriggerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val triggerId = intent.getLongExtra("trigger_id", -1)
        if (triggerId == -1L) {
            LuiLogger.w(TAG, "Received trigger with no ID")
            return
        }

        LuiLogger.i(TAG, "Trigger #$triggerId fired!")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = LuiDatabase.getInstance(context).triggerDao()
                val trigger = dao.getById(triggerId)

                if (trigger == null) {
                    LuiLogger.w(TAG, "Trigger #$triggerId not found in DB")
                    pendingResult.finish()
                    return@launch
                }

                if (!trigger.enabled) {
                    LuiLogger.d(TAG, "Trigger #$triggerId is disabled, skipping")
                    pendingResult.finish()
                    return@launch
                }

                LuiLogger.i(TAG, "Executing trigger: ${trigger.name} → ${trigger.toolName} ${trigger.toolParams}")

                // Parse params
                val params = mutableMapOf<String, String>()
                try {
                    val json = JSONObject(trigger.toolParams)
                    for (key in json.keys()) params[key] = json.getString(key)
                } catch (_: Exception) {}

                // Execute the tool
                val toolCall = ToolCall(trigger.toolName, params)
                val result = ActionExecutor.execute(context, toolCall)

                val success = result is ActionResult.Success
                val msg = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }
                LuiLogger.i(TAG, "Trigger #$triggerId result: ${if (success) "OK" else "FAIL"}: $msg")

                // Push event to bridge if active
                try {
                    com.lui.app.bridge.BridgeEvents.pushTriggerEvent(trigger.name, trigger.toolName, msg)
                } catch (_: Exception) {}

                // Clean up non-recurring scheduled triggers
                if (trigger.type == "scheduled" && !trigger.recurring) {
                    dao.deleteById(triggerId)
                    LuiLogger.i(TAG, "Cleaned up non-recurring trigger #$triggerId")
                }

            } catch (e: Exception) {
                LuiLogger.e(TAG, "Trigger execution failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
