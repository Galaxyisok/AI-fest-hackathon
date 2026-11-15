package com.scamguard.calls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Exists for one reason: since Android 10, while a phone call is active the system gives an
 * ordinary app's microphone pure silence. The documented exception is an app the user has
 * switched on as an accessibility service.
 * (developer.android.com/media/platform/sharing-audio-input, "Voice call + ordinary app")
 *
 * So this service does nothing at all. It asks for no events and cannot read the screen
 * (see res/xml/call_audio_access.xml). Turning it on is what lets the microphone in
 * CallGuardService hear a call that is on speaker.
 */
class CallAudioAccessService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, CallAudioAccessService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
