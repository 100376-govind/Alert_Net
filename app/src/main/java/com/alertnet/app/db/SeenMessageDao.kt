package com.alertnet.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeenMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSeen(id: SeenMessageEntity)

    @Query("SELECT * FROM seen_messages")
    fun getAllSeen(): List<SeenMessageEntity>
}
