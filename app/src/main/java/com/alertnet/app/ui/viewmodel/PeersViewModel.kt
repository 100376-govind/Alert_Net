package com.alertnet.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alertnet.app.mesh.DiscoveryState
import com.alertnet.app.mesh.MeshManager
import com.alertnet.app.mesh.MeshStats
import com.alertnet.app.model.MeshPeer
import kotlinx.coroutines.flow.*

/**
 * ViewModel for the peers/discovery screen and mesh stats.
 */
class PeersViewModel(
    private val meshManager: MeshManager
) : ViewModel() {

    /** Currently active mesh peers */
    val peers: StateFlow<List<MeshPeer>> = meshManager.activePeers

    /** Real-time mesh statistics */
    val meshStats: StateFlow<MeshStats> = meshManager.meshStats

    /** Current discovery state machine phase */
    val discoveryState: StateFlow<DiscoveryState> = meshManager.discoveryState

    /** Whether any scan is currently active (derived from discoveryState) */
    val isDiscovering: StateFlow<Boolean> = discoveryState
        .map { it != DiscoveryState.IDLE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Trigger an immediate peer discovery sweep.
     * Cancels any ongoing scan cycle and starts a fresh BLE → WiFi scan.
     */
    fun refreshPeers() {
        meshManager.peerDiscoveryManager.requestScan()
    }
}
