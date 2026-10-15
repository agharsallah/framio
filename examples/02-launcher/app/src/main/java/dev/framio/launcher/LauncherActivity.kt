package dev.framio.launcher

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

    // Re-read on every resume: apps can be installed or removed over adb while we run
    private var helloInstalled by mutableStateOf(false)
    private var frameoInstalled by mutableStateOf(false)
    private var use24h by mutableStateOf(true)

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
                helloInstalled = helloInstalled,
                onPhotos = { Apps.openPhotos(this) },
                onHello = { Apps.openHello(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        frameoInstalled = Apps.isInstalled(this, Apps.FRAMEO)
        helloInstalled = Apps.isInstalled(this, Apps.HELLO)
        use24h = DateFormat.is24HourFormat(this)
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
