package com.alertnet.app

import java.util.UUID

enum class MessageStatus {
    PENDING,
    SENT,
    FAILED
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String?,
    val payload: String,
    var timestamp: Long = System.currentTimeMillis(),
    var status: MessageStatus = MessageStatus.PENDING,
    var lastAttemptTime: Long = System.currentTimeMillis(),
    var retryCount: Int = 0
)
