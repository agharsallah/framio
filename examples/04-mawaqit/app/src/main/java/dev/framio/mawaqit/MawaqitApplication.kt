package dev.framio.mawaqit

import android.app.Application
import android.content.Context
import dev.framio.mawaqit.audio.AdhanPlayer
import dev.framio.mawaqit.audio.CustomAdhans
import dev.framio.mawaqit.data.Repository
import dev.framio.mawaqit.data.SecondaryMosques
import dev.framio.mawaqit.data.SettingsStore
import dev.framio.mawaqit.engine.Engine
import dev.framio.mawaqit.remote.HttpServer
import dev.framio.mawaqit.remote.Mdns
import dev.framio.mawaqit.remote.NtfyBridge
import dev.framio.mawaqit.remote.Rpc
import dev.framio.mawaqit.system.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

object BuildInfo {
    const val VERSION = "0.1"
}

/** The app's object graph: one of each, alive as long as the process (kept alive by the foreground service). */
class Frame(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SettingsStore(context)
    val repo = Repository(context, store.settings, scope)
    val player = AdhanPlayer(context, scope)
    val secondary = SecondaryMosques(context, store.settings, scope)
    val engine = Engine(context, store, repo, player, scope, secondary)
    val adhans = CustomAdhans(File(context.filesDir, "audio"), store)
    val rpc = Rpc(store, repo, engine, scope, adhans)
    private val server = HttpServer(context, rpc) { store.current.remote.pin }
    private val ntfy = NtfyBridge(rpc, scope)
    private val mdns = Mdns(context)
    private var started = false

    val ntfyConnected get() = ntfy.connected

    fun start() {
        if (started) return
        started = true
        repo.start()
        secondary.start()
        engine.start()
        scope.launch { rpc.hasRoot = Device.hasRoot() }
        scope.launch {
            store.settings.map { it.remote.port }.distinctUntilChanged().collect { port ->
                server.start(port)
                mdns.advertise(port)
            }
        }
        scope.launch { store.settings.map { it.remote }.distinctUntilChanged().collect(ntfy::apply) }
    }
}

class MawaqitApplication : Application() {
    val frame by lazy { Frame(this) }
}

val Context.frame: Frame get() = (applicationContext as MawaqitApplication).frame
