package dev.framio.lab.labs.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.framio.lab.core.Events
import dev.framio.lab.labs.speaker.QuickSound
import dev.framio.lab.labs.speaker.dbToGain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps beating once a second with no activity on screen. Options (intent extras, remembered in prefs
 * so a system restart after a kill uses the same ones):
 *  - foreground: startForeground with a notification → Android treats the process as user-visible
 *  - wakeLock: PARTIAL_WAKE_LOCK → keeps beating while the screen is off
 *  - beepEverySec: plays the quick chime, so you can hear it running behind other apps
 * START_STICKY: if the process is killed, Android restarts the service with a null intent.
 */
class BackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val heartbeat = Heartbeat(PERIOD_MS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = BackgroundState.prefs(this)
        if (intent == null) {
            Events.log(LAB, "service restarted by Android after the process died (START_STICKY), pid ${BackgroundState.pid}")
        }
        val foreground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) ?: prefs.getBoolean(BackgroundState.SERVICE_FOREGROUND, false)
        val lock = intent?.getBooleanExtra(EXTRA_WAKELOCK, false) ?: prefs.getBoolean(BackgroundState.SERVICE_WAKELOCK, false)
        val beep = intent?.getIntExtra(EXTRA_BEEP, 0) ?: prefs.getInt(BackgroundState.SERVICE_BEEP, 0)
        prefs.edit()
            .putBoolean(BackgroundState.SERVICE_WANTED, true)
            .putBoolean(BackgroundState.SERVICE_FOREGROUND, foreground)
            .putBoolean(BackgroundState.SERVICE_WAKELOCK, lock)
            .putInt(BackgroundState.SERVICE_BEEP, beep)
            .apply()

        if (foreground) startForeground(NOTIFICATION_ID, notification()) else stopForeground(true)
        setWakeLock(lock)
        Events.log(LAB, "service started: foreground=$foreground wakeLock=$lock beepEvery=${beep}s, importance ${BackgroundState.importance()}")

        loop?.cancel()
        loop = scope.launch { beat(foreground, lock, beep) }
        return START_STICKY
    }

    private suspend fun beat(foreground: Boolean, lock: Boolean, beepEverySec: Int) {
        val prefs = BackgroundState.prefs(this)
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val gap = heartbeat.beat(now)
            prefs.edit().putLong(BackgroundState.LAST_BEAT, now).apply()
            BackgroundState.update(
                BackgroundState.Live(
                    running = true, foreground = foreground, wakeLock = lock, beepEverySec = beepEverySec,
                    beats = heartbeat.count, maxGapMs = heartbeat.maxGapMs, lateBeats = heartbeat.lateBeats,
                    runningMs = heartbeat.runningMs, lastBeatMs = now,
                ),
            )
            if (gap > 2 * PERIOD_MS) Events.log(LAB, "heartbeat gap ${gap} ms (process or CPU was paused)")
            if (heartbeat.count % 10 == 0) {
                Events.log(LAB, "heartbeat #${heartbeat.count}, max gap ${heartbeat.maxGapMs} ms, late ${heartbeat.lateBeats}, ui visible=${BackgroundState.uiVisible}, importance ${BackgroundState.importance()}")
            }
            if (beepEverySec > 0 && heartbeat.count % beepEverySec == 0) {
                scope.launch { QuickSound.chime(gain = dbToGain(-16.0)) }
            }
            delay(PERIOD_MS)
        }
    }

    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "framio:background").apply { acquire() }
        } else if (!on) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel( // no-op below API 26
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Background test").build(),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hardware Lab")
            .setContentText("Background test running")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Events.log(LAB, "service stopped after ${heartbeat.count} beats, max gap ${heartbeat.maxGapMs} ms")
        scope.cancel()
        setWakeLock(false)
        BackgroundState.update(BackgroundState.Live())
        super.onDestroy()
    }

    companion object {
        const val LAB = "background"
        const val PERIOD_MS = 1000L
        private const val NOTIFICATION_ID = 11
        private const val CHANNEL = "background"
        const val EXTRA_FOREGROUND = "foreground"
        const val EXTRA_WAKELOCK = "wakeLock"
        const val EXTRA_BEEP = "beepEverySec"

        fun start(context: Context, foreground: Boolean, wakeLock: Boolean, beepEverySec: Int) {
            context.startService(
                Intent(context, BackgroundService::class.java)
                    .putExtra(EXTRA_FOREGROUND, foreground)
                    .putExtra(EXTRA_WAKELOCK, wakeLock)
                    .putExtra(EXTRA_BEEP, beepEverySec),
            )
        }

        fun stop(context: Context) {
            BackgroundState.prefs(context).edit().putBoolean(BackgroundState.SERVICE_WANTED, false).apply()
            context.stopService(Intent(context, BackgroundService::class.java))
        }
    }
}
