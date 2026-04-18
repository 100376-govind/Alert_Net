package com.alertnet.app.mesh

import android.util.Log
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.db.PeerQueries
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.transport.ConnectionEvent
import com.alertnet.app.transport.TransportManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Orchestrates BLE-first peer discovery with WiFi Direct fallback.
 *
 * State machine:
 *   IDLE → SCANNING_BLE → (BLE_FOUND | FALLBACK_WIFI_SCAN → WIFI_FOUND) → IDLE
 *
 * Design decisions:
 * - Only ONE scan type is active at a time (battery conservation)
 * - BLE peers are debounced: must be seen ≥2 times within [DiscoveryConfig.debounceMs]
 *   to filter out transient hits from devices passing by
 * - WiFi Direct discovery only activates when BLE produces zero debounced peers
 * - Connection events from transports still flow through for real-time updates
 * - Stale peer cleanup runs on a separate timer
 */
class PeerDiscoveryManager(
    private val transportManager: TransportManager,
    private val config: DiscoveryConfig = DiscoveryConfig()
) {
    companion object {
        private const val TAG = "PeerDiscoveryManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanCycleJob: Job? = null

    // ─── Public Flows ────────────────────────────────────────────

    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    /** Current phase of the discovery state machine */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _activePeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    /** Currently active mesh peers */
    val activePeers: StateFlow<List<MeshPeer>> = _activePeers.asStateFlow()

    /**
     * Tracks how many times each BLE peer has been seen in the current scan window.
     * Key = MAC address or device ID, Value = list of timestamps when seen.
     */
    private val bleSightings = mutableMapOf<String, MutableList<Long>>()

    // ─── Lifecycle ───────────────────────────────────────────────

    /**
     * Start the discovery state machine and background loops.
     */
    fun start() {
        // Observe connection events for real-time updates
        scope.launch {
            transportManager.connectionEvents.collect { event ->
                when (event) {
                    is ConnectionEvent.PeerConnected -> {
                        Log.d(TAG, "Peer connected: ${event.peer.deviceId}")
                        try {
                            PeerQueries.insertPeer(DatabaseProvider.db, event.peer)
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
                delay(config.cleanupIntervalMs)
                cleanupStalePeers()
                refreshActivePeers()
            }
        }

        // Start the main scan cycle
        startScanCycle()
    }

    fun stop() {
        scanCycleJob?.cancel()
        scope.coroutineContext.cancelChildren()
        _discoveryState.value = DiscoveryState.IDLE
    }

    /**
     * Request an immediate scan cycle (e.g., from pull-to-refresh).
     * Cancels any ongoing cycle and starts a fresh one.
     */
    fun requestScan() {
        scanCycleJob?.cancel()
        startScanCycle()
    }

    // ─── Scan Cycle State Machine ────────────────────────────────

    private fun startScanCycle() {
        scanCycleJob = scope.launch {
            while (isActive) {
                runScanCycle()
                _discoveryState.value = DiscoveryState.IDLE
                delay(config.scanCooldownMs)
            }
        }
    }

    /**
     * Execute one full scan cycle:
     * 1. BLE scan for [config.bleScanTimeoutMs]
     * 2. If debounced peers found → BLE_FOUND, done
     * 3. Else → fallback to WiFi Direct for [config.wifiScanTimeoutMs]
     */
    private suspend fun runScanCycle() {
        // ── Phase 1: BLE Scan ──
        _discoveryState.value = DiscoveryState.SCANNING_BLE
        bleSightings.clear()

        Log.d(TAG, "Starting BLE scan phase (${config.bleScanTimeoutMs}ms timeout)")
        transportManager.startBleDiscovery()

        // Collect BLE peer results during the scan window
        val bleCollectJob = scope.launch {
            transportManager.blePeers.collect { peers ->
                val now = System.currentTimeMillis()
                for (peer in peers) {
                    val key = peer.macAddress ?: peer.deviceId
                    val sightings = bleSightings.getOrPut(key) { mutableListOf() }
                    sightings.add(now)
                    // Prune sightings older than debounce window
                    sightings.removeAll { now - it > config.debounceMs }
                }
            }
        }

        delay(config.bleScanTimeoutMs)
        transportManager.stopBleDiscovery()
        bleCollectJob.cancel()

        // Check for debounced peers (seen ≥2 times within debounce window)
        val debouncedPeers = getDebouncedBlePeers()

        if (debouncedPeers.isNotEmpty()) {
            _discoveryState.value = DiscoveryState.BLE_FOUND
            Log.d(TAG, "BLE found ${debouncedPeers.size} debounced peers")
            persistPeers(debouncedPeers, TransportType.BLE)
            refreshActivePeers()
            return
        }

        // ── Phase 2: WiFi Direct Fallback ──
        Log.d(TAG, "No BLE peers found, falling back to WiFi Direct (${config.wifiScanTimeoutMs}ms)")
        _discoveryState.value = DiscoveryState.FALLBACK_WIFI_SCAN

        transportManager.startWifiDiscovery()

        // Wait and collect WiFi Direct peers
        var wifiPeersFound = false
        val wifiCollectJob = scope.launch {
            transportManager.wifiPeers.collect { peers ->
                if (peers.isNotEmpty()) {
                    wifiPeersFound = true
                }
            }
        }

        delay(config.wifiScanTimeoutMs)
        transportManager.stopWifiDiscovery()
        wifiCollectJob.cancel()

        val currentWifiPeers = transportManager.wifiPeers.value
        if (currentWifiPeers.isNotEmpty()) {
            _discoveryState.value = DiscoveryState.WIFI_FOUND
            Log.d(TAG, "WiFi Direct found ${currentWifiPeers.size} peers")
            persistPeers(currentWifiPeers, TransportType.WIFI_DIRECT)
            refreshActivePeers()
        } else {
            Log.d(TAG, "No peers found in either scan phase")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    /**
     * Get BLE peers that have been seen at least twice within the debounce window.
     */
    private fun getDebouncedBlePeers(): List<MeshPeer> {
        val now = System.currentTimeMillis()
        val confirmedKeys = bleSightings.filter { (_, sightings) ->
            val recent = sightings.filter { now - it <= config.debounceMs }
            recent.size >= 2
        }.keys

        return transportManager.blePeers.value.filter { peer ->
            val key = peer.macAddress ?: peer.deviceId
            key in confirmedKeys
        }
    }

    /**
     * Persist discovered peers to the database, tagging with discovery type.
     */
    private suspend fun persistPeers(peers: List<MeshPeer>, discoveryType: TransportType) {
        for (peer in peers) {
            try {
                val tagged = peer.copy(discoveryType = discoveryType)
                PeerQueries.insertPeer(DatabaseProvider.db, tagged)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist peer: ${peer.deviceId}", e)
            }
        }
    }

    /**
     * Refresh the active peers list from the database.
     */
    private suspend fun refreshActivePeers() {
        try {
            val since = System.currentTimeMillis() - config.peerExpiryMs
            val peers = PeerQueries.getActivePeers(DatabaseProvider.db, since)
            _activePeers.value = peers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh peers", e)
        }
    }

    /**
     * Remove peers not seen for more than [DiscoveryConfig.peerExpiryMs].
     */
    private suspend fun cleanupStalePeers() {
        try {
            val cutoff = System.currentTimeMillis() - config.peerExpiryMs
            PeerQueries.deleteStale(DatabaseProvider.db, cutoff)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up stale peers", e)
        }
    }
}
