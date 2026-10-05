package com.wusui.rtmpcapture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wusui.rtmpcapture.capture.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads only transform metadata; no camera pixels or audio/video files are exported. */
@RunWith(AndroidJUnit4::class)
class CameraTransformProbeTest {
    @Test fun probeProducerTransform() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cameraTransformProbe")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val profile=Hardware.validate(context,CaptureSettings())
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val pbuffer=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,eglContext))
        val id=IntArray(1); GLES20.glGenTextures(1,id,0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,id[0])
        val texture=SurfaceTexture(id[0]).apply { setDefaultBufferSize(1280,720) }
        val surface=Surface(texture)
        val first=CountDownLatch(1)
        texture.setOnFrameAvailableListener { first.countDown() }
        val camera=CameraSource(context,profile,{ error(it) })
        try {
            camera.start(surface)
            check(first.await(5,TimeUnit.SECONDS))
            texture.updateTexImage()
            val matrix=FloatArray(16); texture.getTransformMatrix(matrix)
            val result="sensor=${profile.sensorRotation};matrix=${matrix.joinToString(",")}"
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("cameraTransform",result) })
        } finally {
            camera.close(); texture.setOnFrameAvailableListener(null); surface.release(); texture.release()
            GLES20.glDeleteTextures(1,id,0)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,pbuffer); EGL14.eglDestroyContext(display,eglContext); EGL14.eglTerminate(display)
        }
    }
}
