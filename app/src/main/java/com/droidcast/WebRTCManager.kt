package com.droidcast

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private data class ReceiverFeedbackSnapshot(
    val receivedAtMs: Long,
    val packetLossPercent: Double,
    val jitterMs: Double,
    val rttMs: Double?,
    val stalled: Boolean,
)

class WebRTCManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val screenCapturer: ScreenCapturer,
    private val audioCapturer: AudioCapturer,
    private val onLocalOffer: (String) -> Unit,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onIceStateChanged: (PeerConnection.IceConnectionState) -> Unit,
    private val onTelemetry: (CastTelemetry) -> Unit,
    private val onMediaAvailability: (MediaAvailability) -> Unit,
    private val onProjectionStoppedBySystem: () -> Unit,
    private val onWebRtcError: (String, String) -> Unit,
) {
    private var eglBase: EglBase? = null
    private var javaAudioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var adaptiveDataChannel: DataChannel? = null
    private var statsJob: Job? = null

    private var prevBytesSent = 0L
    private var prevStatsTsMs = 0.0
    private var prevFramesEncoded = 0L
    private var prevPacketsSent = 0L
    private var prevRetransmittedPacketsSent = 0L
    private var statsTickCount = 0

    private val adaptiveController = AdaptiveBitrateController()
    private var requestedPreset: QualityPreset = QualityPreset.ADAPTIVE
    private var lastDecision: AdaptiveDecision = adaptiveController.reset(QualityPreset.ADAPTIVE)
    @Volatile
    private var latestReceiverFeedback: ReceiverFeedbackSnapshot? = null
    private var iceRestartCount = 0
    private var lastIceRestartMs = 0L

    private fun ensureInitialized() {
        if (peerConnectionFactory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val egl = EglBase.create()
        eglBase = egl

        val audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioRecordDataCallback(audioCapturer.recordDataCallback)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(false)
            .createAudioDeviceModule()
        audioDeviceModule.setMicrophoneMute(true)
        javaAudioDeviceModule = audioDeviceModule

        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        audioDeviceModule.release()
        DroidLog.i(DroidLog.TAG_WEBRTC, "Initialized PeerConnectionFactory (screen and system audio)")
    }

    fun startCasting(
        projectionResultData: Intent,
        quality: QualityPreset,
        includeSystemAudio: Boolean,
    ): CaptureDimensions {
        stopCasting()
        audioCapturer.stop()

        requestedPreset = quality
        latestReceiverFeedback = null
        lastDecision = adaptiveController.reset(quality, System.currentTimeMillis())
        iceRestartCount = 0

        ensureInitialized()

        val factory = requireNotNull(peerConnectionFactory)
        val egl = requireNotNull(eglBase)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    val s = state ?: return
                    DroidLog.i(DroidLog.TAG_WEBRTC, "ICE connection state -> $s")
                    onIceStateChanged(s)
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            iceRestartCount = 0
                        }

                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            scope.launch {
                                delay(2500)
                                val current = peerConnection?.iceConnectionState()
                                if (current == PeerConnection.IceConnectionState.DISCONNECTED ||
                                    current == PeerConnection.IceConnectionState.FAILED
                                ) {
                                    triggerIceRestart()
                                }
                            }
                        }

                        PeerConnection.IceConnectionState.FAILED -> {
                            if (!triggerIceRestart()) {
                                onWebRtcError(
                                    "ICE_FAILED",
                                    "WebRTC ICE connection failed. Ensure both devices are on the same local Wi-Fi network."
                                )
                            }
                        }

                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        onLocalIceCandidate(candidate)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        ) ?: throw IllegalStateException("Failed to create RTCPeerConnection")

        peerConnection = pc

        val capturer = screenCapturer.createCapturer(
            projectionResultData = projectionResultData,
            onProjectionStopped = onProjectionStoppedBySystem,
            onContentResized = { newDims ->
                videoSource?.adaptOutputFormat(newDims.width, newDims.height, newDims.fps)
                sendAdaptiveStateOverDataChannel(lastDecision, newDims)
            },
        )

        val textureHelper = SurfaceTextureHelper.create("DroidCastCaptureThread", egl.eglBaseContext)
        surfaceTextureHelper = textureHelper

        val vSource = factory.createVideoSource(capturer.isScreencast)
        videoSource = vSource
        capturer.initialize(textureHelper, context.applicationContext, vSource.capturerObserver)
        val initialDecision = lastDecision
        val dims = screenCapturer.start(initialDecision.tier)
        vSource.adaptOutputFormat(dims.width, dims.height, dims.fps)

        val vTrack = factory.createVideoTrack("droidcast-video", vSource).apply {
            setEnabled(true)
        }
        videoTrack = vTrack
        videoSender = pc.addTrack(vTrack, listOf("droidcast-stream"))
        preferHardwareVideoCodecs(pc, factory)
        applySenderMediaTuning(initialDecision, quality)
        setupAdaptiveDataChannel(pc)

        var audioAvailable = false
        var audioReason = "System audio disabled in settings"
        if (includeSystemAudio) {
            val support = audioCapturer.prepare(screenCapturer.getMediaProjection(), userEnabled = true)
            if (support.available) {
                val capture = audioCapturer.startFallbackCaptureIfNeeded()
                if (capture.available) {
                    try {
                        val source = factory.createAudioSource(MediaConstraints())
                        audioSource = source
                        val track = factory.createAudioTrack("droidcast-audio", source).apply {
                            setEnabled(true)
                        }
                        audioTrack = track
                        pc.addTrack(track, listOf("droidcast-stream"))
                        audioAvailable = true
                        audioReason = if (audioCapturer.mutePhoneMediaPlayback()) {
                            "System audio is streaming; phone media volume is muted"
                        } else {
                            "System audio is streaming; phone media volume could not be muted"
                        }
                    } catch (e: Exception) {
                        audioCapturer.stop()
                        audioReason = "Unable to start system audio: ${e.message ?: e.javaClass.simpleName}"
                        DroidLog.w(DroidLog.TAG_AUDIO, audioReason)
                    }
                } else {
                    audioReason = capture.reason
                    audioCapturer.stop()
                    DroidLog.w(DroidLog.TAG_AUDIO, "System audio unavailable: $audioReason")
                }
            } else {
                audioReason = support.reason
                DroidLog.w(DroidLog.TAG_AUDIO, "System audio unavailable: $audioReason")
            }
        }

        onMediaAvailability(
            MediaAvailability(
                videoAvailable = true,
                audioAvailable = audioAvailable,
                audioReason = audioReason,
            )
        )

        createAndSendOffer(pc, iceRestart = false)
        startStatsPolling()
        return dims
    }

    fun triggerIceRestart(): Boolean {
        val pc = peerConnection ?: return false
        val now = System.currentTimeMillis()
        if (iceRestartCount >= 3 || now - lastIceRestartMs < 3000L) {
            return false
        }
        iceRestartCount += 1
        lastIceRestartMs = now
        DroidLog.i(DroidLog.TAG_WEBRTC, "Triggering WebRTC ICE restart (attempt $iceRestartCount)")
        pc.restartIce()
        createAndSendOffer(pc, iceRestart = true)
        return true
    }

    private fun preferHardwareVideoCodecs(pc: PeerConnection, factory: PeerConnectionFactory) {
        try {
            val caps = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val allCodecs = caps.codecs ?: return
            val preferredOrder = listOf("H264", "VP8", "VP9")
            val prioritized = mutableListOf<org.webrtc.RtpCapabilities.CodecCapability>()

            for (name in preferredOrder) {
                allCodecs.filter { it.name.equals(name, ignoreCase = true) }.forEach {
                    prioritized.add(it)
                }
            }
            // Keep auxiliary RTX/RED/ULPFEC codecs
            allCodecs.filter { codec ->
                preferredOrder.none { it.equals(codec.name, ignoreCase = true) }
            }.forEach { prioritized.add(it) }

            val videoTransceiver: RtpTransceiver? = pc.transceivers.firstOrNull {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            }
            videoTransceiver?.setCodecPreferences(prioritized)
            DroidLog.i(DroidLog.TAG_WEBRTC, "Configured video codec priority: H264 -> VP8 -> VP9")
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_WEBRTC, "Codec preference setup skipped: ${e.message}")
        }
    }

    private fun createAndSendOffer(pc: PeerConnection, iceRestart: Boolean) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        pc.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    val tunedSdp = tuneSdpForCasting(desc.description)
                    val tunedDesc = SessionDescription(desc.type, tunedSdp)
                    pc.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                DroidLog.i(DroidLog.TAG_WEBRTC, "Local SDP offer created and set (iceRestart=$iceRestart)")
                                onLocalOffer(tunedDesc.description)
                            }
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {
                                DroidLog.e(DroidLog.TAG_WEBRTC, "setLocalDescription failed: $err")
                                onWebRtcError("WEBRTC_NEGOTIATION_FAILED", "Failed to set local SDP offer.")
                            }
                        },
                        tunedDesc
                    )
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    DroidLog.e(DroidLog.TAG_WEBRTC, "createOffer failed: $err")
                    onWebRtcError("WEBRTC_NEGOTIATION_FAILED", "Failed to create WebRTC SDP offer.")
                }
                override fun onSetFailure(err: String?) {}
            },
            constraints
        )
    }

    fun applyRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    DroidLog.i(DroidLog.TAG_WEBRTC, "Applied remote SDP answer")
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(err: String?) {
                    DroidLog.e(DroidLog.TAG_WEBRTC, "setRemoteDescription(answer) failed: $err")
                    onWebRtcError("WEBRTC_NEGOTIATION_FAILED", "Failed to apply SDP answer from receiver.")
                }
            },
            answer
        )
    }

    fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val pc = peerConnection ?: return
        pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    private fun setupAdaptiveDataChannel(pc: PeerConnection) {
        try {
            val init = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = 2
            }
            val channel = pc.createDataChannel("droidcast-adaptive", init) ?: return
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}

                override fun onStateChange() {
                    if (channel.state() == DataChannel.State.OPEN) {
                        DroidLog.i(DroidLog.TAG_WEBRTC, "Adaptive control DataChannel open")
                        sendAdaptiveStateOverDataChannel(lastDecision, screenCapturer.currentDimensions())
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null || buffer.binary) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    parseReceiverFeedback(String(bytes, Charsets.UTF_8))
                }
            })
            adaptiveDataChannel = channel
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_WEBRTC, "Adaptive DataChannel setup skipped: ${e.message}")
        }
    }

    private fun parseReceiverFeedback(raw: String) {
        try {
            val json = JSONObject(raw)
            if (json.optString("type") != "receiver_feedback") return
            val lossPct = json.optDouble("packetLossPercent", 0.0).coerceIn(0.0, 100.0)
            val jitterMs = json.optDouble("jitterMs", 0.0).coerceAtLeast(0.0)
            val rttMs = if (json.has("rttMs") && !json.isNull("rttMs")) {
                json.optDouble("rttMs", -1.0).takeIf { it >= 0.0 }
            } else {
                null
            }
            val stalled = json.optBoolean("stalled", false)
            latestReceiverFeedback = ReceiverFeedbackSnapshot(
                receivedAtMs = System.currentTimeMillis(),
                packetLossPercent = lossPct,
                jitterMs = jitterMs,
                rttMs = rttMs,
                stalled = stalled,
            )
        } catch (_: Exception) {
        }
    }

    private fun sendAdaptiveStateOverDataChannel(decision: AdaptiveDecision, dims: CaptureDimensions) {
        val channel = adaptiveDataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        try {
            val payload = JSONObject()
                .put("type", "adaptive_state")
                .put("mode", requestedPreset.wireValue)
                .put("tierId", decision.tier.id)
                .put("tierLabel", decision.tier.label)
                .put("targetBitrateKbps", decision.targetMaxBitrateBps / 1000)
                .put("width", dims.width)
                .put("height", dims.height)
                .put("fps", decision.targetFps)
                .put("networkQuality", decision.networkQuality.wireValue)
                .put("reason", decision.reason)
                .toString()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (_: Exception) {
        }
    }

    fun updateQuality(quality: QualityPreset): CaptureDimensions {
        requestedPreset = quality
        val decision = adaptiveController.setPreset(quality, System.currentTimeMillis())
        lastDecision = decision
        val dims = screenCapturer.updateFormat(decision.tier)
        videoSource?.adaptOutputFormat(dims.width, dims.height, dims.fps)
        applySenderMediaTuning(decision, quality)
        sendAdaptiveStateOverDataChannel(decision, dims)
        return dims
    }

    private fun applySenderMediaTuning(decision: AdaptiveDecision, @Suppress("UNUSED_PARAMETER") preset: QualityPreset) {
        val sender = videoSender ?: return
        try {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            val safeMaxBitrate = decision.targetMaxBitrateBps.coerceAtLeast(1_500_000)
            val safeMinBitrate = minOf(decision.targetMinBitrateBps, safeMaxBitrate)
            for (encoding in params.encodings) {
                encoding.minBitrateBps = safeMinBitrate
                encoding.maxBitrateBps = safeMaxBitrate
                encoding.maxFramerate = decision.targetFps
                encoding.scaleResolutionDownBy = 1.0
                encoding.bitratePriority = 4.0
                encoding.networkPriority = 3
            }
            sender.parameters = params
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_WEBRTC, "Unable to update RTP sender parameters: ${e.message}")
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        prevBytesSent = 0L
        prevStatsTsMs = 0.0
        prevFramesEncoded = 0L
        prevPacketsSent = 0L
        prevRetransmittedPacketsSent = 0L
        statsTickCount = 0

        statsJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                val pc = peerConnection ?: continue
                pc.getStats { report ->
                    var bytesSent = 0L
                    var framesEncoded = 0L
                    var packetsSent = 0L
                    var retransmittedPacketsSent = 0L
                    var width = screenCapturer.currentDimensions().width
                    var height = screenCapturer.currentDimensions().height
                    var tsMs = 0.0
                    var qualityLimitationReason = "none"
                    var availableOutgoingBitrate = 0.0
                    var rtcpFractionLost = 0.0
                    var senderRttMs: Double? = null

                    for (stat in report.statsMap.values) {
                        if (stat.type == "outbound-rtp" && stat.members["kind"] == "video") {
                            bytesSent = (stat.members["bytesSent"] as? Number)?.toLong() ?: bytesSent
                            framesEncoded = (stat.members["framesEncoded"] as? Number)?.toLong() ?: framesEncoded
                            packetsSent = (stat.members["packetsSent"] as? Number)?.toLong() ?: packetsSent
                            retransmittedPacketsSent =
                                (stat.members["retransmittedPacketsSent"] as? Number)?.toLong() ?: retransmittedPacketsSent
                            width = (stat.members["frameWidth"] as? Number)?.toInt() ?: width
                            height = (stat.members["frameHeight"] as? Number)?.toInt() ?: height
                            qualityLimitationReason = (stat.members["qualityLimitationReason"] as? String) ?: "none"
                            tsMs = stat.timestampUs / 1000.0
                        } else if (stat.type == "remote-inbound-rtp" && stat.members["kind"] == "video") {
                            val fraction = (stat.members["fractionLost"] as? Number)?.toDouble()
                            if (fraction != null && fraction >= 0.0) {
                                rtcpFractionLost = maxOf(rtcpFractionLost, fraction)
                            }
                            val remoteRttSec = (stat.members["roundTripTime"] as? Number)?.toDouble()
                            if (remoteRttSec != null && remoteRttSec >= 0.0) {
                                senderRttMs = remoteRttSec * 1000.0
                            }
                        } else if (stat.type == "candidate-pair" &&
                            (stat.members["nominated"] == true || stat.members["state"] == "succeeded")
                        ) {
                            availableOutgoingBitrate =
                                (stat.members["availableOutgoingBitrate"] as? Number)?.toDouble() ?: availableOutgoingBitrate
                            val pairRttSec = (stat.members["currentRoundTripTime"] as? Number)?.toDouble()
                            if (senderRttMs == null && pairRttSec != null && pairRttSec >= 0.0) {
                                senderRttMs = pairRttSec * 1000.0
                            }
                        }
                    }

                    var bitrateKbps = 0
                    var actualSendBitrateBps = 0.0
                    var fps = 0
                    var retransmitRatio = 0.0
                    if (prevStatsTsMs > 0.0 && tsMs > prevStatsTsMs) {
                        val deltaSec = (tsMs - prevStatsTsMs) / 1000.0
                        val deltaBytes = (bytesSent - prevBytesSent).coerceAtLeast(0L)
                        val deltaFrames = (framesEncoded - prevFramesEncoded).coerceAtLeast(0L)
                        val deltaPackets = (packetsSent - prevPacketsSent).coerceAtLeast(0L)
                        val deltaRetransmitted = (retransmittedPacketsSent - prevRetransmittedPacketsSent).coerceAtLeast(0L)
                        actualSendBitrateBps = (deltaBytes * 8.0) / deltaSec
                        bitrateKbps = (actualSendBitrateBps / 1000.0).toInt()
                        fps = (deltaFrames / deltaSec).toInt()
                        if (deltaPackets > 0) {
                            retransmitRatio = (deltaRetransmitted.toDouble() / deltaPackets.toDouble()).coerceIn(0.0, 1.0)
                        }
                    }
                    prevBytesSent = bytesSent
                    prevStatsTsMs = tsMs
                    prevFramesEncoded = framesEncoded
                    prevPacketsSent = packetsSent
                    prevRetransmittedPacketsSent = retransmittedPacketsSent
                    statsTickCount += 1

                    val nowMs = System.currentTimeMillis()
                    val feedback = latestReceiverFeedback?.takeIf { nowMs - it.receivedAtMs <= 4_000L }
                    val receiverLossRatio = (feedback?.packetLossPercent ?: 0.0) / 100.0
                    val combinedLossRatio = maxOf(rtcpFractionLost, receiverLossRatio)
                    val effectiveRttMs = senderRttMs ?: feedback?.rttMs

                    val decision = adaptiveController.evaluate(
                        NetworkMetricsSample(
                            timestampMs = nowMs,
                            availableOutgoingBitrateBps = availableOutgoingBitrate,
                            actualSendBitrateBps = actualSendBitrateBps,
                            packetLossRatio = combinedLossRatio,
                            rttMs = effectiveRttMs,
                            receiverJitterMs = feedback?.jitterMs ?: 0.0,
                            qualityLimitationReason = qualityLimitationReason,
                            retransmitRatio = retransmitRatio,
                            receiverStalled = feedback?.stalled == true,
                        )
                    )
                    lastDecision = decision

                    var activeDims = screenCapturer.currentDimensions()
                    if (decision.resolutionChanged) {
                        activeDims = screenCapturer.updateFormat(decision.tier)
                        videoSource?.adaptOutputFormat(activeDims.width, activeDims.height, activeDims.fps)
                        DroidLog.i(
                            DroidLog.TAG_WEBRTC,
                            "Adaptive tier -> ${decision.tier.label} @ ${decision.targetMaxBitrateBps / 1000} kbps (${decision.reason})"
                        )
                    } else if (decision.bitrateChanged) {
                        DroidLog.i(
                            DroidLog.TAG_WEBRTC,
                            "Adaptive bitrate -> ${decision.targetMaxBitrateBps / 1000} kbps (${decision.reason})"
                        )
                    }

                    if (decision.resolutionChanged || decision.bitrateChanged) {
                        applySenderMediaTuning(decision, requestedPreset)
                        sendAdaptiveStateOverDataChannel(decision, activeDims)
                    } else if (statsTickCount % 2 == 0) {
                        sendAdaptiveStateOverDataChannel(decision, activeDims)
                    }

                    onTelemetry(
                        CastTelemetry(
                            width = width,
                            height = height,
                            fps = fps,
                            bitrateKbps = bitrateKbps,
                            targetBitrateKbps = decision.targetMaxBitrateBps / 1000,
                            packetLossPercent = ((combinedLossRatio * 100.0) * 10.0).roundToInt() / 10.0,
                            rttMs = effectiveRttMs?.roundToInt(),
                            adaptiveTierLabel = if (requestedPreset == QualityPreset.ADAPTIVE) {
                                "Auto · ${decision.tier.label}"
                            } else {
                                requestedPreset.label
                            },
                            networkQuality = decision.networkQuality,
                            adaptationReason = decision.reason,
                            iceState = pc.iceConnectionState().name,
                        )
                    )
                }
            }
        }
    }

    fun stopCasting() {
        statsJob?.cancel()
        statsJob = null

        audioCapturer.stop()
        screenCapturer.stopAndDispose()

        try {
            videoTrack?.setEnabled(false)
            videoTrack?.dispose()
        } catch (_: Exception) {
        }
        videoTrack = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }
        videoSource = null

        try {
            audioTrack?.setEnabled(false)
            audioTrack?.dispose()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        audioSource = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        surfaceTextureHelper = null
        try {
            adaptiveDataChannel?.unregisterObserver()
            adaptiveDataChannel?.close()
            adaptiveDataChannel?.dispose()
        } catch (_: Exception) {
        }
        adaptiveDataChannel = null
        latestReceiverFeedback = null
        videoSender = null

        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null

        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {
        }
        peerConnectionFactory = null
        javaAudioDeviceModule = null

        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null
    }

    companion object {
        internal fun tuneSdpForCasting(sdp: String): String {
            val rawLines = sdp.split("\r\n")
            val filteredLines = mutableListOf<String>()
            var inVideoSection = false
            var videoSectionHasBandwidth = false
            for (line in rawLines) {
                if (line.startsWith("m=")) {
                    inVideoSection = line.startsWith("m=video ")
                    if (inVideoSection) {
                        videoSectionHasBandwidth = false
                    }
                    filteredLines.add(line)
                    continue
                }
                if (inVideoSection && line.startsWith("b=AS:")) {
                    videoSectionHasBandwidth = true
                    filteredLines.add("b=AS:24000")
                } else {
                    if (inVideoSection && line.startsWith("a=rtpmap:") && !videoSectionHasBandwidth) {
                        filteredLines.add("b=AS:24000")
                        videoSectionHasBandwidth = true
                    }
                    filteredLines.add(line)
                }
            }

            val videoPayloadTypes = mutableSetOf<String>()
            var inVideoCodecSection = false
            for (line in filteredLines) {
                if (line.startsWith("m=")) {
                    inVideoCodecSection = line.startsWith("m=video ")
                } else if (inVideoCodecSection && line.startsWith("a=rtpmap:")) {
                    val lower = line.lowercase()
                    if (lower.contains("h264/90000") || lower.contains("vp8/90000") || lower.contains("vp9/90000")) {
                        val pt = line.removePrefix("a=rtpmap:").substringBefore(" ")
                        if (pt.isNotBlank()) videoPayloadTypes.add(pt)
                    }
                }
            }

            for (i in filteredLines.indices) {
                val line = filteredLines[i]
                if (line.startsWith("a=fmtp:")) {
                    val pt = line.removePrefix("a=fmtp:").substringBefore(" ")
                    if (pt in videoPayloadTypes) {
                        val additions = buildList {
                            if (!line.contains("x-google-min-bitrate=")) add("x-google-min-bitrate=3000")
                            if (!line.contains("x-google-start-bitrate=")) add("x-google-start-bitrate=8000")
                            if (!line.contains("x-google-max-bitrate=")) add("x-google-max-bitrate=24000")
                        }
                        if (additions.isNotEmpty()) {
                            filteredLines[i] = "$line;${additions.joinToString(";")}"
                        }
                    }
                }
            }
            return filteredLines.joinToString("\r\n")
        }
    }
}
