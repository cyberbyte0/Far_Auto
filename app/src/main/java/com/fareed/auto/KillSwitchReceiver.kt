package com.fareed.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chaquo.python.Python

class KillSwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.fareed.auto.ACTION_KILL_SCRIPT") {
            if (BuildConfig.DEBUG) Log.d("FarAuto", "Kill switch triggered")
            ScriptExecutionService.stopScript()
        }
    }
}
