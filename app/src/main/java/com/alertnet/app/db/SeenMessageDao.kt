package com.alertnet.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data access object for the seen-message deduplication table.
 */
@Dao
interface SeenMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeen(entity: SeenMessageEntity)

    @Query("SELECT COUNT(*) > 0 FROM seen_messages WHERE id = :messageId")
    suspend fun hasSeen(messageId: String): Boolean

    @Query("SELECT * FROM seen_messages")
    suspend fun getAllSeen(): List<SeenMessageEntity>

    /**
     * Clean up deduplication records older than 24 hours
     * to prevent unbounded table growth.
     */
    @Query("DELETE FROM seen_messages WHERE seenAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM seen_messages")
    suspend fun count(): Int
}
