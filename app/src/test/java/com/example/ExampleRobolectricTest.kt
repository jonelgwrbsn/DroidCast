package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droidcast.InboundSignalingMessage
import com.droidcast.ProtocolCodec
import com.droidcast.QualityPreset
import com.droidcast.ScreenCapturer
import com.droidcast.SignalingClient
import com.droidcast.WebRTCManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context matches DroidCast`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DroidCast", appName)
    }

    @Test
    fun `protocol codec parses session_created with pairing code`() {
        val raw = """
            {
              "type": "session_created",
              "sessionId": "sess-42",
              "senderId": "android-1",
              "receiverId": "laptop-1",
              "senderName": "Pixel 9",
              "receiverName": "My Laptop",
              "pairingCode": "482913",
              "pairingExpiresAt": 1999999999999,
              "authorized": false,
              "quality": "1080p60"
            }
        """.trimIndent()

        val parsed = ProtocolCodec.parseInbound(raw)
        assertTrue(parsed is InboundSignalingMessage.SessionCreated)
        val session = (parsed as InboundSignalingMessage.SessionCreated).dto
        assertEquals("482913", session.pairingCode)
        assertEquals(QualityPreset.P1080_60, QualityPreset.fromWire(session.quality))
    }

    @Test
    fun `signaling url normalization handles host port and schemes`() {
        assertEquals("ws://192.168.1.100:3000", SignalingClient.normalizeWsUrl("192.168.1.100:3000"))
        assertEquals("ws://192.168.1.100:3000", SignalingClient.normalizeWsUrl("192.168.1.100"))
        assertEquals("ws://192.168.1.45:3000", SignalingClient.normalizeWsUrl(" 192.168.1.45 : 3000 "))
        assertEquals("ws://192.168.1.45:3000", SignalingClient.normalizeWsUrl("http://192.168.1.45:3000/install"))
        assertEquals("ws://localhost:3000", SignalingClient.normalizeWsUrl("http://localhost:3000"))
        assertEquals("wss://cast.local:3443", SignalingClient.normalizeWsUrl("wss://cast.local:3443"))
        assertEquals("", com.droidcast.CastUiState().manualAddressInput)
    }

    @Test
    fun `tuneSdpForCasting preserves audio and tunes video bitrate`() {
        val sampleSdp = listOf(
            "v=0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 H264/90000",
            "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
        ).joinToString("\r\n")

        val tuned = WebRTCManager.tuneSdpForCasting(sampleSdp)
        assertTrue(tuned.contains("m=audio"))
        assertTrue(tuned.contains("opus/48000"))
        assertTrue(tuned.contains("b=AS:24000"))
        assertTrue(tuned.contains("x-google-min-bitrate=3000"))
        assertTrue(tuned.contains("x-google-start-bitrate=8000"))
        assertTrue(tuned.contains("x-google-max-bitrate=24000"))
    }

    @Test
    fun `screen capturer scales landscape and portrait bounds with even dimensions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturer = ScreenCapturer(context)

        val nativeLandscape = capturer.computeCaptureDimensionsForBounds(2400, 1080, QualityPreset.NATIVE)
        assertEquals(2400, nativeLandscape.width)
        assertEquals(1080, nativeLandscape.height)
        assertEquals(60, nativeLandscape.fps)

        val adaptiveQhdLandscape = capturer.computeCaptureDimensionsForBounds(3120, 1440, QualityPreset.ADAPTIVE)
        assertEquals(3120, adaptiveQhdLandscape.width)
        assertEquals(1440, adaptiveQhdLandscape.height)
        assertEquals(60, adaptiveQhdLandscape.fps)

        val portrait = capturer.computeCaptureDimensionsForBounds(1440, 3120, QualityPreset.P1080_60)
        assertTrue(portrait.width % 2 == 0 && portrait.height % 2 == 0)
        assertTrue(portrait.height <= 1920 && portrait.width <= 1080)
        assertEquals(60, portrait.fps)

        val landscape = capturer.computeCaptureDimensionsForBounds(3120, 1440, QualityPreset.P720_30)
        assertTrue(landscape.width % 2 == 0 && landscape.height % 2 == 0)
        assertTrue(landscape.width <= 1280 && landscape.height <= 720)
        assertEquals(30, landscape.fps)

        val lowTier = capturer.computeCaptureDimensionsForBounds(
            1440,
            3120,
            com.droidcast.AdaptiveTier.P360_24.maxWidth,
            com.droidcast.AdaptiveTier.P360_24.maxHeight,
            com.droidcast.AdaptiveTier.P360_24.fps,
        )
        assertTrue(lowTier.width % 2 == 0 && lowTier.height % 2 == 0)
        assertTrue(lowTier.height <= 640 && lowTier.width <= 360)
        assertEquals(24, lowTier.fps)
    }

    @Test
    fun `connectManualAddress accepts typed IP port without triggering INVALID_ADDRESS`() {
        com.droidcast.CastSessionCoordinator.connectManualAddress("   ")
        assertEquals("INVALID_ADDRESS", com.droidcast.CastSessionCoordinator.uiState.value.error?.code)

        com.droidcast.CastSessionCoordinator.connectManualAddress("192.168.1.45:3000")
        val updated = com.droidcast.CastSessionCoordinator.uiState.value
        assertEquals("192.168.1.45:3000", updated.manualAddressInput)
        assertEquals("192.168.1.45:3000", updated.connectedServerAddress)
        assertEquals(null, updated.error)
    }
}
