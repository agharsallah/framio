package dev.framio.lab.labs.graphics

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.Coral
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.LabeledSlider
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.Surface0
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Mali-400 MP: GL ES 2.0 limits, a GLSurfaceView fill-rate test and a Compose canvas stress test. */
object GraphicsLab {

    val lab = Lab(
        id = "graphics",
        title = "Graphics",
        summary = "Mali-400 GL ES 2.0 limits, shader fill-rate, Compose canvas stress",
        accent = Violet,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        LabPage("Graphics", "ARM Mali-400 MP, OpenGL ES 2.0 only: WebGL 1 works, WebGL 2 does not. No highp floats in fragment shaders.") {
            InfoSection()
            GlSection()
            CanvasSection()
            SelfTestSection(lab.id, note = "Reads the GPU limits on an offscreen EGL context and times the plasma shader at 1280×800.")
        }
    }

    @Composable
    private fun InfoSection() {
        var info by remember { mutableStateOf<GlInfo.Info?>(null) }
        var egl by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.Default) {
                OffscreenGl.use(16, 16) { egl = eglVersion; GlInfo.read() }
            }.let { info = it }
        }
        Section("GPU") {
            val i = info ?: return@Section
            KeyValues(
                listOf(
                    "Renderer" to "${i.vendor} ${i.renderer}",
                    "Version" to "${i.version} · ${i.glsl} · EGL $egl",
                    "Max texture" to "${i.maxTexture} px (renderbuffer ${i.maxRenderbuffer}, viewport ${i.maxViewport})",
                    "Texture units / attribs / varyings" to "${i.maxTextureUnits} / ${i.maxVertexAttribs} / ${i.maxVaryings}",
                    "Fragment highp" to if (i.fragmentHighp) "yes" else "no — use mediump (${i.fragmentMediumpBits}-bit mantissa) in shaders",
                ),
            )
            Text(i.extensions.joinToString("  "), style = Mono)
        }
    }

    /** GLSurfaceView drawing the plasma continuously; fps reported back through Compose state. */
    @Composable
    private fun GlSection() {
        var running by remember { mutableStateOf(false) }
        var layerCount by remember { mutableFloatStateOf(1f) }
        var fps by remember { mutableStateOf("") }
        val renderer = remember {
            object : GLSurfaceView.Renderer {
                @Volatile var layers = 1
                @Volatile var onFps: (Double) -> Unit = {}
                private var plasma: Plasma? = null
                private var w = 1
                private var h = 1
                private val start = System.nanoTime()
                private var frames = 0
                private var windowStart = 0L

                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    plasma = Plasma()
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    w = width; h = height
                }

                override fun onDrawFrame(gl: GL10?) {
                    val now = System.nanoTime()
                    plasma?.draw(w, h, (now - start) / 1e9f, layers)
                    if (windowStart == 0L) windowStart = now
                    if (++frames == 30) {
                        onFps(frames * 1e9 / (now - windowStart))
                        frames = 0
                        windowStart = now
                    }
                }
            }
        }
        renderer.onFps = { fps = "${Format.f1(it)} fps with ${renderer.layers} layer(s) at this view's size" }

        Section(
            "Shader fill-rate (GLSurfaceView)",
            note = "Each layer is one full-view pass of an animated plasma shader, blended. Watch where it drops below 57 fps.",
            trailing = { ActionButton(if (running) "Stop" else "Start", { running = !running }) },
        ) {
            LabeledSlider("Layers", layerCount.toInt().toString(), layerCount, { layerCount = it; renderer.layers = it.toInt() }, range = 1f..8f, steps = 6)
            if (running) {
                AndroidView(
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(2)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    onRelease = { it.onPause() },
                )
            }
            Text(fps.ifEmpty { "Press Start." }, style = Mono)
        }
    }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int)

    /** N particles moved and drawn by Compose every frame: the cost of 2D UI animation on this CPU+GPU. */
    @Composable
    private fun CanvasSection() {
        var running by remember { mutableStateOf(false) }
        var count by remember { mutableFloatStateOf(500f) }
        var frame by remember { mutableLongStateOf(0L) }
        var fps by remember { mutableStateOf("") }
        val particles = remember { ArrayList<Particle>() }
        val colors = listOf(Teal, Amber, Coral, Sky, Violet)
        var frames by remember { mutableIntStateOf(0) }

        LaunchedEffect(running) {
            if (!running) return@LaunchedEffect
            var windowStart = 0L
            var last = 0L
            while (true) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 0f else (t - last) / 1e9f
                    last = t
                    val target = count.toInt()
                    while (particles.size < target) {
                        val a = Random.nextFloat() * 6.283f
                        particles += Particle(Random.nextFloat(), Random.nextFloat(), cos(a) * 0.2f, sin(a) * 0.2f, Random.nextInt(colors.size))
                    }
                    while (particles.size > target) particles.removeAt(particles.size - 1)
                    particles.forEach { p ->
                        p.x += p.vx * dt; p.y += p.vy * dt
                        if (p.x !in 0f..1f) p.vx = -p.vx
                        if (p.y !in 0f..1f) p.vy = -p.vy
                    }
                    frame = t
                    if (windowStart == 0L) windowStart = t
                    if (++frames == 30) {
                        fps = "${Format.f1(30e9 / (t - windowStart))} fps with ${particles.size} particles"
                        frames = 0
                        windowStart = t
                    }
                }
            }
        }

        Section(
            "Compose canvas",
            note = "Particles updated on the main thread and drawn with drawCircle each frame.",
            trailing = { ActionButton(if (running) "Stop" else "Start", { running = !running }) },
        ) {
            LabeledSlider("Particles", count.toInt().toString(), count, { count = it }, range = 100f..5000f)
            if (running) {
                Canvas(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(10.dp)).background(Surface0)) {
                    frame // redraw every frame
                    particles.forEach { p -> drawCircle(colors[p.color], 4f, Offset(p.x * size.width, p.y * size.height)) }
                }
            }
            Text(fps.ifEmpty { "Press Start." }, style = Mono)
            Note("Rule of thumb for dashboards here: animate a few elements with transform/opacity, not hundreds.")
        }
    }

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        guard("GL ES") {
            OffscreenGl.use {
                val i = GlInfo.read()
                if (i.version.contains("OpenGL ES 2")) pass("GL ES", "${i.renderer}: ${i.version}, EGL $eglVersion")
                else info("GL ES", "${i.renderer}: ${i.version}")
                info("Limits", "max texture ${i.maxTexture}, ${i.maxTextureUnits} texture units, ${i.maxVaryings} varyings, ${i.extensions.size} extensions")
                if (i.fragmentHighp) pass("Fragment highp", "supported")
                else warn("Fragment highp", "not supported: WebGL/GL shaders must use mediump (${i.fragmentMediumpBits}-bit)")
                if ("GL_OES_texture_npot" in i.extensions) pass("NPOT textures", "GL_OES_texture_npot") else warn("NPOT textures", "missing")
                val fps1 = GlInfo.plasmaFps(width, height, 1)
                val fps4 = GlInfo.plasmaFps(width, height, 4)
                val text = "1280×800 plasma: ${Format.f1(fps1)} fps (1 layer), ${Format.f1(fps4)} fps (4 layers)"
                if (fps1 >= 50) pass("Fill-rate", text) else warn("Fill-rate", "$text: a full-screen shader can't hold 57 fps")
                val floatTex = i.extensions.any { it == "GL_OES_texture_float" || it == "GL_OES_texture_half_float" }
                info("WebGL", "WebGL 1 only (GL ES 2.0), no WebGL 2; float textures: ${if (floatTex) "yes" else "no"}")
            }
        }
        manual("GL view", "Graphics → Shader fill-rate → Start, raise layers until it drops below 57 fps")
        items
    }
}
