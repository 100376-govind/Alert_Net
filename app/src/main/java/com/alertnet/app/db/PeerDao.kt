package com.alertnet.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data access object for known mesh peers.
 */
@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Update
    suspend fun updatePeer(peer: PeerEntity)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    suspend fun getAllPeers(): List<PeerEntity>

    /**
     * Get peers that have been seen within the given time window.
     * @param since epoch millis — peers with lastSeen >= since are returned
     */
    @Query("SELECT * FROM peers WHERE lastSeen >= :since ORDER BY lastSeen DESC")
    suspend fun getActivePeers(since: Long): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getByDeviceId(deviceId: String): PeerEntity?

    @Query("UPDATE peers SET lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, lastSeen: Long)

    @Query("DELETE FROM peers WHERE lastSeen < :before")
    suspend fun deleteStale(before: Long)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)
}
