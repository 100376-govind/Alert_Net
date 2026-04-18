package com.alertnet.app.model

/**
 * Represents a peer device discovered on the mesh network.
 *
 * A peer can be reachable via BLE, WiFi Direct, or both. The [lastSeen]
 * timestamp is used to determine staleness — peers not seen for 5+ minutes
 * are considered inactive.
 *
 * @property deviceId Unique per-install UUID of the peer
 * @property displayName Human-readable name (device model or user-set name)
 * @property lastSeen Epoch millis when this peer was last detected
 * @property rssi BLE signal strength (null if discovered via WiFi Direct only)
 * @property transportType How this peer is currently reachable
 * @property ipAddress WiFi Direct IP address (null if BLE only)
 * @property macAddress BLE MAC address (null if WiFi Direct only)
 * @property discoveryType How this peer was initially discovered (BLE or WIFI_DIRECT)
 * @property isConnected Whether an active transport connection exists
 */
data class MeshPeer(
    val deviceId: String,
    val displayName: String = "Unknown",
    val lastSeen: Long = System.currentTimeMillis(),
    val rssi: Int? = null,
    val transportType: TransportType = TransportType.WIFI_DIRECT,
    val discoveryType: TransportType = TransportType.BLE,
    val ipAddress: String? = null,
    val macAddress: String? = null,
    val isConnected: Boolean = false
)
