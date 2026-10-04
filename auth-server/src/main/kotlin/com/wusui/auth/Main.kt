package com.wusui.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.security.MessageDigest

data class AuthConfig(val username:String,val password:String,val streams:Set<String>,val lalKey:String) {
    init {
        require(username.isNotBlank() && password.isNotEmpty() && lalKey.isNotEmpty()) { "必须配置账号、密码与 LAL key" }
        require(streams.isNotEmpty() && streams.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "允许的流名配置无效" }
    }
    companion object {
        fun fromEnvironment(env:Map<String,String> = System.getenv()):AuthConfig {
            fun required(name:String)=env[name]?.takeIf { it.isNotBlank() }?:error("缺少环境变量 $name")
            return AuthConfig(required("AUTH_USERNAME"),required("AUTH_PASSWORD"),required("AUTH_STREAMS").split(',').map { it.trim() }.toSet(),required("LAL_KEY"))
        }
    }
    override fun toString()="AuthConfig([redacted])"
}
@Serializable private class TokenRequest(val username:String,val password:String,val stream:String)
@Serializable data class TokenResponse(val lalSecret:String)
@Serializable private data class ErrorResponse(val error:String)

/** LAL SimpleAuthCalcSecret: lowercase MD5 of UTF-8 key + stream, with no delimiter. */
fun lalSecret(key:String,stream:String):String = MessageDigest.getInstance("MD5").digest((key+stream).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun same(a:String,b:String):Boolean {
    fun hash(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(hash(a),hash(b))
}

fun Application.authModule(config:AuthConfig) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<SerializationException> { call,_ -> call.respond(HttpStatusCode.BadRequest,ErrorResponse("invalid_request")) }
        exception<io.ktor.server.plugins.BadRequestException> { call,_ -> call.respond(HttpStatusCode.BadRequest,ErrorResponse("invalid_request")) }
        exception<Throwable> { call,_ -> call.respond(HttpStatusCode.InternalServerError,ErrorResponse("internal_error")) }
    }
    routing {
        get("/health") { call.respondText("ok") }
        post("/v1/publish-token") {
            call.response.header(HttpHeaders.CacheControl,"no-store")
            val length=call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if(length==null || length !in 1..4096) {
                call.respond(HttpStatusCode.BadRequest,ErrorResponse("body_length_required_max_4096")); return@post
            }
            val request=call.receive<TokenRequest>()
            // Evaluate both comparisons, without logging request bodies or credentials.
            val validUser=same(request.username,config.username)
            val validPassword=same(request.password,config.password)
            if(!(validUser and validPassword)) {
                call.respond(HttpStatusCode.Unauthorized,ErrorResponse("invalid_credentials")); return@post
            }
            if(request.stream !in config.streams) {
                call.respond(HttpStatusCode.Forbidden,ErrorResponse("stream_not_allowed")); return@post
            }
            call.respond(TokenResponse(lalSecret(config.lalKey,request.stream)))
        }
    }
}
fun main() {
    val config=AuthConfig.fromEnvironment()
    val port=System.getenv("AUTH_PORT")?.toIntOrNull()?:8081
    val host=System.getenv("AUTH_BIND")?:"127.0.0.1"
    embeddedServer(Netty,port=port,host=host) { authModule(config) }.start(wait=true)
}
