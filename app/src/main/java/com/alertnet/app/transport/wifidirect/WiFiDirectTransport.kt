package com.alertnet.app.transport.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.transport.ConnectionEvent
import com.alertnet.app.transport.Transport
import com.alertnet.app.transport.TransportMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * WiFi Direct transport implementation for AlertNet mesh.
 *
 * Handles high-throughput data transfer including large files (up to 20MB).
 * Uses a length-prefixed binary framing protocol for reliable messaging:
 *
 * Frame format: [4 bytes: payload length (big-endian)] [N bytes: payload]
 *
 * Responsibilities:
 * - Peer discovery via WifiP2pManager
 * - P2P connection management (handles both group owner and client roles)
 * - Persistent server socket for incoming connections
 * - Client connections for outgoing messages
 * - Streaming file transfer without loading into memory
 */
class WiFiDirectTransport(
    private val context: Context,
    private val deviceId: String
) : Transport {

    companion object {
        private const val TAG = "WiFiDirectTransport"
        const val PORT = 8888
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val DISCOVERY_INTERVAL_MS = 30_000L
        private const val MAX_PAYLOAD_SIZE = 20 * 1024 * 1024 // 20 MB
    }

    override val transportType = TransportType.WIFI_DIRECT

    private val _discoveredPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<MeshPeer>> = _discoveredPeers.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<TransportMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<TransportMessage> = _incomingMessages.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()

    /** Maps device address to IP for connected peers */
    private val peerIpMap = ConcurrentHashMap<String, String>()

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverJob: Job? = null
    private var discoveryJob: Job? = null
    private var isRunning = false
    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null

    // ─── Lifecycle ───────────────────────────────────────────────

    override suspend fun start() {
        if (isRunning) return

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported")
            _connectionEvents.emit(
                ConnectionEvent.TransportError(UnsupportedOperationException("WiFi Direct not supported"))
            )
            return
        }

        channel = wifiP2pManager!!.initialize(context, context.mainLooper, null)
        isRunning = true

        registerReceiver()
        startServerSocket()
        startPeriodicDiscovery()

        Log.d(TAG, "WiFi Direct transport started")
    }

    override suspend fun stop() {
        isRunning = false
        discoveryJob?.cancel()
        serverJob?.cancel()
        unregisterReceiver()
        scope.coroutineContext.cancelChildren()
        peersMap.clear()
        peerIpMap.clear()
        _discoveredPeers.value = emptyList()
        Log.d(TAG, "WiFi Direct transport stopped")
    }

    // ─── Broadcast Receiver ─────────────────────────────────────

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.w(TAG, "WiFi Direct is disabled")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeerList()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        if (networkInfo?.isConnected == true) {
                            requestConnectionInfo()
                        } else {
                            Log.d(TAG, "Disconnected from WiFi Direct group")
                            isGroupOwner = false
                            groupOwnerAddress = null
                        }
                    }
                }
            }
        }

        context.registerReceiver(receiver, intentFilter)
    }

    private fun unregisterReceiver() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered")
        }
        receiver = null
    }

    // ─── Peer Discovery ─────────────────────────────────────────

    private fun startPeriodicDiscovery() {
        discoveryJob = scope.launch {
            while (isActive && isRunning) {
                discoverPeers()
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        if (!hasPermissions()) return

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeerList() {
        if (!hasPermissions()) return

        wifiP2pManager?.requestPeers(channel) { peers ->
            val meshPeers = peers.deviceList.map { device ->
                MeshPeer(
                    deviceId = device.deviceAddress,
                    displayName = device.deviceName.ifEmpty { "WiFi-${device.deviceAddress.takeLast(5)}" },
                    lastSeen = System.currentTimeMillis(),
                    transportType = TransportType.WIFI_DIRECT,
                    macAddress = device.deviceAddress,
                    isConnected = device.status == WifiP2pDevice.CONNECTED
                )
            }

            for (peer in meshPeers) {
                peersMap[peer.deviceId] = peer
            }
            _discoveredPeers.value = peersMap.values.toList()

            // Auto-connect to discovered peers
            for (peer in meshPeers) {
                if (!peer.isConnected) {
                    connectToPeer(peer)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(peer: MeshPeer) {
        if (!hasPermissions()) return

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.macAddress ?: peer.deviceId
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated to ${peer.displayName}")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection to ${peer.displayName} failed: $reason")
            }
        })
    }

    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                isGroupOwner = info.isGroupOwner
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress

                Log.d(TAG, "Group formed. isGroupOwner=$isGroupOwner, ownerAddr=$groupOwnerAddress")

                if (!isGroupOwner && groupOwnerAddress != null) {
                    // As a client, we know the GO's IP. Register it.
                    peerIpMap["group_owner"] = groupOwnerAddress!!
                    scope.launch {
                        // Send our device ID to the group owner
                        sendDeviceIdHandshake(groupOwnerAddress!!)
                        _connectionEvents.emit(
                            ConnectionEvent.PeerConnected(
                                MeshPeer(
                                    deviceId = "group_owner",
                                    displayName = "Group Owner",
                                    transportType = TransportType.WIFI_DIRECT,
                                    ipAddress = groupOwnerAddress,
                                    isConnected = true
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    // ─── Server Socket ──────────────────────────────────────────

    private fun startServerSocket() {
        serverJob = scope.launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(PORT)
                serverSocket.reuseAddress = true
                Log.d(TAG, "Server socket listening on port $PORT")

                while (isActive && isRunning) {
                    try {
                        val socket = serverSocket.accept()
                        launch { handleClientConnection(socket) }
                    } catch (e: Exception) {
                        if (isActive && isRunning) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket failed", e)
                _connectionEvents.emit(
                    ConnectionEvent.TransportError(e, "Server socket failed: ${e.message}")
                )
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Handle an incoming client connection using length-prefixed framing.
     *
     * Protocol:
     * 1. Read 4 bytes → payload length (big-endian int)
     * 2. Read N bytes → payload data
     * 3. First message from a new peer should be a device ID handshake
     */
    private suspend fun handleClientConnection(socket: Socket) {
        val senderIP = socket.inetAddress?.hostAddress ?: "unknown"

        try {
            val inputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

            // Read length prefix
            val length = inputStream.readInt()

            if (length <= 0 || length > MAX_PAYLOAD_SIZE) {
                Log.e(TAG, "Invalid payload length: $length from $senderIP")
                return
            }

            // Read payload
            val data = ByteArray(length)
            inputStream.readFully(data)

            Log.d(TAG, "Received $length bytes from $senderIP")

            // Check if it's a handshake (device ID announcement)
            val text = String(data, Charsets.UTF_8)
            if (text.startsWith("MESH_ID:")) {
                val meshId = text.removePrefix("MESH_ID:")
                peerIpMap[meshId] = senderIP
                Log.d(TAG, "Peer $meshId registered at $senderIP")

                val peer = MeshPeer(
                    deviceId = meshId,
                    displayName = "Peer-${meshId.take(8)}",
                    transportType = TransportType.WIFI_DIRECT,
                    ipAddress = senderIP,
                    isConnected = true
                )
                peersMap[meshId] = peer
                _discoveredPeers.value = peersMap.values.toList()
                _connectionEvents.emit(ConnectionEvent.PeerConnected(peer))
                return
            }

            // Regular message
            val senderId = peerIpMap.entries.find { it.value == senderIP }?.key ?: senderIP
            _incomingMessages.emit(
                TransportMessage(
                    data = data,
                    senderPeerId = senderId,
                    transportType = TransportType.WIFI_DIRECT
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection from $senderIP", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ─── Send Message ───────────────────────────────────────────

    override suspend fun sendMessage(peerId: String, data: ByteArray): Boolean {
        if (data.size > MAX_PAYLOAD_SIZE) {
            Log.e(TAG, "Payload exceeds 20MB limit: ${data.size} bytes")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val targetIP = resolveIP(peerId) ?: run {
                    Log.w(TAG, "Cannot resolve IP for peer: $peerId")
                    return@withContext false
                }

                val socket = Socket()
                socket.connect(InetSocketAddress(targetIP, PORT), SOCKET_TIMEOUT_MS)

                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                // Write length-prefixed frame
                outputStream.writeInt(data.size)
                outputStream.write(data)
                outputStream.flush()

                socket.close()
                Log.d(TAG, "Sent ${data.size} bytes to $peerId ($targetIP)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Send to $peerId failed", e)
                false
            }
        }
    }

    override suspend fun broadcastMessage(data: ByteArray, excludePeers: Set<String>) {
        val targets = peersMap.values
            .filter { it.deviceId !in excludePeers }
            .filter { it.ipAddress != null || peerIpMap.containsKey(it.deviceId) }

        for (peer in targets) {
            scope.launch {
                try {
                    sendMessage(peer.deviceId, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast to ${peer.deviceId} failed", e)
                }
            }
        }

        // Also send to group owner if we're not the GO
        if (!isGroupOwner && groupOwnerAddress != null) {
            val goId = peerIpMap.entries.find { it.value == groupOwnerAddress }?.key ?: "group_owner"
            if (goId !in excludePeers) {
                scope.launch {
                    try {
                        sendMessage(goId, data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast to group owner failed", e)
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    /**
     * Send device ID handshake to a newly connected peer.
     */
    private suspend fun sendDeviceIdHandshake(targetIP: String) {
        withContext(Dispatchers.IO) {
            try {
                val handshake = "MESH_ID:$deviceId".toByteArray(Charsets.UTF_8)
                val socket = Socket()
                socket.connect(InetSocketAddress(targetIP, PORT), SOCKET_TIMEOUT_MS)

                val outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                outputStream.writeInt(handshake.size)
                outputStream.write(handshake)
                outputStream.flush()
                socket.close()

                Log.d(TAG, "Sent device ID handshake to $targetIP")
            } catch (e: Exception) {
                Log.e(TAG, "Handshake to $targetIP failed", e)
            }
        }
    }

    /**
     * Resolve a peer's device ID to its IP address.
     */
    private fun resolveIP(peerId: String): String? {
        // Direct IP map lookup
        peerIpMap[peerId]?.let { return it }

        // Check if peerId IS an IP address
        if (peerId.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            return peerId
        }

        // Check peer model for stored IP
        peersMap[peerId]?.ipAddress?.let { return it }

        // If we're the client and the peer might be the group owner
        if (!isGroupOwner) {
            return groupOwnerAddress
        }

        return null
    }

    private fun hasPermissions(): Boolean {
        val hasLocation = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return hasLocation && hasNearby
    }
}
