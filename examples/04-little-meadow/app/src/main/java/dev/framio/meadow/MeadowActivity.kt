package dev.framio.meadow

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.framio.meadow.core.Command
import dev.framio.meadow.core.Gate
import dev.framio.meadow.hw.Device
import dev.framio.meadow.hw.KnockListener
import dev.framio.meadow.ui.MeadowTheme
import dev.framio.meadow.ui.Sprites
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The only activity. Extras (also via onNewIntent; the activity is singleTask), e.g.
 *   adb shell am start -n dev.framio.meadow/.MeadowActivity --es play count
 *
 *  - `--es play <id>` / `--es world <id>`   open an activity / a world
 *  - `--es cmd home|lock|unlock|wiggle|nightlight|star|wake`
 *  - `--ei bonus <minutes>`                  extra play time today
 *  - `--es set '<json>'`                     change settings, e.g. '{"age":4,"lang":"de"}'
 *  - `--es say <voice id>`                   say one narration clip
 */
class MeadowActivity : ComponentActivity() {

    private val nav = Nav()
    private var resumed = false
    private lateinit var knock: KnockListener
    private var backlightOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Sprites.assets = assets
        val app = app
        app.sleepScreen = { runOnUiThread(::sleepScreen) }
        app.wakeScreen = { runOnUiThread(::wake) }

        // An IR remote or `adb shell input keyevent BACK` can send Back; the frame has no key for it
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = nav.back()
        })

        knock = KnockListener(
            this,
            onKnock = { onKnock(double = false) },
            onDoubleKnock = { onKnock(double = true) },
        )

        // brightness: the grown-up's level, dimmer and warmer at bedtime
        lifecycleScope.launch {
            app.settings.flow.combine(app.session.gate) { s, g -> s.brightness to g }.collect { (b, g) ->
                val level = b / 100f * if (g == Gate.Night) 0.55f else 1f
                Device.setWindowBrightness(window, level)
            }
        }

        setContent {
            CompositionLocalProvider(LocalApp provides app) {
                MeadowTheme { MeadowRoot(nav, resumed = { resumed }, onExit = ::finish) }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        app.music.resume()
        if (app.settings.value.knockToWake) knock.start()
    }

    override fun onPause() {
        resumed = false
        app.music.pause()
        app.voice.stop()
        app.progress.save()
        knock.stop()
        super.onPause()
    }

    /** Knock on the case: wakes a dark screen; on the home screen a double knock is a little joke. */
    private fun onKnock(double: Boolean) {
        if (backlightOff) {
            wake()
            return
        }
        if (double && nav.current == Route.Home && app.session.gate.value == Gate.Open) app.voice.say("knock_knock")
    }

    fun wake() {
        if (backlightOff) {
            backlightOff = false
            lifecycleScope.launch { Device.backlightOff(false) }
        }
    }

    /** Called by the night light when it has faded out. */
    fun sleepScreen() {
        if (!app.settings.value.backlightOffAtNight) return
        backlightOff = true
        lifecycleScope.launch { Device.backlightOff(true) }
    }

    private fun handle(intent: Intent) {
        val app = app
        intent.getStringExtra("set")?.let { json ->
            try {
                app.settings.update { it.merge(JSONObject(json)) }
            } catch (_: Exception) {
            }
        }
        intent.getStringExtra("world")?.let { app.commands.send(Command.OpenWorld(it)) }
        intent.getStringExtra("play")?.let { app.commands.send(Command.OpenPlay(it)) }
        intent.getStringExtra("say")?.let { app.commands.send(Command.Say(it)) }
        if (intent.hasExtra("bonus")) app.commands.send(Command.Bonus(intent.getIntExtra("bonus", 15)))
        when (intent.getStringExtra("cmd")) {
            "home" -> app.commands.send(Command.Home)
            "lock" -> app.commands.send(Command.Lock(true))
            "unlock" -> app.commands.send(Command.Lock(false))
            "wiggle" -> app.commands.send(Command.Wiggle)
            "nightlight" -> app.commands.send(Command.NightLight)
            "star" -> app.commands.send(Command.Star)
            "wake" -> wake()
        }
        // consume, so a rotation or re-delivery doesn't repeat it
        for (k in listOf("set", "world", "play", "say", "bonus", "cmd")) intent.removeExtra(k)
    }

    @SuppressLint("RestrictedApi") // overriding Activity's dispatchKeyEvent is fine; androidx marks its own override restricted
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // any key (IR remote, the case's buttons) wakes a dark screen
        if (backlightOff && event.action == KeyEvent.ACTION_DOWN) {
            wake()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (backlightOff) {
            wake() // first touch on a dark screen only turns the light back on
            return true
        }
        return super.dispatchTouchEvent(ev)
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
}
