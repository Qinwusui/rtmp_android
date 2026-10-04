package com.wusui.rtmpcapture.capture

import android.annotation.SuppressLint
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class VideoEncoder(private val settings:CaptureSettings,codecName:String,private val transport:()->RtmpTransport?,private val fatal:(String)->Unit) {
    private val codec=MediaCodec.createByCodecName(codecName)
    private var surface:Surface?=null
    @Volatile private var running=false
    private var thread:Thread?=null
    val encoded=AtomicLong()
    fun start():Surface {
        val format=MediaFormat.createVideoFormat("video/avc",settings.width,settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,settings.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
            // Some vendor codecs reject optional profile/B-frame keys despite advertising them.
            // Keep the mandatory Surface format minimal and let the driver select its AVC profile.
        }
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input=codec.createInputSurface().also { surface=it }
        codec.start(); running=true
        thread=Thread({
            val info=MediaCodec.BufferInfo()
            try {
                while(running) {
                    when(val index=codec.dequeueOutputBuffer(info,10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output=codec.outputFormat
                            transport()?.videoFormat(requireNotNull(output.getByteBuffer("csd-0")),requireNotNull(output.getByteBuffer("csd-1")))
                        }
                        else -> if(index>=0) {
                            try {
                                if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                                    codec.getOutputBuffer(index)?.let { transport()?.sendVideo(it,info) }; encoded.incrementAndGet()
                                }
                            } finally { codec.releaseOutputBuffer(index,false) }
                        }
                    }
                }
            } catch(_:Exception) { if(running) fatal("硬件视频编码失败") }
        },"capture-h264").apply { start() }
        return input
    }
    fun keyframe() { if(running) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) }) } }
    fun bitrate(value:Int) { if(running) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,value) }) }.onFailure { fatal("编码器不支持动态码率") } }
    fun close() { running=false; thread?.join(2000); runCatching { codec.stop() }; runCatching { codec.release() }; surface?.release(); surface=null }
}

class AudioEncoder(private val originNs:Long,private val output:(ByteBuffer,MediaCodec.BufferInfo)->Unit,private val fatal:(String)->Unit) {
    private val codec=MediaCodec.createEncoderByType("audio/mp4a-latm")
    private var recorder:AudioRecord?=null
    private var thread:Thread?=null
    @Volatile private var running=false
    @SuppressLint("MissingPermission")
    fun start() {
        val format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,64000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,3840)
        }
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val minSize=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(minSize>0) { "麦克风不支持 48kHz 单声道" }
        val audio=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minSize*2,7680)).build().also { recorder=it }
        check(audio.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        audio.startRecording(); running=true
        thread=Thread({
            val info=MediaCodec.BufferInfo()
            val timestamp=AudioTimestamp()
            var sampleCount=0L
            var baseNs=0L
            var lastPts=-1L
            try {
                while(running) {
                    val input=codec.dequeueInputBuffer(10_000)
                    if(input>=0) {
                        val buffer=requireNotNull(codec.getInputBuffer(input)); buffer.clear()
                        val size=audio.read(buffer,minOf(buffer.capacity(),1920),AudioRecord.READ_BLOCKING)
                        if(!running) break
                        check(size>0) { "麦克风采集已中断" }
                        val samples=size/2
                        if(baseNs==0L) baseNs=SystemClock.elapsedRealtimeNanos()-samples*1_000_000_000L/48000
                        val captureNs=if(audio.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_BOOTTIME)==AudioRecord.SUCCESS) timestamp.nanoTime+(sampleCount-timestamp.framePosition)*1_000_000_000L/48000 else baseNs+sampleCount*1_000_000_000L/48000
                        val pts=maxOf((captureNs-originNs)/1000,lastPts+1,0)
                        lastPts=pts; sampleCount+=samples
                        codec.queueInputBuffer(input,0,size,pts,0)
                    }
                    while(running) {
                        val index=codec.dequeueOutputBuffer(info,0)
                        if(index<0) break
                        try { if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) codec.getOutputBuffer(index)?.let { output(it,info) } }
                        finally { codec.releaseOutputBuffer(index,false) }
                    }
                }
            } catch(_:Exception) { if(running) fatal("麦克风权限撤销、资源占用或音频编码失败") }
        },"capture-aac").apply { start() }
    }
    fun close() { running=false; runCatching { recorder?.stop() }; thread?.join(2000); runCatching { recorder?.release() }; recorder=null; runCatching { codec.stop() }; runCatching { codec.release() } }
}
