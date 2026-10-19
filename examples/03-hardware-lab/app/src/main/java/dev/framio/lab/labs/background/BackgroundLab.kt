package dev.framio.lab.labs.background

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Choice
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Lime
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.ToggleRow
import dev.framio.lab.core.ui.Verdict
import dev.framio.lab.core.ui.rememberPolled
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Can an app keep working when it is not on screen? Service heartbeat, alarms, boot start, process deaths. */
object BackgroundLab {

    val lab = Lab(
        id = BackgroundService.LAB,
        title = "Background",
        summary = "Keep running off-screen: service heartbeat, audible beep, minute alarms, start at boot",
        accent = Lime,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private enum class Kind(val label: String) { Plain("Plain service"), Foreground("Foreground service") }

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        val live by BackgroundState.live.collectAsState()
        val prefs = remember { BackgroundState.prefs(context) }
        var kind by remember { mutableStateOf(Kind.Foreground) }
        var wakeLock by remember { mutableStateOf(false) }
        var beep by remember { mutableIntStateOf(10) }
        var version by remember { mutableIntStateOf(0) }
        val tick by rememberPolled(1000) { System.currentTimeMillis() }

        LabPage("Background", "Start the service, leave the app (Home, Photos, screen off) and come back: the heartbeat shows whether it kept running.") {
            Section("Service", note = "A Service that beats once a second. With the beep on you can hear it while other apps are on screen.") {
                Choice(Kind.entries, kind, { it.label }) { kind = it }
                Note(
                    if (kind == Kind.Foreground) "startForeground(): Android ranks the process like a visible app and won't kill it for memory."
                    else "No notification: the process ranks as a background service and may be killed when memory is short.",
                )
                ToggleRow("Keep CPU awake", wakeLock, { wakeLock = it }, note = "PARTIAL_WAKE_LOCK: keeps beating with the screen off (Frameo's night mode turns it off).")
                Choice(listOf(0, 10, 30, 60), beep, { if (it == 0) "No beep" else "Beep every $it s" }) { beep = it }
                Actions {
                    ActionButton(if (live.running) "Restart with these options" else "Start", {
                        BackgroundService.start(context, kind == Kind.Foreground, wakeLock, beep)
                    })
                    ActionButton("Stop", { BackgroundService.stop(context) }, enabled = live.running, primary = false)
                    ActionButton("Go to home screen", {
                        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, primary = false)
                }
                tick.let {
                    if (live.running) {
                        val age = System.currentTimeMillis() - live.lastBeatMs
                        Verdict(
                            if (age < 3000 && live.lateBeats == 0) Status.PASS else Status.WARN,
                            "Running ${Format.duration(live.runningMs)} · ${live.beats} beats · max gap ${live.maxGapMs} ms · ${live.lateBeats} late · last beat ${age} ms ago",
                        )
                        StatGrid(
                            listOf(
                                "Type" to if (live.foreground) "foreground" else "plain",
                                "Wake lock" to live.wakeLock.toString(),
                                "Beep" to if (live.beepEverySec == 0) "off" else "every ${live.beepEverySec} s",
                                "Importance now" to BackgroundState.importance(),
                            ),
                        )
                    } else {
                        Note("Not running.")
                    }
                }
            }

            Section("Minute alarm", note = "AlarmManager exact alarm every 60 s (chained). Fires while the process is alive; once it has been killed, this firmware refuses to start it for the alarm ('process is bad').") {
                version.let {
                    val on = prefs.getBoolean(BackgroundState.ALARM_ON, false)
                    ToggleRow("Alarm every minute", on, { enable ->
                        MinuteAlarm.set(context, enable)
                        version++
                    })
                    tick.let {
                        val last = prefs.getLong(BackgroundState.LAST_ALARM, 0L)
                        Text("Fired ${prefs.getInt(BackgroundState.ALARM_COUNT, 0)} times" + if (last > 0) ", last at ${time.format(Date(last))}" else "", style = Mono)
                    }
                }
            }

            Section("Process and boot", note = "The process start counter goes up when Android had to start the app again; the boot receiver shows whether the firmware lets the app wake up at boot.") {
                version.let {
                    val autostart = prefs.getBoolean(BackgroundState.AUTOSTART, false)
                    tick.let {
                        val boot = prefs.getLong(BackgroundState.LAST_BOOT_RECEIVED, 0L)
                        StatGrid(
                            listOf(
                                "PID" to BackgroundState.pid.toString(),
                                "Process starts" to prefs.getInt(BackgroundState.PROCESS_STARTS, 0).toString(),
                                "This process since" to time.format(Date(BackgroundState.processStartedAt(prefs))),
                                "BOOT_COMPLETED seen" to "${prefs.getInt(BackgroundState.BOOTS_SEEN, 0)}×" + if (boot > 0) " (last ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(boot))})" else "",
                            ),
                        )
                    }
                    ToggleRow("Start the foreground service at boot", autostart, {
                        prefs.edit().putBoolean(BackgroundState.AUTOSTART, it).apply()
                        version++
                    }, note = "BootReceiver → startService. On this firmware BOOT_COMPLETED never arrives ('process is bad'): the HOME app is the only thing started at boot.")
                }
            }

            Section("How to test by hand") {
                Note(
                    "1. Start a foreground service with a beep every 10 s.\n" +
                        "2. Go to the home screen, open Photos: the chime should keep coming.\n" +
                        "3. Turn the screen off with the power button for a minute, with and without 'Keep CPU awake'.\n" +
                        "4. Come back: max gap and late beats show what happened. Everything is also logged as EVENT|background.",
                )
            }

            SelfTestSection(lab.id, note = "Starts a plain service for 4 s and checks the heartbeat, then reports alarms, boot receiver and process starts.")
        }
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val prefs = BackgroundState.prefs(context)
        val wasRunning = BackgroundState.live.value.running
        if (wasRunning) {
            val l = BackgroundState.live.value
            info("Service", "already running ${Format.duration(l.runningMs)}: ${l.beats} beats, max gap ${l.maxGapMs} ms, ${l.lateBeats} late")
        } else {
            BackgroundService.start(context, foreground = false, wakeLock = false, beepEverySec = 0)
            delay(4500)
            val l = BackgroundState.live.value
            BackgroundService.stop(context)
            if (l.beats >= 4 && l.maxGapMs < 2000) pass("Service", "${l.beats} beats in ${Format.duration(l.runningMs)}, max gap ${l.maxGapMs} ms")
            else fail("Service", "${l.beats} beats, max gap ${l.maxGapMs} ms")
        }
        info("Process", "pid ${BackgroundState.pid}, ${prefs.getInt(BackgroundState.PROCESS_STARTS, 0)} process starts so far, importance ${BackgroundState.importance()}")
        val boots = prefs.getInt(BackgroundState.BOOTS_SEEN, 0)
        if (boots > 0) pass("Boot receiver", "BOOT_COMPLETED received $boots× (last ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(prefs.getLong(BackgroundState.LAST_BOOT_RECEIVED, 0)))})")
        else absent("Boot receiver", "never received: this firmware refuses background starts of the app (logcat: 'Unable to launch app … for broadcast … BOOT_COMPLETED: process is bad'); start background work from the launcher instead")
        if (prefs.getBoolean(BackgroundState.ALARM_ON, false)) info("Minute alarm", "on, fired ${prefs.getInt(BackgroundState.ALARM_COUNT, 0)}×")
        manual("Off-screen", "Background → start with beep, go home / open Photos / screen off, then compare max gap and late beats")
        items
    }
}
