package com.wusui.rtmpcapture.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range

data class HardwareProfile(val cameraId: String, val codecName: String, val flash: Boolean, val fpsRange: Range<Int>, val sensorRotation: Int)

object Hardware {
    fun backCamera(context: Context): Pair<String, CameraCharacteristics> {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: manager.cameraIdList.firstOrNull() ?: error("没有可用摄像头")
        return id to manager.getCameraCharacteristics(id)
    }
    fun hasFlash(context: Context): Boolean = runCatching { backCamera(context).second.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)

    fun validate(context: Context, settings: CaptureSettings): HardwareProfile {
        require(settings.validationErrors().isEmpty()) { settings.validationErrors().joinToString("；") }
        val (id, camera) = backCamera(context)
        val sizes = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        require(sizes.any { it.width == settings.width && it.height == settings.height }) { "摄像头不支持 ${settings.width}×${settings.height} Surface 输出" }
        val ranges = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.filter { it.contains(settings.fps) }.minByOrNull { it.upper - it.lower }
            ?: error("摄像头不支持 ${settings.fps} fps")
        val minFrame = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputMinFrameDuration(SurfaceTexture::class.java, android.util.Size(settings.width, settings.height)) ?: 0
        require(minFrame == 0L || minFrame <= 1_000_000_000L / settings.fps) { "此分辨率无法达到目标帧率" }
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.contains("video/avc") && hardwareCodec(info) && runCatching {
                val caps = info.getCapabilitiesForType("video/avc")
                val video = requireNotNull(caps.videoCapabilities)
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && video.areSizeAndRateSupported(settings.width, settings.height, settings.fps.toDouble()) && video.bitrateRange.contains(settings.bitrate)
            }.getOrDefault(false)
        } ?: error("硬件 H.264 编码器不支持此分辨率、帧率或码率")
        return HardwareProfile(id, codec.name, camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true, range, camera.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
    }
    private fun hardwareCodec(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else {
        val name = info.name.lowercase()
        !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains("software")
    }
}
