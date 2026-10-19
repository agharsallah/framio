package dev.framio.hub.di

import android.content.Context
import dev.framio.hub.core.engine.HubEngine
import dev.framio.hub.core.engine.Input
import dev.framio.hub.core.engine.Scheduler
import dev.framio.hub.data.FeedRepository
import dev.framio.hub.data.HubStateStore
import dev.framio.hub.data.ScribbleFiles
import dev.framio.hub.data.SecretsStore
import dev.framio.hub.platform.Accelerometer
import dev.framio.hub.platform.DisplayPower
import dev.framio.hub.platform.Logs
import dev.framio.hub.platform.RootShell
import dev.framio.hub.platform.SoundEngine
import dev.framio.hub.platform.TimeSource
import dev.framio.hub.service.CrashGuard
import dev.framio.hub.service.EffectRouter
import dev.framio.hub.service.Presence
import dev.framio.hub.service.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: the whole object graph, built lazily, so nothing runs at process start that
 * the first frame doesn't need. One instance per process, owned by HubApplication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(private val context: Context) {
    /** The app's own scope: lives as long as the process. Failures in one child don't cancel the others. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val safeMode: Boolean by lazy { CrashGuard.safeMode(context) }

    val presence = Presence()
    val time by lazy { TimeSource(context, scope) }
    val root by lazy { RootShell() }
    val display by lazy { DisplayPower(context, root) }
    val sound by lazy { SoundEngine(scope) }
    val accelerometer by lazy { Accelerometer(context) }
    val secrets by lazy { SecretsStore(context, scope) }
    val scribbles by lazy { ScribbleFiles(context) }
    private val store by lazy { HubStateStore(context, scope) }

    val effects by lazy { EffectRouter(context, scope, sound, secrets, scribbles, presence) { feeds.refreshNow() } }

    /** The single writer of the household's state; inputs are applied one at a time, in order. */
    val engine by lazy {
        HubEngine(time, store, effects, CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)), log = { Logs.event("engine", it) })
    }

    private val scheduler by lazy { Scheduler(engine, time, scope) }

    val http by lazy { FeedRepository.client(context) }

    val feeds by lazy {
        FeedRepository(context, scope, http, time, secrets, engine.state, time.trusted, onHttpDate = time::offerHttpDate).also { it.loadCache() }
    }

    private var started = false

    /** Starts the background machinery once per process: the clock, the scheduler, the service, the feeds. */
    fun start() {
        if (started) return
        started = true
        Logs.event("app", "start${if (safeMode) " in SAFE MODE (${CrashGuard.recentCount(context)} recent crashes)" else ""}")
        time.onChanged = { scheduler.poke() }
        time.start()
        scheduler.poke()
        ServiceController(context, scope, engine.state, presence.onScreen, presence.quit).start()
        // The clock just became trusted: notes and deadlines get their wall times
        scope.launch { time.trusted.distinctUntilChanged().drop(1).filter { it }.collect { engine.dispatch(Input.TimeTrusted) } }
        scope.launch { if (time.gate.isTrusted) engine.dispatch(Input.TimeTrusted) }
        if (!safeMode) feeds.start()
        scope.launch { display.recoverAtStart() }
    }
}
