package dev.framio.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The frame's home button: a dot above every app (including Frameo). The frame has no navigation bar.
 *
 * - **Tap**: a small menu next to it, with Home, Back, Close app (the one in front) and Controls.
 * - **Hold**: straight Home, as before.
 * - **Drag**: put it anywhere; it settles on the nearest side edge and stays there.
 *
 * It fades to [Config.dotRestAlpha] a few seconds after each use, and hides while Home itself is in front. Plain
 * Views, not Compose: two tiny windows in the HOME process don't need a recomposer, and the overlay must stay cheap
 * above a running slideshow.
 */
class HotCornerService : Service() {

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var config = Config()
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var dot: DotView? = null
    private var dotParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private var fade: ValueAnimator? = null
    private var snap: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (dot != null) return START_STICKY
        config = prefs.load()
        if (!config.dotEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay not allowed yet (the launcher grants it with root at start)")
            stopSelf()
            return START_NOT_STICKY
        }
        showDot()
        prefsListener = prefs.listen(::onPrefsChanged)
        return START_STICKY
    }

    override fun onDestroy() {
        prefsListener?.let(prefs::unlisten)
        hideMenu()
        dot?.let { wm.removeView(it) }
        dot = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun onPrefsChanged() {
        val old = config
        config = prefs.load()
        when {
            !config.dotEnabled -> stopSelf()
            old.dotSize != config.dotSize || old.dotPlace != config.dotPlace -> place(animate = false)
            old.dotRestAlpha != config.dotRestAlpha -> rest()
        }
    }

    // --- The dot

    @SuppressLint("ClickableViewAccessibility")
    private fun showDot() {
        val view = DotView(this)
        val params = WindowManager.LayoutParams(
            px(config.dotSize), px(config.dotSize), overlayType(),
            // keys and touches outside the dot go to the app below
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        view.setOnTouchListener(DotTouch())
        view.setOnClickListener { toggleMenu() }
        wm.addView(view, params)
        dot = view
        dotParams = params
        place(animate = false)
        visibleOnHome(homeVisible)
        rest()
    }

    /** x/y from the saved edge and height, kept off the screen's edges by [MARGIN]. */
    private fun place(animate: Boolean) {
        val view = dot ?: return
        val p = dotParams ?: return
        val size = px(config.dotSize)
        p.width = size
        p.height = size
        val (w, h) = screen()
        val m = px(MARGIN)
        val targetX = if (config.dotPlace.right) w - size - m else m
        val targetY = (m + (h - size - 2 * m) * config.dotPlace.y.coerceIn(0f, 1f)).roundToInt()
        snap?.cancel()
        if (!animate) {
            p.x = targetX
            p.y = targetY
            wm.updateViewLayout(view, p)
            return
        }
        val fromX = p.x
        val fromY = p.y
        snap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                p.x = (fromX + (targetX - fromX) * t).roundToInt()
                p.y = (fromY + (targetY - fromY) * t).roundToInt()
                if (dot != null) wm.updateViewLayout(view, p)
            }
            start()
        }
    }

    /** Full opacity now, back to the resting opacity after [REST_MS]. */
    private fun wakeDot() {
        fade?.cancel()
        dot?.alpha = 1f
        main.removeCallbacks(restRunnable)
        main.postDelayed(restRunnable, REST_MS)
    }

    private val restRunnable = Runnable { rest() }

    private fun rest() {
        val view = dot ?: return
        if (menu != null) return // stays lit while its menu is open
        fade?.cancel()
        fade = ValueAnimator.ofFloat(view.alpha, config.dotRestAlpha).apply {
            duration = 600
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun visibleOnHome(home: Boolean) {
        val v = if (home) View.GONE else View.VISIBLE
        dot?.visibility = v
        if (home) hideMenu()
    }

    private inner class DotTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@HotCornerService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            dot?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            goHome()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = dotParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressed = false
                    wakeDot()
                    (v as DotView).held = true
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        hideMenu()
                    }
                    if (dragging) {
                        p.x = startX + dx.roundToInt()
                        p.y = startY + dy.roundToInt()
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    (v as DotView).held = false
                    when {
                        dragging -> settle(p)
                        longPressed -> Unit
                        e.actionMasked == MotionEvent.ACTION_UP -> v.performClick()
                    }
                    wakeDot()
                }
            }
            return true
        }
    }

    /** After a drag: the nearest side edge, at the height it was dropped; saved for next time. */
    private fun settle(p: WindowManager.LayoutParams) {
        val (w, h) = screen()
        val size = px(config.dotSize)
        val m = px(MARGIN)
        val right = p.x + size / 2 > w / 2
        val y = ((p.y - m).toFloat() / (h - size - 2 * m).coerceAtLeast(1)).coerceIn(0f, 1f)
        config = config.copy(dotPlace = DotPlace(right, y))
        place(animate = true)
        prefs.save(prefs.load().copy(dotPlace = config.dotPlace))
    }

    // --- The menu

    private fun toggleMenu() = if (menu != null) hideMenu() else showMenu()

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val p = dotParams ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(10), px(10), px(10), px(10))
            background = GradientDrawable().apply {
                cornerRadius = px(28).toFloat()
                setColor(0xF016181D.toInt())
                setStroke(px(1), 0xFF2C3038.toInt())
            }
            addView(menuButton(MenuGlyph.HOME, "Home") { goHome() })
            addView(menuButton(MenuGlyph.BACK, "Back") { back() })
            addView(menuButton(MenuGlyph.CLOSE, "Close app") { closeFront() })
            addView(menuButton(MenuGlyph.CONTROLS, "Controls") { openControls() })
            // A touch anywhere else closes the menu (and still reaches the app below)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu()
                false
            }
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mw = panel.measuredWidth
        val mh = panel.measuredHeight
        val (w, h) = screen()
        val size = px(config.dotSize)
        val gap = px(12)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (config.dotPlace.right) p.x - gap - mw else p.x + size + gap).coerceIn(0, (w - mw).coerceAtLeast(0))
            y = (p.y + size / 2 - mh / 2).coerceIn(0, (h - mh).coerceAtLeast(0))
            windowAnimations = android.R.style.Animation_Toast
        }
        wm.addView(panel, params)
        menu = panel
        main.removeCallbacks(menuTimeout)
        main.postDelayed(menuTimeout, MENU_MS)
    }

    private val menuTimeout = Runnable { hideMenu() }

    private fun hideMenu() {
        main.removeCallbacks(menuTimeout)
        menu?.let { wm.removeView(it) }
        if (menu != null) {
            menu = null
            wakeDot()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // the listener only tints; it returns false, so the click still runs
    private fun menuButton(glyph: MenuGlyph, label: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(14), px(10), px(14), px(8))
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat() }
            setOnTouchListener { v, e ->
                (v.background as GradientDrawable).setColor(if (e.actionMasked == MotionEvent.ACTION_DOWN) 0x22FFFFFF else 0)
                false
            }
            setOnClickListener {
                hideMenu()
                action()
            }
            addView(GlyphView(this@HotCornerService, glyph), LinearLayout.LayoutParams(px(56), px(56)))
            // Own width, one line: a vertical LinearLayout would otherwise give it the glyph's 56 px and wrap it
            addView(TextView(this@HotCornerService).apply {
                text = label
                maxLines = 1
                setTextColor(0xFFF1ECE4.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17).toFloat())
                setPadding(0, px(6), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    // --- Actions

    private fun goHome() {
        hideMenu()
        // Same intent the system uses for "home", aimed at us: brings the home task forward
        startActivity(homeIntent(this))
    }

    private fun openControls() {
        startActivity(homeIntent(this).putExtra(LauncherActivity.EXTRA_OPEN, LauncherActivity.OPEN_CONTROLS))
    }

    /** The accessibility service's Back if it's on; else a root key event (it starts a Java VM: ~1 s here). */
    private fun back() {
        if (NavService.back()) return
        scope.launch {
            if (!Root.run("input keyevent 4").ok) toast("Back needs root or Controls → Home button → Instant Back")
        }
    }

    /** Home first (the app leaves the screen at once), then the app in front is closed. */
    private fun closeFront() = scope.launch {
        val pkg = Device.foregroundPackage()
        if (pkg == null || pkg == packageName) {
            toast("No app to close")
            return@launch
        }
        goHome()
        if (Device.close(this@HotCornerService, pkg)) toast("Closed ${Apps.label(this@HotCornerService, pkg)}")
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    // --- Helpers

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    private fun screen(): Pair<Int, Int> = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    @Suppress("DEPRECATION")
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /** Dark fill + light ring: visible on Frameo's white screens and on photos alike. */
    private class DotView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xDDFFFFFF.toInt()
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt() }

        var held = false
            set(v) {
                field = v
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            ring.strokeWidth = (width * 0.06f).coerceAtLeast(2f)
            canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
            canvas.drawCircle(r, r, if (held) r * 0.34f else r * 0.2f, core)
        }
    }

    private enum class MenuGlyph { HOME, BACK, CLOSE, CONTROLS }

    private class GlyphView(context: Context, private val glyph: MenuGlyph) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0xFFF1ECE4.toInt()
        }
        private val path = Path()
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.strokeWidth = w * 0.07f
            path.reset()
            when (glyph) {
                MenuGlyph.HOME -> {
                    path.moveTo(w * 0.18f, h * 0.5f); path.lineTo(w * 0.5f, h * 0.2f); path.lineTo(w * 0.82f, h * 0.5f)
                    path.moveTo(w * 0.28f, h * 0.42f); path.lineTo(w * 0.28f, h * 0.8f); path.lineTo(w * 0.72f, h * 0.8f); path.lineTo(w * 0.72f, h * 0.42f)
                }
                MenuGlyph.BACK -> {
                    path.moveTo(w * 0.44f, h * 0.24f); path.lineTo(w * 0.18f, h * 0.5f); path.lineTo(w * 0.44f, h * 0.76f)
                    path.moveTo(w * 0.2f, h * 0.5f); path.lineTo(w * 0.82f, h * 0.5f)
                }
                MenuGlyph.CLOSE -> {
                    path.moveTo(w * 0.26f, h * 0.26f); path.lineTo(w * 0.74f, h * 0.74f)
                    path.moveTo(w * 0.74f, h * 0.26f); path.lineTo(w * 0.26f, h * 0.74f)
                }
                MenuGlyph.CONTROLS -> {
                    // two sliders
                    path.moveTo(w * 0.2f, h * 0.36f); path.lineTo(w * 0.8f, h * 0.36f)
                    path.moveTo(w * 0.2f, h * 0.64f); path.lineTo(w * 0.8f, h * 0.64f)
                    oval.set(w * 0.52f, h * 0.27f, w * 0.7f, h * 0.45f)
                    path.addOval(oval, Path.Direction.CW)
                    oval.set(w * 0.3f, h * 0.55f, w * 0.48f, h * 0.73f)
                    path.addOval(oval, Path.Direction.CW)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val TAG = "HotCorner"
        private const val MARGIN = 16
        private const val REST_MS = 3_000L
        private const val MENU_MS = 6_000L

        // Cleared in onDestroy; the service and the launcher share the process and the main thread
        @SuppressLint("StaticFieldLeak")
        private var instance: HotCornerService? = null
        private var homeVisible = false

        /** The launcher tells the dot when Home is in front: no need for a home button there. Main thread only. */
        fun setHomeVisible(visible: Boolean) {
            homeVisible = visible
            instance?.visibleOnHome(visible)
        }

        fun homeIntent(context: Context): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClass(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
