package com.wusui.rtmpcapture

import android.media.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodecProbeTest {
    @Test fun actualHardwareSurfaceConfiguration() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val profile=com.wusui.rtmpcapture.capture.Hardware.validate(context,com.wusui.rtmpcapture.capture.CaptureSettings())
        val codec=MediaCodec.createByCodecName(profile.codecName)
        try {
            val format=MediaFormat.createVideoFormat("video/avc",1280,720).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,3_000_000); setInteger(MediaFormat.KEY_FRAME_RATE,30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface=codec.createInputSurface()
            try { codec.start(); codec.stop() } finally { surface.release() }
        } finally { codec.release() }
    }
}
