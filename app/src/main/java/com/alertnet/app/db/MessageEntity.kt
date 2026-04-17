package com.alertnet.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val receiverId: String?,
    val payload: String,
    val timestamp: Long,
    val status: String,
    val retryCount: Int,
    val lastAttemptTime: Long
)
