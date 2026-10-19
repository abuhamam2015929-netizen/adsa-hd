package com.adsa.hd

import android.graphics.ColorMatrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * معالجة حية على كل إطار عبر OpenGL (CameraX SurfaceProcessor):
 *  - فلاتر الألوان أثناء تسجيل الفيديو (وتظهر نفسها على المعاينة).
 *  - بورتريه سينمائي: طمس خلفية على شكل بوكيه حول نقطة التركيز التي يحددها المستخدم، بخمس درجات.
 *
 * تعمل على مسار GPU فقط (تمريرة واحدة بدون قراءة بكسلات للمعالج) لتقليل الأثر على معدل الإطارات.
 */
object GlEffect {

    /** تأثير يُربط مع المعاينة (وتسجيل الفيديو عند الحاجة). */
    class Effect(targets: Int, processor: Processor, onError: (Throwable) -> Unit) :
        CameraEffect(
            targets,
            processor.executor,
            processor,
            androidx.core.util.Consumer<Throwable> { onError(it) }
        )

    private const val KERNEL_N = 16

    // أقصى نصف قطر للطمس كنسبة من ارتفاع الإطار، لكل مستوى (1..5)
    private val BLUR_LEVELS = floatArrayOf(0.004f, 0.008f, 0.014f, 0.022f, 0.032f)

    private val IDENTITY_COLOR = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f
    )

    private const val VERTEX_SHADER = """
        attribute vec4 aPos;
        attribute vec2 aTex;
        uniform mat4 uTexMat;
        varying vec2 vPos;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            vPos = aTex;
            vTex = (uTexMat * vec4(aTex, 0.0, 1.0)).xy;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vPos;
        varying vec2 vTex;
        uniform samplerExternalOES sTex;
        uniform mat4 uTexMat;
        uniform vec4 uRowR;
        uniform vec4 uRowG;
        uniform vec4 uRowB;
        uniform float uBlur;
        uniform vec2 uFocus;
        uniform float uAspect;
        uniform vec2 uKernel[16];

        void main() {
            vec3 c = texture2D(sTex, vTex).rgb;
            if (uBlur > 0.0) {
                vec2 d = (vPos - uFocus) * vec2(uAspect, 1.0);
                float k = smoothstep(0.14, 0.42, length(d));
                float r = uBlur * k;
                if (r > 0.0004) {
                    vec2 sx = uTexMat[0].xy;
                    vec2 sy = uTexMat[1].xy;
                    vec3 acc = c;
                    for (int i = 0; i < 16; i++) {
                        vec2 o = uKernel[i] * r;
                        vec2 tc = vTex + (o.x / uAspect) * sx + o.y * sy;
                        acc += texture2D(sTex, tc).rgb;
                    }
                    c = acc / 17.0;
                }
            }
            vec3 o;
            o.r = dot(uRowR.xyz, c) + uRowR.w;
            o.g = dot(uRowG.xyz, c) + uRowG.w;
            o.b = dot(uRowB.xyz, c) + uRowB.w;
            gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);
        }
    """

    private class Out(val output: SurfaceOutput, val eglSurface: EGLSurface)

    class Processor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

        private val thread = HandlerThread("AdsaGlEffect").also { it.start() }
        private val handler = Handler(thread.looper)
        val executor: Executor = Executor { handler.post(it) }

        @Volatile private var colorRows: FloatArray = IDENTITY_COLOR
        @Volatile private var blurAmount = 0f
        @Volatile private var focusX = 0.5f
        @Volatile private var focusY = 0.5f   // محور y للأعلى (أصل الإحداثيات أسفل اليسار)
        @Volatile var onError: ((Throwable) -> Unit)? = null

        // EGL
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        private var glReady = false
        private var released = false

        // GL
        private var program = 0
        private var aPos = 0
        private var aTex = 0
        private var uTexMat = 0
        private var uRowR = 0
        private var uRowG = 0
        private var uRowB = 0
        private var uBlur = 0
        private var uFocus = 0
        private var uAspect = 0
        private var uKernel = 0
        private var uSampler = 0

        private val vertexBuf: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val texBuf: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        private val kernel = FloatArray(KERNEL_N * 2)

        // الإدخال والإخراج
        private var inputTexture: SurfaceTexture? = null
        private var inputSurface: Surface? = null
        private var texId = 0
        private val outputs = ArrayList<Out>()
        private val texMatrix = FloatArray(16)
        private val outMatrix = FloatArray(16)

        init {
            for (i in 0 until KERNEL_N) {
                val rad = sqrt((i + 1).toFloat() / KERNEL_N)
                val ang = (i + 1) * 2.399963f
                kernel[i * 2] = cos(ang) * rad
                kernel[i * 2 + 1] = sin(ang) * rad
            }
        }

        // ---------------- التحكم من الواجهة (آمن من أي خيط)

        fun setColor(matrix: ColorMatrix?) {
            val a = matrix?.array
            colorRows = if (a == null) IDENTITY_COLOR else floatArrayOf(
                a[0], a[1], a[2], a[4] / 255f,
                a[5], a[6], a[7], a[9] / 255f,
                a[10], a[11], a[12], a[14] / 255f
            )
        }

        /** level: 0 = إيقاف، 1..5 = درجة العزل. (fx, fyUp) نسب 0..1 مع y للأعلى. */
        fun setBlur(level: Int, fx: Float, fyUp: Float) {
            blurAmount = if (level <= 0) 0f else BLUR_LEVELS[(level - 1).coerceIn(0, BLUR_LEVELS.size - 1)]
            focusX = fx.coerceIn(0f, 1f)
            focusY = fyUp.coerceIn(0f, 1f)
        }

        // ---------------- SurfaceProcessor

        override fun onInputSurface(request: SurfaceRequest) {
            handler.post {
                try {
                    ensureGl()
                    makeCurrentPbuffer()
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    val tex = ids[0]
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                    val st = SurfaceTexture(tex)
                    val res: Size = request.resolution
                    st.setDefaultBufferSize(res.width, res.height)
                    val sf = Surface(st)
                    st.setOnFrameAvailableListener(this, handler)

                    inputTexture = st
                    inputSurface = sf
                    texId = tex

                    request.provideSurface(sf, executor) {
                        handler.post {
                            st.setOnFrameAvailableListener(null)
                            if (inputTexture === st) {
                                inputTexture = null
                                inputSurface = null
                            }
                            try {
                                makeCurrentPbuffer()
                                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                            } catch (_: Throwable) { }
                            st.release()
                            sf.release()
                        }
                    }
                } catch (t: Throwable) {
                    try { request.willNotProvideSurface() } catch (_: Throwable) { }
                    fail(t)
                }
            }
        }

        override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
            handler.post {
                try {
                    ensureGl()
                    val surface = surfaceOutput.getSurface(executor) { event ->
                        if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                            handler.post { removeOutput(surfaceOutput) }
                        }
                    }
                    val eglSurface = EGL14.eglCreateWindowSurface(
                        display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
                    )
                    outputs.add(Out(surfaceOutput, eglSurface))
                } catch (t: Throwable) {
                    try { surfaceOutput.close() } catch (_: Throwable) { }
                    fail(t)
                }
            }
        }

        override fun onFrameAvailable(st: SurfaceTexture) {
            if (released) return
            try {
                makeCurrentPbuffer()
                st.updateTexImage()
                if (st !== inputTexture) return
                st.getTransformMatrix(texMatrix)
                val ts = st.timestamp
                for (o in ArrayList(outputs)) {
                    EGL14.eglMakeCurrent(display, o.eglSurface, o.eglSurface, context)
                    val size = o.output.size
                    GLES20.glViewport(0, 0, size.width, size.height)
                    o.output.updateTransformMatrix(outMatrix, texMatrix)
                    draw(outMatrix, size)
                    EGLExt.eglPresentationTimeANDROID(display, o.eglSurface, ts)
                    EGL14.eglSwapBuffers(display, o.eglSurface)
                }
                makeCurrentPbuffer()
            } catch (t: Throwable) {
                fail(t)
            }
        }

        // ---------------- الرسم

        private fun draw(m: FloatArray, size: Size) {
            val rows = colorRows
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uSampler, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, m, 0)
            GLES20.glUniform4f(uRowR, rows[0], rows[1], rows[2], rows[3])
            GLES20.glUniform4f(uRowG, rows[4], rows[5], rows[6], rows[7])
            GLES20.glUniform4f(uRowB, rows[8], rows[9], rows[10], rows[11])
            GLES20.glUniform1f(uBlur, blurAmount)
            GLES20.glUniform2f(uFocus, focusX, focusY)
            GLES20.glUniform1f(uAspect, size.width.toFloat() / size.height.toFloat())
            GLES20.glUniform2fv(uKernel, KERNEL_N, kernel, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        // ---------------- EGL / GL

        private fun ensureGl() {
            if (glReady) return
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }

            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                0x3142, 1,   // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                "no EGL config"
            }
            config = configs[0]

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }

            pbuffer = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            makeCurrentPbuffer()

            program = buildProgram()
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
            uRowR = GLES20.glGetUniformLocation(program, "uRowR")
            uRowG = GLES20.glGetUniformLocation(program, "uRowG")
            uRowB = GLES20.glGetUniformLocation(program, "uRowB")
            uBlur = GLES20.glGetUniformLocation(program, "uBlur")
            uFocus = GLES20.glGetUniformLocation(program, "uFocus")
            uAspect = GLES20.glGetUniformLocation(program, "uAspect")
            uKernel = GLES20.glGetUniformLocation(program, "uKernel")
            uSampler = GLES20.glGetUniformLocation(program, "sTex")
            glReady = true
        }

        private fun makeCurrentPbuffer() {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        }

        private fun removeOutput(output: SurfaceOutput) {
            val o = outputs.firstOrNull { it.output === output }
            if (o != null) {
                outputs.remove(o)
                try {
                    makeCurrentPbuffer()
                    EGL14.eglDestroySurface(display, o.eglSurface)
                } catch (_: Throwable) { }
            }
            try { output.close() } catch (_: Throwable) { }
        }

        private fun buildProgram(): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "link failed: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "shader failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun fail(t: Throwable) {
            onError?.invoke(t)
        }

        /** تحرير كل شيء عند إغلاق التطبيق. */
        fun release() {
            handler.post {
                released = true
                try {
                    for (o in ArrayList(outputs)) removeOutput(o.output)
                    inputTexture?.setOnFrameAvailableListener(null)
                    inputSurface?.release()
                    inputTexture?.release()
                    inputSurface = null
                    inputTexture = null
                    if (glReady) {
                        EGL14.eglMakeCurrent(
                            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                        )
                        EGL14.eglDestroySurface(display, pbuffer)
                        EGL14.eglDestroyContext(display, context)
                        EGL14.eglTerminate(display)
                    }
                } catch (_: Throwable) { }
                thread.quitSafely()
            }
        }

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a)
                position(0)
            }
    }
}
