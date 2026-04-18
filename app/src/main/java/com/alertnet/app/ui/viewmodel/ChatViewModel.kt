package com.alertnet.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alertnet.app.mesh.MeshManager
import com.alertnet.app.model.DeliveryStatus
import com.alertnet.app.model.MeshMessage
import com.alertnet.app.model.MessageType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

/**
 * ViewModel for the chat screen.
 *
 * Provides reactive message list, send operations, and message expiry
 * when the user navigates away from the chat.
 */
class ChatViewModel(
    private val meshManager: MeshManager,
    val deviceId: String
) : ViewModel() {

    private var currentPeerId: String? = null

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    /** Messages in the current conversation */
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    private val _sendingState = MutableStateFlow<SendingState>(SendingState.Idle)
    val sendingState: StateFlow<SendingState> = _sendingState.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Set the peer we're chatting with and start polling the conversation.
     */
    fun openConversation(peerId: String) {
        currentPeerId = peerId
        startPolling()

        // Also observe ACK-driven delivery updates
        viewModelScope.launch {
            meshManager.ackTracker.deliveredMessages.collect { deliveredIds ->
                refreshMessages()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && currentPeerId != null) {
                refreshMessages()
                delay(2000L) // Poll every 2 seconds
            }
        }
    }

    private suspend fun refreshMessages() {
        currentPeerId?.let { peerId ->
            try {
                val msgs = meshManager.getConversation(peerId)
                _messages.value = msgs
            } catch (e: Exception) {
                // Ignore DB errors on refresh
            }
        }
    }

    /**
     * Send a text message to the current peer.
     */
    fun sendTextMessage(text: String) {
        val peerId = currentPeerId ?: return
        if (text.isBlank()) return

        _sendingState.value = SendingState.Sending

        viewModelScope.launch {
            try {
                meshManager.sendTextMessage(peerId, text)
                refreshMessages()
                _sendingState.value = SendingState.Idle
            } catch (e: Exception) {
                _sendingState.value = SendingState.Error(e.message ?: "Send failed")
            }
        }
    }

    /**
     * Send a broadcast message (no specific target).
     */
    fun sendBroadcastMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                meshManager.sendTextMessage(null, text)
            } catch (e: Exception) {
                _sendingState.value = SendingState.Error(e.message ?: "Broadcast failed")
            }
        }
    }

    /**
     * Send a file/image to the current peer.
     */
    fun sendFile(uri: Uri) {
        val peerId = currentPeerId

        _sendingState.value = SendingState.Sending

        viewModelScope.launch {
            try {
                meshManager.sendFile(peerId, uri)
                refreshMessages()
                _sendingState.value = SendingState.Idle
            } catch (e: Exception) {
                _sendingState.value = SendingState.Error(e.message ?: "File send failed")
            }
        }
    }

    /**
     * Decrypt a message payload for display.
     */
    fun decryptPayload(message: MeshMessage): String {
        return when (message.type) {
            MessageType.TEXT -> meshManager.decryptPayload(message.payload)
            MessageType.ACK -> "✓ Delivered"
            else -> message.fileName ?: "File"
        }
    }

    /**
     * Called when user navigates back from the chat screen.
     * Deletes conversation messages per the expiry policy.
     */
    fun onBackPressed() {
        val peerId = currentPeerId ?: return
        pollJob?.cancel()
        viewModelScope.launch {
            meshManager.expireConversation(peerId)
        }
        currentPeerId = null
    }

    override fun onCleared() {
        super.onCleared()
        // Expire conversation when ViewModel is destroyed
        currentPeerId?.let { peerId ->
            viewModelScope.launch {
                meshManager.expireConversation(peerId)
            }
        }
    }
}

/**
 * State of the message sending operation.
 */
sealed class SendingState {
    data object Idle : SendingState()
    data object Sending : SendingState()
    data class Error(val message: String) : SendingState()
}
