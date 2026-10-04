package com.wusui.rtmpcapture.capture

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class PoliciesTest {
    @Test fun congestionRecoveryKeepsTimestampsAndReconnectStartsNewTimeline() {
        val clock=PublishTimeline()
        assertEquals(-1L,clock.relative(5_000_000))
        clock.keyframe(5_000_000)
        assertEquals(0L,clock.relative(5_000_000))
        assertEquals(-1L,clock.relative(4_999_999))
        clock.keyframe(6_000_000) // Keyframe after clearing congestion in the same connection.
        assertEquals(1_000_000L,clock.relative(6_000_000))
        clock.reset() // A replacement transport has its own RTMP timestamp origin.
        clock.keyframe(8_000_000)
        assertEquals(0L,clock.relative(8_000_000))
        assertEquals(21_000L,clock.relative(8_021_000))
    }
    @Test fun nightCrossesMidnightAndUsesExclusiveEnd() {
        assertTrue(NightPolicy.enabled(LightMode.AUTO, LocalTime.of(23, 59)))
        assertTrue(NightPolicy.enabled(LightMode.AUTO, LocalTime.MIDNIGHT))
        assertTrue(NightPolicy.enabled(LightMode.AUTO, LocalTime.of(18, 0)))
        assertFalse(NightPolicy.enabled(LightMode.AUTO, LocalTime.of(6, 0)))
        assertFalse(NightPolicy.enabled(LightMode.OFF, LocalTime.MIDNIGHT))
        assertTrue(NightPolicy.enabled(LightMode.ON, LocalTime.NOON))
        assertTrue(NightPolicy.enabled(LightMode.AUTO, LocalTime.NOON, 600, 840))
    }
    @Test fun invalidSettingsRejected() {
        assertTrue(CaptureSettings().validationErrors().isEmpty())
        assertFalse(CaptureSettings(width = 1279, fps = 0, minBitrate = 4_000_000, pipMaxHeight = .5f).validationErrors().isEmpty())
    }
    @Test fun portraitScreenFitsBoundsAndKeepsAspect() {
        val s = CaptureSettings()
        val rect = PipLayout.rect(s, 1080, 2400)
        assertTrue(rect.width <= 384 && rect.height <= 288)
        assertEquals(1080.0 / 2400, rect.width.toDouble() / rect.height, .005)
        assertEquals(1280, rect.x + rect.width)
        assertEquals(0, rect.y)
        val capture = PipLayout.captureSize(s.copy(width = 3840, height = 2160), 2400, 1080)
        assertTrue(maxOf(capture.first, capture.second) <= 640)
    }
    @Test fun retryBoundedAndSecretCannotInjectQuery() {
        assertEquals(1000, RetryPolicy.delayMillis(0, .5))
        assertEquals(30000, RetryPolicy.delayMillis(100, .5))
        assertTrue(RetryPolicy.authFailure("NetStream.Publish.BadName"))
        assertNull(PublishAddress.validate("rtmp://192.168.1.1/live/demo", "demo", "http://192.168.1.1:8081/v1/publish-token"))
        assertNotNull(PublishAddress.validate("rtmp://user:pass@host/live/demo", "demo", "https://host"))
        assertNotNull(PublishAddress.validate("rtmp://host/live/other", "demo", "https://host"))
        assertNotNull(PublishAddress.validate("rtmp://host/live/demo?lal_secret=x", "demo", "https://host"))
        assertEquals("rtmp://host/live/demo?lal_secret=${"a".repeat(32)}", PublishAddress.withSecret("rtmp://host/live/demo", "a".repeat(32)))
    }
}
