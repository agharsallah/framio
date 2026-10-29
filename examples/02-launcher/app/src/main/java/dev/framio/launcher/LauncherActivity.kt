package dev.framio.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The frame's home screen. Android starts it at boot and whenever "home" is requested. */
class LauncherActivity : ComponentActivity() {

    private enum class Panel { NONE, CONTROLS, APPS }

    private lateinit var prefs: Prefs
    private lateinit var controller: Controller
    private lateinit var wifi: Wifi
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // Re-read on every resume, and while visible on every install/removal (apps arrive over adb)
    private var frameoInstalled by mutableStateOf(false)
    private var apps by mutableStateOf(emptyList<AppTile>())
    private var use24h by mutableStateOf(true)

    private var panel by mutableStateOf(Panel.NONE)
    private var tab by mutableStateOf(Tab.QUICK)
    private var sheet by mutableStateOf<AppEntry?>(null)
    private var countdown by mutableStateOf<Countdown?>(null)
    private var countdownJob: Job? = null
    private var lastTouch = SystemClock.elapsedRealtime()

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshApps()
            if (panel != Panel.NONE) controller.refreshAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        controller = Controller(this, lifecycleScope, prefs)
        wifi = Wifi(this, lifecycleScope)

        // This is home: Back only closes what is open over it
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    controller.sleeping -> controller.wake()
                    sheet != null -> sheet = null
                    panel != Panel.NONE -> panel = Panel.NONE
                }
            }
        })

        setContent { Content() }

        lifecycleScope.launch {
            // Grants itself the overlay, brightness and Wi-Fi scan permissions (only what's missing), then the dot
            Device.provision(this@LauncherActivity)
            Device.recoverBacklight(this@LauncherActivity)
            startDot()
        }
        prefsListener = prefs.listen {
            controller.reload()
            startDot()
            refreshApps()
        }
        handleIntent(intent)
        maybeStartup()
        watchIdle()
    }

    override fun onDestroy() {
        prefsListener?.let(prefs::unlisten)
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        val fade = tween<Float>(160)
        Box(Modifier.fillMaxSize()) {
            HomeScreen(
                lifecycle = lifecycle,
                use24h = use24h,
                frameoInstalled = frameoInstalled,
                apps = apps,
                status = HomeStatus(wifi.level, wifi.status, controller.media),
                countdown = countdown,
                onPhotos = { Apps.openPhotos(this@LauncherActivity) },
                onOpen = { Apps.open(this@LauncherActivity, it) },
                onAppMenu = { sheet = AppEntry(it.component, it.title, isSystem(it.pkg), it.subtitle, it.accent) },
                onControls = {
                    tab = Tab.QUICK
                    panel = Panel.CONTROLS
                },
                onAllApps = { panel = Panel.APPS },
                onCountdownNow = { finishCountdown(open = true) },
                onCountdownCancel = { finishCountdown(open = false) },
            )
            AnimatedVisibility(panel == Panel.CONTROLS, enter = fadeIn(fade), exit = fadeOut(fade)) {
                ControlCenter(controller, wifi, tab, { tab = it }, ::previewBrightness, onClose = { panel = Panel.NONE })
            }
            AnimatedVisibility(panel == Panel.APPS, enter = fadeIn(fade), exit = fadeOut(fade)) {
                AllApps(
                    controller,
                    onOpen = { Apps.openComponent(this@LauncherActivity, it.component) },
                    onMenu = { sheet = it },
                    onClose = { panel = Panel.NONE },
                )
            }
            sheet?.let { e ->
                AppSheet(controller, e, onOpen = {
                    sheet = null
                    Apps.openComponent(this@LauncherActivity, e.component)
                }, onDismiss = { sheet = null })
            }
            if (controller.sleeping) {
                // The backlight is off; any touch brings it back
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { controller.wake() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** HOME while Home is in front closes whatever is open, like on a phone; the dot's Controls opens the panel. */
    private fun handleIntent(intent: Intent?) {
        sheet = null
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_CONTROLS) {
            tab = Tab.QUICK
            panel = Panel.CONTROLS
            intent.removeExtra(EXTRA_OPEN)
        } else {
            panel = Panel.NONE
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packagesChanged, filter)
        wifi.start()
    }

    override fun onStop() {
        unregisterReceiver(packagesChanged)
        wifi.stop()
        // Never leave the frame dark behind another app
        if (controller.sleeping) controller.wake()
        previewBrightness(null)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        controller.refresh()
        use24h = DateFormat.is24HourFormat(this)
        lastTouch = SystemClock.elapsedRealtime()
        HotCornerService.setHomeVisible(true)
    }

    override fun onPause() {
        HotCornerService.setHomeVisible(false)
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshApps() {
        val c = controller.config
        frameoInstalled = Apps.isInstalled(this, Apps.FRAMEO)
        apps = Apps.discover(this, c.hidden, c.pinned)
    }

    private fun startDot() {
        val intent = Intent(this, HotCornerService::class.java)
        if (controller.config.dotEnabled) startService(intent) else stopService(intent)
    }

    // --- Startup app

    /**
     * Once per boot (the kernel's boot id changes), Home counts down and opens the startup app. Started from here
     * because the firmware lets nothing else start an app at boot (`BOOT_COMPLETED: process is bad`), but always
     * starts HOME. The count only runs while Home is in front.
     */
    private fun maybeStartup() {
        val boot = Device.bootId() ?: return
        if (prefs.lastBootId == boot) return
        prefs.lastBootId = boot
        val target = controller.config.startup ?: return
        val label = Apps.targetLabel(this, target) ?: return
        val seconds = controller.config.startupDelaySec
        if (seconds <= 0) {
            Apps.openTarget(this, target)
            return
        }
        countdown = Countdown(label, seconds)
        countdownJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val c = countdown ?: break
                    if (c.secondsLeft <= 0) {
                        finishCountdown(open = true)
                        break
                    }
                    delay(1_000)
                    countdown = countdown?.let { it.copy(secondsLeft = it.secondsLeft - 1) }
                }
            }
        }
    }

    private fun finishCountdown(open: Boolean) {
        countdown = null
        countdownJob?.cancel()
        countdownJob = null
        if (open) controller.config.startup?.let { Apps.openTarget(this, it) }
    }

    /** Home left untouched for [Config.idleReturnMin] minutes goes back to the startup app (a frame belongs on its photos). */
    private fun watchIdle() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val cfg = controller.config
                    val limit = cfg.idleReturnMin * 60_000L
                    if (limit <= 0 || cfg.startup == null) {
                        delay(60_000)
                        continue
                    }
                    val left = limit - (SystemClock.elapsedRealtime() - lastTouch)
                    if (left > 0) {
                        delay(left.coerceAtMost(60_000))
                        continue
                    }
                    if (countdown == null && !controller.sleeping && sheet == null) {
                        panel = Panel.NONE
                        Apps.openTarget(this@LauncherActivity, cfg.startup)
                    }
                    lastTouch = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    // --- Brightness preview while dragging: this window only, instant; the system setting is written on release

    private fun previewBrightness(level: Float?) {
        window.attributes = window.attributes.apply {
            screenBrightness = level?.let { (30 + 225 * it) / 255f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun isSystem(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** The frame has no bars anyway; this keeps it that way if the firmware ever shows them. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_OPEN = "dev.framio.launcher.OPEN"
        const val OPEN_CONTROLS = "controls"
    }
}
