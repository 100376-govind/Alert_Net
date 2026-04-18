package com.alertnet.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.mesh.MeshManager
import com.alertnet.app.repository.MessageRepository
import com.alertnet.app.transport.TransportManager
import java.util.UUID

/**
 * Application class for AlertNet.
 *
 * Initializes core singletons and provides them to the rest of the app:
 * - Room database
 * - Device identity (per-install UUID)
 * - TransportManager
 * - MessageRepository
 * - MeshManager
 */
class AlertNetApplication : Application() {

    companion object {
        private const val TAG = "AlertNetApp"
        private const val PREFS_NAME = "alertnet_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"

        lateinit var instance: AlertNetApplication
            private set
    }

    lateinit var deviceId: String
        private set

    lateinit var transportManager: TransportManager
        private set

    lateinit var messageRepository: MessageRepository
        private set

    lateinit var meshManager: MeshManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        DatabaseProvider.init(this)

        // Initialize device identity
        deviceId = getOrCreateDeviceId()
        Log.d(TAG, "Device ID: $deviceId")

        // Initialize core components
        transportManager = TransportManager(this, deviceId)
        messageRepository = MessageRepository()
        meshManager = MeshManager(this, deviceId, transportManager, messageRepository)

        Log.d(TAG, "AlertNet initialized")
    }

    /**
     * Get or create a per-install UUID stored in SharedPreferences.
     * This is the device's identity on the mesh network.
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)

        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        Log.d(TAG, "Generated new device ID: $newId")
        return newId
    }

    /**
     * Get or set the user-visible device name.
     */
    fun getDeviceName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_NAME, null)
            ?: android.os.Build.MODEL.also { setDeviceName(it) }
    }

    fun setDeviceName(name: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}
