package com.alertnet.app.repository

import android.util.Log
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.db.toEntity
import com.alertnet.app.db.toModel
import com.alertnet.app.model.DeliveryStatus
import com.alertnet.app.model.MeshMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for mesh message persistence.
 *
 * Wraps Room DAO operations with domain-level APIs, exposing
 * Flow-based observation for reactive UI updates.
 */
class MessageRepository {

    companion object {
        private const val TAG = "MessageRepository"
        /** Messages with TTL 0 older than this are cleaned up */
        private const val EXPIRED_CLEANUP_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val messageDao get() = DatabaseProvider.db.messageDao()
    private val seenMessageDao get() = DatabaseProvider.db.seenMessageDao()

    // ─── Message CRUD ────────────────────────────────────────────

    /**
     * Insert or replace a message.
     */
    suspend fun insertMessage(message: MeshMessage) {
        try {
            messageDao.insertMessage(message.toEntity())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert message ${message.id}", e)
        }
    }

    /**
     * Update a message's delivery status.
     */
    suspend fun updateStatus(messageId: String, status: DeliveryStatus) {
        try {
            messageDao.updateStatus(messageId, status.name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update status for $messageId", e)
        }
    }

    /**
     * Get a message by its ID.
     */
    suspend fun getById(messageId: String): MeshMessage? {
        return try {
            messageDao.getById(messageId)?.toModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get message $messageId", e)
            null
        }
    }

    // ─── Conversation Queries ────────────────────────────────────

    /**
     * Observe conversation with a specific peer (reactive Flow).
     * Returns only TEXT, IMAGE, FILE messages sorted by timestamp.
     */
    fun getConversation(peerId: String): Flow<List<MeshMessage>> {
        return messageDao.getConversation(peerId).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * Delete all messages in a conversation.
     * Called when user backs out of chat (message expiry policy).
     */
    suspend fun deleteConversation(peerId: String) {
        try {
            messageDao.deleteConversation(peerId)
            Log.d(TAG, "Deleted conversation with $peerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete conversation with $peerId", e)
        }
    }

    // ─── Store-and-Forward Queue ─────────────────────────────────

    /**
     * Get messages that need to be relayed to other peers.
     */
    suspend fun getQueuedForRelay(): List<MeshMessage> {
        return try {
            messageDao.getPendingForRelay().map { it.toModel() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get relay queue", e)
            emptyList()
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────

    /**
     * Delete expired messages and old deduplication records.
     */
    suspend fun cleanupExpired() {
        try {
            val cutoff = System.currentTimeMillis() - EXPIRED_CLEANUP_AGE_MS
            messageDao.deleteExpired(cutoff)
            seenMessageDao.deleteOlderThan(cutoff)
            Log.d(TAG, "Cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
        }
    }

    // ─── Statistics ──────────────────────────────────────────────

    suspend fun countByStatus(status: DeliveryStatus): Int {
        return try {
            messageDao.countByStatus(status.name)
        } catch (e: Exception) {
            0
        }
    }

    suspend fun countDataMessages(): Int {
        return try {
            messageDao.countDataMessages()
        } catch (e: Exception) {
            0
        }
    }

    // ─── Seen Messages ───────────────────────────────────────────

    suspend fun markSeen(messageId: String) {
        try {
            seenMessageDao.insertSeen(
                com.alertnet.app.db.SeenMessageEntity(id = messageId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark seen: $messageId", e)
        }
    }

    suspend fun hasSeen(messageId: String): Boolean {
        return try {
            seenMessageDao.hasSeen(messageId)
        } catch (e: Exception) {
            false
        }
    }
}
