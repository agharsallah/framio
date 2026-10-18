package dev.framio.mawaqit.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.framio.mawaqit.MawaqitActivity
import dev.framio.mawaqit.frame

/**
 * Keeps the process alive behind Frameo and with the screen off, so the adhan plays on time and the remote panel
 * answers. On this firmware nothing revives a killed app except a user/HOME launch (example 03, Background lab):
 * a foreground service is what survives. Started by the activity, which the launcher or the user opens.
 */
class FrameService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        // The kernel never suspends on this frame (the IR driver holds a wake lock), but other devices would
        @Suppress("WakelockTimeout") // held for the service's life on purpose: the adhan must fire on time
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "framio:mawaqit")
            .apply { setReferenceCounted(false); acquire() }
        frame.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        wakeLock?.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN).setName("Prayer times").build(),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MawaqitActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Mawaqit")
            .setContentText("Prayer times, adhan and remote panel are running")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL = "frame"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startService(Intent(context, FrameService::class.java))
        }
    }
}
