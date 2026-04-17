package com.alertnet.app.db

import com.alertnet.app.Message
import com.alertnet.app.MessageStatus

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        senderId = senderId,
        receiverId = receiverId,
        payload = payload,
        timestamp = timestamp,
        status = status.name,
        retryCount = retryCount,
        lastAttemptTime = lastAttemptTime
    )
}

fun MessageEntity.toModel(): Message {
    return Message(
        id = id,
        senderId = senderId,
        receiverId = receiverId,
        payload = payload,
        timestamp = timestamp,
        status = MessageStatus.valueOf(status),
        lastAttemptTime = lastAttemptTime,
        retryCount = retryCount
    )
}
