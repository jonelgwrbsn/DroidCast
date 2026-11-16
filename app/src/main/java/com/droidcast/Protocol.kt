package com.droidcast

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject

@JsonClass(generateAdapter = true)
data class DeviceCapabilitiesDto(
    val video: Boolean,
    val audio: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
)

@JsonClass(generateAdapter = true)
data class DeviceInfoDto(
    val deviceId: String,
    val role: String,
    val name: String,
    val platform: String,
    val status: String,
    val address: String? = null,
    val capabilities: DeviceCapabilitiesDto,
    val connectedAt: Long,
)

@JsonClass(generateAdapter = true)
data class DeviceRegisterPayload(
    val type: String = "device_register",
    val deviceId: String,
    val role: String = "sender",
    val name: String,
    val platform: String = "android",
    val capabilities: DeviceCapabilitiesDto,
)

@JsonClass(generateAdapter = true)
data class CreateSessionPayload(
    val type: String = "create_session",
    val targetDeviceId: String,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class OfferPayload(
    val type: String = "offer",
    val sessionId: String,
    val sdp: String,
)

@JsonClass(generateAdapter = true)
data class IceCandidatePayload(
    val type: String = "ice_candidate",
    val sessionId: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
)

@JsonClass(generateAdapter = true)
data class CastStartedPayload(
    val type: String = "cast_started",
    val sessionId: String,
    val videoAvailable: Boolean,
    val audioAvailable: Boolean,
    val audioReason: String? = null,
    val width: Int,
    val height: Int,
    val fps: Int,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class CastStoppedPayload(
    val type: String = "cast_stopped",
    val sessionId: String,
    val reason: String,
)

@JsonClass(generateAdapter = true)
data class QualityChangePayload(
    val type: String = "quality_change",
    val sessionId: String,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class PingPayload(
    val type: String = "ping",
    val timestamp: Long,
)

@JsonClass(generateAdapter = true)
data class DeviceRegisteredDto(
    val type: String,
    val deviceId: String,
    val role: String,
    val peers: List<DeviceInfoDto>,
)

@JsonClass(generateAdapter = true)
data class DeviceDiscoveredDto(
    val type: String,
    val action: String,
    val device: DeviceInfoDto,
)

@JsonClass(generateAdapter = true)
data class SessionCreatedDto(
    val type: String,
    val sessionId: String,
    val senderId: String,
    val receiverId: String,
    val senderName: String,
    val receiverName: String,
    val pairingCode: String? = null,
    val pairingExpiresAt: Long,
    val authorized: Boolean,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class PairingVerifiedDto(
    val type: String,
    val sessionId: String,
    val authorized: Boolean,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class AnswerDto(
    val type: String,
    val sessionId: String,
    val sdp: String,
)

@JsonClass(generateAdapter = true)
data class IceCandidateDto(
    val type: String,
    val sessionId: String,
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int,
)

@JsonClass(generateAdapter = true)
data class QualityChangeDto(
    val type: String,
    val sessionId: String,
    val quality: String,
)

@JsonClass(generateAdapter = true)
data class CastStoppedDto(
    val type: String,
    val sessionId: String,
    val reason: String,
)

@JsonClass(generateAdapter = true)
data class PeerDisconnectedDto(
    val type: String,
    val sessionId: String? = null,
    val deviceId: String,
    val reason: String,
)

@JsonClass(generateAdapter = true)
data class ErrorDto(
    val type: String,
    val code: String,
    val message: String,
    val sessionId: String? = null,
)

sealed interface InboundSignalingMessage {
    data class DeviceRegistered(val dto: DeviceRegisteredDto) : InboundSignalingMessage
    data class DeviceDiscovered(val dto: DeviceDiscoveredDto) : InboundSignalingMessage
    data class SessionCreated(val dto: SessionCreatedDto) : InboundSignalingMessage
    data class PairingVerified(val dto: PairingVerifiedDto) : InboundSignalingMessage
    data class Answer(val dto: AnswerDto) : InboundSignalingMessage
    data class IceCandidate(val dto: IceCandidateDto) : InboundSignalingMessage
    data class QualityChange(val dto: QualityChangeDto) : InboundSignalingMessage
    data class CastStopped(val dto: CastStoppedDto) : InboundSignalingMessage
    data class PeerDisconnected(val dto: PeerDisconnectedDto) : InboundSignalingMessage
    data class ProtocolError(val dto: ErrorDto) : InboundSignalingMessage
    data class Pong(val timestamp: Long) : InboundSignalingMessage
}

object ProtocolCodec {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val registerAdapter = moshi.adapter(DeviceRegisterPayload::class.java)
    private val createSessionAdapter = moshi.adapter(CreateSessionPayload::class.java)
    private val offerAdapter = moshi.adapter(OfferPayload::class.java)
    private val iceOutAdapter = moshi.adapter(IceCandidatePayload::class.java)
    private val castStartedAdapter = moshi.adapter(CastStartedPayload::class.java)
    private val castStoppedOutAdapter = moshi.adapter(CastStoppedPayload::class.java)
    private val qualityOutAdapter = moshi.adapter(QualityChangePayload::class.java)
    private val pingAdapter = moshi.adapter(PingPayload::class.java)

    private val deviceRegisteredAdapter = moshi.adapter(DeviceRegisteredDto::class.java)
    private val deviceDiscoveredAdapter = moshi.adapter(DeviceDiscoveredDto::class.java)
    private val sessionCreatedAdapter = moshi.adapter(SessionCreatedDto::class.java)
    private val pairingVerifiedAdapter = moshi.adapter(PairingVerifiedDto::class.java)
    private val answerAdapter = moshi.adapter(AnswerDto::class.java)
    private val iceInAdapter = moshi.adapter(IceCandidateDto::class.java)
    private val qualityInAdapter = moshi.adapter(QualityChangeDto::class.java)
    private val castStoppedInAdapter = moshi.adapter(CastStoppedDto::class.java)
    private val peerDisconnectedAdapter = moshi.adapter(PeerDisconnectedDto::class.java)
    private val errorAdapter = moshi.adapter(ErrorDto::class.java)

    fun encodeRegister(payload: DeviceRegisterPayload): String = registerAdapter.toJson(payload)
    fun encodeCreateSession(payload: CreateSessionPayload): String = createSessionAdapter.toJson(payload)
    fun encodeOffer(payload: OfferPayload): String = offerAdapter.toJson(payload)
    fun encodeIceCandidate(payload: IceCandidatePayload): String = iceOutAdapter.toJson(payload)
    fun encodeCastStarted(payload: CastStartedPayload): String = castStartedAdapter.toJson(payload)
    fun encodeCastStopped(payload: CastStoppedPayload): String = castStoppedOutAdapter.toJson(payload)
    fun encodeQualityChange(payload: QualityChangePayload): String = qualityOutAdapter.toJson(payload)
    fun encodePing(timestamp: Long): String = pingAdapter.toJson(PingPayload(timestamp = timestamp))

    fun parseInbound(raw: String): InboundSignalingMessage? {
        val type = try {
            JSONObject(raw).optString("type", "")
        } catch (_: Exception) {
            return null
        }

        return when (type) {
            "device_registered" -> deviceRegisteredAdapter.fromJson(raw)?.let(InboundSignalingMessage::DeviceRegistered)
            "device_discovered" -> deviceDiscoveredAdapter.fromJson(raw)?.let(InboundSignalingMessage::DeviceDiscovered)
            "session_created" -> sessionCreatedAdapter.fromJson(raw)?.let(InboundSignalingMessage::SessionCreated)
            "pairing_verified" -> pairingVerifiedAdapter.fromJson(raw)?.let(InboundSignalingMessage::PairingVerified)
            "answer" -> answerAdapter.fromJson(raw)?.let(InboundSignalingMessage::Answer)
            "ice_candidate" -> iceInAdapter.fromJson(raw)?.let(InboundSignalingMessage::IceCandidate)
            "quality_change" -> qualityInAdapter.fromJson(raw)?.let(InboundSignalingMessage::QualityChange)
            "cast_stopped" -> castStoppedInAdapter.fromJson(raw)?.let(InboundSignalingMessage::CastStopped)
            "peer_disconnected" -> peerDisconnectedAdapter.fromJson(raw)?.let(InboundSignalingMessage::PeerDisconnected)
            "error" -> errorAdapter.fromJson(raw)?.let(InboundSignalingMessage::ProtocolError)
            "pong" -> InboundSignalingMessage.Pong(JSONObject(raw).optLong("timestamp", 0L))
            else -> null
        }
    }
}
