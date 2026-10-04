package com.wusui.rtmpcapture.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraSource(context:Context, private val profile:HardwareProfile, private val fatal:(String)->Unit,private val interrupted:(String)->Unit = fatal) {
    private val manager=context.getSystemService(CameraManager::class.java)
    private val thread=HandlerThread("capture-camera").apply { start() }
    private val handler=Handler(thread.looper)
    private var camera:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var request:CaptureRequest.Builder?=null
    private val closed=AtomicBoolean(false)
    private val ready=CompletableFuture<Unit>()
    @SuppressLint("MissingPermission")
    fun start(surface:Surface) {
        manager.openCamera(profile.cameraId,object:CameraDevice.StateCallback() {
            override fun onOpened(device:CameraDevice) {
                if(closed.get()) { device.close(); return }
                camera=device
                request=device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,profile.fpsRange)
                    set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface),object:CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value:CameraCaptureSession) {
                        if(closed.get()) { value.close(); return }
                        session=value
                        runCatching { value.setRepeatingRequest(requireNotNull(request).build(),null,handler) }
                            .fold({ ready.complete(Unit) }, { ready.completeExceptionally(it); fatal("摄像头启动失败") })
                    }
                    override fun onConfigureFailed(value:CameraCaptureSession) { value.close(); ready.completeExceptionally(IllegalStateException("摄像头配置失败")); fatal("摄像头配置失败") }
                },handler)
            }
            override fun onDisconnected(device:CameraDevice) { device.close(); ready.completeExceptionally(IllegalStateException("摄像头被占用")); if(!closed.get()) interrupted("摄像头被系统暂时占用，正在恢复") }
            override fun onError(device:CameraDevice,error:Int) {
                device.close(); ready.completeExceptionally(IllegalStateException("摄像头错误 $error"))
                if(!closed.get()) {
                    if(error==ERROR_CAMERA_IN_USE || error==ERROR_MAX_CAMERAS_IN_USE || error==ERROR_CAMERA_DEVICE) interrupted("摄像头暂时不可用，正在恢复")
                    else fatal("摄像头权限撤销或设备错误 $error")
                }
            }
        },handler)
        ready.get(10,TimeUnit.SECONDS)
    }
    fun light(enabled:Boolean) {
        if(!profile.flash || closed.get()) return
        handler.post {
            runCatching {
                request?.set(CaptureRequest.FLASH_MODE,if(enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                request?.let { session?.setRepeatingRequest(it.build(),null,handler) }
            }.onFailure { if(!closed.get()) fatal("补光控制失败，摄像头资源可能已撤销") }
        }
    }
    fun close() {
        closed.set(true)
        val done=CompletableFuture<Unit>()
        handler.post {
            runCatching {
                request?.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
                request?.let { session?.setRepeatingRequest(it.build(),null,handler) }
            }
            runCatching { session?.stopRepeating() }; runCatching { session?.close() }; runCatching { camera?.close() }
            session=null; camera=null; request=null; done.complete(Unit)
        }
        runCatching { done.get(3,TimeUnit.SECONDS) }; thread.quitSafely(); thread.join(2000)
    }
}
