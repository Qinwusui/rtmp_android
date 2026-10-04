package com.wusui.rtmpcapture.data

import com.wusui.rtmpcapture.capture.PublishAddress
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private class TokenRequest(val username:String,val password:String,val stream:String)
@Serializable private data class TokenResponse(val lalSecret:String)
class AuthDenied:Exception("认证失败：账号错误或无此流的发布权限")

class AuthClient(private val secrets:SecretStore) {
    // No logging plugin, disk HTTP cache or persisted tokens.
    private val client=HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }
        install(HttpTimeout) { requestTimeoutMillis=15_000; connectTimeoutMillis=10_000; socketTimeoutMillis=15_000 }
        followRedirects=false
    }
    suspend fun publishAddress(server:ServerConfig):String {
        PublishAddress.validate(server.rtmpUrl,server.stream,server.authUrl)?.let { throw IllegalArgumentException(it) }
        val password=try { secrets.decrypt(server.encryptedPassword) } catch(_:Exception) { throw IllegalStateException("密码无法解密，请重新保存服务器凭据") }
        val response=client.post(server.authUrl) { contentType(ContentType.Application.Json); setBody(TokenRequest(server.username,password,server.stream)) }
        if(response.status==HttpStatusCode.Unauthorized || response.status==HttpStatusCode.Forbidden) throw AuthDenied()
        check(response.status==HttpStatusCode.OK) { "认证服务返回 ${response.status.value}" }
        return PublishAddress.withSecret(server.rtmpUrl,response.body<TokenResponse>().lalSecret)
    }
    suspend fun queryStatus(url:String):String {
        require(url.startsWith("http://") || url.startsWith("https://"))
        val response=client.get(url)
        check(response.status==HttpStatusCode.OK) { "LAL 状态查询返回 ${response.status.value}" }
        // Summarize only status; never expose arbitrary response bodies or authentication params.
        val json=response.body<kotlinx.serialization.json.JsonObject>()
        return "LAL 状态：${json["error_code"]?:"已响应"}"
    }
}
