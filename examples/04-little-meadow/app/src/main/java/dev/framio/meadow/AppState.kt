package dev.framio.meadow

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import dev.framio.meadow.audio.Music
import dev.framio.meadow.audio.Sfx
import dev.framio.meadow.audio.Voice
import dev.framio.meadow.core.Commands
import dev.framio.meadow.core.Family
import dev.framio.meadow.core.Progress
import dev.framio.meadow.core.Session
import dev.framio.meadow.core.SettingsStore
import dev.framio.meadow.hw.Device
import dev.framio.meadow.remote.RemoteServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything that lives as long as the process: stores, audio, the command bus and the dashboard
 * server. Created once by [MeadowApplication]; Compose reaches it through [LocalApp].
 */
class AppState(val context: Context) {

    val settings = SettingsStore(File(context.filesDir, "settings.json"))
    val progress = Progress(File(context.filesDir, "progress.json"))
    val session = Session(settings, progress, context.getSharedPreferences("session", Context.MODE_PRIVATE))
    val family = Family(File(context.filesDir, "family"))
    val voice = Voice(context, family.dir)
    val sfx = Sfx(context)
    val music = Music(context)
    val commands = Commands()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val server = RemoteServer(this)

    /** What's on screen, for the dashboard ("Playing: Count with me"). Set by the UI. */
    @Volatile var screen: String = "home"
    @Volatile var playId: String? = null

    /** Turns the backlight off until the next touch, knock or key (the night light calls it when it has faded out). */
    @Volatile var sleepScreen: () -> Unit = {}
    /** Backlight back on (a command from the dashboard, a knock). */
    @Volatile var wakeScreen: () -> Unit = {}

    init {
        voice.childName = { settings.value.childName }
        voice.messageFrom = { path -> family.items.value.firstOrNull { it.file.absolutePath == path }?.from }
        scope.launch {
            settings.flow.map { Triple(it.volume, it.lang, it.music) }.distinctUntilChanged().collect { (vol, lang, music) ->
                Device.setVolume(context, vol)
                voice.lang = lang
                this@AppState.music.setEnabled(music)
            }
        }
        scope.launch { voice.speaking.collect { music.duck(it) } }
        scope.launch {
            var age = settings.value.age
            settings.flow.collect {
                if (it.age != age) {
                    age = it.age
                    progress.resetLevels()
                    progress.log("Age set to ${it.age}: levels start again")
                }
            }
        }
        scope.launch {
            while (true) { // the frame has no battery: save often, writes are atomic
                delay(5000)
                progress.save()
            }
        }
        server.start()
    }
}

val LocalApp = staticCompositionLocalOf<AppState> { error("no AppState") }
