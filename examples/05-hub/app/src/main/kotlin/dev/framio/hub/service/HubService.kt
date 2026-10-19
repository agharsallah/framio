package dev.framio.hub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.framio.hub.HubActivity
import dev.framio.hub.R
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Run
import dev.framio.hub.platform.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** When the foreground service must run. Pure, so the table in the design doc is a unit test. */
object ServicePolicy {
    fun shouldRun(onScreen: Boolean, timersNeedIt: Boolean, keepRunning: Boolean, quit: Boolean): Boolean =
        !quit && (onScreen || timersNeedIt || keepRunning)

    /** A running or ringing timer must be able to ring and bring the Hub back, whatever is on screen. */
    fun timersNeedIt(s: HubState) = s.timers.any { it.run is Run.Running || it.run is Run.Ringing }
}

/**
 * A foreground service with no logic of its own: it only keeps the process at foreground priority while the
 * Hub is needed off screen (timers, phones). The engine, scheduler and servers live in the AppContainer.
 * START_NOT_STICKY: this firmware can't restart a killed process anyway (03 LOG), so we don't pretend.
 */
class HubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(this))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Logs.event("service", "stopped")
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "hub"

        private fun notification(context: Context): Notification {
            NotificationManagerCompat.from(context).createNotificationChannel( // no-op below API 26
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.service_channel))
                    .build(),
            )
            val open = PendingIntent.getActivity(context, 0, Intent(context, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_hub)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }
}

/** Starts and stops [HubService] by [ServicePolicy], following the state, the Hub's visibility and Quit. */
class ServiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<HubState>,
    private val onScreen: StateFlow<Boolean>,
    private val quit: StateFlow<Boolean>,
) {
    fun start() {
        scope.launch {
            combine(state, onScreen, quit) { s, visible, q ->
                ServicePolicy.shouldRun(visible, ServicePolicy.timersNeedIt(s), s.settings.keepRunning, q)
            }.distinctUntilChanged().collect { run ->
                val intent = Intent(context, HubService::class.java)
                if (run) {
                    ContextCompat.startForegroundService(context, intent)
                    Logs.event("service", "running")
                } else {
                    context.stopService(intent)
                }
            }
        }
    }
}
