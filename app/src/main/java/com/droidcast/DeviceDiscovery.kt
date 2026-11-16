package com.droidcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

class DeviceDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val SERVICE_TYPE = "_droidcast._tcp."
        private const val UDP_DISCOVERY_PORT = 39481
        private const val UDP_MAGIC = "DROIDCAST_DISCOVER_V1"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _endpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
    val endpoints: StateFlow<List<DiscoveredEndpoint>> = _endpoints.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpJob: Job? = null

    fun startDiscovery() {
        stopDiscovery()
        _isScanning.value = true
        acquireMulticastLock()
        startNsdDiscovery()
        startUdpLanProbe()
    }

    fun stopDiscovery() {
        _isScanning.value = false
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                DroidLog.w(DroidLog.TAG_NETWORK, "NsdManager stop warning: ${e.message}")
            }
        }
        discoveryListener = null
        udpJob?.cancel()
        udpJob = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("DroidCastDiscovery")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_NETWORK, "Unable to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun startNsdDiscovery() {
        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                DroidLog.w(DroidLog.TAG_NETWORK, "mDNS discovery start failed (code=$errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                DroidLog.w(DroidLog.TAG_NETWORK, "mDNS discovery stop failed (code=$errorCode)")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                DroidLog.i(DroidLog.TAG_NETWORK, "mDNS discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                DroidLog.i(DroidLog.TAG_NETWORK, "mDNS discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveNsdService(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = "mdns:${serviceInfo.serviceName}"
                _endpoints.value = _endpoints.value.filterNot { it.id == id }
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            DroidLog.w(DroidLog.TAG_NETWORK, "Failed to start NsdManager: ${e.message}")
            discoveryListener = null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNsdService(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                manager.registerServiceInfoCallback(
                    serviceInfo,
                    { it.run() },
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            val v4 = updated.hostAddresses.firstOrNull { it is Inet4Address }
                                ?: updated.hostAddresses.firstOrNull()
                            val host = v4?.hostAddress ?: return
                            if (updated.port > 0) {
                                upsertEndpoint(
                                    DiscoveredEndpoint(
                                        id = "mdns:${updated.serviceName}",
                                        name = updated.serviceName.removePrefix("DroidCast-"),
                                        host = host,
                                        port = updated.port,
                                        source = "mDNS",
                                    )
                                )
                            }
                            try {
                                manager.unregisterServiceInfoCallback(this)
                            } catch (_: Exception) {
                            }
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
                return
            } catch (_: Exception) {
            }
        }

        try {
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        if (resolved.port > 0) {
                            upsertEndpoint(
                                DiscoveredEndpoint(
                                    id = "mdns:${resolved.serviceName}",
                                    name = resolved.serviceName.removePrefix("DroidCast-"),
                                    host = host,
                                    port = resolved.port,
                                    source = "mDNS",
                                )
                            )
                        }
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun startUdpLanProbe() {
        udpJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1500
                    bind(InetSocketAddress(0))
                }

                val payload = UDP_MAGIC.toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                var attempts = 0
                while (isActive && attempts < 4) {
                    attempts += 1
                    try {
                        socket.send(DatagramPacket(payload, payload.size, broadcastAddr, UDP_DISCOVERY_PORT))
                    } catch (_: Exception) {
                    }

                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    val deadline = System.currentTimeMillis() + 1800
                    while (isActive && System.currentTimeMillis() < deadline) {
                        try {
                            socket.receive(packet)
                            val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            parseUdpDiscoveryResponse(raw, packet.address.hostAddress)
                        } catch (_: Exception) {
                            break
                        }
                    }
                    delay(2000)
                }
            } catch (e: Exception) {
                DroidLog.w(DroidLog.TAG_NETWORK, "UDP LAN discovery probe ended: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                _isScanning.value = false
            }
        }
    }

    private fun parseUdpDiscoveryResponse(raw: String, fallbackIp: String?) {
        try {
            val json = JSONObject(raw)
            if (json.optString("service") != "droidcast-signaling") return
            val port = json.optInt("port", 3000)
            val name = json.optString("name", "Laptop Receiver")
            val addresses = json.optJSONArray("addresses")
            val ip = if (addresses != null && addresses.length() > 0) {
                addresses.optString(0, fallbackIp ?: "")
            } else {
                fallbackIp ?: ""
            }
            if (ip.isBlank()) return

            upsertEndpoint(
                DiscoveredEndpoint(
                    id = "udp:$ip:$port",
                    name = name,
                    host = ip,
                    port = port,
                    source = "LAN",
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun upsertEndpoint(endpoint: DiscoveredEndpoint) {
        val current = _endpoints.value
        val filtered = current.filterNot { it.host == endpoint.host && it.port == endpoint.port }
        _endpoints.value = filtered + endpoint
        DroidLog.i(DroidLog.TAG_NETWORK, "Discovered signaling server ${endpoint.name} at ${endpoint.displayAddress} (${endpoint.source})")
    }
}
