package com.alertnet.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for mesh messages.
 * All operations are suspend or Flow-based to avoid blocking the main thread.
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageEntity?

    /**
     * Get conversation between this device and a specific peer.
     * Returns messages where either sender or target matches the peer ID.
     */
    @Query("""
        SELECT * FROM messages 
        WHERE (senderId = :peerId OR targetId = :peerId) 
        AND type IN ('TEXT', 'IMAGE', 'FILE')
        ORDER BY timestamp ASC
    """)
    fun getConversation(peerId: String): Flow<List<MessageEntity>>

    /**
     * Get messages that need to be relayed to other peers.
     * These are messages in QUEUED or SENT status with remaining TTL.
     */
    @Query("""
        SELECT * FROM messages 
        WHERE status IN ('QUEUED', 'SENT') 
        AND ttl > 0
        AND type IN ('TEXT', 'IMAGE', 'FILE', 'ACK')
        ORDER BY timestamp ASC
    """)
    suspend fun getPendingForRelay(): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    /**
     * Delete all conversation messages for a specific peer.
     * Called when user backs out of chat screen (message expiry policy).
     */
    @Query("""
        DELETE FROM messages 
        WHERE (senderId = :peerId OR targetId = :peerId) 
        AND type IN ('TEXT', 'IMAGE', 'FILE')
    """)
    suspend fun deleteConversation(peerId: String)

    /**
     * Delete expired messages (TTL = 0 and older than given timestamp).
     */
    @Query("DELETE FROM messages WHERE ttl <= 0 AND timestamp < :before")
    suspend fun deleteExpired(before: Long)

    /**
     * Count messages by status for mesh statistics.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE type NOT IN ('ACK', 'PEER_ANNOUNCE', 'PEER_LEAVE')")
    suspend fun countDataMessages(): Int
}
