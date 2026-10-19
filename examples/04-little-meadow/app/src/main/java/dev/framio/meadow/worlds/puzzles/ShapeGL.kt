package dev.framio.meadow.worlds.puzzles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One big clay-lit solid in real time on the frame's Mali-400 (OpenGL ES 2.0). Drag spins it with a
 * gentle inertia, a tap makes it do its thing (the ball rolls, the cube tumbles, the cone spins…).
 * Renders only while something moves (RENDERMODE_WHEN_DIRTY). The surface stays below the window
 * (so the friend, gates and the ending draw over it) and paints its own piece of the backdrop
 * behind the shape, so it blends into the meadow.
 */
@SuppressLint("ViewConstructor")
internal class ShapeView(
    context: Context,
    /** The full-screen backdrop drawn behind this view (cover-scaled like ui.Backdrop). */
    private val backdrop: android.graphics.Bitmap?,
    private val onTap: () -> Unit,
) : GLSurfaceView(context) {
    val renderer = ShapeRenderer()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var dragging = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser())
        holder.setFormat(PixelFormat.RGBA_8888)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.wake = { requestRender() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val bg = backdrop ?: return
        if (width == 0 || height == 0) return
        // the piece of the backdrop this view covers, with the same cover scaling as the backdrop
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val root = rootView
        val sc = maxOf(root.width.toFloat() / bg.width, root.height.toFloat() / bg.height)
        val ox = (root.width - bg.width * sc) / 2
        val oy = (root.height - bg.height * sc) / 2
        val x = ((loc[0] - ox) / sc).toInt().coerceIn(0, bg.width - 1)
        val y = ((loc[1] - oy) / sc).toInt().coerceIn(0, bg.height - 1)
        val w = (width / sc).toInt().coerceIn(1, bg.width - x)
        val h = (height / sc).toInt().coerceIn(1, bg.height - y)
        val piece = android.graphics.Bitmap.createBitmap(bg, x, y, w, h)
        Log.i(GL_TAG, "backdrop piece $x,$y ${w}x$h")
        renderer.post { it.setBackdrop(piece) }
    }

    fun show(solid: Solid) {
        queueEvent { renderer.setSolid(solid) }
        requestRender()
    }

    fun act() = renderer.post { it.startAction() }

    /** Fades the shape out (about 300 ms). */
    fun vanish() = renderer.post { it.vanish = true }

    /** Frees the GL objects on the GL thread; call before the view is removed. */
    fun release() = queueEvent { renderer.release() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; lastT = now
                dragging = false
                renderer.post { it.grab() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) dragging = true
                if (dragging) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    val dt = (now - lastT).coerceAtLeast(8L) / 1000f
                    val k = 2.6f / width.coerceAtLeast(1)       // a drag across the view ≈ a bit less than half a turn
                    renderer.post { it.drag(dx * k, dy * k, dt) }
                }
                lastX = e.x; lastY = e.y; lastT = now
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    act()
                    onTap()
                }
                renderer.post { it.release(now - lastT > 80) }
            }
            MotionEvent.ACTION_CANCEL -> renderer.post { it.release(true) }
        }
        requestRender()
        return true
    }
}

/** 4× multisampling when the driver offers it (smooth edges), else a plain RGBA8888 + depth config. */
private class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val base = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
        )
        val tries = listOf(
            base + intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE),
            base + intArrayOf(EGL10.EGL_NONE),
        )
        for ((i, attrs) in tries.withIndex()) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, attrs, configs, 1, num) && num[0] > 0 && configs[0] != null) {
                Log.i(GL_TAG, if (i == 0) "EGL config with 4x MSAA" else "EGL config without MSAA")
                return configs[0]!!
            }
        }
        throw IllegalStateException("no EGL config")
    }
}

internal const val GL_TAG = "MeadowShapes"

private const val VERT = """
uniform mat4 uMvp;
uniform mat4 uModel;
attribute vec3 aPos;
attribute vec3 aNor;
varying vec3 vN;
varying vec3 vP;
varying vec3 vO;
void main() {
  vO = aPos;
  vec4 wp = uModel * vec4(aPos, 1.0);
  vP = wp.xyz;
  vN = (uModel * vec4(aNor, 0.0)).xyz;
  gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

// mediump only: the Mali-400's fragment shaders have no highp
private const val FRAG = """
precision mediump float;
uniform vec3 uColor;
uniform vec3 uEye;
uniform float uAlpha;
uniform float uBand;
varying vec3 vN;
varying vec3 vP;
varying vec3 vO;
void main() {
  vec3 n = normalize(vN);
  // a soft lighter band round the ball's middle, so you can see it roll
  float band = uBand * (1.0 - smoothstep(0.16, 0.24, abs(vO.y)));
  vec3 base = mix(uColor, vec3(0.99, 0.96, 0.90), band * 0.75);
  vec3 l = normalize(vec3(-0.45, 0.8, 0.65));
  vec3 v = normalize(uEye - vP);
  float wrap = clamp((dot(n, l) + 0.5) / 1.5, 0.0, 1.0);
  wrap = wrap * wrap * (3.0 - 2.0 * wrap);
  vec3 sky = mix(vec3(0.62, 0.54, 0.55), vec3(0.80, 0.84, 0.93), 0.5 + 0.5 * n.y);
  vec3 lin = wrap * vec3(1.0, 0.95, 0.88) * 0.74 + sky * 0.66;
  float rim = 1.0 - clamp(dot(n, v), 0.0, 1.0);
  rim = rim * rim * rim;
  vec3 h = normalize(l + v);
  float sp = pow(clamp(dot(n, h), 0.0, 1.0), 22.0) * 0.1;
  vec3 col = base * lin + base * base * 0.16 * (1.0 - wrap) + rim * 0.22 * vec3(1.0, 0.97, 0.95) + sp;
  col = col / (1.0 + col * 0.22) * 1.12;
  gl_FragColor = vec4(col * uAlpha, uAlpha);
}
"""

private const val SHADOW_VERT = """
uniform mat4 uMvp;
attribute vec2 aXZ;
varying vec2 vUv;
void main() {
  vUv = aXZ;
  gl_Position = uMvp * vec4(aXZ.x, 0.0, aXZ.y, 1.0);
}
"""

private const val SHADOW_FRAG = """
precision mediump float;
uniform float uStrength;
varying vec2 vUv;
void main() {
  float d = length(vUv);
  float a = (1.0 - smoothstep(0.15, 1.0, d)) * uStrength;
  gl_FragColor = vec4(vec3(0.22, 0.16, 0.26) * a, a);
}
"""

private const val BG_VERT = """
attribute vec2 aXY;
varying vec2 vUv;
void main() {
  vUv = vec2(aXY.x * 0.5 + 0.5, 0.5 - aXY.y * 0.5);
  gl_Position = vec4(aXY, 0.0, 1.0);
}
"""

private const val BG_FRAG = """
precision mediump float;
uniform sampler2D uTex;
varying vec2 vUv;
void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }
"""

private val COLORS = mapOf(
    Solid.Sphere to floatArrayOf(0.91f, 0.62f, 0.60f),
    Solid.Cube to floatArrayOf(0.53f, 0.67f, 0.84f),
    Solid.Cylinder to floatArrayOf(0.53f, 0.71f, 0.52f),
    Solid.Cone to floatArrayOf(0.92f, 0.69f, 0.36f),
    Solid.Pyramid to floatArrayOf(0.61f, 0.58f, 0.84f),
    Solid.Torus to floatArrayOf(0.87f, 0.55f, 0.40f),
)

/**
 * The GL side. Input arrives through [post] (from the UI thread) and is applied at the start of the
 * next frame, so all state is touched on the GL thread only.
 */
internal class ShapeRenderer : GLSurfaceView.Renderer {
    var wake: () -> Unit = {}
    private val inbox = ArrayList<(ShapeRenderer) -> Unit>()

    fun post(f: (ShapeRenderer) -> Unit) {
        synchronized(inbox) { inbox += f }
        wake()
    }

    private var solid = Solid.Sphere
    private var program = 0
    private var shadowProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var shadowVbo = 0
    private var indexCount = 0
    private var meshFor: Solid? = null
    private var width = 1
    private var height = 1

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val spin = FloatArray(16)   // what the child's fingers did, accumulated
    private val trickSpin = FloatArray(16)
    private val scratch = FloatArray(16)
    private val shadowModel = FloatArray(16)
    private val shadowMvp = FloatArray(16)
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uEye = 0
    private var uAlpha = 0
    private var uBand = 0
    private var aPos = 0
    private var aNor = 0
    private var uShadowMvp = 0
    private var uStrength = 0
    private var aXZ = 0
    private var bgProgram = 0
    private var bgTex = 0
    private var aXY = 0
    private var pendingBg: android.graphics.Bitmap? = null
    private var lastBg: android.graphics.Bitmap? = null

    fun setBackdrop(b: android.graphics.Bitmap) {
        pendingBg = b
    }
    private val eye = floatArrayOf(0f, 0.9f, 6.6f)

    // drag velocity, in radians per second around the screen's y (yaw) and x (pitch) axes
    private var vYaw = 0f
    private var vPitch = 0f
    private var held = false
    private var actionStart = -1L
    private var appear = 0f
    var vanish = false
    private var lastFrame = 0L
    private var frames = 0
    private var drawNanos = 0L
    private var fpsStart = 0L

    fun setSolid(s: Solid) {
        if (s != solid) appear = 0f
        solid = s
        Matrix.setIdentityM(spin, 0)
        Matrix.rotateM(spin, 0, if (s == Solid.Torus) 28f else 14f, 1f, 0f, 0f)
        Matrix.rotateM(spin, 0, -24f, 0f, 1f, 0f)
        actionStart = -1L
        vYaw = 0f
        vPitch = 0f
    }

    fun grab() {
        held = true
        vYaw = 0f
        vPitch = 0f
    }

    fun drag(dYaw: Float, dPitch: Float, dt: Float) {
        rotateSpin(dYaw, dPitch)
        // a smoothed velocity for the throw
        val a = 0.5f
        vYaw = vYaw * (1 - a) + (dYaw / dt) * a
        vPitch = vPitch * (1 - a) + (dPitch / dt) * a
    }

    fun release(still: Boolean) {
        held = false
        if (still) {
            vYaw = 0f
            vPitch = 0f
        }
        vYaw = vYaw.coerceIn(-9f, 9f)
        vPitch = vPitch.coerceIn(-9f, 9f)
    }

    fun startAction() {
        if (actionStart < 0) actionStart = SystemClock.uptimeMillis()
    }

    private fun rotateSpin(yaw: Float, pitch: Float) {
        Matrix.setIdentityM(tmp, 0)
        Matrix.rotateM(tmp, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(tmp, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.multiplyMM(scratch, 0, tmp, 0, spin, 0)
        System.arraycopy(scratch, 0, spin, 0, 16)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERT, FRAG)
        shadowProgram = link(SHADOW_VERT, SHADOW_FRAG)
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uEye = GLES20.glGetUniformLocation(program, "uEye")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uBand = GLES20.glGetUniformLocation(program, "uBand")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNor = GLES20.glGetAttribLocation(program, "aNor")
        uShadowMvp = GLES20.glGetUniformLocation(shadowProgram, "uMvp")
        uStrength = GLES20.glGetUniformLocation(shadowProgram, "uStrength")
        aXZ = GLES20.glGetAttribLocation(shadowProgram, "aXZ")
        bgProgram = link(BG_VERT, BG_FRAG)
        aXY = GLES20.glGetAttribLocation(bgProgram, "aXY")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        bgTex = t[0]
        if (pendingBg == null) pendingBg = lastBg // a new context (after a pause): upload again
        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        vbo = ids[0]; ibo = ids[1]; shadowVbo = ids[2]
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, floats(quad), GLES20.GL_STATIC_DRAW)
        meshFor = null
        hasBg = false
        lastFrame = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 30f, w.toFloat() / h, 1f, 20f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, -0.15f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (program == 0) return
        val t0 = System.nanoTime()
        val todo = synchronized(inbox) { inbox.toList().also { inbox.clear() } }
        todo.forEach { it(this) }
        if (meshFor != solid) upload(solid)
        pendingBg?.let { uploadBackdrop(it) }

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now

        // inertia: a slow, soft stop
        if (!held && (abs(vYaw) > 0.01f || abs(vPitch) > 0.01f)) {
            rotateSpin(vYaw * dt, vPitch * dt)
            val decay = exp(-2.4f * dt)
            vYaw *= decay
            vPitch *= decay
        }
        appear = if (vanish) max(0f, appear - dt / 0.3f) else min(1f, appear + dt / 0.45f)

        // the shape's trick, on top of the child's spin
        var lift = 0f
        var slide = 0f
        Matrix.setIdentityM(tmp, 0)
        if (actionStart >= 0) {
            val t = ((now - actionStart) / ACTION_MS.toFloat()).coerceIn(0f, 1f)
            val e = ease(t)
            when (solid) {
                Solid.Sphere -> { // rolls to the right and back
                    slide = 0.9f * sin(PI.toFloat() * e)
                    Matrix.rotateM(tmp, 0, -Math.toDegrees((slide / 1f).toDouble()).toFloat(), 0f, 0f, 1f)
                }
                Solid.Cube -> { // a little hop and a tumble over its edge
                    lift = 0.55f * sin(PI.toFloat() * t)
                    Matrix.rotateM(tmp, 0, -90f * e, 0f, 0f, 1f)
                }
                Solid.Cylinder -> { // a hop and a full turn
                    lift = 0.4f * sin(PI.toFloat() * t)
                    Matrix.rotateM(tmp, 0, 360f * e, 0f, 1f, 0f)
                }
                Solid.Cone -> Matrix.rotateM(tmp, 0, 720f * e, 0f, 1f, 0f) // spins like a top
                Solid.Pyramid -> { // two bounces
                    lift = 0.45f * abs(sin(2 * PI.toFloat() * t)) * (1f - 0.4f * t)
                }
                Solid.Torus -> { // flips like a coin
                    lift = 0.5f * sin(PI.toFloat() * t)
                    Matrix.rotateM(tmp, 0, 360f * e, 1f, 0f, 0f)
                }
            }
            if (t >= 1f) actionStart = -1L
        }

        // model = translate(slide, lift) · trick · spin
        Matrix.multiplyMM(trickSpin, 0, tmp, 0, spin, 0)
        Matrix.setIdentityM(scratch, 0)
        Matrix.translateM(scratch, 0, slide, lift, 0f)
        Matrix.multiplyMM(model, 0, scratch, 0, trickSpin, 0)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)

        GLES20.glClearColor(0.95f, 0.93f, 0.89f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawBackdrop()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // premultiplied

        drawShadow(slide, lift)
        drawSolid()

        val moving = held || actionStart >= 0 || abs(vYaw) > 0.01f || abs(vPitch) > 0.01f || (if (vanish) appear > 0f else appear < 1f)
        if (moving) {
            wake()
            drawNanos += System.nanoTime() - t0
            countFrame(now)
        } else fpsStart = 0L
    }

    private fun uploadBackdrop(b: android.graphics.Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE) // NPOT: clamp, no mipmaps
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        pendingBg = null
        lastBg = b
        hasBg = true
    }

    private var hasBg = false

    private fun drawBackdrop() {
        if (!hasBg) return
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProgram, "uTex"), 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo) // the same ±1 quad
        GLES20.glEnableVertexAttribArray(aXY)
        GLES20.glVertexAttribPointer(aXY, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aXY)
    }

    private fun drawShadow(slide: Float, lift: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(shadowProgram)
        Matrix.setIdentityM(shadowModel, 0)
        Matrix.translateM(shadowModel, 0, slide, GROUND, 0f)
        val s = 1.3f * (1f + lift * 0.35f)
        Matrix.scaleM(shadowModel, 0, s, 1f, s * 0.8f)
        Matrix.multiplyMM(shadowMvp, 0, viewProj, 0, shadowModel, 0)
        GLES20.glUniformMatrix4fv(uShadowMvp, 1, false, shadowMvp, 0)
        GLES20.glUniform1f(uStrength, 0.32f * ease(appear) / (1f + lift * 1.6f))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo)
        GLES20.glEnableVertexAttribArray(aXZ)
        GLES20.glVertexAttribPointer(aXZ, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aXZ)
    }

    private fun drawSolid() {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        val c = COLORS.getValue(solid)
        GLES20.glUniform3f(uColor, c[0], c[1], c[2])
        GLES20.glUniform3f(uEye, eye[0], eye[1], eye[2])
        GLES20.glUniform1f(uAlpha, ease(appear))
        GLES20.glUniform1f(uBand, if (solid == Solid.Sphere) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNor)
    }

    private fun upload(s: Solid) {
        val mesh = ShapeMeshes.of(s)
        val data = mesh.interleaved()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES20.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(mesh.indices).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, ib, GLES20.GL_STATIC_DRAW)
        indexCount = mesh.indices.size
        meshFor = s
    }

    /** Deletes buffers and programs (GL thread). The context itself goes when the view detaches. */
    fun release() {
        if (program == 0) return
        GLES20.glDeleteBuffers(3, intArrayOf(vbo, ibo, shadowVbo), 0)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(shadowProgram)
        GLES20.glDeleteProgram(bgProgram)
        GLES20.glDeleteTextures(1, intArrayOf(bgTex), 0)
        hasBg = false
        program = 0
        shadowProgram = 0
        meshFor = null
    }

    private fun countFrame(now: Long) {
        if (fpsStart == 0L) {
            fpsStart = now
            frames = 0
            drawNanos = 0
            return
        }
        frames++
        if (now - fpsStart >= 2000) {
            Log.i(GL_TAG, "fps ${frames * 1000 / (now - fpsStart)}, cpu ${drawNanos / 1000 / frames.coerceAtLeast(1)} µs per frame")
            fpsStart = now
            frames = 0
            drawNanos = 0
        }
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(GL_TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v) // flagged; freed with the program
        GLES20.glDeleteShader(f)
        return p
    }

    private companion object {
        const val ACTION_MS = 1300L
        /** The soft shadow floats just under the shape, which turns around its middle. */
        const val GROUND = -1.15f
        fun ease(t: Float) = t * t * (3f - 2f * t)
        fun floats(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    }
}
