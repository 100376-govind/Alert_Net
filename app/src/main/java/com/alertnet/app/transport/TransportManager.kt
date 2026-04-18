package com.alertnet.app.transport

import android.content.Context
import android.util.Log
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.transport.ble.BleConstants
import com.alertnet.app.transport.ble.BleTransport
import com.alertnet.app.transport.wifidirect.WiFiDirectTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Orchestrates multiple transport layers (BLE + WiFi Direct).
 *
 * Provides a unified API for sending/receiving messages and discovering peers,
 * merging results from both transports. Automatically selects the best
 * transport based on payload size:
 * - Small messages (<500 bytes): BLE preferred (lower power)
 * - Large payloads: WiFi Direct required (higher throughput)
 *
 * If a peer is reachable via both transports, both entries are merged
 * into a single MeshPeer with [TransportType.BOTH].
 */
class TransportManager(
    private val context: Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "TransportManager"
    }

    private val bleTransport = BleTransport(context, deviceId)
    private val wifiDirectTransport = WiFiDirectTransport(context, deviceId)
    private val transports: List<Transport> = listOf(bleTransport, wifiDirectTransport)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Merged view of all peers from both transports.
     * Peers reachable via both transports are merged into one entry.
     */
    val allPeers: StateFlow<List<MeshPeer>> = combine(
        bleTransport.discoveredPeers,
        wifiDirectTransport.discoveredPeers
    ) { blePeers, wifiPeers ->
        mergePeers(blePeers, wifiPeers)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Merged stream of incoming messages from all transports.
     */
    val incomingMessages: SharedFlow<TransportMessage> = merge(
        bleTransport.incomingMessages,
        wifiDirectTransport.incomingMessages
    ).shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /**
     * Merged connection events from all transports.
     */
    val connectionEvents: SharedFlow<ConnectionEvent> = merge(
        bleTransport.connectionEvents,
        wifiDirectTransport.connectionEvents
    ).shareIn(scope, SharingStarted.Eagerly, replay = 0)

    // ─── Lifecycle ───────────────────────────────────────────────

    suspend fun start() {
        Log.d(TAG, "Starting all transports")
        coroutineScope {
            launch { bleTransport.start() }
            launch { wifiDirectTransport.start() }
        }
        Log.d(TAG, "All transports started")
    }

    suspend fun stop() {
        Log.d(TAG, "Stopping all transports")
        coroutineScope {
            launch { bleTransport.stop() }
            launch { wifiDirectTransport.stop() }
        }
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "All transports stopped")
    }

    // ─── Send ────────────────────────────────────────────────────

    /**
     * Send data to a specific peer, using the best available transport.
     *
     * Strategy:
     * 1. If payload > BLE limit → WiFi Direct only
     * 2. If peer reachable via WiFi Direct → prefer WiFi Direct
     * 3. If peer reachable via BLE only → use BLE
     * 4. Try both as fallback
     */
    suspend fun sendToPeer(peerId: String, data: ByteArray): Boolean = coroutineScope {
        val peer = findPeer(peerId)
        val isLargePayload = data.size > BleConstants.MAX_BLE_PAYLOAD

        if (isLargePayload) {
            Log.d(TAG, "Large payload (${data.size} bytes), sending via WiFi Direct")
            return@coroutineScope wifiDirectTransport.sendMessage(peerId, data)
        }

        Log.d(TAG, "Sending ${data.size} bytes to $peerId concurrently via available transports")

        // For small messages, race them concurrently! Fastest transport wins.
        // This eliminates the 15-second sequential fallback latency.
        val deferredWifi = async { wifiDirectTransport.sendMessage(peerId, data) }
        val deferredBle = async { bleTransport.sendMessage(peerId, data) }

        // Wait for WiFi Direct first (since it's much faster if connected)
        val wifiResult = deferredWifi.await()
        if (wifiResult) {
            // WiFi succeeded, no need to wait for BLE (cancel to save battery/overhead)
            deferredBle.cancel()
            Log.d(TAG, "Sent successfully via WiFi Direct")
            return@coroutineScope true
        }

        // If WiFi failed (e.g. IP not resolved), wait for BLE
        val bleResult = deferredBle.await()
        if (bleResult) {
            Log.d(TAG, "Sent successfully via BLE")
        } else {
            Log.w(TAG, "All transports failed to send to $peerId")
        }
        
        return@coroutineScope bleResult
    }

    /**
     * Broadcast data to all known peers via all transports.
     */
    suspend fun broadcastToAll(data: ByteArray, exclude: Set<String> = emptySet()) {
        coroutineScope {
            for (transport in transports) {
                launch {
                    try {
                        transport.broadcastMessage(data, exclude)
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast via ${transport.transportType} failed", e)
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    /**
     * Upgrades a peer's identity across all transports from a temporary ID (like MAC address)
     * to its actual mesh UUID.
     */
    fun upgradePeerId(oldId: String, newId: String) {
        for (transport in transports) {
            transport.upgradePeerId(oldId, newId)
        }
    }

    /**
     * Select the best transport for a given peer and payload size.
     */
    private fun getBestTransport(peer: MeshPeer?, payloadSize: Int): Transport {
        // Large payloads must go over WiFi Direct
        if (payloadSize > BleConstants.MAX_BLE_PAYLOAD) {
            return wifiDirectTransport
        }

        // Use peer's known transport type
        return when (peer?.transportType) {
            TransportType.WIFI_DIRECT -> wifiDirectTransport
            TransportType.BLE -> bleTransport
            TransportType.BOTH -> wifiDirectTransport // prefer WiFi Direct for reliability
            null -> wifiDirectTransport // default
        }
    }

    private fun findPeer(peerId: String): MeshPeer? {
        return allPeers.value.find { it.deviceId == peerId }
    }

    /**
     * Merge peers from BLE and WiFi Direct, deduplicating by MAC address.
     * Peers seen on both transports get [TransportType.BOTH].
     */
    private fun mergePeers(blePeers: List<MeshPeer>, wifiPeers: List<MeshPeer>): List<MeshPeer> {
        val merged = mutableMapOf<String, MeshPeer>()

        for (peer in wifiPeers) {
            val key = peer.macAddress ?: peer.deviceId
            merged[key] = peer
        }

        for (peer in blePeers) {
            val key = peer.macAddress ?: peer.deviceId
            val existing = merged[key]
            if (existing != null) {
                // Merge: combine info from both transports
                merged[key] = existing.copy(
                    transportType = TransportType.BOTH,
                    rssi = peer.rssi ?: existing.rssi,
                    macAddress = peer.macAddress ?: existing.macAddress,
                    ipAddress = existing.ipAddress ?: peer.ipAddress,
                    lastSeen = maxOf(peer.lastSeen, existing.lastSeen),
                    isConnected = peer.isConnected || existing.isConnected
                )
            } else {
                merged[key] = peer
            }
        }

        return merged.values.toList()
    }
}
