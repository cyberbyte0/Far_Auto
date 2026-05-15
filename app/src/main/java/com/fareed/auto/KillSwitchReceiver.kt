package com.fareed.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chaquo.python.Python

class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.fareed.auto.ACTION_KILL_SCRIPT") {
            Log.d("FarAuto", "Kill switch triggered")
            ScriptExecutionService.isRunning.set(false)
            
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    py.getModule("automator").put("last_stopped_session", ScriptExecutionService.executionSessionId)
                }
            } catch (e: Exception) {
                Log.e("FarAuto", "Error setting last_stopped_session", e)
            }

            val stopIntent = Intent(context, ScriptExecutionService::class.java)
            context.stopService(stopIntent)
        }
    }
}
