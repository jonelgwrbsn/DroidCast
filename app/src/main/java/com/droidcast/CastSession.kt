package com.droidcast

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import java.util.UUID

sealed interface CastActionEvent {
    data object RequestMediaProjectionPermission : CastActionEvent
}

object CastSessionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CastActionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CastActionEvent> = _events.asSharedFlow()

    private var appContext: Context? = null
    private var deviceId: String = "android-${UUID.randomUUID().toString().take(8)}"
    private var deviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private var deviceDiscovery: DeviceDiscovery? = null
    private var signalingClient: SignalingClient? = null
    private var screenCapturer: ScreenCapturer? = null
    private var audioCapturer: AudioCapturer? = null
    private var webRtcManager: WebRTCManager? = null

    private var activeSessionId: String? = null
    private var activeReceiverName: String? = null
    private var pendingProjectionIntent: Intent? = null
    private var lastServerAddress: String? = null
    private var previousSignalingState: SignalingState = SignalingState.Disconnected

    fun initialize(context: Context) {
        if (appContext != null) return
        val appCtx = context.applicationContext
        appContext = appCtx

        val prefs = appCtx.getSharedPreferences("droidcast_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: deviceId.also {
            prefs.edit().putString("device_id", it).apply()
        }
        val savedAddress = prefs.getString("last_server_address", "")?.trim().orEmpty()
        _uiState.update { it.copy(manualAddressInput = savedAddress) }

        val discovery = DeviceDiscovery(appCtx, scope)
        deviceDiscovery = discovery
        scope.launch {
            discovery.endpoints.collect { endpoints ->
                _uiState.update { it.copy(discoveredEndpoints = endpoints) }
                // Auto-connect to the first discovered LAN server if currently disconnected
                if (
                    endpoints.isNotEmpty() &&
                    _uiState.value.signalingState is SignalingState.Disconnected &&
                    lastServerAddress == null
                ) {
                    connectToSignaling(endpoints.first().wsUrl)
                }
            }
        }
        scope.launch {
            discovery.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }

        val sCapturer = ScreenCapturer(appCtx)
        val aCapturer = AudioCapturer(appCtx)
        screenCapturer = sCapturer
        audioCapturer = aCapturer

        signalingClient = SignalingClient(
            context = appCtx,
            scope = scope,
            deviceId = deviceId,
            deviceName = deviceName,
            onStateChanged = ::handleSignalingState,
            onMessage = ::handleInboundMessage,
            onConnectionFailure = { msg ->
                _uiState.update {
                    it.copy(
                        error = DroidCastError(
                            code = "WEBSOCKET_FAILED",
                            title = "Unable to connect to receiver",
                            message = msg,
                            technicalDetail = "Signaling connection failed for $lastServerAddress",
                            canRetry = true,
                        )
                    )
                }
            }
        )

        webRtcManager = WebRTCManager(
            context = appCtx,
            scope = scope,
            screenCapturer = sCapturer,
            audioCapturer = aCapturer,
            onLocalOffer = { sdp ->
                val sid = activeSessionId ?: return@WebRTCManager
                signalingClient?.sendOffer(sid, sdp)
            },
            onLocalIceCandidate = { candidate ->
                val sid = activeSessionId ?: return@WebRTCManager
                signalingClient?.sendIceCandidate(
                    sessionId = sid,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                )
            },
            onIceStateChanged = { iceState ->
                _uiState.update { state ->
                    val nextCast = when {
                        iceState == PeerConnection.IceConnectionState.DISCONNECTED &&
                            state.castStatus is CastStatus.Casting ->
                            CastStatus.Reconnecting(
                                sessionId = state.castStatus.sessionId,
                                receiverName = state.castStatus.receiverName,
                            )

                        iceState == PeerConnection.IceConnectionState.CONNECTED &&
                            state.castStatus is CastStatus.Reconnecting ->
                            CastStatus.Casting(
                                sessionId = state.castStatus.sessionId,
                                receiverName = state.castStatus.receiverName,
                            )

                        else -> state.castStatus
                    }
                    state.copy(
                        castStatus = nextCast,
                        telemetry = state.telemetry.copy(iceState = iceState.name),
                    )
                }
            },
            onTelemetry = { telemetry ->
                _uiState.update { it.copy(telemetry = telemetry) }
            },
            onMediaAvailability = { media ->
                _uiState.update { it.copy(mediaAvailability = media) }
            },
            onProjectionStoppedBySystem = {
                scope.launch {
                    stopCasting(reason = "Screen capture stopped by Android system")
                }
            },
            onWebRtcError = { code, message ->
                scope.launch {
                    _uiState.update {
                        it.copy(
                            error = DroidCastError(
                                code = code,
                                title = "Streaming error",
                                message = message,
                                technicalDetail = "WebRTC failure ($code)",
                                canRetry = true,
                            )
                        )
                    }
                }
            },
        )

        discovery.startDiscovery()
    }

    fun refreshDiscovery() {
        deviceDiscovery?.startDiscovery()
    }

    fun updateManualAddress(input: String) {
        _uiState.update { it.copy(manualAddressInput = input) }
    }

    fun connectManualAddress(explicitInput: String? = null) {
        val raw = explicitInput ?: _uiState.value.manualAddressInput
        val input = raw.trim()
        if (explicitInput != null) {
            _uiState.update { it.copy(manualAddressInput = raw) }
        }
        if (input.isBlank()) {
            _uiState.update {
                it.copy(
                    error = DroidCastError(
                        code = "INVALID_ADDRESS",
                        title = "Enter receiver address",
                        message = "Enter your receiver's LAN IP and port (e.g., 192.168.1.50:3000), or scan the receiver's QR code.",
                        technicalDetail = "Empty host/port input",
                        canRetry = false,
                    )
                )
            }
            return
        }
        connectToSignaling(input)
    }

    fun connectToEndpoint(endpoint: DiscoveredEndpoint) {
        _uiState.update { it.copy(manualAddressInput = endpoint.displayAddress) }
        connectToSignaling(endpoint.wsUrl)
    }

    fun connectToSignaling(addressOrUrl: String) {
        lastServerAddress = addressOrUrl
        _uiState.update { it.copy(error = null, connectedServerAddress = addressOrUrl) }
        signalingClient?.connect(addressOrUrl)
    }

    fun selectReceiver(receiverId: String) {
        _uiState.update { it.copy(selectedReceiverId = receiverId, error = null) }
    }

    fun setQualityPreset(preset: QualityPreset) {
        _uiState.update { it.copy(qualityPreset = preset) }
        val sid = activeSessionId
        if (sid != null && _uiState.value.castStatus is CastStatus.Casting) {
            val dims = webRtcManager?.updateQuality(preset)
            signalingClient?.sendQualityChange(sid, preset)
            if (dims != null) {
                _uiState.update {
                    it.copy(telemetry = it.telemetry.copy(width = dims.width, height = dims.height))
                }
            }
        }
    }

    fun setIncludeSystemAudio(enabled: Boolean) {
        _uiState.update { it.copy(includeSystemAudio = enabled) }
    }

    fun startCastingFlow() {
        val state = _uiState.value
        val targetReceiver = state.availableReceivers.firstOrNull { it.deviceId == state.selectedReceiverId }
            ?: state.availableReceivers.firstOrNull { it.status == "ready" }
            ?: state.availableReceivers.firstOrNull()

        if (state.signalingState !is SignalingState.Connected) {
            _uiState.update {
                it.copy(
                    error = DroidCastError(
                        code = "RECEIVER_UNAVAILABLE",
                        title = "Not connected to receiver",
                        message = "Connect to your laptop's DroidCast signaling address before starting.",
                        technicalDetail = "Signaling state is ${state.signalingState}",
                    )
                )
            }
            return
        }

        if (targetReceiver == null) {
            _uiState.update {
                it.copy(
                    error = DroidCastError(
                        code = "RECEIVER_NOT_FOUND",
                        title = "No laptop receiver open",
                        message = "Open the DroidCast receiver page in your laptop browser and wait for it to show Ready.",
                        technicalDetail = "0 registered receivers on signaling server",
                    )
                )
            }
            return
        }

        _uiState.update { it.copy(error = null, selectedReceiverId = targetReceiver.deviceId) }
        DroidLog.i(DroidLog.TAG_SESSION, "Requesting pairing session with ${targetReceiver.name}")
        signalingClient?.sendCreateSession(targetReceiver.deviceId, state.qualityPreset)
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        val sessionId = activeSessionId
        val receiverName = activeReceiverName ?: "My Laptop"
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            DroidLog.w(DroidLog.TAG_SCREEN, "MediaProjection permission denied by user")
            _uiState.update {
                it.copy(
                    castStatus = CastStatus.Idle,
                    error = DroidCastError(
                        code = "PERMISSION_DENIED",
                        title = "Screen capture permission denied",
                        message = "DroidCast requires your approval on the Android screen capture prompt to cast.",
                        technicalDetail = "MediaProjection resultCode=$resultCode",
                    )
                )
            }
            if (sessionId != null) {
                signalingClient?.sendCastStopped(sessionId, "Screen capture permission denied")
                activeSessionId = null
            }
            return
        }

        if (sessionId == null) {
            DroidLog.w(DroidLog.TAG_SESSION, "MediaProjection granted after session closed")
            return
        }

        pendingProjectionIntent = data
        _uiState.update { it.copy(castStatus = CastStatus.Starting(sessionId, receiverName)) }

        val ctx = appContext ?: return
        val serviceIntent = Intent(ctx, CastService::class.java).apply {
            action = CastService.ACTION_START_CAST
            putExtra(CastService.EXTRA_RECEIVER_NAME, receiverName)
        }
        ContextCompat.startForegroundService(ctx, serviceIntent)
    }

    /**
     * Called by CastService AFTER startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
     * so Android 14+ MediaProjection authorization rules are strictly satisfied.
     */
    fun onForegroundServiceStarted() {
        val data = pendingProjectionIntent ?: return
        pendingProjectionIntent = null
        val sessionId = activeSessionId ?: return
        val receiverName = activeReceiverName ?: "My Laptop"
        val state = _uiState.value

        try {
            val dims = requireNotNull(webRtcManager).startCasting(
                projectionResultData = data,
                quality = state.qualityPreset,
                includeSystemAudio = state.includeSystemAudio,
            )
            val media = _uiState.value.mediaAvailability

            signalingClient?.sendCastStarted(
                CastStartedPayload(
                    sessionId = sessionId,
                    videoAvailable = true,
                    audioAvailable = media.audioAvailable,
                    audioReason = media.audioReason,
                    width = dims.width,
                    height = dims.height,
                    fps = dims.fps,
                    quality = state.qualityPreset.wireValue,
                )
            )

            _uiState.update {
                it.copy(
                    castStatus = CastStatus.Casting(sessionId, receiverName),
                    error = null,
                )
            }
            DroidLog.i(DroidLog.TAG_SESSION, "Casting active to $receiverName ($sessionId)")
        } catch (e: Exception) {
            DroidLog.e(DroidLog.TAG_SCREEN, "Failed to start MediaProjection WebRTC capture", e)
            stopCasting("Failed to initialize screen capture: ${e.message}")
            _uiState.update {
                it.copy(
                    error = DroidCastError(
                        code = "CAPTURE_FAILED",
                        title = "Unable to start screen capture",
                        message = e.message ?: "Android screen capture initialization failed.",
                        technicalDetail = e::class.java.simpleName,
                    )
                )
            }
        }
    }

    fun onConfigurationChanged() {
        val state = _uiState.value
        if (state.castStatus is CastStatus.Casting) {
            val dims = webRtcManager?.updateQuality(state.qualityPreset)
            if (dims != null) {
                DroidLog.i(DroidLog.TAG_SCREEN, "Adjusted capture after rotation: ${dims.width}x${dims.height}")
            }
        }
    }

    fun stopCasting(reason: String = "Stopped by user") {
        val sessionId = activeSessionId
        activeSessionId = null
        activeReceiverName = null
        pendingProjectionIntent = null

        webRtcManager?.stopCasting()
        if (sessionId != null) {
            signalingClient?.sendCastStopped(sessionId, reason)
        }

        appContext?.let { ctx ->
            try {
                ctx.stopService(Intent(ctx, CastService::class.java))
            } catch (_: Exception) {
            }
        }

        _uiState.update {
            it.copy(
                castStatus = CastStatus.Idle,
                mediaAvailability = MediaAvailability(),
                telemetry = CastTelemetry(),
            )
        }
        DroidLog.i(DroidLog.TAG_SESSION, "Casting stopped: $reason")
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun retryLastConnection() {
        _uiState.update { it.copy(error = null) }
        val addr = (lastServerAddress ?: _uiState.value.manualAddressInput).trim()
        if (addr.isBlank()) {
            connectManualAddress()
        } else {
            connectToSignaling(addr)
        }
    }

    private fun handleSignalingState(state: SignalingState) {
        val prev = previousSignalingState
        previousSignalingState = state
        _uiState.update { it.copy(signalingState = state) }

        if (state is SignalingState.Connected) {
            lastServerAddress?.let { connectedAddr ->
                appContext?.getSharedPreferences("droidcast_prefs", Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString("last_server_address", connectedAddr.removePrefix("ws://"))
                    ?.apply()
            }
            val dims = screenCapturer?.computeCaptureDimensions(QualityPreset.ADAPTIVE)
            signalingClient?.sendRegister(
                DeviceCapabilitiesDto(
                    video = true,
                    audio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    maxWidth = dims?.width ?: 2560,
                    maxHeight = dims?.height ?: 1440,
                    maxFps = 60,
                )
            )
            if (prev is SignalingState.Reconnecting) {
                _uiState.update { it.copy(justReconnected = true, error = null) }
                if (_uiState.value.castStatus is CastStatus.Casting ||
                    _uiState.value.castStatus is CastStatus.Reconnecting
                ) {
                    webRtcManager?.triggerIceRestart()
                }
                scope.launch {
                    delay(3500)
                    _uiState.update { it.copy(justReconnected = false) }
                }
            }
        }
    }

    private fun handleInboundMessage(msg: InboundSignalingMessage) {
        scope.launch {
            when (msg) {
                is InboundSignalingMessage.DeviceRegistered -> {
                    val receivers = msg.dto.peers
                        .filter { it.role == "receiver" }
                        .map { ReceiverPeer(it.deviceId, it.name, it.address, it.status) }
                    _uiState.update { current ->
                        val selected = current.selectedReceiverId
                            ?.takeIf { id -> receivers.any { it.deviceId == id } }
                            ?: receivers.firstOrNull()?.deviceId
                        current.copy(availableReceivers = receivers, selectedReceiverId = selected)
                    }
                }

                is InboundSignalingMessage.DeviceDiscovered -> {
                    val peer = msg.dto.device
                    if (peer.role != "receiver") return@launch
                    _uiState.update { current ->
                        val updated = if (msg.dto.action == "left") {
                            current.availableReceivers.filterNot { it.deviceId == peer.deviceId }
                        } else {
                            current.availableReceivers.filterNot { it.deviceId == peer.deviceId } +
                                ReceiverPeer(peer.deviceId, peer.name, peer.address, peer.status)
                        }
                        val selected = current.selectedReceiverId
                            ?.takeIf { id -> updated.any { it.deviceId == id } }
                            ?: updated.firstOrNull()?.deviceId
                        current.copy(availableReceivers = updated, selectedReceiverId = selected)
                    }
                }

                is InboundSignalingMessage.SessionCreated -> {
                    val dto = msg.dto
                    activeSessionId = dto.sessionId
                    activeReceiverName = dto.receiverName
                    val code = dto.pairingCode ?: "------"
                    _uiState.update {
                        it.copy(
                            castStatus = CastStatus.PairingPending(
                                sessionId = dto.sessionId,
                                receiverName = dto.receiverName,
                                pairingCode = code,
                                expiresAt = dto.pairingExpiresAt,
                            ),
                            error = null,
                        )
                    }
                    DroidLog.i(DroidLog.TAG_SESSION, "Pairing code issued for ${dto.receiverName}")
                }

                is InboundSignalingMessage.PairingVerified -> {
                    val dto = msg.dto
                    if (dto.sessionId != activeSessionId || !dto.authorized) return@launch
                    val receiverName = activeReceiverName ?: "My Laptop"
                    _uiState.update {
                        it.copy(
                            castStatus = CastStatus.WaitingMediaPermission(
                                sessionId = dto.sessionId,
                                receiverName = receiverName,
                            )
                        )
                    }
                    DroidLog.i(DroidLog.TAG_SESSION, "Pairing verified; requesting MediaProjection consent")
                    _events.tryEmit(CastActionEvent.RequestMediaProjectionPermission)
                }

                is InboundSignalingMessage.Answer -> {
                    if (msg.dto.sessionId == activeSessionId) {
                        webRtcManager?.applyRemoteAnswer(msg.dto.sdp)
                    }
                }

                is InboundSignalingMessage.IceCandidate -> {
                    if (msg.dto.sessionId == activeSessionId) {
                        webRtcManager?.addRemoteIceCandidate(
                            candidate = msg.dto.candidate,
                            sdpMid = msg.dto.sdpMid,
                            sdpMLineIndex = msg.dto.sdpMLineIndex,
                        )
                    }
                }

                is InboundSignalingMessage.QualityChange -> {
                    if (msg.dto.sessionId == activeSessionId) {
                        val preset = QualityPreset.fromWire(msg.dto.quality)
                        _uiState.update { it.copy(qualityPreset = preset) }
                        webRtcManager?.updateQuality(preset)
                    }
                }

                is InboundSignalingMessage.CastStopped -> {
                    if (msg.dto.sessionId == activeSessionId) {
                        stopCasting("Stopped by receiver (${msg.dto.reason})")
                    }
                }

                is InboundSignalingMessage.PeerDisconnected -> {
                    if (msg.dto.sessionId == activeSessionId || activeSessionId != null) {
                        val reason = msg.dto.reason
                        stopCasting(reason)
                        _uiState.update {
                            it.copy(
                                error = DroidCastError(
                                    code = "LAPTOP_DISCONNECTED",
                                    title = "Laptop disconnected",
                                    message = reason,
                                    technicalDetail = "Peer ${msg.dto.deviceId} disconnected",
                                )
                            )
                        }
                    }
                }

                is InboundSignalingMessage.ProtocolError -> {
                    val err = msg.dto
                    DroidLog.e(DroidLog.TAG_SESSION, "Protocol error ${err.code}: ${err.message}")
                    if (err.code == "PAIRING_EXPIRED" || err.code == "TOO_MANY_ATTEMPTS" || err.code == "SESSION_EXPIRED") {
                        stopCasting(err.message)
                    }
                    _uiState.update {
                        it.copy(
                            error = DroidCastError(
                                code = err.code,
                                title = "Session error",
                                message = err.message,
                                technicalDetail = "Server code ${err.code}",
                            )
                        )
                    }
                }

                is InboundSignalingMessage.Pong -> Unit
            }
        }
    }
}
