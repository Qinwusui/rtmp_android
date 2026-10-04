package com.wusui.rtmpcapture.capture

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Surface
import java.time.LocalTime

/** Caller serializes lifecycle operations off the main thread. */
class CapturePipeline(private val context:Context,private var settings:CaptureSettings,private val update:(CaptureStats)->Unit,private val fatal:(String)->Unit,private val screenStopped:()->Unit,private val screenResized:(Int,Int)->Unit,private val cameraInterrupted:(String)->Unit) {
    private var video:VideoEncoder?=null
    private var audio:AudioEncoder?=null
    private var gl:GlCompositor?=null
    private var camera:CameraSource?=null
    private var projection:ProjectionSource?=null
    private var transport:RtmpTransport?=null
    private var profile:HardwareProfile?=null
    private var cameraInput:Surface?=null
    private var cameraUnavailable=false
    private var originNs=0L
    private var lastEncoded=0L
    private var lastSampleNs=0L
    private var active=false
    private var targetBitrate=settings.bitrate
    private var stableSeconds=0
    private var lastLight:Boolean?=null
    @Volatile private var connected=false
    @Volatile private var message="正在启动"

    fun start(address:String) {
        val profile=Hardware.validate(context,settings).also { this.profile=it }
        originNs=SystemClock.elapsedRealtimeNanos(); lastSampleNs=originNs; active=true
        transport=RtmpTransport(settings,{ value,text -> connected=value; message=text },fatal,{ video?.keyframe() })
        video=VideoEncoder(settings,profile.codecName,{ transport },fatal)
        val encoder=requireNotNull(video).start()
        gl=GlCompositor(settings,originNs,profile.sensorRotation,fatal)
        val surface=requireNotNull(gl).start(encoder).also { cameraInput=it }
        camera=CameraSource(context,profile,fatal,cameraInterrupted)
        requireNotNull(camera).start(surface)
        audio=AudioEncoder(originNs,{ buffer,info -> transport?.sendAudio(buffer,info) },fatal)
        requireNotNull(audio).start()
        transport?.start(address)
        refreshLight()
    }
    fun attachScreen(consent:Intent) {
        check(active && projection==null) { "请先启动采集，且每次屏幕采集须重新授权" }
        val next=ProjectionSource(context,requireNotNull(gl),settings,screenStopped,screenResized)
        projection=next
        try { next.start(consent); message="屏幕合流已接入" } catch(e:Exception) { detachScreen(); throw e }
    }
    fun detachScreen() { projection?.close(); projection=null; if(active) message="屏幕采集已停止；摄像头与麦克风继续推流" }
    fun resizeScreen(width:Int,height:Int) { projection?.resize(width,height) }
    fun preview(surface:Surface?,width:Int=0,height:Int=0) { gl?.preview(if(projection!=null) null else surface,width,height) }
    fun updateSettings(value:CaptureSettings) {
        settings=settings.copy(light=value.light,nightStart=value.nightStart,nightEnd=value.nightEnd,corner=value.corner,pipWidth=value.pipWidth,pipMaxHeight=value.pipMaxHeight)
        gl?.updateSettings(settings); refreshLight()
    }
    fun refreshLight() {
        val value=active && NightPolicy.enabled(settings.light,LocalTime.now(),settings.nightStart,settings.nightEnd)
        if(value!=lastLight) { camera?.light(value); lastLight=value }
    }
    fun markCameraUnavailable() { cameraUnavailable=true }
    fun recoverCamera() {
        check(active)
        camera?.close(); camera=null
        camera=CameraSource(context,requireNotNull(profile),fatal,cameraInterrupted)
        requireNotNull(camera).start(requireNotNull(cameraInput))
        cameraUnavailable=false; lastLight=null; refreshLight()
    }
    fun sample():CaptureStats {
        val now=SystemClock.elapsedRealtimeNanos()
        val count=video?.encoded?.get()?:0
        val fps=(count-lastEncoded)*1_000_000_000.0/(now-lastSampleNs).coerceAtLeast(1)
        lastEncoded=count; lastSampleNs=now
        val network=transport
        if(settings.adaptive && network?.connected==true) {
            if(network.queued>=64) {
                targetBitrate=maxOf(settings.minBitrate,(targetBitrate*.75).toInt()); video?.bitrate(targetBitrate)
                network.clearCongestion(); stableSeconds=0
            } else if(network.queued<16 && ++stableSeconds>=15 && targetBitrate<settings.bitrate) {
                targetBitrate=minOf(settings.bitrate,targetBitrate+200_000); video?.bitrate(targetBitrate); stableSeconds=0
            }
        }
        refreshLight()
        return CaptureStats(active,connected,projection!=null,fps,network?.measuredBitrate?:0,targetBitrate,network?.drops?:0,network?.queued?:0,(now-originNs)/1_000_000_000,network?.reconnects?:0,if(cameraUnavailable) "摄像头被系统占用，保留最后画面；麦克风与推流继续，正在恢复摄像头" else message).also(update)
    }
    fun close() {
        active=false
        runCatching { detachScreen() }
        runCatching { camera?.close() }; camera=null
        cameraInput=null; profile=null
        runCatching { audio?.close() }; audio=null
        runCatching { gl?.close() }; gl=null
        runCatching { video?.close() }; video=null
        runCatching { transport?.close() }; transport=null
    }
}
