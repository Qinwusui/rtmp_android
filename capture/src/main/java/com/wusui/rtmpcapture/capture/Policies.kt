package com.wusui.rtmpcapture.capture

import java.net.URI
import java.time.LocalTime
import kotlin.math.min
import kotlin.math.roundToInt

enum class LightMode { AUTO, ON, OFF }
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class CaptureSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrate: Int = 3_000_000,
    val minBitrate: Int = 1_000_000,
    val adaptive: Boolean = true,
    val light: LightMode = LightMode.AUTO,
    val nightStart: Int = 18 * 60,
    val nightEnd: Int = 6 * 60,
    val corner: Corner = Corner.BOTTOM_RIGHT,
    val pipWidth: Float = .30f,
    val pipMaxHeight: Float = .40f,
) {
    fun validationErrors(): List<String> = buildList {
        if (width !in 160..3840 || height !in 160..3840 || width % 2 != 0 || height % 2 != 0) add("分辨率须为 160–3840 范围内的偶数")
        if (fps !in 1..60) add("帧率须在 1–60 fps 范围内")
        if (bitrate !in 128_000..20_000_000 || minBitrate !in 128_000..bitrate) add("码率须为 128kbps–20Mbps，最低码率不能超过目标码率")
        if (nightStart !in 0..1439 || nightEnd !in 0..1439) add("补光时间格式无效")
        if (pipWidth !in .1f.. .6f || pipMaxHeight !in .1f.. .4f) add("小窗宽度须为 10%–60%，高度上限为 10%–40%")
    }
}

object NightPolicy {
    fun enabled(mode: LightMode, time: LocalTime, start: Int = 1080, end: Int = 360): Boolean {
        val minute = time.hour * 60 + time.minute
        return when (mode) {
            LightMode.ON -> true
            LightMode.OFF -> false
            LightMode.AUTO -> if (start == end) true else if (start < end) minute in start until end else minute >= start || minute < end
        }
    }
}

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
object PipLayout {
    fun rect(settings: CaptureSettings, sourceWidth: Int, sourceHeight: Int): Rect {
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = min(settings.width * settings.pipWidth / sourceWidth, settings.height * settings.pipMaxHeight / sourceHeight)
        val w = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val right = settings.corner == Corner.TOP_RIGHT || settings.corner == Corner.BOTTOM_RIGHT
        val top = settings.corner == Corner.TOP_LEFT || settings.corner == Corner.TOP_RIGHT
        return Rect(if (right) settings.width - w else 0, if (top) settings.height - h else 0, w, h)
    }
    fun captureSize(settings: CaptureSettings, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val rect = rect(settings, sourceWidth, sourceHeight)
        val scale = min(1f, 640f / maxOf(rect.width, rect.height))
        return (rect.width * scale).roundToInt().coerceAtLeast(2) to (rect.height * scale).roundToInt().coerceAtLeast(2)
    }
}

object RetryPolicy {
    private val seconds = intArrayOf(1, 2, 4, 8, 16, 30)
    fun delayMillis(attempt: Int, jitter: Double): Long {
        require(attempt >= 0 && jitter in 0.0..1.0)
        return (seconds[min(attempt, seconds.lastIndex)] * 1000 * (.8 + .4 * jitter)).toLong()
    }
    fun authFailure(reason: String): Boolean = listOf("auth", "403", "rejected", "badname", "publish.failed").any { reason.contains(it, ignoreCase = true) }
}

/** Each new RTMP connection starts at zero; clearing backlog preserves the same timeline. */
class PublishTimeline {
    private var originUs = -1L
    fun reset() { originUs = -1L }
    fun keyframe(ptsUs: Long) { require(ptsUs >= 0); if (originUs < 0) originUs = ptsUs }
    fun relative(ptsUs: Long): Long = if (originUs < 0 || ptsUs < originUs) -1 else ptsUs - originUs
}

object PublishAddress {
    fun validate(rtmp: String, stream: String, auth: String): String? = runCatching {
        val uri = URI(rtmp)
        require(uri.scheme in listOf("rtmp", "rtmps") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(uri.path.trim('/').split('/').size >= 2 && uri.path.substringAfterLast('/') == stream)
        require(stream.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(uri.rawQuery == null) // tokens come from the authentication service only
        val authUri = URI(auth)
        require(authUri.scheme in listOf("http", "https") && !authUri.host.isNullOrBlank() && authUri.userInfo == null && authUri.fragment == null && authUri.rawQuery == null)
        null
    }.getOrElse { "请输入完整 RTMP 地址（末段须等于流名）与 HTTP(S) 认证地址，地址不能内嵌凭据或参数" }

    fun withSecret(rtmp: String, secret: String): String {
        require(secret.matches(Regex("[a-fA-F0-9]{32}")))
        require(URI(rtmp).rawQuery == null)
        return "$rtmp?lal_secret=$secret"
    }
}

data class CaptureStats(
    val running: Boolean = false,
    val connected: Boolean = false,
    val screen: Boolean = false,
    val fps: Double = 0.0,
    val bitrate: Long = 0,
    val targetBitrate: Int = 0,
    val dropped: Long = 0,
    val queue: Int = 0,
    val elapsedSeconds: Long = 0,
    val reconnects: Int = 0,
    val message: String = "待机",
)
