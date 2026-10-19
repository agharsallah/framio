package dev.framio.lab

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.LocalHost
import dev.framio.lab.labs.background.BackgroundService
import dev.framio.lab.labs.background.BackgroundState
import dev.framio.lab.labs.background.MinuteAlarm
import dev.framio.lab.core.ui.LabTheme

/**
 * The only activity. Extras (also via onNewIntent, the activity is singleTask):
 *  - `--es lab <id>`        open a lab, e.g. `audio`, `network`
 *  - `--ez selftest true`   run every lab's automated probes
 *  - `--es selftest_lab <id>` run one lab's probes
 *  - `--es background start --ez foreground true --ez wakeLock false --ei beep 10` / `--es background stop`
 *    drive the background lab's service (it isn't exported, so adb can't start it directly)
 *  - `--es alarm on|off`     the background lab's minute alarm
 *  - `--es autostart on|off` start the foreground service from BOOT_COMPLETED
 */
class LabActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        host.onPermissionsResult()
    }

    private lateinit var host: LabHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        host = LabHost(
            context = this,
            window = window,
            scope = lifecycleScope,
            labs = { Labs.all },
            setOrientation = { requestedOrientation = it },
            launchPermissions = { permissions.launch(it) },
        )

        // The frame has no Back key, but an IR remote or `adb shell input keyevent BACK` can send one
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    host.overlay != null -> host.overlay = null
                    host.selected != LabHost.OVERVIEW -> host.selected = LabHost.OVERVIEW
                    else -> finish()
                }
            }
        })

        setContent {
            CompositionLocalProvider(LocalHost provides host) {
                LabTheme { LabApp(host, onExit = ::finish) }
            }
        }
        handle(intent)
    }

    // The background lab logs whether its heartbeats happen with the app on screen or not
    override fun onStart() {
        super.onStart()
        BackgroundState.uiVisible = true
    }

    override fun onStop() {
        BackgroundState.uiVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_LAB)?.let { id -> if (Labs.all.any { it.id == id }) host.selected = id }
        if (intent.getBooleanExtra(EXTRA_SELFTEST, false)) host.selfTest.run()
        intent.getStringExtra(EXTRA_SELFTEST_LAB)?.let { host.selfTest.run(onlyLab = it) }
        when (intent.getStringExtra(EXTRA_BACKGROUND)) {
            "start" -> BackgroundService.start(
                this,
                foreground = intent.getBooleanExtra("foreground", true),
                wakeLock = intent.getBooleanExtra("wakeLock", false),
                beepEverySec = intent.getIntExtra("beep", 0),
            )
            "stop" -> BackgroundService.stop(this)
        }
        intent.removeExtra(EXTRA_BACKGROUND)
        intent.getStringExtra(EXTRA_ALARM)?.let { MinuteAlarm.set(this, it == "on") }
        intent.removeExtra(EXTRA_ALARM)
        intent.getStringExtra(EXTRA_AUTOSTART)?.let { BackgroundState.prefs(this).edit().putBoolean(BackgroundState.AUTOSTART, it == "on").apply() }
        intent.removeExtra(EXTRA_AUTOSTART)
        // consume, so a rotation or re-delivery doesn't run it again
        intent.removeExtra(EXTRA_SELFTEST)
        intent.removeExtra(EXTRA_SELFTEST_LAB)
    }

    @SuppressLint("RestrictedApi") // false positive: androidx.core marks its own override restricted, overriding Activity's is fine
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (host.onKey(event) && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
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

    companion object {
        const val EXTRA_LAB = "lab"
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_SELFTEST_LAB = "selftest_lab"
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_ALARM = "alarm"
        const val EXTRA_AUTOSTART = "autostart"
    }
}
