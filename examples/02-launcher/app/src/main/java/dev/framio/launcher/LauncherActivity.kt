package dev.framio.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The frame's home screen. Android starts it at boot and whenever "home" is requested. */
class LauncherActivity : ComponentActivity() {

    // Re-read on every resume, and while visible on every install/removal (apps arrive over adb)
    private var frameoInstalled by mutableStateOf(false)
    private var apps by mutableStateOf(emptyList<AppTile>())
    private var use24h by mutableStateOf(true)

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // This is home: there is nothing to go back to
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            HomeScreen(
                lifecycle = lifecycle,
                use24h = use24h,
                frameoInstalled = frameoInstalled,
                apps = apps,
                onPhotos = { Apps.openPhotos(this) },
                onOpen = { Apps.open(this, it) },
            )
        }

        // Started from a foreground activity, which the firmware allows (a boot receiver is not)
        startService(Intent(this, HotCornerService::class.java))
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
    }

    override fun onStop() {
        unregisterReceiver(packagesChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        use24h = DateFormat.is24HourFormat(this)
    }

    private fun refreshApps() {
        frameoInstalled = Apps.isInstalled(this, Apps.FRAMEO)
        apps = Apps.discover(this)
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
}
