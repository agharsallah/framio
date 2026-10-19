package dev.framio.lab.labs.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.framio.lab.core.Events

/**
 * BOOT_COMPLETED: does the firmware let the app start itself after a reboot?
 * (Earlier, example 01 got "process is bad" here.) With autostart on, it also starts the service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = BackgroundState.prefs(context)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(BackgroundState.LAST_BOOT_RECEIVED, now)
            .putInt(BackgroundState.BOOTS_SEEN, prefs.getInt(BackgroundState.BOOTS_SEEN, 0) + 1)
            .apply()
        val autostart = prefs.getBoolean(BackgroundState.AUTOSTART, false)
        Events.log(BackgroundService.LAB, "BOOT_COMPLETED received, autostart=$autostart, importance ${BackgroundState.importance()}")
        if (autostart) BackgroundService.start(context, foreground = true, wakeLock = false, beepEverySec = 0)
        if (prefs.getBoolean(BackgroundState.ALARM_ON, false)) MinuteAlarm.schedule(context) // alarms don't survive a reboot
    }
}

/** An exact alarm every minute, chained (each one schedules the next), fired even with the app closed. */
object MinuteAlarm {

    private const val PERIOD_MS = 60_000L

    /** Turns the chain on (first alarm in a minute) or off, and resets the counter. */
    fun set(context: Context, on: Boolean) {
        BackgroundState.prefs(context).edit().putBoolean(BackgroundState.ALARM_ON, on).putInt(BackgroundState.ALARM_COUNT, 0).apply()
        if (on) schedule(context) else cancel(context)
        Events.log(BackgroundService.LAB, "minute alarm ${if (on) "on" else "off"}")
    }

    fun schedule(context: Context, inMs: Long = PERIOD_MS) {
        val at = System.currentTimeMillis() + inMs
        context.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, at))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, 0))
    }

    private fun pending(context: Context, at: Long) = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, MinuteAlarmReceiver::class.java).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun onFire(context: Context, at: Long) {
        val prefs = BackgroundState.prefs(context)
        if (!prefs.getBoolean(BackgroundState.ALARM_ON, false)) return
        val n = prefs.getInt(BackgroundState.ALARM_COUNT, 0) + 1
        prefs.edit().putInt(BackgroundState.ALARM_COUNT, n).putLong(BackgroundState.LAST_ALARM, System.currentTimeMillis()).apply()
        Events.log(BackgroundService.LAB, "minute alarm #$n, ${System.currentTimeMillis() - at} ms late, ui visible=${BackgroundState.uiVisible}, importance ${BackgroundState.importance()}")
        schedule(context)
    }
}

class MinuteAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = MinuteAlarm.onFire(context, intent.getLongExtra("at", 0L))
}
