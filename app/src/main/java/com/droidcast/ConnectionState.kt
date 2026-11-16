package com.droidcast

enum class QualityPreset(
    val wireValue: String,
    val label: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val fps: Int,
    val maxBitrateBps: Int,
) {
    ADAPTIVE("adaptive", "Adaptive (Native)", 3840, 2160, 60, 24_000_000),
    NATIVE("native", "Native · Match Phone", 3840, 2160, 60, 24_000_000),
    P720_30("720p30", "720p · 30 FPS", 1280, 720, 30, 4_000_000),
    P1080_30("1080p30", "1080p · 30 FPS", 1920, 1080, 30, 10_000_000),
    P1080_60("1080p60", "1080p · 60 FPS", 1920, 1080, 60, 16_000_000),
    P1440_60("1440p60", "1440p · 60 FPS", 2560, 1440, 60, 20_000_000);

    companion object {
        fun fromWire(value: String?): QualityPreset =
            entries.firstOrNull { it.wireValue == value } ?: ADAPTIVE
    }
}

sealed interface SignalingState {
    data object Disconnected : SignalingState
    data object Connecting : SignalingState
    data object Connected : SignalingState
    data object Reconnecting : SignalingState
}

sealed interface CastStatus {
    data object Idle : CastStatus
    data class PairingPending(
        val sessionId: String,
        val receiverName: String,
        val pairingCode: String,
        val expiresAt: Long,
    ) : CastStatus
    data class WaitingMediaPermission(
        val sessionId: String,
        val receiverName: String,
    ) : CastStatus
    data class Starting(
        val sessionId: String,
        val receiverName: String,
    ) : CastStatus
    data class Casting(
        val sessionId: String,
        val receiverName: String,
    ) : CastStatus
    data class Reconnecting(
        val sessionId: String,
        val receiverName: String,
    ) : CastStatus
}

data class DiscoveredEndpoint(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val source: String, // "mDNS" | "LAN" | "Manual"
) {
    val wsUrl: String get() = "ws://$host:$port"
    val displayAddress: String get() = "$host:$port"
}

data class ReceiverPeer(
    val deviceId: String,
    val name: String,
    val address: String?,
    val status: String, // "ready" | "pairing" | "casting"
)

data class MediaAvailability(
    val videoAvailable: Boolean = false,
    val audioAvailable: Boolean = false,
    val audioReason: String = "System audio not active",
)

data class CastTelemetry(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val targetBitrateKbps: Int = 0,
    val packetLossPercent: Double = 0.0,
    val rttMs: Int? = null,
    val adaptiveTierLabel: String = "Auto",
    val networkQuality: NetworkQualityLevel = NetworkQualityLevel.EXCELLENT,
    val adaptationReason: String = "Optimal",
    val iceState: String = "NEW",
)

data class DroidCastError(
    val code: String,
    val title: String,
    val message: String,
    val technicalDetail: String,
    val canRetry: Boolean = true,
)

data class CastUiState(
    val signalingState: SignalingState = SignalingState.Disconnected,
    val connectedServerAddress: String? = null,
    val manualAddressInput: String = "",
    val discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    val availableReceivers: List<ReceiverPeer> = emptyList(),
    val selectedReceiverId: String? = null,
    val castStatus: CastStatus = CastStatus.Idle,
    val qualityPreset: QualityPreset = QualityPreset.ADAPTIVE,
    val includeSystemAudio: Boolean = true,
    val mediaAvailability: MediaAvailability = MediaAvailability(),
    val telemetry: CastTelemetry = CastTelemetry(),
    val isScanning: Boolean = false,
    val justReconnected: Boolean = false,
    val error: DroidCastError? = null,
)
