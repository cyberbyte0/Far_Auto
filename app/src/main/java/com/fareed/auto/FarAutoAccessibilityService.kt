package com.fareed.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * The core engine of Far_Auto. This service must be enabled in Android Settings.
 * It provides the ability to inspect the UI tree and perform gestures.
 */
class FarAutoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FarAutoAccessibilityService? = null
            private set
            
        fun isRunning(): Boolean = instance != null
    }

    @Volatile
    var lastToastText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("FarAuto", "Accessibility Service Connected and Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text?.joinToString(" ")
            val pkg = event.packageName?.toString() ?: "unknown"
            
            if (!text.isNullOrEmpty()) {
                lastToastText = text
                Log.i("FarAuto", "Notification Event from [$pkg]: $text")
            }
        }
    }

    override fun onInterrupt() {
        Log.w("FarAuto", "Accessibility Service Interrupted")
    }

    private var lastVolumeDownTime: Long = 0

    /**
     * Emergency Kill-Switch: Double-tap Volume Down to stop any running script.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastVolumeDownTime < 500) {
                Log.d("FarAuto", "Emergency Stop Triggered via Volume Keys")
                triggerKillSwitch()
                return true
            }
            lastVolumeDownTime = currentTime
        }
        return super.onKeyEvent(event)
    }

    private fun triggerKillSwitch() {
        val intent = Intent("com.fareed.auto.ACTION_KILL_SCRIPT").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i("FarAuto", "Accessibility Service Disconnected")
    }
}
