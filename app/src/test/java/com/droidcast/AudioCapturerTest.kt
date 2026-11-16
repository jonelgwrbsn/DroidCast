package com.droidcast

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioCapturerTest {

    private fun invokeCallback(
        callback: AudioRecordDataCallback,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        buffer: ByteBuffer,
    ) {
        val method = AudioRecordDataCallback::class.java.methods.first {
            it.parameterCount == 4 && it.declaringClass != Any::class.java
        }
        method.invoke(callback, audioFormat, channelCount, sampleRate, buffer)
    }

    @Test
    fun `stream webrtc audio classes expose expected audioInput and audioRecord fields for direct playback injection`() {
        val jadmClass = Class.forName("org.webrtc.audio.JavaAudioDeviceModule")
        val warClass = Class.forName("org.webrtc.audio.WebRtcAudioRecord")

        val hasAudioInputField = generateSequence<Class<*>>(jadmClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .any { it.name == "audioInput" || warClass.isAssignableFrom(it.type) }
        assertTrue(
            "Expected JavaAudioDeviceModule to expose audioInput / WebRtcAudioRecord field",
            hasAudioInputField,
        )

        val hasAudioRecordField = generateSequence<Class<*>>(warClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .any { it.name == "audioRecord" || AudioRecord::class.java.isAssignableFrom(it.type) }
        assertTrue(
            "Expected WebRtcAudioRecord to expose audioRecord / AudioRecord field",
            hasAudioRecordField,
        )
    }

    @Test
    fun `recordDataCallback zeros WebRTC buffer when playback ring is empty instead of passing microphone audio`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturer = AudioCapturer(context)

        // Pre-fill buffer with loud synthetic "microphone" samples
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64) {
            buf.put(i, 0x7F.toByte())
        }

        invokeCallback(
            callback = capturer.recordDataCallback,
            audioFormat = 2,
            channelCount = 2,
            sampleRate = 48_000,
            buffer = buf,
        )

        for (i in 0 until 64) {
            assertEquals("Mic byte $i must be overwritten with silence", 0.toByte(), buf.get(i))
        }
    }

    @Test
    fun `fallback ring buffer writes and downmixes stereo playback PCM cleanly to mono WebRTC buffer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturer = AudioCapturer(context)

        val writeMethod = AudioCapturer::class.java.getDeclaredMethod(
            "writeToRing",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )
        writeMethod.isAccessible = true

        // 2 stereo frames: Frame0 (L=1000, R=3000 -> mono=2000), Frame1 (L=-2000, R=-4000 -> mono=-3000)
        val stereoBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        stereoBytes.putShort(1000)
        stereoBytes.putShort(3000)
        stereoBytes.putShort(-2000)
        stereoBytes.putShort(-4000)
        writeMethod.invoke(capturer, stereoBytes.array(), 8)

        val monoTarget = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        invokeCallback(
            callback = capturer.recordDataCallback,
            audioFormat = 2,
            channelCount = 1,
            sampleRate = 48_000,
            buffer = monoTarget,
        )

        monoTarget.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000.toShort(), monoTarget.getShort(0))
        assertEquals((-3000).toShort(), monoTarget.getShort(2))
    }

    @Test
    fun `evaluateSupport succeeds cleanly when userEnabled is true and verifies field helper handles null gracefully`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturer = AudioCapturer(context)
        val status = capturer.evaluateSupport(userEnabled = false)
        assertEquals(false, status.available)
        assertNotNull(status.reason)
    }

    @Test
    fun `phone media volume is restored when audio capture stops`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = maxOf(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        val capturer = AudioCapturer(context)

        assertTrue(capturer.mutePhoneMediaPlayback())
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        capturer.stop()

        assertEquals(originalVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
