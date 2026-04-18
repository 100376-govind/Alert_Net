package com.alertnet.app.mesh

/**
 * Configuration for peer discovery timing.
 *
 * Centralised so that unit tests can inject shorter timeouts
 * and production code has sensible defaults.
 */
data class DiscoveryConfig(
    /** How long to run BLE scanning before falling back to WiFi Direct */
    val bleScanTimeoutMs: Long = 10_000L,
    /** Maximum duration for the WiFi Direct fallback scan */
    val wifiScanTimeoutMs: Long = 30_000L,
    /** Cooldown between full scan cycles to save battery */
    val scanCooldownMs: Long = 45_000L,
    /** A BLE peer must be seen at least twice within this window to count */
    val debounceMs: Long = 2_000L,
    /** Peers not seen for this duration are considered stale */
    val peerExpiryMs: Long = 5 * 60 * 1000L,
    /** How often to run the stale-peer cleanup */
    val cleanupIntervalMs: Long = 60_000L
)
