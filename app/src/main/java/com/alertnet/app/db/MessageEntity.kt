package com.alertnet.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting mesh messages.
 *
 * Indices on senderId, targetId, and status enable efficient queries
 * for conversation loading, relay queue, and status updates.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("senderId"),
        Index("targetId"),
        Index("status"),
        Index("timestamp")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val targetId: String?,
    val type: String,
    val payload: String,
    val fileName: String?,
    val mimeType: String?,
    val timestamp: Long,
    val ttl: Int,
    val hopCount: Int,
    val hopPath: String,        // JSON-serialized List<String>
    val status: String,
    val ackForMessageId: String?
)
