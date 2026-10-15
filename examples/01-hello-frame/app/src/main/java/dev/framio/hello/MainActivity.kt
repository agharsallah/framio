package dev.framio.hello

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Lets you inspect the page from desktop Chrome at chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Exposed to the page as `window.Frame`
            addJavascriptInterface(FrameBridge(), "Frame")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** Native functions the web page can call. Runs on a background thread, so UI work goes through runOnUiThread. */
    inner class FrameBridge {

        @JavascriptInterface
        fun deviceInfo(): String = JSONObject()
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()

        @JavascriptInterface
        fun toast(message: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        /** 0.0–1.0; only applies while this app is on screen. */
        @JavascriptInterface
        fun setBrightness(level: Float) = runOnUiThread {
            window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
        }

        /** Escape hatch: closes the app and returns to the previous screen (the launcher). */
        @JavascriptInterface
        fun exit() = runOnUiThread { finish() }
    }
}
