package dev.framio.lab.labs.graphics

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A full-screen animated plasma, drawn [layers] times with blending: a fill-rate load for the Mali-400.
 * Uses mediump only, because the Mali-400 has no highp in fragment shaders.
 * Needs a current GLES 2.0 context; create it on the GL thread.
 */
class Plasma {

    private val program: Int
    private val aPos: Int
    private val uTime: Int
    private val uLayer: Int
    private val uRes: Int

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uLayer = GLES20.glGetUniformLocation(program, "uLayer")
        uRes = GLES20.glGetUniformLocation(program, "uRes")
    }

    fun draw(width: Int, height: Int, seconds: Float, layers: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(uTime, seconds)
        for (l in 0 until layers) {
            GLES20.glUniform1f(uLayer, l.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    fun release() = GLES20.glDeleteProgram(program)

    companion object {
        private const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT = """
            precision mediump float;
            uniform float uTime;
            uniform float uLayer;
            uniform vec2 uRes;
            void main() {
                vec2 p = gl_FragCoord.xy / uRes * 6.0 - 3.0;
                float t = uTime * 0.6 + uLayer * 1.7;
                float v = sin(p.x + t) + sin(p.y * 1.3 - t) + sin((p.x + p.y) * 0.7 + t * 1.1) + sin(length(p) * 2.0 - t);
                vec3 c = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + v * 1.5 + uLayer);
                gl_FragColor = vec4(c * 0.6, 0.6);
            }
        """

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }
    }
}
