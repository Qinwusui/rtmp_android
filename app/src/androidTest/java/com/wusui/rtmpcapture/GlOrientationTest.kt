package com.wusui.rtmpcapture

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wusui.rtmpcapture.capture.CaptureSettings
import com.wusui.rtmpcapture.capture.GlCompositor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real GLES/SurfaceTexture test using synthetic colors, never exporting actual camera frames. */
@RunWith(AndroidJUnit4::class)
class GlOrientationTest {
    @Test fun outputAndPreviewTransformAllFourDeviceDirections() {
        val width=1280; val height=720
        val output=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
        var failure:String?=null
        val gl=GlCompositor(CaptureSettings(),SystemClock.elapsedRealtimeNanos(),0,{ failure=it })
        try {
            val input=gl.start(output.surface)
            val canvas=input.lockCanvas(Rect(0,0,width,height))
            val paint=android.graphics.Paint()
            fun block(color:Int,left:Int,top:Int,right:Int,bottom:Int) {
                paint.color=color; canvas.drawRect(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat(),paint)
            }
            block(Color.RED,0,0,width/2,height/2); block(Color.GREEN,width/2,0,width,height/2)
            block(Color.BLUE,0,height/2,width/2,height); block(Color.YELLOW,width/2,height/2,width,height)
            input.unlockCanvasAndPost(canvas)
            val expected=listOf(
                0 to listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW),
                90 to listOf(Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN),
                180 to listOf(Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED),
                270 to listOf(Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE),
            )
            for((degrees,colors) in expected) {
                gl.setDeviceOrientation(degrees)
                val deadline=SystemClock.elapsedRealtime()+5000
                var matched=false
                while(SystemClock.elapsedRealtime()<deadline) {
                    assertNull(failure)
                    val image=output.acquireLatestImage()
                    if(image!=null) {
                        val actual=try { listOf(pixel(image,width/4,height/4),pixel(image,3*width/4,height/4),pixel(image,width/4,3*height/4),pixel(image,3*width/4,3*height/4)) } finally { image.close() }
                        if(actual==colors) { matched=true; break }
                    }
                    Thread.sleep(10)
                }
                assertTrue("Incorrect GLES pixels at device rotation $degrees",matched)
            }
        } finally {
            // Drain while close is queued so ImageReader backpressure cannot block EGL teardown.
            val closer=Thread { gl.close() }.apply { start() }
            while(closer.isAlive) { output.acquireLatestImage()?.close(); Thread.sleep(10) }
            output.close()
        }
    }
    private fun pixel(image:Image,x:Int,y:Int):Int {
        val plane=image.planes[0]
        val offset=y*plane.rowStride+x*plane.pixelStride
        val data=plane.buffer
        return Color.rgb(data.get(offset).toInt() and 255,data.get(offset+1).toInt() and 255,data.get(offset+2).toInt() and 255)
    }
}
