package dev.framio.pomodoro.di

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.datastore.dataStoreFile
import dev.framio.pomodoro.BuildConfig
import dev.framio.pomodoro.MainActivity
import dev.framio.pomodoro.core.engine.Effect
import dev.framio.pomodoro.core.engine.EngineConfig
import dev.framio.pomodoro.core.engine.FocusEngine
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Snapshot
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.time.EngineClock
import dev.framio.pomodoro.core.time.GatedClock
import dev.framio.pomodoro.core.time.TimeGate
import dev.framio.pomodoro.data.HistoryRepository
import dev.framio.pomodoro.data.LineFile
import dev.framio.pomodoro.data.SessionLogFile
import dev.framio.pomodoro.data.TagStore
import dev.framio.pomodoro.data.SettingsStore
import dev.framio.pomodoro.data.SnapshotStore
import dev.framio.pomodoro.platform.AndroidBootClock
import dev.framio.pomodoro.platform.DisplayController
import dev.framio.pomodoro.platform.DisplayPower
import dev.framio.pomodoro.platform.EffectRouter
import dev.framio.pomodoro.platform.EffectSinks
import dev.framio.pomodoro.platform.Logs
import dev.framio.pomodoro.platform.Motion
import dev.framio.pomodoro.platform.PrefsDarkMarker
import dev.framio.pomodoro.platform.Presence
import dev.framio.pomodoro.platform.RootShell
import dev.framio.pomodoro.platform.ServiceController
import dev.framio.pomodoro.platform.SoundEngine
import dev.framio.pomodoro.platform.SoundscapePlayer
import dev.framio.pomodoro.platform.EveningWatch
import dev.framio.pomodoro.platform.TimeWatcher
import dev.framio.pomodoro.platform.systemBacklightLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/** The dispatchers, injected so tests can pass test dispatchers. */
data class AppDispatchers(
    val default: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Manual dependency injection: the whole object graph, built lazily, so nothing runs at process start that the first
 * frame doesn't need. One instance per process, owned by [dev.framio.pomodoro.PomodoroApp].
 */
class AppContainer(private val context: Context, val dispatchers: AppDispatchers = AppDispatchers()) {
    /** The app's own scope: lives as long as the process. A failing child doesn't cancel the others. */
    val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val presence = Presence()
    val timeGate by lazy { TimeGate(notBefore = Build.TIME) } // M0 §7: trust from the firmware's build date on
    private val timeWatcher by lazy { TimeWatcher(timeGate) }
    val clock: EngineClock by lazy { GatedClock(AndroidBootClock(), timeGate) { ZoneId.systemDefault() } }

    val root by lazy { RootShell() }
    val displayPower by lazy { DisplayPower(root, PrefsDarkMarker(context), dispatchers.io) { systemBacklightLevel(context) } }
    val display by lazy { DisplayController(displayPower, scope) }
    val sound by lazy { SoundEngine(scope, dispatchers.default.limitedParallelism(1)) }
    val soundscape by lazy { SoundscapePlayer() }

    /** Evening amber: on from the evening start until the morning, only with a trusted clock (core Evening). */
    val evening by lazy { EveningWatch(clock, engine.state, scope) }
    val motion by lazy { Motion(context, sound.playing) }
    val hardware by lazy { AndroidDeepHardware(motion, display, scope) }
    val service by lazy { ServiceController(context, scope) }
    private val settingsStore by lazy { SettingsStore(context.dataStoreFile("settings.json"), scope, dispatchers.io) }
    private val snapshotStore by lazy { SnapshotStore(context.dataStoreFile("snapshot.json"), scope, dispatchers.io) }
    val sessionLog by lazy { SessionLogFile(File(context.filesDir, "sessions.jsonl")) }
    private val logQueue = Channel<SessionRecord>(Channel.UNLIMITED)

    /** Sessions joined with their notes (`history.jsonl`), the reason tags, Insights' cache. Loaded lazily. */
    val history: HistoryRepository by lazy {
        HistoryRepository(
            sessions = sessionLog,
            journalFile = LineFile(File(context.filesDir, "history.jsonl")),
            cacheFile = File(context.filesDir, "stats-cache.json"),
            tagStore = TagStore(context.dataStoreFile("tags.json"), scope, dispatchers.io),
            scope = scope,
            io = dispatchers.io,
            zone = { ZoneId.systemDefault() },
            wall = { clock.now().wallMs },
            cacheDelay = 2.seconds,
        )
    }

    /** Debug builds only: "minutes as seconds" (adb `--ei sec N`), read once when the engine is built. */
    val timeScaled: Boolean by lazy { DebugTime.scaled(context) }

    @Volatile var engineBuilt = false
        private set

    private val sinks = object : EffectSinks {
        override fun cue(effect: Effect.PlayCue) = sound.cue(effect.kind, effect.style, engine.state.value.settings.soundVolume)
        override fun display(effect: Effect.SetDisplay) = this@AppContainer.display.set(effect.level, effect.ramp)
        override fun service(effect: Effect) {
            if (effect == Effect.StartService) sound.prepare() // the first cue on time (LOG M2: 0.6 s late when rendered on use)
            this@AppContainer.service.handle(effect)
        }
        override fun appendLog(record: SessionRecord) {
            logQueue.trySend(record)
        }
        override fun persist(snapshot: Snapshot) = snapshotStore.offer(snapshot)
        override fun saveSettings(settings: Settings) = settingsStore.offer(settings)
        override fun notes(effect: Effect) = when (effect) {
            is Effect.ThoughtParked -> history.parked(effect.session, effect.thought, effect.at)
            is Effect.ThoughtsCleared -> history.resolved(effect.thoughts)
            else -> Unit
        }
        override fun soundscape(soundscape: Soundscape?) =
            this@AppContainer.soundscape.set(soundscape, engine.state.value.settings.soundscapeVolume)
        override fun bringToFront() = context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /** The single writer of the focus state. */
    val engine: FocusEngine by lazy {
        engineBuilt = true
        val config = if (timeScaled) EngineConfig.MinutesAsSeconds else EngineConfig.Default
        FocusEngine(clock, EffectRouter(sinks, presence), scope, dispatchers.default, config, log = { Logs.event("engine", it) })
    }

    val port: EnginePort by lazy { EngineAdapter(engine, clock) }

    private var started = false

    /**
     * Once per process: trust the clock if it's set, restore the backlight if a killed run left it dark, load the
     * settings and the snapshot, and hand them to the engine (which resolves deadlines that passed meanwhile).
     */
    fun start() {
        if (started) return
        started = true
        Logs.event("app", "start ${BuildConfig.VERSION_NAME}${if (timeScaled) " · minutes as seconds (debug)" else ""}")
        // Built here, on the caller's (main) thread, before the coroutines below need it: when a worker built it
        // first, the main thread sat 68 ms on the lazy's lock waiting for it during start-up (atrace, LOG M6)
        engine
        timeWatcher.offer("start")
        timeWatcher.register(context) { trusted ->
            if (trusted) engine.dispatch(Input.TimeTrusted)
            engine.dispatch(Input.DeadlineReached) // the scheduler looks again at the new time
            evening.recheck()
        }
        scope.launch {
            // A volume change in Settings reaches a playing soundscape at once
            engine.state.map { it.settings.soundscapeVolume }.distinctUntilChanged().collect { soundscape.volume(it) }
        }
        scope.launch {
            // Evening amber caps the brightness too (only our window; Blackout's sunrise rises to the cap)
            evening.on.collect { display.evening(it) }
        }
        scope.launch {
            for (record in logQueue) {
                withContext(dispatchers.io) {
                    try {
                        sessionLog.append(record)
                    } catch (e: Exception) {
                        Logs.warn("log", "append failed: ${e.message}", e)
                    }
                    history.onLogged(record)
                }
            }
        }
        scope.launch {
            displayPower.recoverAtStart()
            val settings = settingsStore.load()
            val snapshot = snapshotStore.load()
            engine.dispatch(Input.Restore(snapshot, settings))
            Logs.event("app", "restored ${snapshot.phase::class.simpleName} (saved at ${snapshot.savedAt?.elapsedMs})")
        }
    }
}

/** Debug builds: whether the engine runs "minutes as seconds". Kept in prefs so a kill → relaunch keeps the scale. */
object DebugTime {
    private const val PREFS = "debug"
    private const val KEY = "minutesAsSeconds"

    fun scaled(context: Context): Boolean =
        BuildConfig.HOOKS && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        if (!BuildConfig.HOOKS) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putBoolean(KEY, on) } // read right after, maybe by a new process
    }
}
