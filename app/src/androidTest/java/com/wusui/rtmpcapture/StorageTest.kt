package com.wusui.rtmpcapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.wusui.rtmpcapture.data.*
import com.wusui.rtmpcapture.capture.Hardware
import com.wusui.rtmpcapture.capture.CaptureSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun mainActivityLaunchesWithServiceBinding() {
        androidx.test.core.app.ActivityScenario.launch(com.wusui.rtmpcapture.ui.MainActivity::class.java).use { scenario ->
            scenario.onActivity { Assert.assertFalse(it.isFinishing) }
        }
    }
    @Test fun roomAndKeystoreSurviveRoundTrip()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val secret=SecretStore()
        val encrypted=secret.encrypt("round-trip-only")
        Assert.assertFalse(encrypted.contains("round-trip-only"))
        Assert.assertEquals("round-trip-only",SecretStore().decrypt(encrypted))
        val db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).build()
        try {
            val id=db.dao().save(ServerConfig(name="测试",rtmpUrl="rtmp://localhost/live/demo",stream="demo",authUrl="http://localhost:8081/v1/publish-token",username="tester",encryptedPassword=encrypted))
            Assert.assertEquals(encrypted,db.dao().server(id)?.encryptedPassword)
            val session=db.dao().begin(SessionSummary(serverName="测试",startedAt=1))
            db.dao().end(session,2,1,0,0,"完成")
            Assert.assertEquals("完成",db.dao().sessions().first().first().endReason)
        } finally { db.close() }
    }
    @Test fun defaultHardwareConfigurationIsSupported() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val profile=Hardware.validate(context,CaptureSettings())
        Assert.assertTrue(profile.codecName.isNotBlank())
    }
}
