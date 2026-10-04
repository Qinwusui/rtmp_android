package com.wusui.rtmpcapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wusui.rtmpcapture.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Uses the opt-in local test backend via adb reverse; never ships test credentials in app code. */
@RunWith(AndroidJUnit4::class)
class PackagedIntegrationTest {
    @Test fun authenticationAndSerializationInPackagedApp()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localBackend")=="true")
        val secrets=SecretStore()
        val auth=AuthClient(secrets)
        val server=ServerConfig(name="本机验证 LAL",rtmpUrl="rtmp://127.0.0.1:1935/live/demo",stream="demo",authUrl="http://127.0.0.1:8081/v1/publish-token",username="tester",encryptedPassword=secrets.encrypt("test-password"),monitorUrl="http://127.0.0.1:8080/live/demo.flv",statusUrl="http://127.0.0.1:8083/api/stat/all_group")
        Assert.assertTrue(auth.publishAddress(server).contains("?lal_secret="))
        try { auth.publishAddress(server.copy(encryptedPassword=secrets.encrypt("wrong"))); Assert.fail("Bad credentials accepted") } catch(_:AuthDenied) { }
        try { auth.publishAddress(server.copy(rtmpUrl="rtmp://127.0.0.1/live/denied",stream="denied")); Assert.fail("Unauthorized stream accepted") } catch(_:AuthDenied) { }
        val dao=GlobalContext.get().get<CaptureDao>()
        val existing=dao.servers().first().firstOrNull { it.name==server.name }
        dao.save(server.copy(id=existing?.id?:0))
        Assert.assertTrue(auth.queryStatus(server.statusUrl).contains("LAL 状态"))
    }
}
