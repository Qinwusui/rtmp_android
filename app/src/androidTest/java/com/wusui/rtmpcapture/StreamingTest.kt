package com.wusui.rtmpcapture

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wusui.rtmpcapture.data.CaptureDao
import com.wusui.rtmpcapture.service.CaptureService
import com.wusui.rtmpcapture.service.CaptureState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in real camera/microphone/RTMP smoke test. Requires already granted device permissions. */
@RunWith(AndroidJUnit4::class)
class StreamingTest {
    @Test fun lockedSessionSurvivesWakeAndNetworkLoss()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("networkScenario")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val state=GlobalContext.get().get<CaptureState>()
        val server=GlobalContext.get().get<CaptureDao>().servers().first().first { it.name=="本机验证 LAL" }
        fun key(code:Int) { instrumentation.uiAutomation.executeShellCommand("input keyevent $code").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        try {
            ContextCompat.startForegroundService(context,Intent(context,CaptureService::class.java).setAction(CaptureService.START).putExtra("server",server.id))
            withTimeout(30_000) { state.stats.first { it.connected && it.fps>20 } }
            key(223); delay(8000)
            Assert.assertTrue(state.stats.value.running && state.stats.value.connected)
            key(224); delay(15_000)
            Assert.assertTrue("Stream stopped after waking",state.stats.value.running)
            Assert.assertFalse("Camera did not recover after system use",state.stats.value.message.contains("保留最后画面"))
            // The host restarts its own LAL after ~30 seconds of received media.
            withTimeout(60_000) { state.stats.first { it.reconnects>0 && it.connected && it.fps>20 } }
            delay(3000)
            Assert.assertTrue(state.stats.value.running && state.stats.value.connected)
            Assert.assertTrue(state.stats.value.fps in 27.0..33.0)
        } finally {
            context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.STOP))
            withTimeout(15_000) { state.stats.first { !it.running } }
        }
    }
    @Test fun repeatedStartsKeepHardwareAndTransportHealthy()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localBackend")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dao=GlobalContext.get().get<CaptureDao>()
        val state=GlobalContext.get().get<CaptureState>()
        val server=dao.servers().first().firstOrNull { it.name=="本机验证 LAL" }?:error("Run PackagedIntegrationTest to configure the local backend first")
        try {
            repeat(3) { iteration ->
                ContextCompat.startForegroundService(context,Intent(context,CaptureService::class.java).setAction(CaptureService.START).putExtra("server",server.id))
                withTimeout(30_000) { state.stats.first { it.connected && it.fps>20 } }
                delay(5000)
                val stats=state.stats.value
                Assert.assertTrue(stats.running && stats.connected)
                Assert.assertTrue("Encoded fps ${stats.fps}",stats.fps in 27.0..33.0)
                Assert.assertTrue(stats.queue<=128)
                println("Iteration $iteration: fps=${stats.fps}, bitrate=${stats.bitrate}, dropped=${stats.dropped}")
                context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.STOP))
                withTimeout(15_000) { state.stats.first { !it.running } }
                delay(1000)
            }
        } finally {
            context.startService(Intent(context,CaptureService::class.java).setAction(CaptureService.STOP))
        }
    }
}
