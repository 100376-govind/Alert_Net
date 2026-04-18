package com.alertnet.app.mesh

import android.util.Log
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.db.toEntity
import com.alertnet.app.db.toModel
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.transport.ConnectionEvent
import com.alertnet.app.transport.TransportManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages the lifecycle of discovered mesh peers.
 *
 * Responsibilities:
 * - Merges peer discoveries from all transports
 * - Persists peers to Room for state across restarts
 * - Tracks active vs stale peers (5-minute timeout)
 * - Emits a consolidated peer list as a StateFlow
 */
class PeerDiscoveryManager(
    private val transportManager: TransportManager
) {
    companion object {
        private const val TAG = "PeerDiscoveryManager"
        /** Peers not seen for this duration are considered stale */
        private const val STALE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        /** Periodic cleanup interval */
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L // 1 minute
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _activePeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    /** Currently active mesh peers */
    val activePeers: StateFlow<List<MeshPeer>> = _activePeers.asStateFlow()

    /**
     * Start observing transport peer discoveries and connection events.
     */
    fun start() {
        // Observe merged peers from TransportManager
        scope.launch {
            transportManager.allPeers.collect { peers ->
                val now = System.currentTimeMillis()
                for (peer in peers) {
                    try {
                        DatabaseProvider.db.peerDao().insertPeer(peer.toEntity())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist peer: ${peer.deviceId}", e)
                    }
                }
                refreshActivePeers()
            }
        }

        // Observe connection events for real-time updates
        scope.launch {
            transportManager.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.PeerConnected -> {
                        Log.d(TAG, "Peer connected: ${event.peer.deviceId}")
                        try {
                            DatabaseProvider.db.peerDao().insertPeer(event.peer.toEntity())
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist connected peer", e)
                        }
                        refreshActivePeers()
                    }
                    is ConnectionEvent.PeerDisconnected -> {
                        Log.d(TAG, "Peer disconnected: ${event.peerId}")
                        refreshActivePeers()
                    }
                    is ConnectionEvent.TransportError -> {
                        Log.w(TAG, "Transport error: ${event.message}")
                    }
                }
            }
        }

        // Periodic stale peer cleanup
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupStalePeers()
                refreshActivePeers()
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Refresh the active peers list from the database.
     */
    private suspend fun refreshActivePeers() {
        try {
            val since = System.currentTimeMillis() - STALE_TIMEOUT_MS
            val peers = DatabaseProvider.db.peerDao().getActivePeers(since)
            _activePeers.value = peers.map { it.toModel() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh peers", e)
        }
    }

    /**
     * Remove peers not seen for more than [STALE_TIMEOUT_MS].
     */
    private suspend fun cleanupStalePeers() {
        try {
            val cutoff = System.currentTimeMillis() - STALE_TIMEOUT_MS
            DatabaseProvider.db.peerDao().deleteStale(cutoff)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up stale peers", e)
        }
    }
}
