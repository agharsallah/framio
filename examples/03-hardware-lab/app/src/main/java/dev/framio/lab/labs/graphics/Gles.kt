package dev.framio.lab.labs.graphics

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * A headless GLES 2.0 context on an EGL pbuffer: reads GPU limits and benchmarks without a window.
 * Everything runs on the calling thread; [use] makes the context current and tears it down after.
 */
class OffscreenGl private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface,
    val width: Int,
    val height: Int,
) {

    companion object {
        fun <T> use(width: Int = 1280, height: Int = 800, block: OffscreenGl.() -> T): T {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            check(count[0] > 0) { "no pbuffer config" }
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, ctx)) { "eglMakeCurrent" }
            try {
                return OffscreenGl(display, ctx, surface, width, height).block()
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }

    val eglVersion: String get() = EGL14.eglQueryString(display, EGL14.EGL_VERSION) ?: "?"
}

/** GPU facts that matter for WebGL and shaders. Call with a current context. */
object GlInfo {

    data class Info(
        val vendor: String, val renderer: String, val version: String, val glsl: String,
        val maxTexture: Int, val maxRenderbuffer: Int, val maxViewport: String,
        val maxTextureUnits: Int, val maxVertexAttribs: Int, val maxVaryings: Int,
        val fragmentHighp: Boolean, val fragmentMediumpBits: Int,
        val extensions: List<String>,
    )

    fun read(): Info {
        fun int(p: Int) = IntArray(2).also { GLES20.glGetIntegerv(p, it, 0) }
        val range = IntArray(2)
        val precision = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
        val highp = precision[0] > 0
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_MEDIUM_FLOAT, range, 0, precision, 0)
        val vp = int(GLES20.GL_MAX_VIEWPORT_DIMS)
        return Info(
            vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
            renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
            version = GLES20.glGetString(GLES20.GL_VERSION) ?: "?",
            glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "?",
            maxTexture = int(GLES20.GL_MAX_TEXTURE_SIZE)[0],
            maxRenderbuffer = int(GLES20.GL_MAX_RENDERBUFFER_SIZE)[0],
            maxViewport = "${vp[0]}×${vp[1]}",
            maxTextureUnits = int(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS)[0],
            maxVertexAttribs = int(GLES20.GL_MAX_VERTEX_ATTRIBS)[0],
            maxVaryings = int(GLES20.GL_MAX_VARYING_VECTORS)[0],
            fragmentHighp = highp,
            fragmentMediumpBits = precision[0],
            extensions = (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "").split(' ').filter { it.isNotBlank() }.sorted(),
        )
    }

    /** Draws [frames] plasma frames with [layers] of overdraw into the current surface; returns fps. */
    fun plasmaFps(width: Int, height: Int, layers: Int, frames: Int = 40): Double {
        val plasma = Plasma()
        plasma.draw(width, height, 0f, layers)
        GLES20.glFinish() // warm up: shader compile, first allocation
        val t0 = System.nanoTime()
        repeat(frames) { plasma.draw(width, height, it / 30f, layers) }
        GLES20.glFinish()
        val fps = frames / ((System.nanoTime() - t0) / 1e9)
        plasma.release()
        return fps
    }
}
