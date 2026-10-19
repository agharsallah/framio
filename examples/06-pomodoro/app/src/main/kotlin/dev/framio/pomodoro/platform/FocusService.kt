package dev.framio.pomodoro.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.framio.pomodoro.MainActivity
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.engine.Effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the foreground service runs and when the app comes back to the front. Pure, so it's a unit test.
 * The engine says when a session needs the service (StartService from Begin until Home, see Derive.serviceActive);
 * the policy only turns those effects into start/stop calls without repeating them.
 */
object ServicePolicy {
    enum class Action { Start, Stop, None }

    fun onEffect(effect: Effect, running: Boolean): Action = when (effect) {
        Effect.StartService -> if (running) Action.None else Action.Start
        Effect.StopService -> if (running) Action.Stop else Action.None
        else -> Action.None
    }

    /**
     * A block or break ended (BringToFront): show Pomodoro again if another app (Frameo, the launcher) is in front.
     * Never while it's already on screen: a second start would only re-deliver an intent.
     */
    fun bringToFront(onScreen: Boolean): Boolean = !onScreen
}

/**
 * A foreground service with no logic of its own: while a session runs it keeps the process at foreground priority,
 * so the deadline cue plays and BringToFront works behind Frameo. The engine, its scheduler and the stores live in
 * the AppContainer. START_NOT_STICKY: this firmware never restarts a killed process anyway (03 LOG).
 */
class FocusService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Logs.event("service", "stopped")
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 6
        const val CHANNEL = "focus"

        /** Low-key: minimum priority, no sound, no time; the frame has no status bar to show it anyway. */
        fun notification(context: Context): Notification {
            NotificationManagerCompat.from(context).createNotificationChannel( // no-op below API 26
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName(context.getString(R.string.service_channel))
                    .build(),
            )
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }
}

/**
 * Starts and stops [FocusService] as [ServicePolicy] says. Called on the engine's thread; never blocks.
 *
 * The start waits [startDelay]: Begin's 3 s "breathe in" is the app's most expensive animation, and a service start
 * runs its onCreate and onStartCommand on the main thread (13–14 ms in the first second, LOG M3). Nothing needs the
 * service sooner: the process is in front and the first deadline is minutes away. A stop cancels a pending start.
 */
class ServiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val startDelay: Duration = START_DELAY,
) {
    private val running = AtomicBoolean(false)
    private val wanted = AtomicBoolean(false)
    private var pending: Job? = null

    /** Whether the service has been started (and not stopped since). */
    val isRunning: Boolean get() = running.get()

    /** Whether a session wants it: true from StartService on, even while the start is still deferred. */
    val isWanted: Boolean get() = wanted.get()

    fun handle(effect: Effect) {
        when (ServicePolicy.onEffect(effect, wanted.get())) {
            ServicePolicy.Action.Start -> {
                wanted.set(true)
                pending?.cancel()
                pending = scope.launch {
                    delay(startDelay)
                    if (wanted.get() && !running.getAndSet(true)) {
                        ContextCompat.startForegroundService(context, Intent(context, FocusService::class.java))
                        Logs.event("service", "start")
                    }
                }
            }
            ServicePolicy.Action.Stop -> {
                wanted.set(false)
                pending?.cancel()
                if (running.getAndSet(false)) {
                    context.stopService(Intent(context, FocusService::class.java))
                    Logs.event("service", "stop")
                }
            }
            ServicePolicy.Action.None -> Unit
        }
    }

    private companion object {
        /** Past Begin's breath (3 s). */
        val START_DELAY: Duration = 3500.milliseconds
    }
}
