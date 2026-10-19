package dev.framio.lab.labs.time

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Sand
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.rememberPolled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** No RTC, mains power only: clocks and NTP, alarms, jobs, wake locks, power state, notifications. */
object TimeLab {

    val lab = Lab(
        id = "time",
        title = "Time & power",
        summary = "Clock vs NTP (no RTC!), exact alarms, JobScheduler, wake locks, Doze, notifications",
        accent = Sand,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS zzz", Locale.US)

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        LabPage("Time & power", "The frame has no battery-backed clock: it boots at 1970/01-01 and trusts NTP. It runs on mains only.") {
            ClockSection(context)
            SchedulingSection(context)
            PowerSection(context)
            NotifySection(context)
            SelfTestSection(lab.id, note = "Checks the clock against pool.ntp.org, fires an exact alarm and a job, posts a notification, takes a wake lock.")
        }
    }

    @Composable
    private fun ClockSection(context: Context) {
        val now by rememberPolled(250) { System.currentTimeMillis() }
        val scope = rememberCoroutineScope()
        var ntp by remember { mutableStateOf<String?>(null) }
        Section("Clocks", note = "currentTimeMillis follows the wall clock (NTP); elapsedRealtime counts from boot and never jumps.") {
            val t = now ?: return@Section
            val elapsed = SystemClock.elapsedRealtime()
            StatGrid(
                listOf(
                    "Wall clock" to fmt.format(Date(t)),
                    "Since boot" to Format.duration(elapsed),
                    "Booted at" to fmt.format(Date(t - elapsed)),
                    "Time zone" to "${TimeZone.getDefault().id} (${TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)})",
                    "Auto time (NTP)" to Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0).toString(),
                    "Auto zone" to Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0).toString(),
                    "24-hour" to android.text.format.DateFormat.is24HourFormat(context).toString(),
                    "Awake since boot" to Format.duration(SystemClock.uptimeMillis()),
                ),
                columns = 4,
            )
            Actions {
                ActionButton("Compare with NTP", {
                    scope.launch {
                        ntp = withContext(Dispatchers.IO) {
                            runCatching { Sntp.query() }.fold(
                                { r -> "${r.server}: offset ${r.offsetMs} ms, round trip ${r.roundTripMs} ms, stratum ${r.stratum}" },
                                { "SNTP failed: ${it.message}" },
                            )
                        }
                    }
                })
            }
            ntp?.let { Text(it, style = Mono) }
            Note("Dashboards: until the clock is synced after boot (year < 2020), hide the time instead of showing 1970.")
        }
    }

    @Composable
    private fun SchedulingSection(context: Context) {
        val alarm by Schedulers.alarm.collectAsState()
        val job by Schedulers.job.collectAsState()
        var alarmAt by remember { mutableStateOf<Long?>(null) }
        var jobOk by remember { mutableStateOf<Boolean?>(null) }
        Section("Alarms and jobs", note = "AlarmManager.setExactAndAllowWhileIdle (fires through Doze) and JobScheduler with a 3–10 s window.") {
            Actions {
                ActionButton("Alarm in 10 s", { alarmAt = Schedulers.scheduleAlarm(context, 10_000) })
                ActionButton("Job in 3–10 s", { jobOk = Schedulers.scheduleJob(context, 3000, 10_000) }, primary = false)
            }
            alarmAt?.let {
                Text("Alarm set for ${fmt.format(Date(it))} · " + (alarm?.let { a -> "fired ${a.lateMs} ms late" } ?: "waiting…"), style = Mono)
            }
            jobOk?.let {
                Text("Job scheduled=$it · " + (job?.let { j -> "ran ${j.lateMs} ms after its earliest time" } ?: "waiting…"), style = Mono)
            }
        }
    }

    @Composable
    private fun PowerSection(context: Context) {
        val state by rememberPolled(2000) { power(context) }
        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = remember { pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "framio:lab") }
        var held by remember { mutableStateOf(false) }
        Section("Power", note = "Battery broadcast and PowerManager. No battery: the 'battery' is a placeholder from the HAL.") {
            state?.let { StatGrid(it, columns = 4) }
            Actions {
                ActionButton(if (held) "Release wake lock" else "Hold wake lock 30 s", {
                    if (held) wakeLock.release() else wakeLock.acquire(30_000)
                    held = wakeLock.isHeld
                }, primary = false)
            }
            Note("PARTIAL_WAKE_LOCK keeps the CPU running with the screen off; FLAG_KEEP_SCREEN_ON (used by this app) keeps the screen on.")
        }
    }

    private fun power(context: Context): List<Pair<String, String>> {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm = context.getSystemService(PowerManager::class.java)
        val plugged = when (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            0 -> "unplugged"
            else -> "?"
        }
        return listOf(
            "Power source" to plugged,
            "Battery present" to (b?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)?.toString() ?: "?"),
            "Level (fake)" to "${b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)} %",
            "Temp (fake)" to "${(b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0} °C",
            "Screen on" to pm.isInteractive.toString(),
            "Power save" to pm.isPowerSaveMode.toString(),
            "Doze (idle)" to pm.isDeviceIdleMode.toString(),
            "Ignoring battery opt." to pm.isIgnoringBatteryOptimizations(context.packageName).toString(),
        )
    }

    @Composable
    private fun NotifySection(context: Context) {
        var result by remember { mutableStateOf("") }
        Section("Notifications and toasts", note = "The frame has no status bar, so notifications are posted but never seen. Toasts do show.") {
            Actions {
                ActionButton("Post notification", { result = postNotification(context) }, primary = false)
                ActionButton("Show toast", { Toast.makeText(context, "Toast from Hardware Lab", Toast.LENGTH_LONG).show() }, primary = false)
            }
            if (result.isNotEmpty()) Text(result, style = Mono)
        }
    }

    private const val NOTIFICATION_ID = 7

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is a runtime permission only from API 33; the frame is API 23
    private fun postNotification(context: Context): String {
        val n = NotificationCompat.Builder(context, "lab")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Hardware Lab")
            .setContentText("Test notification")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
        return "enabled=${NotificationManagerCompat.from(context).areNotificationsEnabled()} · active after notify=$active"
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val now = System.currentTimeMillis()
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date(now)).toInt()
        if (year >= 2024) pass("Wall clock", fmt.format(Date(now))) else fail("Wall clock", "${fmt.format(Date(now))}: not synced yet")
        guard("NTP") {
            val r = withContext(Dispatchers.IO) { Sntp.query() }
            val text = "${r.server}: offset ${r.offsetMs} ms, round trip ${r.roundTripMs} ms"
            if (abs(r.offsetMs) < 2000) pass("NTP", text) else warn("NTP", "$text: clock is off")
        }
        info("Auto time", "AUTO_TIME=${Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0)}, zone ${TimeZone.getDefault().id}")
        guard("RTC") {
            val booted = now - SystemClock.elapsedRealtime()
            absent("RTC", "no hardware clock; booted at ${fmt.format(Date(booted))}, wall time comes from NTP after Wi-Fi connects")
        }

        val at = Schedulers.scheduleAlarm(context, 3000)
        val okJob = Schedulers.scheduleJob(context, 1000, 4000)
        val alarm = withTimeoutOrNull(12_000) { Schedulers.alarm.filterNotNull().first() }
        if (alarm != null) pass("Exact alarm", "fired ${alarm.lateMs} ms after ${fmt.format(Date(at))}") else fail("Exact alarm", "did not fire within 12 s")
        val job = withTimeoutOrNull(12_000) { Schedulers.job.filterNotNull().first() }
        if (job != null) pass("JobScheduler", "ran ${job.lateMs} ms after its earliest time") else fail("JobScheduler", "scheduled=$okJob but did not run within 12 s")

        guard("Wake lock") {
            val wl = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "framio:selftest")
            wl.acquire(2000)
            val held = wl.isHeld
            wl.release()
            if (held) pass("Wake lock", "PARTIAL_WAKE_LOCK acquired and released") else fail("Wake lock", "not held")
        }
        guard("Power") {
            val p = power(context).toMap()
            info("Power", "source ${p["Power source"]}, battery present ${p["Battery present"]}, Doze ${p["Doze (idle)"]}")
        }
        guard("Notification") {
            val r = withContext(Dispatchers.Main) { postNotification(context) }
            if (r.contains("active after notify=true")) pass("Notification", "$r (no status bar to show it)") else warn("Notification", r)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        items
    }
}
