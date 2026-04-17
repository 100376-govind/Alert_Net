package com.alertnet.app

import android.util.Log

object MessageQueue {

    private val pendingMessages = mutableListOf<Message>()
    private val outgoingQueue = mutableListOf<Message>()

    @Synchronized
    fun addNewMessage(message: Message) {
        pendingMessages.add(message)
        outgoingQueue.add(message)
        Log.d("MessageQueue", "Added message ${message.id} | pending=${pendingMessages.size} outgoing=${outgoingQueue.size}")
    }

    @Synchronized
    fun addReceivedMessage(message: Message) {
        pendingMessages.add(message)
        Log.d("MessageQueue", "Received message ${message.id} | pending=${pendingMessages.size}")
    }

    @Synchronized
    fun markAsSent(messageId: String) {
        pendingMessages.find { it.id == messageId }?.status = MessageStatus.SENT
        outgoingQueue.removeAll { it.id == messageId }
        Log.d("MessageQueue", "Marked $messageId as SENT | outgoing=${outgoingQueue.size}")
    }

    @Synchronized
    fun getPendingMessages(): List<Message> {
        return pendingMessages.toList()
    }

    @Synchronized
    fun getOutgoingMessages(): List<Message> {
        return outgoingQueue.toList()
    }
}
