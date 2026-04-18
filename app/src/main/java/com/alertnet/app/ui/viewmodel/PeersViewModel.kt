package com.alertnet.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alertnet.app.mesh.MeshManager
import com.alertnet.app.mesh.MeshStats
import com.alertnet.app.model.MeshPeer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Trigger a manual peer discovery sweep.
     */
    fun refreshPeers() {
        viewModelScope.launch {
            _isDiscovering.value = true
            // Discovery is handled by TransportManager automatically,
            // but we signal the UI refresh state
            kotlinx.coroutines.delay(3000)
            _isDiscovering.value = false
        }
    }
}
