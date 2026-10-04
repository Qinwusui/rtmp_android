package com.wusui.auth

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class AuthTest {
    private val config=AuthConfig("tester","test-password",setOf("demo"),"test-key")
    @Test fun signatureMatchesLalNativeRule() {
        assertEquals("5d41402abc4b2a76b9719d911017c592",lalSecret("hel","lo"))
        assertNotEquals(lalSecret("test-key","demo"),lalSecret("test-key","other"))
    }
    @Test fun accountsAndStreamPermissionsEnforced()=testApplication {
        application { authModule(config) }
        val jsonClient=createClient { install(ContentNegotiation) { json() } }
        suspend fun request(user:String,password:String,stream:String)=jsonClient.post("/v1/publish-token") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"$password","stream":"$stream"}""")
        }
        val success=request("tester","test-password","demo")
        assertEquals(HttpStatusCode.OK,success.status)
        assertEquals(lalSecret("test-key","demo"),success.body<TokenResponse>().lalSecret)
        assertEquals("no-store",success.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Unauthorized,request("tester","bad","demo").status)
        assertEquals(HttpStatusCode.Unauthorized,request("unknown","test-password","demo").status)
        assertEquals(HttpStatusCode.Forbidden,request("tester","test-password","other").status)
        assertEquals(HttpStatusCode.BadRequest,jsonClient.post("/v1/publish-token") { contentType(ContentType.Application.Json); setBody("{}") }.status)
    }
    @Test fun configCannotRunWithMissingSecrets() {
        assertThrows(IllegalStateException::class.java) { AuthConfig.fromEnvironment(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { AuthConfig("a","b",setOf("bad/name"),"key") }
        assertFalse(config.toString().contains("test-password"))
    }
}
