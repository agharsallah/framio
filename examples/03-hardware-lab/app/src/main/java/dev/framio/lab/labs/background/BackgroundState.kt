package dev.framio.lab.labs.background

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the background lab knows. In-process state for the UI, plus SharedPreferences for what must
 * survive the process being killed (that is the point: was it killed, did it come back?).
 */
object BackgroundState {

    data class Live(
        val running: Boolean = false,
        val foreground: Boolean = false,
        val wakeLock: Boolean = false,
        val beepEverySec: Int = 0,
        val beats: Int = 0,
        val maxGapMs: Long = 0,
        val lateBeats: Int = 0,
        val runningMs: Long = 0,
        val lastBeatMs: Long = 0,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    /** Set by LabActivity onStart/onStop: is the app's screen what the user sees? */
    @Volatile var uiVisible = false

    fun update(live: Live) {
        _live.value = live
    }

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("background", Context.MODE_PRIVATE)

    // persisted keys
    const val PROCESS_STARTS = "processStarts"
    const val LAST_PROCESS_START = "lastProcessStart"
    const val LAST_BEAT = "lastBeat"
    const val SERVICE_WANTED = "serviceWanted"
    const val SERVICE_FOREGROUND = "serviceForeground"
    const val SERVICE_WAKELOCK = "serviceWakeLock"
    const val SERVICE_BEEP = "serviceBeep"
    const val BOOTS_SEEN = "bootsSeen"
    const val LAST_BOOT_RECEIVED = "lastBootReceived"
    const val AUTOSTART = "autostart"
    const val ALARM_ON = "alarmOn"
    const val ALARM_COUNT = "alarmCount"
    const val LAST_ALARM = "lastAlarm"

    /** Android's view of this process: 100 foreground, 125 foreground service, 130 perceptible, 300 service, 400 background… */
    fun importance(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        val name = when (info.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            125 -> "foreground service" // IMPORTANCE_FOREGROUND_SERVICE, named only from API 23 docs
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "background"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "empty"
            else -> "?"
        }
        return "$name (${info.importance})"
    }

    val pid get() = Process.myPid()

    /** Wall-clock time this process started. */
    fun processStartedAt(prefs: SharedPreferences) = prefs.getLong(LAST_PROCESS_START, 0L)
}
