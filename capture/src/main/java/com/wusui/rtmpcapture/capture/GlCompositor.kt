package com.wusui.rtmpcapture.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** All EGL/SurfaceTexture operations are confined to one render thread. No raw video buffers. */
class GlCompositor(
    private var settings: CaptureSettings,
    private val originNs: Long,
    initialDeviceDegrees: Int,
    private val fatal: (String) -> Unit,
) {
    private val thread = HandlerThread("capture-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var encoderEgl = EGL14.EGL_NO_SURFACE
    private var previewEgl = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0
    private var program = 0
    private var cameraTexture = 0
    private var screenTexture = 0
    private var cameraSt: SurfaceTexture? = null
    private var screenSt: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var screenSurface: Surface? = null
    private var cameraPending = false
    private var screenPending = false
    private var hasCamera = false
    private var hasScreen = false
    private var screenVisible = true
    private var screenWidth = 1
    private var screenHeight = 1
    private var running = false
    private var lastFrameNs = 0L
    private var nextTickNs = 0L
    val rendered = AtomicLong()
    val skipped = AtomicLong()
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f, 0f,0f, 1f,-1f, 1f,0f, -1f,1f, 0f,1f, 1f,1f, 1f,1f)); position(0)
    }
    private val stMatrix = FloatArray(16)
    private val cameraMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)
    private var deviceDegrees=initialDeviceDegrees
    private var matrixDeviceDegrees=-1
    private var matrixProducerSwapped:Boolean?=null
    private var posLocation = 0
    private var uvLocation = 0
    private var matrixLocation = 0
    private var samplerLocation = 0

    private fun <T> call(block: () -> T): T {
        val result = CompletableFuture<T>()
        handler.post { runCatching(block).fold({ result.complete(it) }, { result.completeExceptionally(it) }) }
        return result.get(5, TimeUnit.SECONDS)
    }

    fun start(encoder: Surface): Surface = call {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL 初始化失败" }
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE),0, configs,0,1,IntArray(1),0))
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT)
        encoderEgl = window(encoder)
        makeCurrent(encoderEgl)
        program = createProgram()
        posLocation = GLES20.glGetAttribLocation(program,"aPosition")
        uvLocation = GLES20.glGetAttribLocation(program,"aUv")
        matrixLocation = GLES20.glGetUniformLocation(program,"uMatrix")
        samplerLocation = GLES20.glGetUniformLocation(program,"uTexture")
        cameraTexture = texture()
        cameraSt = SurfaceTexture(cameraTexture).apply {
            setDefaultBufferSize(settings.width, settings.height)
            setOnFrameAvailableListener({ cameraPending = true }, handler)
        }
        running = true
        nextTickNs = SystemClock.elapsedRealtimeNanos()
        handler.post(tick)
        Surface(requireNotNull(cameraSt)).also { cameraSurface = it }
    }

    fun createScreen(width: Int, height: Int): Surface = call {
        releaseScreen()
        makeCurrent(encoderEgl)
        screenWidth = width; screenHeight = height
        val size = PipLayout.captureSize(settings, width, height)
        screenTexture = texture()
        screenSt = SurfaceTexture(screenTexture).apply {
            setDefaultBufferSize(size.first,size.second)
            setOnFrameAvailableListener({ screenPending = true },handler)
        }
        screenVisible = true
        Surface(requireNotNull(screenSt)).also { screenSurface = it }
    }
    fun resizeScreen(width: Int, height: Int): Pair<Int, Int> = call {
        screenWidth = width; screenHeight = height
        PipLayout.captureSize(settings,width,height).also { screenSt?.setDefaultBufferSize(it.first,it.second) }
    }
    fun screenVisibility(visible: Boolean) { handler.post { screenVisible = visible } }
    fun setDeviceOrientation(degrees:Int) {
        require(degrees in listOf(0,90,180,270))
        handler.post { deviceDegrees=degrees }
    }
    fun removeScreen() { call { releaseScreen() } }
    fun updateSettings(value: CaptureSettings) { handler.post { settings = settings.copy(corner=value.corner,pipWidth=value.pipWidth,pipMaxHeight=value.pipMaxHeight) } }

    fun preview(surface: Surface?, width: Int = 0, height: Int = 0) {
        call {
            makeCurrent(encoderEgl)
            if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
            if (surface != null && surface.isValid && width > 0 && height > 0) {
                previewEgl = window(surface); previewWidth=width; previewHeight=height
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                makeCurrent(encoderEgl)
                if (cameraPending) {
                    cameraSt?.updateTexImage(); cameraPending=false; hasCamera=true
                } else if (hasCamera) skipped.incrementAndGet()
                if (screenPending) { screenSt?.updateTexImage(); screenPending=false; hasScreen=true }
                if (hasCamera) {
                    draw(settings.width,settings.height)
                    val now = SystemClock.elapsedRealtimeNanos()
                    val pts = maxOf(now-originNs,lastFrameNs+1)
                    lastFrameNs=pts
                    EGLExt.eglPresentationTimeANDROID(display,encoderEgl,pts)
                    check(EGL14.eglSwapBuffers(display,encoderEgl)) { "编码 Surface 已失效" }
                    rendered.incrementAndGet()
                    if (previewEgl != EGL14.EGL_NO_SURFACE && screenSt == null) {
                        // Activity Surface destruction must never terminate the encoder pipeline.
                        val current=EGL14.eglMakeCurrent(display,previewEgl,previewEgl,context)
                        if(current) draw(previewWidth,previewHeight)
                        if (!current || !EGL14.eglSwapBuffers(display,previewEgl)) {
                            makeCurrent(encoderEgl); EGL14.eglDestroySurface(display,previewEgl); previewEgl=EGL14.EGL_NO_SURFACE
                        }
                    }
                }
                // Use absolute cadence so GL/swap time does not accumulate onto every interval.
                val period = 1_000_000_000L/settings.fps
                nextTickNs += period
                val now = SystemClock.elapsedRealtimeNanos()
                if(nextTickNs < now-period) nextTickNs = now
                handler.postDelayed(this, ((nextTickNs-now).coerceAtLeast(0)+999_999L)/1_000_000L)
            } catch (_: Exception) { running=false; fatal("视频合成失败，请重新启动") }
        }
    }

    private fun draw(width: Int, height: Int) {
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val aspect = settings.width.toFloat()/settings.height
        val w = minOf(width, (height*aspect).toInt())
        val h = (w/aspect).toInt()
        val x = (width-w)/2; val y=(height-h)/2
        GLES20.glViewport(x,y,w,h)
        cameraSt?.getTransformMatrix(stMatrix)
        val producerSwapped=CameraOrientation.producerSwapsAxes(stMatrix)
        if(matrixDeviceDegrees!=deviceDegrees || matrixProducerSwapped!=producerSwapped) {
            CameraOrientation.writeUvMatrix(cameraMatrix,settings.width,settings.height,settings.width,settings.height,producerSwapped,deviceDegrees)
            matrixDeviceDegrees=deviceDegrees; matrixProducerSwapped=producerSwapped
        }
        Matrix.multiplyMM(combinedMatrix,0,stMatrix,0,cameraMatrix,0)
        quad(cameraTexture,combinedMatrix)
        if (hasScreen && screenVisible && screenSt != null) {
            val rect = PipLayout.rect(settings,screenWidth,screenHeight)
            GLES20.glViewport(x+rect.x*w/settings.width,y+rect.y*h/settings.height,rect.width*w/settings.width,rect.height*h/settings.height)
            screenSt?.getTransformMatrix(stMatrix); quad(screenTexture,stMatrix)
        }
    }
    private fun quad(texture: Int, matrix: FloatArray) {
        GLES20.glUseProgram(program)
        vertices.position(0); GLES20.glVertexAttribPointer(posLocation,2,GLES20.GL_FLOAT,false,16,vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glEnableVertexAttribArray(posLocation); GLES20.glEnableVertexAttribArray(uvLocation)
        GLES20.glUniformMatrix4fv(matrixLocation,1,false,matrix,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(samplerLocation,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }
    private fun texture(): Int {
        val id=IntArray(1); GLES20.glGenTextures(1,id,0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,id[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private fun createProgram(): Int {
        fun shader(type:Int,source:String):Int {
            val id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
            val ok=IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { "着色器编译失败" }; return id
        }
        val vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec4 aPosition; attribute vec4 aUv; uniform mat4 uMatrix; varying vec2 vUv; void main(){gl_Position=aPosition;vUv=(uMatrix*aUv).xy;}")
        val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTexture; void main(){gl_FragColor=texture2D(uTexture,vUv);}")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,vertex); GLES20.glAttachShader(it,fragment); GLES20.glLinkProgram(it)
            val ok=IntArray(1); GLES20.glGetProgramiv(it,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0) { "着色器链接失败" }
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        }
    }
    private fun window(surface:Surface):EGLSurface = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0).also { check(it!=EGL14.EGL_NO_SURFACE) }
    private fun makeCurrent(surface:EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) }
    private fun releaseScreen() {
        screenSt?.setOnFrameAvailableListener(null)
        screenSurface?.release(); screenSurface=null
        screenSt?.release(); screenSt=null
        if (screenTexture!=0 && display!=EGL14.EGL_NO_DISPLAY && EGL14.eglMakeCurrent(display,encoderEgl,encoderEgl,context)) GLES20.glDeleteTextures(1,intArrayOf(screenTexture),0)
        screenTexture=0; hasScreen=false; screenPending=false
    }
    fun close() {
        runCatching { call {
            running=false; handler.removeCallbacks(tick)
            releaseScreen()
            cameraSt?.setOnFrameAvailableListener(null); cameraSurface?.release(); cameraSt?.release()
            if (display!=EGL14.EGL_NO_DISPLAY) {
                if (context!=EGL14.EGL_NO_CONTEXT && encoderEgl!=EGL14.EGL_NO_SURFACE) {
                    if(EGL14.eglMakeCurrent(display,encoderEgl,encoderEgl,context)) { GLES20.glDeleteTextures(1,intArrayOf(cameraTexture),0); GLES20.glDeleteProgram(program) }
                }
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                if(previewEgl!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,previewEgl)
                if(encoderEgl!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,encoderEgl)
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
            }
        } }
        thread.quitSafely(); thread.join(2000)
    }
}
