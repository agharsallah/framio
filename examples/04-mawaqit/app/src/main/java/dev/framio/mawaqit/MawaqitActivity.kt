package dev.framio.mawaqit

import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.framio.mawaqit.audio.CustomAdhans
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.service.FrameService
import dev.framio.mawaqit.system.Device
import dev.framio.mawaqit.ui.Actions
import dev.framio.mawaqit.ui.LocalAdhkar
import dev.framio.mawaqit.ui.LocalAdhkarLoop
import dev.framio.mawaqit.ui.MawaqitRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The prayer display. It only draws [dev.framio.mawaqit.engine.Engine.state]; the service runs the schedule. */
class MawaqitActivity : ComponentActivity() {

    private var sheet by mutableStateOf(false)
    private val closeSheet = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            settingsOpen = false
        }
    }
    private var settingsOpen: Boolean
        get() = sheet
        set(value) {
            sheet = value
            closeSheet.isEnabled = value // Back closes the sheet; otherwise it leaves the display as usual
        }

    /** The system picker (DocumentsUI: internal storage, SD card, USB stick). */
    private val pickAdhan = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importAdhan) }

    /** Same import as an upload from the panel; picked on the frame, it becomes the voice of every prayer. */
    private fun importAdhan(uri: Uri) {
        val frame = frame
        val app = applicationContext
        val s = frame.engine.state.value.strings
        Toast.makeText(app, s["adhan_importing"], Toast.LENGTH_SHORT).show()
        frame.scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val a = app.contentResolver.openInputStream(uri)?.use { frame.adhans.import(it, name) }
                        ?: throw CustomAdhans.Rejected("bad_request", "no stream")
                    frame.store.patch(JSONObject().put("adhan", JSONObject().put("voice", "custom:${a.id}")))
                    s["adhan_added"].replace("{n}", a.name)
                } catch (e: CustomAdhans.Rejected) {
                    s[when (e.code) { "too_large" -> "adhan_too_big"; "too_many" -> "adhan_too_many"; else -> "adhan_bad" }]
                } catch (e: Exception) { // SecurityException, IOException from the provider
                    s["adhan_bad"]
                }
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, closeSheet)
        FrameService.start(this)
        val frame = frame
        frame.start() // also from here: the first frame draws before the service's onCreate runs

        val actions = Actions(
            patch = { frame.store.patch(it) },
            testAdhan = { frame.engine.testAdhan(PrayerKey.DHUHR) },
            stopAudio = { frame.player.stop() },
            adhanVolume = { v, m -> frame.engine.adhanVolume(v, m) },
            skipAdhan = { frame.engine.skipAdhan() },
            openPhotos = if (Device.frameoInstalled(this)) ({ settingsOpen = false; frame.engine.openPhotos() }) else null,
            refresh = { frame.repo.refresh() },
            remoteLink = { frame.rpc.remoteInfo().optString("link") },
            ntfyConnected = { frame.ntfyConnected },
            chooseAdhan = {
                try {
                    pickAdhan.launch(arrayOf("audio/*", "application/ogg"))
                } catch (_: ActivityNotFoundException) {
                    // no DocumentsUI: the panel's upload still works
                }
            },
        )
        setContent {
            val st by frame.engine.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAdhkar provides frame.engine.adhkar, LocalAdhkarLoop provides frame.engine.loop) {
                MawaqitRoot(st, settingsOpen, { settingsOpen = it }, actions)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    frame.engine.state.map { it.screen.brightness }.distinctUntilChanged().collect { b ->
                        window.attributes = window.attributes.apply { screenBrightness = b.coerceIn(0.01f, 1f) }
                    }
                }
                launch {
                    frame.store.settings.map { it.display.orientation }.distinctUntilChanged().collect { o ->
                        requestedOrientation = when (o) {
                            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR // follows the stand
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        frame.engine.setDisplayVisible(true)
    }

    override fun onPause() {
        frame.engine.setDisplayVisible(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // The frame has no bars anyway; this keeps it that way if the firmware ever shows them
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /** Any touch counts as presence (wakes a black night screen); a tap also closes a message or a preview. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val st = frame.engine.state.value
            frame.engine.userActivity()
            if (st.screen.black || st.overlay != null) {
                frame.engine.dismissOverlay()
                return true // the first tap only wakes / closes
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** IR remote / keyboard: OK or Menu toggles the settings. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        frame.engine.userActivity()
        if (!settingsOpen && frame.engine.loop.onKey(keyCode)) return true // pages, and ◀ ▶ on the adhkar display
        if (!settingsOpen && frame.engine.adhkar.onKey(keyCode)) return true // the adhkar reader's keys first
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> settingsOpen = !settingsOpen
            // the adhan's controls on the remote: stop skips it, volume and mute act on it while it plays
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT -> frame.engine.skipAdhan()
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val a = frame.player.playing.value ?: return super.onKeyDown(keyCode, event)
                frame.engine.adhanVolume(a.volume + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 10 else -10)
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val a = frame.player.playing.value ?: return super.onKeyDown(keyCode, event)
                frame.engine.adhanVolume(null, !a.muted)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
