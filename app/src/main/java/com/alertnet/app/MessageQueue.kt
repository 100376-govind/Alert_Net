package com.alertnet.app

import android.util.Log
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.db.toEntity
import com.alertnet.app.db.toModel

object MessageQueue {

    private val pendingMessages = mutableListOf<Message>()
    private val outgoingQueue = mutableListOf<Message>()

    @Synchronized
    fun addNewMessage(message: Message) {
        message.status = MessageStatus.PENDING
        message.retryCount = 0
        message.lastAttemptTime = System.currentTimeMillis()
        pendingMessages.add(message)
        outgoingQueue.add(message)
        DatabaseProvider.db.messageDao().insertMessage(message.toEntity())
        Log.d("MessageQueue", "Added message ${message.id} | pending=${pendingMessages.size} outgoing=${outgoingQueue.size}")
    }

    @Synchronized
    fun addReceivedMessage(message: Message) {
        pendingMessages.add(message)
        DatabaseProvider.db.messageDao().insertMessage(message.toEntity())
        Log.d("MessageQueue", "Received message ${message.id} | pending=${pendingMessages.size}")
    }

    @Synchronized
    fun markAsSent(messageId: String) {
        val msg = pendingMessages.find { it.id == messageId }
        msg?.status = MessageStatus.SENT
        outgoingQueue.removeAll { it.id == messageId }
        msg?.let { DatabaseProvider.db.messageDao().updateMessage(it.toEntity()) }
        Log.d("MessageQueue", "Marked $messageId as SENT | outgoing=${outgoingQueue.size}")
    }

    @Synchronized
    fun markAsFailed(messageId: String) {
        val msg = pendingMessages.find { it.id == messageId }
        msg?.status = MessageStatus.FAILED
        msg?.let { DatabaseProvider.db.messageDao().updateMessage(it.toEntity()) }
        Log.d("MessageQueue", "Marked $messageId as FAILED")
    }

    @Synchronized
    fun getPendingMessages(): List<Message> {
        return pendingMessages.toList()
    }

    @Synchronized
    fun getOutgoingMessages(): List<Message> {
        return outgoingQueue.toList()
    }

    /**
     * Attempt to send a single message using the provided sendFunction.
     * On success → markAsSent. On failure → increment retryCount, update lastAttemptTime.
     */
    fun attemptSend(message: Message, sendFunction: (Message) -> Boolean) {
        val success = sendFunction(message)
        if (success) {
            markAsSent(message.id)
        } else {
            synchronized(this) {
                message.retryCount++
                message.lastAttemptTime = System.currentTimeMillis()
                DatabaseProvider.db.messageDao().updateMessage(message.toEntity())
            }
            Log.d("MessageQueue", "Send failed for ${message.id} | retryCount=${message.retryCount}")
        }
    }

    /**
     * Retry all PENDING messages in the outgoing queue.
     * Called only on connection events — no timers, no loops.
     */
    fun retryPendingMessages(sendFunction: (Message) -> Boolean) {
        val toRetry = synchronized(this) {
            outgoingQueue.filter { it.status == MessageStatus.PENDING || it.status == MessageStatus.FAILED }.toList()
        }

        Log.d("MessageQueue", "Retrying ${toRetry.size} pending messages")

        for (message in toRetry) {
            attemptSend(message, sendFunction)
        }
    }

    /**
     * Rebuild in-memory queues from DB. Call once on app startup after DatabaseProvider.init().
     */
    @Synchronized
    fun rehydrate() {
        val allFromDb = DatabaseProvider.db.messageDao().getAllMessages().map { it.toModel() }

        pendingMessages.clear()
        outgoingQueue.clear()

        for (msg in allFromDb) {
            pendingMessages.add(msg)
            if (msg.status == MessageStatus.PENDING || msg.status == MessageStatus.FAILED) {
                outgoingQueue.add(msg)
            }
        }

        Log.d("MessageQueue", "Rehydrated | pending=${pendingMessages.size} outgoing=${outgoingQueue.size}")
    }
}
