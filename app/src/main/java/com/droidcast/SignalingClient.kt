package com.droidcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class SignalingClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val onStateChanged: (SignalingState) -> Unit,
    private val onMessage: (InboundSignalingMessage) -> Unit,
    private val onConnectionFailure: (String) -> Unit,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var manuallyClosed = false
    private var reconnectAttempt = 0
    private var hasConnectedOnce = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val url = currentUrl ?: return
            if (!manuallyClosed && webSocket == null) {
                DroidLog.i(DroidLog.TAG_NETWORK, "Wi-Fi/network available, triggering signaling reconnect")
                scope.launch(Dispatchers.Main) {
                    reconnectAttempt = 0
                    openWebSocket(url)
                }
            }
        }

        override fun onLost(network: Network) {
            DroidLog.w(DroidLog.TAG_NETWORK, "Active network lost")
        }
    }

    fun connect(wsUrl: String) {
        if (wsUrl.isBlank()) {
            onStateChanged(SignalingState.Disconnected)
            onConnectionFailure("Enter a valid receiver address such as 192.168.1.50:3000.")
            return
        }
        val normalized = normalizeWsUrl(wsUrl)
        currentUrl = normalized
        manuallyClosed = false
        reconnectAttempt = 0
        hasConnectedOnce = false
        registerNetworkObserver()
        reconnectJob?.cancel()
        closeExistingSocket()
        openWebSocket(normalized)
    }

    fun disconnect() {
        manuallyClosed = true
        currentUrl = null
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        unregisterNetworkObserver()
        closeExistingSocket()
        onStateChanged(SignalingState.Disconnected)
    }

    fun sendRegister(capabilities: DeviceCapabilitiesDto): Boolean =
        sendRaw(
            ProtocolCodec.encodeRegister(
                DeviceRegisterPayload(
                    deviceId = deviceId,
                    name = deviceName,
                    capabilities = capabilities,
                )
            )
        )

    fun sendCreateSession(targetDeviceId: String, quality: QualityPreset): Boolean =
        sendRaw(
            ProtocolCodec.encodeCreateSession(
                CreateSessionPayload(
                    targetDeviceId = targetDeviceId,
                    quality = quality.wireValue,
                )
            )
        )

    fun sendOffer(sessionId: String, sdp: String): Boolean =
        sendRaw(ProtocolCodec.encodeOffer(OfferPayload(sessionId = sessionId, sdp = sdp)))

    fun sendIceCandidate(sessionId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int): Boolean =
        sendRaw(
            ProtocolCodec.encodeIceCandidate(
                IceCandidatePayload(
                    sessionId = sessionId,
                    candidate = candidate,
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex,
                )
            )
        )

    fun sendCastStarted(payload: CastStartedPayload): Boolean =
        sendRaw(ProtocolCodec.encodeCastStarted(payload))

    fun sendCastStopped(sessionId: String, reason: String): Boolean =
        sendRaw(ProtocolCodec.encodeCastStopped(CastStoppedPayload(sessionId = sessionId, reason = reason)))

    fun sendQualityChange(sessionId: String, quality: QualityPreset): Boolean =
        sendRaw(ProtocolCodec.encodeQualityChange(QualityChangePayload(sessionId = sessionId, quality = quality.wireValue)))

    private fun sendRaw(json: String): Boolean {
        val socket = webSocket ?: return false
        return socket.send(json)
    }

    private fun openWebSocket(url: String) {
        onStateChanged(if (hasConnectedOnce) SignalingState.Reconnecting else SignalingState.Connecting)
        DroidLog.i(DroidLog.TAG_NETWORK, "Connecting signaling WebSocket to $url")

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_NETWORK, "Invalid signaling address ($url): ${e.message ?: "malformed URL"}")
            onStateChanged(SignalingState.Disconnected)
            onConnectionFailure("Invalid receiver address ($url). Use host:port such as 192.168.1.50:3000.")
            return
        }

        val socket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (webSocket !== ws) return
                    reconnectAttempt = 0
                    hasConnectedOnce = true
                    DroidLog.i(DroidLog.TAG_NETWORK, "Signaling WebSocket connected")
                    onStateChanged(SignalingState.Connected)
                    startHeartbeat()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (webSocket !== ws) return
                    val parsed = ProtocolCodec.parseInbound(text)
                    if (parsed != null) {
                        onMessage(parsed)
                    } else {
                        DroidLog.w(DroidLog.TAG_NETWORK, "Ignored unrecognized signaling payload")
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (webSocket !== ws) return
                    webSocket = null
                    heartbeatJob?.cancel()
                    DroidLog.w(DroidLog.TAG_NETWORK, "Signaling WebSocket closed (code=$code, reason=$reason)")
                    if (!manuallyClosed) {
                        scheduleReconnect()
                    } else {
                        onStateChanged(SignalingState.Disconnected)
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== ws) return
                    webSocket = null
                    heartbeatJob?.cancel()
                    val detail = t.message ?: t::class.java.simpleName
                    DroidLog.w(DroidLog.TAG_NETWORK, "Signaling WebSocket unreachable ($url): $detail")
                    if (!hasConnectedOnce) {
                        onStateChanged(SignalingState.Disconnected)
                        onConnectionFailure(
                            "Could not reach $url ($detail). Make sure the DroidCast signaling server & receiver are running on that computer, port 3000 is allowed through the firewall, and both devices are on the same Wi-Fi without AP/client isolation."
                        )
                    } else if (!manuallyClosed) {
                        scheduleReconnect()
                    }
                }
            }
        )
        webSocket = socket
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                sendRaw(ProtocolCodec.encodePing(System.currentTimeMillis()))
            }
        }
    }

    private fun scheduleReconnect() {
        val url = currentUrl ?: return
        if (manuallyClosed) return
        onStateChanged(SignalingState.Reconnecting)
        reconnectJob?.cancel()
        val delayMs = min(1000.0 * 1.6.pow(reconnectAttempt.toDouble()), 10_000.0).toLong()
        reconnectAttempt += 1
        reconnectJob = scope.launch(Dispatchers.Main) {
            DroidLog.i(DroidLog.TAG_NETWORK, "Reconnecting signaling in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            if (!manuallyClosed && currentUrl == url && webSocket == null) {
                openWebSocket(url)
            }
        }
    }

    private fun closeExistingSocket() {
        val socket = webSocket
        webSocket = null
        try {
            socket?.close(1000, "Client closing")
        } catch (_: Exception) {
        }
    }

    private fun registerNetworkObserver() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_NETWORK, "NetworkCallback registration failed: ${e.message}")
        }
    }

    private fun unregisterNetworkObserver() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    companion object {
        fun normalizeWsUrl(raw: String): String {
            val cleaned = raw.trim().replace("\\s+".toRegex(), "")
            val withScheme = when {
                cleaned.startsWith("ws://", ignoreCase = true) -> "ws://${cleaned.substring(5)}"
                cleaned.startsWith("wss://", ignoreCase = true) -> "wss://${cleaned.substring(6)}"
                cleaned.startsWith("http://", ignoreCase = true) -> "ws://${cleaned.substring(7)}"
                cleaned.startsWith("https://", ignoreCase = true) -> "wss://${cleaned.substring(8)}"
                cleaned.contains(":") -> "ws://$cleaned"
                else -> "ws://$cleaned:3000"
            }
            val schemeEnd = withScheme.indexOf("://") + 3
            val slashIdx = withScheme.indexOf('/', startIndex = schemeEnd)
            return if (slashIdx >= 0) withScheme.substring(0, slashIdx) else withScheme
        }
    }
}
