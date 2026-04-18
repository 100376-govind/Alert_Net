package com.alertnet.app.mesh

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.alertnet.app.model.DeliveryStatus
import com.alertnet.app.model.MeshMessage
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.MessageType
import com.alertnet.app.repository.MessageRepository
import com.alertnet.app.security.CryptoManager
import com.alertnet.app.security.KeyManager
import com.alertnet.app.transport.TransportManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Top-level orchestrator for the AlertNet mesh network.
 *
 * MeshManager is the "brain" — it coordinates all subsystems:
 * - [TransportManager]: BLE + WiFi Direct communication
 * - [MessageRouter]: Routing decisions (deliver, forward, drop)
 * - [PeerDiscoveryManager]: Peer tracking and lifecycle
 * - [MessageRepository]: Persistent message storage
 * - [CryptoManager]: AES-256-GCM encryption/decryption
 * - [AckTracker]: Delivery acknowledgment tracking
 * - [DeduplicationManager]: Duplicate message prevention
 *
 * Background loops:
 * - Store-and-forward: retries queued messages when new peers appear
 * - Maintenance: cleans up expired data periodically
 * - ACK timeout: checks for unconfirmed deliveries
 */
class MeshManager(
    private val context: Context,
    private val deviceId: String,
    private val transportManager: TransportManager,
    private val repository: MessageRepository
) {
    companion object {
        private const val TAG = "MeshManager"
        private const val STORE_FORWARD_INTERVAL_MS = 30_000L
        private const val MAINTENANCE_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        private const val ACK_CHECK_INTERVAL_MS = 60_000L
        private const val MAX_FILE_SIZE = 20 * 1024 * 1024L // 20 MB
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deduplicationManager = DeduplicationManager()
    val ackTracker = AckTracker(deviceId)
    val messageRouter = MessageRouter(deduplicationManager, ackTracker, deviceId)
    val peerDiscoveryManager = PeerDiscoveryManager(transportManager, DiscoveryConfig())

    // ─── Public Flows ────────────────────────────────────────────

    /** Active mesh peers */
    val activePeers: StateFlow<List<MeshPeer>> = peerDiscoveryManager.activePeers

    /** Current discovery state machine phase */
    val discoveryState: StateFlow<DiscoveryState> = peerDiscoveryManager.discoveryState

    private val _meshStats = MutableStateFlow(MeshStats())
    /** Real-time mesh statistics */
    val meshStats: StateFlow<MeshStats> = _meshStats.asStateFlow()

    private var isRunning = false

    // ─── Lifecycle ───────────────────────────────────────────────

    suspend fun start() {
        if (isRunning) return
        isRunning = true

        Log.d(TAG, "Starting MeshManager for device $deviceId")

        // Initialize subsystems
        deduplicationManager.initialize()
        transportManager.start()
        peerDiscoveryManager.start()

        // Start message processing pipeline
        startMessageProcessing()

        // Start background loops
        startStoreAndForward()
        startMaintenanceLoop()
        startAckTimeoutChecker()

        // Trigger store-and-forward when new peers connect
        scope.launch {
            transportManager.connectionEvents.collect { event ->
                if (event is com.alertnet.app.transport.ConnectionEvent.PeerConnected) {
                    Log.d(TAG, "New peer connected, triggering store-and-forward")
                    retryPendingMessages()
                }
            }
        }

        updateStats()
        Log.d(TAG, "MeshManager started")
    }

    suspend fun stop() {
        isRunning = false
        peerDiscoveryManager.stop()
        transportManager.stop()
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "MeshManager stopped")
    }

    // ─── Public API (for ViewModels) ────────────────────────────

    /**
     * Send a text message to a specific peer (or broadcast if targetId is null).
     */
    suspend fun sendTextMessage(targetId: String?, text: String) {
        val encrypted = encryptPayload(text) ?: text

        val message = MeshMessage(
            id = UUID.randomUUID().toString(),
            senderId = deviceId,
            targetId = targetId,
            type = MessageType.TEXT,
            payload = encrypted,
            timestamp = System.currentTimeMillis(),
            ttl = 7,
            hopCount = 0,
            hopPath = listOf(deviceId),
            status = DeliveryStatus.QUEUED
        )

        // Mark as seen immediately (don't re-process our own messages)
        deduplicationManager.markSeen(message.id)

        // Persist
        repository.insertMessage(message)

        // Track ACK if targeted
        if (targetId != null) {
            ackTracker.expectAck(message.id)
        }

        // Send via transports
        sendViaTransport(message)

        updateStats()
    }

    /**
     * Send a file or image to a specific peer.
     */
    suspend fun sendFile(targetId: String?, uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment ?: "file"

                // Read file content
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open file: $uri")

                val fileBytes = inputStream.use { it.readBytes() }

                if (fileBytes.size > MAX_FILE_SIZE) {
                    Log.e(TAG, "File too large: ${fileBytes.size} bytes (max: $MAX_FILE_SIZE)")
                    return@withContext
                }

                // Encode as base64
                val base64Content = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                val encrypted = encryptPayload(base64Content) ?: base64Content

                val type = if (mimeType.startsWith("image/")) MessageType.IMAGE else MessageType.FILE

                val message = MeshMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = deviceId,
                    targetId = targetId,
                    type = type,
                    payload = encrypted,
                    fileName = fileName,
                    mimeType = mimeType,
                    timestamp = System.currentTimeMillis(),
                    ttl = 5, // Lower TTL for files (they're expensive to relay)
                    hopCount = 0,
                    hopPath = listOf(deviceId),
                    status = DeliveryStatus.QUEUED
                )

                deduplicationManager.markSeen(message.id)
                repository.insertMessage(message)

                if (targetId != null) {
                    ackTracker.expectAck(message.id)
                }

                sendViaTransport(message)
                updateStats()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file", e)
            }
        }
    }

    /**
     * Get conversation messages with a specific peer.
     */
    suspend fun getConversation(peerId: String): List<MeshMessage> {
        return repository.getConversation(peerId)
    }

    /**
     * Delete conversation messages when user backs out of chat (expiry policy).
     */
    suspend fun expireConversation(peerId: String) {
        repository.deleteConversation(peerId)
        Log.d(TAG, "Expired conversation with $peerId")
    }

    /**
     * Decrypt a message payload for display.
     */
    fun decryptPayload(encryptedPayload: String): String {
        val key = KeyManager.getKey(context) ?: return encryptedPayload
        return CryptoManager.decryptString(encryptedPayload, key) ?: encryptedPayload
    }

    // ─── Internal: Message Processing Pipeline ──────────────────

    private fun startMessageProcessing() {
        scope.launch {
            transportManager.incomingMessages.collect { transportMsg ->
                try {
                    val messageJson = String(transportMsg.data, Charsets.UTF_8)
                    val message = json.decodeFromString<MeshMessage>(messageJson)

                    // Passively learn the direct peer's UUID!
                    // If hopCount is 0, the direct peer is the sender.
                    // If hopCount > 0, the direct peer is the last one in the hopPath.
                    val directPeerUuid = message.hopPath.lastOrNull() ?: message.senderId
                    if (directPeerUuid != transportMsg.senderPeerId) {
                        Log.d(TAG, "Passively learning peer UUID: upgrading ${transportMsg.senderPeerId} to $directPeerUuid")
                        transportManager.upgradePeerId(
                            oldId = transportMsg.senderPeerId,
                            newId = directPeerUuid
                        )
                    }

                    processIncomingMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process incoming message", e)
                }
            }
        }
    }

    private suspend fun processIncomingMessage(message: MeshMessage) {
        val peers = activePeers.value
        val decision = messageRouter.routeMessage(message, peers)

        when (decision) {
            is RoutingDecision.Drop -> {
                // Nothing to do
            }

            is RoutingDecision.Deliver -> {
                // Store for local display
                val delivered = decision.message.copy(status = DeliveryStatus.DELIVERED)
                repository.insertMessage(delivered)

                // Send ACK back if applicable
                decision.ackMessage?.let { ack ->
                    deduplicationManager.markSeen(ack.id)
                    repository.insertMessage(ack)
                    sendViaTransport(ack)
                }
                updateStats()
            }

            is RoutingDecision.Forward -> {
                // Store for relay
                repository.insertMessage(decision.message)
                // Forward to selected peers
                sendToTargets(decision.message, decision.targets)
                updateStats()
            }

            is RoutingDecision.DeliverAndForward -> {
                // Deliver locally
                val delivered = decision.message.copy(status = DeliveryStatus.DELIVERED)
                repository.insertMessage(delivered)
                // Also forward to other peers
                sendToTargets(decision.forwardMessage, decision.targets)
                updateStats()
            }

            is RoutingDecision.AckReceived -> {
                // Update the original message status to DELIVERED
                repository.updateStatus(decision.originalMessageId, DeliveryStatus.DELIVERED)
                Log.d(TAG, "Message ${decision.originalMessageId} confirmed delivered")
                updateStats()
            }

            is RoutingDecision.PeerControl -> {
                // Peer announcements are handled by PeerDiscoveryManager
                Log.d(TAG, "Peer control message: ${decision.message.type}")
            }
        }
    }

    // ─── Internal: Transport Sending ────────────────────────────

    private suspend fun sendViaTransport(message: MeshMessage) {
        try {
            val serialized = json.encodeToString(message).toByteArray(Charsets.UTF_8)

            if (message.targetId != null) {
                // Targeted: try direct send first, then broadcast
                val directSuccess = transportManager.sendToPeer(message.targetId, serialized)
                if (directSuccess) {
                    repository.updateStatus(message.id, DeliveryStatus.SENT)
                } else {
                    // Peer not directly reachable, broadcast for relay
                    val exclude = message.hopPath.toSet()
                    transportManager.broadcastToAll(serialized, exclude)
                    repository.updateStatus(message.id, DeliveryStatus.SENT)
                }
            } else {
                // Broadcast
                val exclude = message.hopPath.toSet()
                transportManager.broadcastToAll(serialized, exclude)
                repository.updateStatus(message.id, DeliveryStatus.SENT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transport send failed for ${message.id}", e)
            repository.updateStatus(message.id, DeliveryStatus.FAILED)
        }
    }

    private suspend fun sendToTargets(message: MeshMessage, targets: List<MeshPeer>) {
        val serialized = json.encodeToString(message).toByteArray(Charsets.UTF_8)

        for (peer in targets) {
            scope.launch {
                try {
                    transportManager.sendToPeer(peer.deviceId, serialized)
                } catch (e: Exception) {
                    Log.e(TAG, "Forward to ${peer.deviceId} failed", e)
                }
            }
        }

        repository.updateStatus(message.id, DeliveryStatus.SENT)
    }

    // ─── Internal: Store-and-Forward Loop ───────────────────────

    private fun startStoreAndForward() {
        scope.launch {
            while (isActive && isRunning) {
                delay(STORE_FORWARD_INTERVAL_MS)
                retryPendingMessages()
            }
        }
    }

    private suspend fun retryPendingMessages() {
        try {
            val pending = repository.getQueuedForRelay()
            if (pending.isEmpty()) return

            val peers = activePeers.value
            if (peers.isEmpty()) return

            Log.d(TAG, "Store-and-forward: ${pending.size} pending, ${peers.size} peers")

            for (message in pending) {
                val newPeers = peers.filter { it.deviceId !in message.hopPath }
                if (newPeers.isNotEmpty()) {
                    sendToTargets(message, newPeers)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Store-and-forward error", e)
        }
    }

    // ─── Internal: Maintenance Loop ─────────────────────────────

    private fun startMaintenanceLoop() {
        scope.launch {
            while (isActive && isRunning) {
                delay(MAINTENANCE_INTERVAL_MS)
                try {
                    // Clean up old deduplication records
                    deduplicationManager.cleanup()
                    // Clean up expired messages
                    repository.cleanupExpired()
                    updateStats()
                } catch (e: Exception) {
                    Log.e(TAG, "Maintenance error", e)
                }
            }
        }
    }

    // ─── Internal: ACK Timeout Checker ──────────────────────────

    private fun startAckTimeoutChecker() {
        scope.launch {
            while (isActive && isRunning) {
                delay(ACK_CHECK_INTERVAL_MS)
                try {
                    val timedOut = ackTracker.getTimedOutAcks()
                    for (messageId in timedOut) {
                        // Mark as SENT (delivered to relay, but unconfirmed at target)
                        repository.updateStatus(messageId, DeliveryStatus.SENT)
                    }
                    if (timedOut.isNotEmpty()) {
                        updateStats()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ACK timeout check error", e)
                }
            }
        }
    }

    // ─── Internal: Stats ────────────────────────────────────────

    private suspend fun updateStats() {
        try {
            _meshStats.value = MeshStats(
                totalPeers = activePeers.value.size,
                activePeers = activePeers.value.count { it.isConnected },
                messagesSent = repository.countByStatus(DeliveryStatus.SENT) +
                        repository.countByStatus(DeliveryStatus.DELIVERED),
                messagesRelayed = 0, // TODO: track separately
                messagesDelivered = repository.countByStatus(DeliveryStatus.DELIVERED),
                pendingMessages = repository.countByStatus(DeliveryStatus.QUEUED),
                seenMessageCount = deduplicationManager.cacheSize()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stats update failed", e)
        }
    }

    // ─── Internal: Encryption ───────────────────────────────────

    private fun encryptPayload(plaintext: String): String? {
        val key = KeyManager.getKey(context) ?: return null
        return CryptoManager.encryptString(plaintext, key)
    }
}
