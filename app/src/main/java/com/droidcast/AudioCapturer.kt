package com.droidcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class AudioCaptureStatus(
    val available: Boolean,
    val reason: String,
)

class AudioCapturer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val running = AtomicBoolean(false)
    private val directCaptureActive = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var playbackRecord: AudioRecord? = null
    @Volatile
    private var activeProjection: MediaProjection? = null

    private val lock = Any()
    // 48 kHz * 2 channels * 2 bytes = 192,000 bytes/sec (1920 bytes per 10ms)
    // Cap buffer at ~40ms (7,680 bytes) to keep A/V sync tight with minimal latency.
    private val maxBufferedBytes = 7_680
    private val ringBuffer = ByteArray(19_200)
    private var readPos = 0
    private var writePos = 0
    private var bufferedBytes = 0
    private var previousMusicVolume: Int? = null

    fun evaluateSupport(userEnabled: Boolean): AudioCaptureStatus {
        if (!userEnabled) {
            return AudioCaptureStatus(false, "System audio disabled in settings")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AudioCaptureStatus(
                false,
                "System audio capture requires Android 10 (API 29) or higher"
            )
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return AudioCaptureStatus(
                false,
                "Audio permission not granted; casting video only"
            )
        }
        return AudioCaptureStatus(true, "AudioPlaybackCapture ready")
    }

    fun attachMediaProjection(mediaProjection: MediaProjection?) {
        activeProjection = mediaProjection
    }

    /**
     * Fallback callback only used if WebRtcAudioRecord direct AudioRecord replacement fails.
     * When direct AudioPlaybackCapture replacement is active, this is a no-op so WebRTC's
     * buffer is passed through untouched without microphone interference or extra copies.
     */
    val recordDataCallback = AudioRecordDataCallback { _, channelCount, sampleRate, byteBuffer ->
        if (directCaptureActive.get()) return@AudioRecordDataCallback
        fillFromPlaybackBuffer(byteBuffer, channelCount, sampleRate)
    }

    fun prepare(mediaProjection: MediaProjection?, userEnabled: Boolean): AudioCaptureStatus {
        stop()
        val support = evaluateSupport(userEnabled)
        if (!support.available) {
            DroidLog.i(DroidLog.TAG_AUDIO, "Audio unavailable: ${support.reason}")
            return support
        }
        if (mediaProjection == null) {
            val status = AudioCaptureStatus(false, "MediaProjection unavailable for audio capture")
            DroidLog.w(DroidLog.TAG_AUDIO, status.reason)
            return status
        }
        activeProjection = mediaProjection
        return AudioCaptureStatus(true, "System audio capture ready")
    }

    /**
     * Replaces WebRtcAudioRecord's internal microphone AudioRecord directly with an
     * AudioPlaybackCapture AudioRecord matching WebRTC's initialized sample rate & channels.
     * Must be invoked inside WebRtcAudioRecord's onWebRtcAudioRecordStart() before its read loop.
     */
    @SuppressLint("MissingPermission")
    fun swapIntoWebRtcAudioModule(audioDeviceModule: Any?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val projection = activeProjection ?: return false
        if (audioDeviceModule == null) return false

        return try {
            val webRtcAudioRecord = findWebRtcAudioRecord(audioDeviceModule) ?: return false
            val audioRecordField = findAudioRecordField(webRtcAudioRecord) ?: return false
            val currentRecord = audioRecordField.get(webRtcAudioRecord) as? AudioRecord

            if (currentRecord != null && currentRecord === playbackRecord) {
                directCaptureActive.set(true)
                return true
            }

            val targetSampleRate = currentRecord?.sampleRate?.takeIf { it > 0 } ?: 48_000
            val targetChannels = currentRecord?.channelCount?.takeIf { it in 1..2 } ?: 2

            val newPlaybackRecord = buildPlaybackAudioRecordQ(
                mediaProjection = projection,
                sampleRate = targetSampleRate,
                channelCount = targetChannels,
            ) ?: return false

            newPlaybackRecord.startRecording()
            if (newPlaybackRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    newPlaybackRecord.release()
                } catch (_: Exception) {
                }
                DroidLog.w(DroidLog.TAG_AUDIO, "Playback AudioRecord failed to enter recording state")
                return false
            }

            // Stop and release any fallback thread before switching WebRTC onto direct capture
            stopFallbackThreadOnly()

            audioRecordField.set(webRtcAudioRecord, newPlaybackRecord)
            val previousPlayback = playbackRecord
            playbackRecord = newPlaybackRecord
            directCaptureActive.set(true)

            if (currentRecord != null && currentRecord !== previousPlayback) {
                try {
                    if (currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        currentRecord.stop()
                    }
                } catch (_: Exception) {
                }
                try {
                    currentRecord.release()
                } catch (_: Exception) {
                }
            }
            if (previousPlayback != null && previousPlayback !== newPlaybackRecord) {
                try {
                    if (previousPlayback.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        previousPlayback.stop()
                    }
                } catch (_: Exception) {
                }
                try {
                    previousPlayback.release()
                } catch (_: Exception) {
                }
            }

            DroidLog.i(
                DroidLog.TAG_AUDIO,
                "Replaced WebRTC mic with direct AudioPlaybackCapture (${targetSampleRate}Hz, ${targetChannels}ch PCM16)"
            )
            true
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_AUDIO, "Direct AudioPlaybackCapture attach failed; using buffer fallback: ${e.message}")
            startFallbackCaptureIfNeeded()
            false
        }
    }

    fun startFallbackCaptureIfNeeded(): AudioCaptureStatus {
        if (directCaptureActive.get() || running.get()) {
            return AudioCaptureStatus(true, "System audio capture active")
        }
        val projection = activeProjection
            ?: return AudioCaptureStatus(false, "MediaProjection unavailable for audio capture")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AudioCaptureStatus(false, "Android 10+ required")
        }
        return startFallbackPlaybackCaptureQ(projection)
    }

    fun mutePhoneMediaPlayback(): Boolean {
        if (previousMusicVolume != null) return true
        return try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            previousMusicVolume = currentVolume
            if (currentVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
            val muted = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            if (!muted) previousMusicVolume = null
            muted
        } catch (e: Exception) {
            previousMusicVolume = null
            DroidLog.w(DroidLog.TAG_AUDIO, "Unable to mute phone media playback: ${e.message}")
            false
        }
    }

    private fun restorePhoneMediaPlayback() {
        val volumeToRestore = previousMusicVolume ?: return
        previousMusicVolume = null
        try {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 && volumeToRestore > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeToRestore, 0)
            }
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_AUDIO, "Unable to restore phone media volume: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildPlaybackAudioRecordQ(
        mediaProjection: MediaProjection,
        sampleRate: Int,
        channelCount: Int,
    ): AudioRecord? {
        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(4096)

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            DroidLog.w(DroidLog.TAG_AUDIO, "AudioPlaybackCapture failed to initialize (${sampleRate}Hz, ${channelCount}ch)")
            return null
        }
        return record
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startFallbackPlaybackCaptureQ(mediaProjection: MediaProjection): AudioCaptureStatus {
        return try {
            val audioRecord = buildPlaybackAudioRecordQ(
                mediaProjection = mediaProjection,
                sampleRate = 48_000,
                channelCount = 2,
            ) ?: return AudioCaptureStatus(false, "AudioPlaybackCapture failed to initialize on this device")

            synchronized(lock) {
                readPos = 0
                writePos = 0
                bufferedBytes = 0
            }

            playbackRecord = audioRecord
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.release()
                playbackRecord = null
                return AudioCaptureStatus(false, "AudioPlaybackCapture did not start recording")
            }
            running.set(true)

            captureThread = thread(start = true, name = "DroidCastAudioCapture") {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val chunk = ByteArray(1920) // 10ms stereo 48kHz 16-bit
                while (running.get()) {
                    val read = audioRecord.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        writeToRing(chunk, read)
                    } else if (read < 0) {
                        running.set(false)
                        DroidLog.w(DroidLog.TAG_AUDIO, "AudioPlaybackCapture read failed: $read")
                        break
                    }
                }
            }

            DroidLog.i(DroidLog.TAG_AUDIO, "Started fallback AudioPlaybackCapture (48kHz stereo PCM16)")
            AudioCaptureStatus(true, "System audio capture active")
        } catch (e: SecurityException) {
            DroidLog.w(DroidLog.TAG_AUDIO, "Audio capture permission/security error: ${e.message}")
            AudioCaptureStatus(false, "Audio permission denied by system")
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_AUDIO, "Audio capture unavailable: ${e.message}")
            AudioCaptureStatus(false, e.message ?: "System audio capture unsupported")
        }
    }

    private fun writeToRing(data: ByteArray, length: Int) {
        synchronized(lock) {
            for (i in 0 until length) {
                ringBuffer[writePos] = data[i]
                writePos = (writePos + 1) % ringBuffer.size
                if (bufferedBytes == ringBuffer.size) {
                    readPos = (readPos + 1) % ringBuffer.size
                } else {
                    bufferedBytes += 1
                }
            }
            if (bufferedBytes > maxBufferedBytes) {
                val excess = bufferedBytes - maxBufferedBytes
                readPos = (readPos + excess) % ringBuffer.size
                bufferedBytes = maxBufferedBytes
            }
        }
    }

    private fun fillFromPlaybackBuffer(target: ByteBuffer, channelCount: Int, sampleRate: Int) {
        if (target.isReadOnly) return
        val needed = target.capacity()
        target.clear()
        val temp = ByteArray(needed)

        // Fallback capture produces stereo 16-bit PCM (4 bytes/frame). If WebRTC requested mono, downmix 2ch -> 1ch.
        val stereoNeeded = if (channelCount == 1) needed * 2 else needed
        val stereoTemp = if (channelCount == 1) ByteArray(stereoNeeded) else temp

        synchronized(lock) {
            val available = minOf(stereoNeeded, bufferedBytes)
            for (i in 0 until available) {
                stereoTemp[i] = ringBuffer[readPos]
                readPos = (readPos + 1) % ringBuffer.size
            }
            bufferedBytes -= available
        }

        if (channelCount == 1) {
            var outIdx = 0
            var inIdx = 0
            while (inIdx + 3 < stereoNeeded && outIdx + 1 < needed) {
                val left = ((stereoTemp[inIdx + 1].toInt() shl 8) or (stereoTemp[inIdx].toInt() and 0xFF)).toShort()
                val right = ((stereoTemp[inIdx + 3].toInt() shl 8) or (stereoTemp[inIdx + 2].toInt() and 0xFF)).toShort()
                val mono = ((left.toInt() + right.toInt()) / 2).toShort()
                temp[outIdx] = (mono.toInt() and 0xFF).toByte()
                temp[outIdx + 1] = ((mono.toInt() ushr 8) and 0xFF).toByte()
                outIdx += 2
                inIdx += 4
            }
        }

        target.put(temp)
        target.flip()
    }

    private fun stopFallbackThreadOnly() {
        running.set(false)
        try {
            captureThread?.join(250)
        } catch (_: InterruptedException) {
        }
        captureThread = null
    }

    fun stop() {
        directCaptureActive.set(false)
        activeProjection = null
        stopFallbackThreadOnly()

        val record = playbackRecord
        playbackRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
            DroidLog.i(DroidLog.TAG_AUDIO, "Stopped AudioPlaybackCapture")
        }
        restorePhoneMediaPlayback()
    }

    companion object {
        internal fun findWebRtcAudioRecord(audioDeviceModule: Any): Any? {
            val webRtcAudioRecordClass = Class.forName("org.webrtc.audio.WebRtcAudioRecord")
            var cls: Class<*>? = audioDeviceModule.javaClass
            while (cls != null) {
                for (field in cls.declaredFields) {
                    if (field.name == "audioInput" || webRtcAudioRecordClass.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        return field.get(audioDeviceModule)
                    }
                }
                cls = cls.superclass
            }
            return null
        }

        internal fun findAudioRecordField(webRtcAudioRecord: Any): java.lang.reflect.Field? {
            var cls: Class<*>? = webRtcAudioRecord.javaClass
            while (cls != null) {
                for (field in cls.declaredFields) {
                    if (field.name == "audioRecord" || AudioRecord::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        return field
                    }
                }
                cls = cls.superclass
            }
            return null
        }
    }
}
