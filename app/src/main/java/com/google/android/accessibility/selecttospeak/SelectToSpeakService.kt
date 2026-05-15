package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * DEPRECATED: This legacy identity is no longer used.
 * The app now uses com.fareed.auto.FarAutoAccessibilityService.
 */
class SelectToSpeakService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
