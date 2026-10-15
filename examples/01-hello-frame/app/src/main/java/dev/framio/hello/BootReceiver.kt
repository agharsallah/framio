package dev.framio.hello

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the hot corner back after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startService(Intent(context, HotCornerService::class.java))
        }
    }
}
