package com.wusui.rtmpcapture.capture

import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.OrientationEventListener

/** Owned by the capture pipeline, independent of Activity recreation and auto-rotate lock. */
class DeviceOrientationMonitor(context: Context, private val changed: (Int)->Unit) {
    private val displays=context.getSystemService(DisplayManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    @Volatile var degrees=displayDegrees(); private set
    @Volatile private var active=false
    @Volatile private var receivedSensor=false
    private val sensor=object:OrientationEventListener(context.applicationContext,SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if(!active || orientation==ORIENTATION_UNKNOWN) return
            receivedSensor=true
            update(CameraOrientation.update(orientation,degrees))
        }
    }
    private val displayListener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int)=Unit
        override fun onDisplayRemoved(displayId: Int)=Unit
        override fun onDisplayChanged(displayId: Int) {
            if(active && displayId==Display.DEFAULT_DISPLAY && !receivedSensor) update(displayDegrees())
        }
    }
    private fun displayDegrees():Int = CameraOrientation.fromDisplayRotation(displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation?:0)
    private fun update(value:Int) { if(value!=degrees) { degrees=value; changed(value) } }
    fun start() {
        active=true
        displays.registerDisplayListener(displayListener,handler)
        if(sensor.canDetectOrientation()) sensor.enable()
    }
    fun close() { active=false; sensor.disable(); displays.unregisterDisplayListener(displayListener) }
}
