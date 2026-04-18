package com.alertnet.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for tracking known mesh peers.
 * Used by PeerDiscoveryManager to persist peer information across restarts.
 */
@Entity(
    tableName = "peers",
    indices = [Index("lastSeen")]
)
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val lastSeen: Long,
    val rssi: Int?,
    val transportType: String,
    val ipAddress: String?,
    val macAddress: String?
)
