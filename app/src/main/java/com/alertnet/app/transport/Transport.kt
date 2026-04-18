package com.alertnet.app.transport

import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over a communication transport (BLE or WiFi Direct).
 *
 * Each transport implementation handles discovery, connection management,
 * and data transfer for its specific medium. The [TransportManager]
 * orchestrates multiple transports and exposes a unified API.
 */
interface Transport {

    /** The type of transport this implementation provides */
    val transportType: TransportType

    /** Currently discovered peers via this transport */
    val discoveredPeers: StateFlow<List<MeshPeer>>

    /** Incoming messages received through this transport */
    val incomingMessages: SharedFlow<TransportMessage>

    /** Connection lifecycle events */
    val connectionEvents: SharedFlow<ConnectionEvent>

    /** Start discovery and listening */
    suspend fun start()

    /** Stop all transport activity */
    suspend fun stop()

    /**
     * Send raw data to a specific peer.
     * @return true if the send was successful
     */
    suspend fun sendMessage(peerId: String, data: ByteArray): Boolean

    /**
     * Broadcast raw data to all connected/discovered peers.
     * @param excludePeers Set of peer IDs to skip (e.g., the sender)
     */
    suspend fun broadcastMessage(data: ByteArray, excludePeers: Set<String> = emptySet())
}
