package dev.framio.pomodoro

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.framio.pomodoro.core.engine.Derive
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.di.AppContainer
import dev.framio.pomodoro.di.DebugTime
import dev.framio.pomodoro.platform.Logs
import dev.framio.pomodoro.platform.Logs.Status
import dev.framio.pomodoro.platform.WindowLevels
import dev.framio.pomodoro.platform.WindowLight
import dev.framio.pomodoro.platform.systemBacklightLevel
import dev.framio.pomodoro.ui.PomodoroRoot
import dev.framio.pomodoro.ui.settings.SettingsHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The only activity. It draws the engine's state and carries out what needs a window: keep-screen-on, window
 * brightness, immersive mode. adb extras (also via onNewIntent; singleTask), e.g.
 *   adb shell am start -n dev.framio.pomodoro/.MainActivity --es mode classic --es cmd start
 *
 *  - `--es cmd start|ritual|pause|resume|end|stop|extend|skip|done|home|peek|selftest` (peek: Deep's knock peek
 *    without knocking; stop: Flow's "Stop here"; extend: +5 min on a break or a fixed block)
 *  - `--es mode classic|deep|flow|gentle`, `--es task "<title>"` (the intention for start and ritual),
 *    `--es display calm|blackout` (start: a dark mode's screen for this run, as the ritual's toggle)
 *  - `--ei min N`: this block lasts N minutes
 *  - hooks builds: `--ei sec N` runs the engine "minutes as seconds" (from the next process start; force-stop
 *    first) and makes this block N "minutes" = N real seconds. `--ei sec 0` goes back to real time. Release ignores it.
 *  - `--es cmd export`: History as JSON + two CSVs into `/sdcard/Android/data/dev.framio.pomodoro/files/export/`
 *  - hooks builds: `--es cmd seed --ei days N` adds N days of made-up history (`--ei days 0` removes it)
 *  - hooks builds: `--es cmd evening --es at 21:30` judges evening amber as if the local time were
 *    21:30 (the system clock is never changed); `--es at off` goes back to the real time.
 *  - hooks builds: `--es cmd knock` feeds the knock detector a synthetic double knock (needs Deep focus on screen,
 *    i.e. the accelerometer registered); release ignores it.
 *  - hooks builds (debug and dev): every focus block logs what its first 1.7 s allocated (`EVENT|alloc|…`, for Begin's jank)
 * Every command logs `EVENT|adb|…`; selftest logs `CHECK|…` lines.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as PomodoroApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.HOOKS && savedInstanceState == null) debugTimeScale(intent)
        container.start()
        val port = container.port
        val host = SettingsHost(
            playTest = { container.sound.test(it) },
            preview = { soundscape, volume -> container.soundscape.preview(soundscape, volume) },
            export = { exportFiles() },
            version = BuildConfig.VERSION_NAME,
        )
        setContent {
            PomodoroRoot(
                port, playTest = host.playTest, onExit = ::exit, hardware = container.hardware, history = container.history,
                settingsHost = host, evening = container.evening.on,
            )
        }
        // The root paints every pixel itself: without this the window's own background is one more full-screen fill
        // under it on every frame (LOG M4). The launch preview keeps the theme's background.
        window.setBackgroundDrawable(null)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Keep the screen on while a session runs (Focus, Paused, Wrap-up, Break, Ready); Home may sleep
                launch {
                    port.state.map { it.loaded && Derive.serviceActive(it) }.distinctUntilChanged().collect { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch { container.display.window.collectLatest(::applyWindowLight) }
                launch {
                    port.state.map { it.loaded to it.phase::class.simpleName }.distinctUntilChanged().collect { (loaded, phase) ->
                        if (loaded) Logs.event("phase", phase.orEmpty())
                        if (BuildConfig.HOOKS && loaded && phase.orEmpty().endsWith("Focus")) launch { allocProbe() }
                    }
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.HOOKS && intent.hasExtra(EXTRA_SEC) && container.engineBuilt) {
            val want = intent.getIntExtra(EXTRA_SEC, 0) > 0
            DebugTime.set(this, want)
            if (want != container.timeScaled) Logs.event("adb", "time scale changes at the next start: force-stop first")
        }
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        container.presence.onScreen.value = true
        val s = container.port.state.value
        if (s.loaded) container.display.set(Derive.display(s), Duration.ZERO) // back from elsewhere: re-apply (Blackout, M3)
    }

    override fun onStop() {
        container.presence.onScreen.value = false
        val s = container.port.state.value
        if (!isFinishing && s.loaded && Derive.serviceActive(s)) {
            // M0: Frameo may come forward; don't fight it mid-focus, the deadline's REORDER_TO_FRONT brings us back
            Logs.event("focus", "lost-front in ${s.phase::class.simpleName}")
        }
        container.display.onLeftScreen()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // The frame has no bars; this keeps it that way if the firmware ever shows them
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun exit() {
        Logs.event("app", "exit")
        finish()
    }

    /** Window brightness for the phase; ramps (Deep's sunrise) step every 100 ms from the current value. */
    private suspend fun applyWindowLight(light: WindowLight) {
        val needsSystem = light.ramp.isPositive() || light.cap != null
        val system = if (needsSystem) withContext(Dispatchers.IO) { systemBacklightLevel(this@MainActivity) } / 255f else 1f
        // Evening amber: never brighter than the cap, the system's own level included
        val target = WindowLevels.capped(light, system)
        if (light.ramp.isPositive()) {
            val from = window.attributes.screenBrightness.let { if (it < 0) system else it }
            val to = if (target < 0) system else target
            val steps = (light.ramp.inWholeMilliseconds / RAMP_STEP_MS).toInt().coerceAtLeast(1)
            Logs.event("window", "brightness ${level255(from)} → ${level255(to)} over ${light.ramp}")
            for (i in 1..steps) {
                setWindowBrightness(from + (to - from) * i / steps)
                delay(RAMP_STEP_MS)
            }
        }
        setWindowBrightness(target)
        Logs.event("window", "brightness ${if (target < 0) "system" else level255(target).toString()}")
    }

    private fun level255(f: Float) = (f * 255).toInt()

    private fun setWindowBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    /** Debug builds: `--ei sec N` decides the engine's time scale before the engine is first built. */
    private fun debugTimeScale(intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA_SEC)) return
        DebugTime.set(this, intent.getIntExtra(EXTRA_SEC, 0) > 0)
    }

    private fun handle(intent: Intent?) {
        val extras = intent?.extras ?: return
        val cmd = extras.getString(EXTRA_CMD)
        val mode = extras.getString(EXTRA_MODE)
        val task = extras.getString(EXTRA_TASK)
        val min = extras.getInt(EXTRA_MIN, 0)
        val display = when (extras.getString(EXTRA_DISPLAY)) {
            "calm" -> Display.Calm
            "blackout" -> Display.Blackout
            else -> null
        }
        val sec = if (BuildConfig.HOOKS) extras.getInt(EXTRA_SEC, 0) else 0
        if (cmd == null && mode == null) return
        val days = extras.getInt(EXTRA_DAYS, 400)
        intent.replaceExtras(Bundle()) // handled once: a later re-creation with this intent must not replay it
        val port = container.port
        lifecycleScope.launch {
            val s = port.state.first { it.loaded }
            if (mode != null) {
                Logs.event("adb", "mode $mode")
                port.send(Input.SelectMode(ModeId(mode)))
            }
            val override = when {
                sec > 0 -> if (container.timeScaled) sec.minutes else sec.seconds
                min > 0 -> min.minutes
                else -> null
            }
            when (cmd) {
                null -> Unit
                "start" -> {
                    Logs.event("adb", "start${override?.let { " for $it" } ?: ""}${task?.let { " · $it" } ?: ""}")
                    if (s.phase == Phase.Ready && override == null && task == null) {
                        port.send(Input.StartNext)
                    } else {
                        val required = port.state.value.settings.mode(mode?.let(::ModeId) ?: s.settings.selectedMode)?.ritual?.intention == IntentionRule.Required
                        port.send(Input.Begin(intention = task ?: if (required) "adb test" else null, focusOverride = override, display = display))
                    }
                }
                "pause" -> send("pause", Input.Pause)
                "resume" -> send("resume", Input.Resume)
                "end" -> send("end", Input.EndEarly())
                "skip" -> send("skip", Input.SkipBreak)
                "stop" -> send("stop", Input.Complete)
                "extend" -> send("extend", if (port.state.value.phase is Phase.Break) Input.ExtendBreak() else Input.Extend())
                "done" -> send("done", Input.WrapUp(Outcome.Done))
                "home" -> send("home", Input.Home)
                "ritual" -> send("ritual", Input.OpenRitual(task))
                "peek" -> container.hardware.peekNow()
                "knock" -> if (BuildConfig.HOOKS) container.hardware.knock() else Logs.event("adb", "knock is debug-only")
                "selftest" -> selfTest()
                "export" -> export()
                "seed" -> if (BuildConfig.HOOKS) seed(days) else Logs.event("adb", "seed is debug-only")
                "evening" -> if (BuildConfig.HOOKS) fakeEvening(extras.getString(EXTRA_AT)) else Logs.event("adb", "evening is a hooks-build command")
                else -> Logs.event("adb", "unknown cmd $cmd")
            }
        }
    }

    /** `--es cmd export`: History as JSON + CSV in app-specific external storage (no permission), for `adb pull`. */
    private suspend fun export() {
        exportFiles()
    }

    /** History as JSON + CSV (adb `export`, Settings → Data); the folder it went to. */
    private suspend fun exportFiles(): String {
        val dir = getExternalFilesDir("export") ?: java.io.File(filesDir, "export")
        val files = container.history.export(dir, System.currentTimeMillis())
        Logs.event("export", "${files.joinToString { it.name }} in ${dir.path}")
        return dir.path
    }

    /** Hooks builds: `--es cmd evening --es at 21:30` judges evening amber as if it were 21:30; `--es at off` stops. */
    private fun fakeEvening(at: String?) {
        val time = at?.takeIf { it != "off" }?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        Logs.event("adb", "evening ${time ?: "real time"}")
        container.evening.fake(time)
    }

    /** Debug: `--es cmd seed --ei days N` adds N days of made-up history (ids `seed-…`); `--ei days 0` removes it. */
    private suspend fun seed(days: Int) {
        Logs.event("adb", "seed $days days")
        container.history.seed(days)
    }

    private fun send(name: String, input: Input) {
        Logs.event("adb", name)
        container.port.send(input)
    }

    private suspend fun selfTest() {
        val c = container
        val s = c.port.state.value
        val keepOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        withContext(Dispatchers.IO) {
            val now = c.clock.now()
            Logs.check("time", "boot", if (now.bootId.startsWith("unknown")) Status.WARN else Status.PASS, "boot ${now.bootId.take(8)} elapsed ${now.elapsedMs}")
            Logs.check("time", "trusted", if (c.timeGate.isTrusted) Status.PASS else Status.WARN, "wall ${now.wallMs} zone ${now.zone}")
            Logs.check("engine", "loaded", if (s.loaded) Status.PASS else Status.FAIL, "phase ${s.phase::class.simpleName} scale ${c.port.config.timeScale} today ${s.completedToday}/${s.settings.dailyTarget}")
            val active = s.loaded && Derive.serviceActive(s)
            Logs.check("service", "running", if (active == c.service.isWanted) Status.PASS else Status.FAIL, "wanted ${c.service.isWanted}, running ${c.service.isRunning} (starts 3.5 s after Begin), session ${if (active) "active" else "none"}")
            Logs.check("window", "keepScreenOn", if (keepOn == active) Status.PASS else Status.FAIL, "flag $keepOn")
            val root = c.root.available()
            Logs.check("root", "su", if (root) Status.PASS else Status.FAIL, if (root) "uid 0" else "no root")
            val level = c.root.read()
            Logs.check("display", "backlight", if (level != null && level > 0) Status.PASS else Status.WARN, "level $level, we wrote ${c.displayPower.written}")
            Logs.check("sound", "track", if (c.sound.selfTest()) Status.PASS else Status.FAIL, "static AudioTrack, USAGE_ALARM")
            val log = c.sessionLog.read()
            Logs.check("store", "log", if (log.skipped == 0) Status.PASS else Status.WARN, "${log.records.size} records, ${log.skipped} unreadable lines")
        }
    }

    /**
     * Hooks builds: what the first 1.7 s of a focus block allocate (all threads and the main thread) and how many GCs
     * that costs: `EVENT|alloc|…`, for Begin's jank (LOG M6).
     */
    @Suppress("DEPRECATION") // the alloc counters are obsolete but still work on ART 6, which is all this is for
    private suspend fun allocProbe() {
        fun stat(name: String) = android.os.Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1L
        val bytes0 = stat("art.gc.bytes-allocated")
        val gc0 = stat("art.gc.gc-count")
        val gcMs0 = stat("art.gc.gc-time")
        android.os.Debug.resetAllCounts()
        android.os.Debug.startAllocCounting()
        delay(1_700)
        val main = android.os.Debug.getThreadAllocSize()
        val mainCount = android.os.Debug.getThreadAllocCount()
        android.os.Debug.stopAllocCounting()
        Logs.event(
            "alloc",
            "first 1.7 s: ${(stat("art.gc.bytes-allocated") - bytes0) / 1024} kB in all, main $main B in $mainCount objects; " +
                "${stat("art.gc.gc-count") - gc0} GCs, ${stat("art.gc.gc-time") - gcMs0} ms",
        )
    }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TASK = "task"
        const val EXTRA_MIN = "min"
        const val EXTRA_SEC = "sec"
        const val EXTRA_DISPLAY = "display"
        const val EXTRA_DAYS = "days"
        const val EXTRA_AT = "at"
        const val RAMP_STEP_MS = 100L
    }
}
