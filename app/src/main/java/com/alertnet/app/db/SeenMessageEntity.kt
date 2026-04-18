package com.alertnet.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks message IDs that this device has already seen/processed.
 * Prevents duplicate processing in the mesh network.
 * The [seenAt] timestamp enables periodic cleanup of old records.
 */
@Entity(tableName = "seen_messages")
data class SeenMessageEntity(
    @PrimaryKey val id: String,
    val seenAt: Long = System.currentTimeMillis()
)
