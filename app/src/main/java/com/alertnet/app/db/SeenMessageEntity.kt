package com.alertnet.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_messages")
data class SeenMessageEntity(
    @PrimaryKey val id: String
)
