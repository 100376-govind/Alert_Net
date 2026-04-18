package com.alertnet.app.mesh

/**
 * Represents the current phase of the peer discovery state machine.
 *
 * Flow: IDLE → SCANNING_BLE → (BLE_FOUND | FALLBACK_WIFI_SCAN → WIFI_FOUND) → IDLE
 */
enum class DiscoveryState {
    /** Not actively scanning */
    IDLE,
    /** BLE scan in progress, waiting for results */
    SCANNING_BLE,
    /** Peers discovered via BLE, scan paused */
    BLE_FOUND,
    /** BLE scan timed out with no peers, WiFi Direct discovery active */
    FALLBACK_WIFI_SCAN,
    /** Peers discovered via WiFi Direct fallback */
    WIFI_FOUND
}
