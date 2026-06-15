package com.fareed.auto

import android.accessibilityservice.AccessibilityService
import android.app.Notification
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

    // Both fields written as one atomic pair so readers never see a mismatched text/package.
    @Volatile
    var lastToast: Pair<String, String>? = null // (text, package)

    // When set, only toasts from this package are captured (null = capture from all apps)
    @Volatile
    var toastPackageFilter: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (BuildConfig.DEBUG) Log.i("FarAuto", "Accessibility Service Connected and Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // Status-bar notifications carry a Notification payload; real toasts do not.
            if (event.parcelableData is Notification) {
                return
            }
            val text = event.text?.joinToString(" ")
            val pkg = event.packageName?.toString() ?: "unknown"

            val filter = toastPackageFilter
            if (filter != null && pkg != filter) {
                return
            }
            if (!text.isNullOrEmpty()) {
                lastToast = Pair(text, pkg)
                if (BuildConfig.DEBUG) Log.i("FarAuto", "Toast Event from [$pkg]: $text")
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
                if (BuildConfig.DEBUG) Log.d("FarAuto", "Emergency Stop Triggered via Volume Keys")
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
        if (BuildConfig.DEBUG) Log.i("FarAuto", "Accessibility Service Disconnected")
    }
}
