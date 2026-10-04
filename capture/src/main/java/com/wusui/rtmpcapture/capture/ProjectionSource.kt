package com.wusui.rtmpcapture.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** A consent Intent is consumed once. Resizes use the same VirtualDisplay, never another create. */
class ProjectionSource(private val context:Context,private val gl:GlCompositor,private val settings:CaptureSettings,private val stopped:()->Unit,private val resized:(Int,Int)->Unit) {
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    @Volatile private var closing=false
    private val callback=object:MediaProjection.Callback() {
        override fun onStop() { if(!closing) stopped() }
        override fun onCapturedContentResize(width:Int,height:Int) { if(!closing && width>0 && height>0) resized(width,height) }
        override fun onCapturedContentVisibilityChanged(isVisible:Boolean) { if(!closing) gl.screenVisibility(isVisible) }
    }
    fun start(consent:Intent) {
        val manager=context.getSystemService(MediaProjectionManager::class.java)
        val next=requireNotNull(manager.getMediaProjection(Activity.RESULT_OK,consent)) { "屏幕授权无效，请重新授权" }
        projection=next
        next.registerCallback(callback,Handler(Looper.getMainLooper()))
        val metrics=context.resources.displayMetrics
        val physical=context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val dimensions=android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        physical?.getRealMetrics(dimensions)
        val width=dimensions.widthPixels.takeIf { it>0 }?:metrics.widthPixels
        val height=dimensions.heightPixels.takeIf { it>0 }?:metrics.heightPixels
        val size=PipLayout.captureSize(settings,width,height)
        val surface=gl.createScreen(width,height)
        display=next.createVirtualDisplay("screen-pip",size.first,size.second,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)
    }
    fun resize(width:Int,height:Int) {
        if(closing || display==null) return
        val size=gl.resizeScreen(width,height)
        display?.resize(size.first,size.second,context.resources.displayMetrics.densityDpi)
    }
    fun close() {
        closing=true; runCatching { display?.release() }; display=null
        projection?.let { runCatching { it.unregisterCallback(callback) }; runCatching { it.stop() } }; projection=null
        gl.removeScreen()
    }
}
