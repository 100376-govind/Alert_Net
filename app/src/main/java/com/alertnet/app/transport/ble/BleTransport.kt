package com.alertnet.app.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.alertnet.app.model.MeshPeer
import com.alertnet.app.model.TransportType
import com.alertnet.app.transport.ConnectionEvent
import com.alertnet.app.transport.Transport
import com.alertnet.app.transport.TransportMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport implementation for AlertNet mesh.
 *
 * Responsibilities:
 * - Advertise this device's mesh presence via GATT server
 * - Scan for nearby AlertNet peers via BLE scanning
 * - Exchange small messages (<500 bytes) via GATT characteristics
 * - For larger payloads, the TransportManager will prefer WiFi Direct
 *
 * Battery optimization:
 * - Duty-cycled scanning: 15s scan / 45s idle
 * - Low-power scan mode
 * - Advertising uses low-latency mode only when actively sending
 */
class BleTransport(
    private val context: Context,
    private val deviceId: String
) : Transport {

    companion object {
        private const val TAG = "BleTransport"
    }

    override val transportType = TransportType.BLE

    private val _discoveredPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<MeshPeer>> = _discoveredPeers.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<TransportMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<TransportMessage> = _incomingMessages.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    override val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isRunning = false
    private var scanJob: Job? = null

    // ─── Lifecycle ───────────────────────────────────────────────

    override suspend fun start() {
        if (isRunning) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            _connectionEvents.emit(
                ConnectionEvent.TransportError(IllegalStateException("Bluetooth not available"))
            )
            return
        }

        if (!hasPermissions()) {
            Log.w(TAG, "Missing BLE permissions")
            _connectionEvents.emit(
                ConnectionEvent.TransportError(SecurityException("Missing BLE permissions"))
            )
            return
        }

        bleScanner = bluetoothAdapter!!.bluetoothLeScanner
        bleAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser

        isRunning = true
        startGattServer()
        startAdvertising()
        startDutyCycledScanning()

        Log.d(TAG, "BLE transport started")
    }

    override suspend fun stop() {
        isRunning = false
        scanJob?.cancel()
        stopAdvertising()
        stopGattServer()
        scope.coroutineContext.cancelChildren()
        peersMap.clear()
        _discoveredPeers.value = emptyList()
        Log.d(TAG, "BLE transport stopped")
    }

    // ─── GATT Server (receive messages) ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (!hasPermissions()) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val address = device.address ?: return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT client connected: $address")
                        scope.launch {
                            val peer = MeshPeer(
                                deviceId = address, // will be updated when we read their mesh ID
                                displayName = device.name ?: "BLE Device",
                                transportType = TransportType.BLE,
                                macAddress = address,
                                isConnected = true
                            )
                            peersMap[address] = peer
                            updatePeersList()
                            _connectionEvents.emit(ConnectionEvent.PeerConnected(peer))
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT client disconnected: $address")
                        scope.launch {
                            peersMap.remove(address)
                            updatePeersList()
                            _connectionEvents.emit(ConnectionEvent.PeerDisconnected(address))
                        }
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                when (characteristic.uuid) {
                    BleConstants.MESSAGE_CHARACTERISTIC -> {
                        Log.d(TAG, "Received ${value.size} bytes from ${device.address}")
                        scope.launch {
                            _incomingMessages.emit(
                                TransportMessage(
                                    data = value,
                                    senderPeerId = device.address ?: "unknown",
                                    transportType = TransportType.BLE
                                )
                            )
                        }
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                            )
                        }
                    }
                    BleConstants.MESH_ID_CHARACTERISTIC -> {
                        // Client writing their mesh ID
                        val meshId = String(value, Charsets.UTF_8)
                        Log.d(TAG, "Peer mesh ID received: $meshId from ${device.address}")
                        val existing = peersMap[device.address]
                        if (existing != null) {
                            peersMap[device.address] = existing.copy(deviceId = meshId)
                            // Also store under the mesh ID
                            peersMap[meshId] = existing.copy(deviceId = meshId)
                            scope.launch { updatePeersList() }
                        }
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                            )
                        }
                    }
                    else -> {
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                            )
                        }
                    }
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == BleConstants.MESH_ID_CHARACTERISTIC) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS,
                        0, deviceId.toByteArray(Charsets.UTF_8)
                    )
                } else {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            }
        })

        // Create GATT service with characteristics
        val service = BluetoothGattService(
            BleConstants.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val meshIdChar = BluetoothGattCharacteristic(
            BleConstants.MESH_ID_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val messageChar = BluetoothGattCharacteristic(
            BleConstants.MESSAGE_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(meshIdChar)
        service.addCharacteristic(messageChar)
        gattServer?.addService(service)

        Log.d(TAG, "GATT server started")
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        if (!hasPermissions()) return
        gattServer?.close()
        gattServer = null
    }

    // ─── BLE Advertising ────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!hasPermissions() || bleAdvertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // save space
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.d(TAG, "BLE advertising started")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!hasPermissions()) return
        bleAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            scope.launch {
                _connectionEvents.emit(
                    ConnectionEvent.TransportError(
                        RuntimeException("BLE advertising failed: $errorCode")
                    )
                )
            }
        }
    }

    // ─── BLE Scanning (Duty-Cycled) ─────────────────────────────

    private fun startDutyCycledScanning() {
        scanJob = scope.launch {
            while (isActive && isRunning) {
                startScan()
                delay(BleConstants.SCAN_DURATION_MS)
                stopScan()
                delay(BleConstants.SCAN_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions() || bleScanner == null) return

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!hasPermissions()) return
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return

            @SuppressLint("MissingPermission")
            val name = device.name ?: "BLE-${address.takeLast(5)}"

            val peer = MeshPeer(
                deviceId = address,
                displayName = name,
                lastSeen = System.currentTimeMillis(),
                rssi = result.rssi,
                transportType = TransportType.BLE,
                macAddress = address,
                isConnected = false
            )

            val existing = peersMap[address]
            if (existing == null || System.currentTimeMillis() - existing.lastSeen > 5000) {
                peersMap[address] = peer
                scope.launch {
                    updatePeersList()
                    if (existing == null) {
                        _connectionEvents.emit(ConnectionEvent.PeerConnected(peer))
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    // ─── Send Message ───────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun sendMessage(peerId: String, data: ByteArray): Boolean {
        if (!hasPermissions()) return false

        if (data.size > BleConstants.MAX_BLE_PAYLOAD) {
            Log.w(TAG, "Payload too large for BLE (${data.size} bytes), use WiFi Direct")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val peer = peersMap[peerId] ?: run {
                    Log.w(TAG, "Peer not found: $peerId")
                    return@withContext false
                }

                val macAddress = peer.macAddress ?: run {
                    Log.w(TAG, "No MAC address for peer: $peerId")
                    return@withContext false
                }

                val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return@withContext false
                val result = CompletableDeferred<Boolean>()

                val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.requestMtu(BleConstants.REQUESTED_MTU)
                        } else {
                            if (!result.isCompleted) result.complete(false)
                            gatt.close()
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            gatt.discoverServices()
                        } else {
                            if (!result.isCompleted) result.complete(false)
                            gatt.close()
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            if (!result.isCompleted) result.complete(false)
                            gatt.close()
                            return
                        }

                        val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(BleConstants.MESSAGE_CHARACTERISTIC)

                        if (characteristic == null) {
                            Log.e(TAG, "Message characteristic not found on peer")
                            if (!result.isCompleted) result.complete(false)
                            gatt.close()
                            return
                        }

                        characteristic.value = data
                        val writeResult = gatt.writeCharacteristic(characteristic)
                        if (!writeResult) {
                            if (!result.isCompleted) result.complete(false)
                            gatt.close()
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        result.complete(status == BluetoothGatt.GATT_SUCCESS)
                        gatt.close()
                    }
                })

                withTimeoutOrNull(3_000) { result.await() } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "BLE send failed", e)
                false
            }
        }
    }

    override suspend fun broadcastMessage(data: ByteArray, excludePeers: Set<String>) {
        val targets = peersMap.values
            .filter { it.deviceId !in excludePeers && it.macAddress !in excludePeers }
            .distinctBy { it.macAddress }

        for (peer in targets) {
            try {
                sendMessage(peer.deviceId, data)
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast to ${peer.deviceId} failed", e)
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun updatePeersList() {
        _discoveredPeers.value = peersMap.values
            .distinctBy { it.macAddress ?: it.deviceId }
            .toList()
    }

    private fun hasPermissions(): Boolean {
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasAdvertise = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val hasLocation = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return hasConnect && hasScan && hasAdvertise && hasLocation
    }
}
