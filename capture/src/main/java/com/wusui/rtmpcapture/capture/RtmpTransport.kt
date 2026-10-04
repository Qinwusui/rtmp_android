package com.wusui.rtmpcapture.capture

import android.media.MediaCodec
import android.os.SystemClock
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.random.Random

/** Reconnect replaces only the RTMP client. Capture/codec resources and their clock stay alive. */
class RtmpTransport(private val settings:CaptureSettings,private val status:(Boolean,String)->Unit,private val fatal:(String)->Unit,private val keyframe:()->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Any()
    private var client:RtmpClient?=null
    @Volatile private var generation=0
    private var retry:Job?=null
    private var attempt=0
    private var url=""
    private var sps:ByteArray?=null
    private var pps:ByteArray?=null
    @Volatile private var active=true
    private var waitingKey=true
    private val timeline=PublishTimeline()
    private var connectedAt=0L
    private var immediateDisconnects=0
    private val videoInfo=MediaCodec.BufferInfo()
    private val audioInfo=MediaCodec.BufferInfo()
    @Volatile var connected=false; private set
    @Volatile var reconnects=0; private set
    @Volatile var measuredBitrate=0L; private set
    private var previousDrops=0L
    private var previousBytes=0L
    val drops:Long get()=synchronized(lock) { previousDrops+(client?.droppedVideoFrames?:0)+(client?.droppedAudioFrames?:0) }
    val bytes:Long get()=synchronized(lock) { previousBytes+(client?.bytesSend?:0) }
    val queued:Int get()=synchronized(lock) { client?.getItemsInCache()?:0 }

    fun start(address:String) { synchronized(lock) { url=address; if(sps!=null) connect() } }
    fun videoFormat(spsBuffer:ByteBuffer,ppsBuffer:ByteBuffer) = synchronized(lock) {
        fun bytes(buffer:ByteBuffer)=ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        sps=bytes(spsBuffer); pps=bytes(ppsBuffer)
        if(url.isNotEmpty() && client==null) connect()
    }
    private fun connect() {
        if(!active) return
        generation++
        val current=generation
        val next=RtmpClient(object:ConnectChecker {
            override fun onConnectionStarted(url:String) { if(current==generation && active) status(false,"正在连接服务器") }
            override fun onConnectionSuccess() = synchronized(lock) {
                if(current!=generation || !active) return@synchronized
                connected=true; connectedAt=SystemClock.elapsedRealtime(); waitingKey=true; timeline.reset(); attempt=0; retry=null; status(true,"推流中"); keyframe()
            }
            override fun onConnectionFailed(reason:String) { failure(reason,current) }
            override fun onDisconnect() { failure("连接断开",current) }
            override fun onAuthError() { synchronized(lock) { if(current==generation && active) fatal("发布认证失败，请修改服务器配置") } }
            override fun onAuthSuccess() = Unit
            override fun onNewBitrate(bitrate:Long) { if(current==generation) measuredBitrate=bitrate }
        }).apply {
            setLogs(false); resizeCache(128); setAudioInfo(48000,false)
            setVideoResolution(settings.width,settings.height); setFps(settings.fps)
            setVideoInfo(ByteBuffer.wrap(requireNotNull(sps)),ByteBuffer.wrap(requireNotNull(pps)),null)
            shouldFailOnRead=true; setCheckServerAlive(true); tlsHostVerification=true
        }
        client=next
        next.connect(url)
    }
    private fun failure(reason:String,expectedGeneration:Int) = synchronized(lock) {
        if(expectedGeneration!=generation || !active || retry?.isActive==true) return@synchronized
        if(connected) {
            val lifetime=SystemClock.elapsedRealtime()-connectedAt
            if(lifetime<2000) immediateDisconnects++ else if(lifetime>=10_000) immediateDisconnects=0
        }
        connected=false; waitingKey=true
        if(RetryPolicy.authFailure(reason) || immediateDisconnects>=3) { fatal("发布被拒绝或连续立即断开，请检查账号、流权限、LAL key 与服务器"); return@synchronized }
        val delayMs=RetryPolicy.delayMillis(attempt++,Random.nextDouble())
        status(false,"连接中断，${delayMs/1000.0} 秒后重连")
        // Invalidate callbacks BEFORE disconnecting the old asynchronous RootEncoder client.
        generation++
        client?.let { previousDrops+=it.droppedAudioFrames+it.droppedVideoFrames; previousBytes+=it.bytesSend; it.disconnect() }
        client=null
        retry=scope.launch {
            delay(delayMs)
            synchronized(lock) { if(active) { reconnects++; connect() } }
        }
    }
    fun sendVideo(buffer:ByteBuffer,info:MediaCodec.BufferInfo) = synchronized(lock) {
        if(!active || !connected) return@synchronized
        if(waitingKey) {
            if(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME==0) return@synchronized
            waitingKey=false; timeline.keyframe(info.presentationTimeUs)
        }
        val pts=timeline.relative(info.presentationTimeUs)
        if(pts<0) return@synchronized
        videoInfo.set(info.offset,info.size,pts,info.flags)
        client?.sendVideo(buffer,videoInfo)
    }
    fun sendAudio(buffer:ByteBuffer,info:MediaCodec.BufferInfo) = synchronized(lock) {
        if(!active || !connected || waitingKey) return@synchronized
        val pts=timeline.relative(info.presentationTimeUs)
        if(pts<0) return@synchronized
        audioInfo.set(info.offset,info.size,pts,info.flags)
        client?.sendAudio(buffer,audioInfo)
    }
    fun clearCongestion() = synchronized(lock) {
        client?.let { previousDrops+=it.getItemsInCache(); it.clearCache() }
        waitingKey=true; keyframe()
    }
    fun close() = synchronized(lock) {
        active=false; connected=false; generation++; scope.cancel(); client?.disconnect(); client=null; url=""; sps=null; pps=null
    }
}
