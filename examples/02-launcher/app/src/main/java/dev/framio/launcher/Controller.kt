package dev.framio.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and actions behind the Controls panel and the All apps grid. Lives as long as the launcher activity; reads
 * the system again on [refresh] (each time Home or the panel shows), since other apps change volumes and brightness.
 */
class Controller(context: Context, private val scope: CoroutineScope, private val prefs: Prefs) {

    private val app = context.applicationContext

    var config by mutableStateOf(prefs.load())
        private set

    fun update(change: Config.() -> Config) {
        config = config.change()
        prefs.save(config)
    }

    /** Called when the service changed the prefs (it moves the dot). */
    fun reload() {
        config = prefs.load()
    }

    // --- Sound and light

    var media by mutableFloatStateOf(0f)
        private set
    var alarm by mutableFloatStateOf(0f)
        private set
    var brightness by mutableFloatStateOf(0.5f)
        private set

    fun refresh() {
        media = Device.volume(app, Device.Stream.MEDIA)
        alarm = Device.volume(app, Device.Stream.ALARM)
        brightness = Device.brightnessToLevel(Device.brightness(app))
        navEnabled = Device.navEnabled(app)
    }

    fun setVolume(s: Device.Stream, level: Float) {
        val set = Device.setVolume(app, s, level)
        if (s == Device.Stream.MEDIA) media = set else alarm = set
    }

    /** While dragging: only the number (the activity previews the level on its own window). */
    fun previewBrightness(level: Float) {
        brightness = level
    }

    fun commitBrightness(level: Float) {
        brightness = level
        scope.launch { Device.setBrightness(app, level) }
    }

    // --- Sleep screen

    var sleeping by mutableStateOf(false)
        private set

    fun sleep() = scope.launch {
        if (Device.backlight(app, on = false)) sleeping = true else say("Turning the screen off needs root")
    }

    fun wake() = scope.launch {
        Device.backlight(app, on = true)
        sleeping = false
    }

    // --- Running apps

    var running by mutableStateOf<List<RunningApp>?>(null)
        private set
    var memory by mutableStateOf(Device.memory(app))
        private set
    var root by mutableStateOf<Boolean?>(Root.available)
        private set

    fun refreshRunning() = scope.launch {
        root = Root.check()
        memory = Device.memory(app)
        running = Device.runningApps(app)
    }

    fun close(pkg: String) = scope.launch {
        val before = Device.memory(app).availMb
        if (!Device.close(app, pkg)) say("Asked Android to close ${Apps.label(app, pkg)} (no root: it may not close)")
        delay(400) // let the kernel take the memory back
        refreshRunning().join()
        freed(before)
    }

    /** Closes everything that isn't in [Config.keepRunning]. */
    fun closeAll() = scope.launch {
        val before = Device.memory(app).availMb
        val targets = running.orEmpty().filter { it.pkg !in config.keepRunning }
        targets.forEach { Device.close(app, it.pkg) }
        delay(600)
        refreshRunning().join()
        if (targets.isEmpty()) say("Nothing to close") else freed(before, "Closed ${targets.size} apps")
    }

    private fun freed(beforeMb: Long, prefix: String? = null) {
        val gained = memory.availMb - beforeMb
        say(listOfNotNull(prefix, if (gained > 0) "$gained MB freed" else null).joinToString(" · ").ifEmpty { "Closed" })
    }

    // --- Apps

    var allApps by mutableStateOf(emptyList<AppEntry>())
        private set

    fun refreshAll() = scope.launch {
        allApps = withContext(Dispatchers.IO) { Apps.all(app) }
    }

    fun togglePinned(e: AppEntry) = update {
        if (e.framio) {
            copy(hidden = if (e.pkg in hidden) hidden - e.pkg else hidden + e.pkg)
        } else {
            copy(pinned = if (e.pkg in pinned) pinned - e.pkg else pinned + e.pkg)
        }
    }

    fun onShelf(e: AppEntry): Boolean = if (e.framio) e.pkg !in config.hidden else e.pkg in config.pinned

    fun toggleKeep(pkg: String) = update { copy(keepRunning = if (pkg in keepRunning) keepRunning - pkg else keepRunning + pkg) }

    // --- Home button's Back

    var navEnabled by mutableStateOf(false)
        private set

    fun setNav(on: Boolean) = scope.launch {
        if (Device.setNavEnabled(app, on)) {
            delay(300)
            navEnabled = Device.navEnabled(app)
        } else {
            say("Needs root")
        }
    }

    // --- Power

    fun reboot() = scope.launch { if (!Device.reboot()) say("Reboot needs root") }

    fun powerOff() = scope.launch { if (!Device.powerOff()) say("Power off needs root") }

    // --- One line of feedback at the bottom of the panel

    var message by mutableStateOf<String?>(null)
        private set
    private var messageJob: Job? = null

    fun say(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(3_500)
            message = null
        }
    }
}
