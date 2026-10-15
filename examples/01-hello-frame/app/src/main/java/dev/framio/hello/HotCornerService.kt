package dev.framio.hello

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws a small dot above every app (including Frameo). Long-press it to open Hello Frame.
 * The frame has no navigation bar, so this is the on-device way in.
 */
class HotCornerService : Service() {

    private var dot: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (dot != null) return START_STICKY
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay not allowed. Run: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
            stopSelf()
            return START_NOT_STICKY
        }
        showDot()
        return START_STICKY
    }

    override fun onDestroy() {
        dot?.let { getSystemService(WindowManager::class.java).removeView(it) }
        dot = null
        super.onDestroy()
    }

    private fun showDot() {
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // dark fill + light ring: visible on Frameo's white UI and on photos
                setColor(0x66000000)
                setStroke(3, 0xAAFFFFFF.toInt())
            }
            setOnLongClickListener {
                startActivity(
                    Intent(this@HotCornerService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                true
            }
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            DOT_SIZE_PX, DOT_SIZE_PX, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // keys and touches outside the dot go to the app below
            PixelFormat.TRANSLUCENT,
        ).apply {
            // bottom-left: top-left would cover Frameo's close (X) button
            gravity = Gravity.BOTTOM or Gravity.START
            x = 16
            y = 16
        }

        getSystemService(WindowManager::class.java).addView(view, params)
        dot = view
    }

    private companion object {
        const val TAG = "HotCorner"
        const val DOT_SIZE_PX = 56
    }
}
