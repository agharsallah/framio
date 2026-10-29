package dev.framio.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional (Controls → Button): gives the home button an instant Back, and tells it which app is in front so
 * "Close app" needs no `dumpsys`. Without it, Back goes through root `input keyevent`, which starts a Java VM
 * (about a second on this frame).
 */
class NavService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own overlay windows (the dot, its menu) are not "the app in front"
        if (pkg == packageName && event.className?.toString() != LauncherActivity::class.java.name) return
        foreground = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        foreground = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: NavService? = null

        /** The package of the last activity window that came to the front; null while the service is off. */
        @Volatile
        var foreground: String? = null
            private set

        /** True if the service is connected and took the action. */
        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true
    }
}
