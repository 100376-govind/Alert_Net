package com.alertnet.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.alertnet.app.service.MeshForegroundService
import com.alertnet.app.ui.navigation.NavGraph
import com.alertnet.app.ui.theme.AlertNetTheme
import com.alertnet.app.ui.theme.MeshNavy

/**
 * Main entry point for AlertNet.
 *
 * Handles:
 * - Runtime permission requests (BLE, WiFi Direct, Location, Notifications)
 * - Starting the MeshForegroundService
 * - Hosting the Compose NavGraph
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            startMeshService()
        } else {
            val denied = permissions.filter { !it.value }.keys
            Log.w(TAG, "Permissions denied: $denied")
            Toast.makeText(
                this,
                "Some permissions were denied. Mesh may not work properly.",
                Toast.LENGTH_LONG
            ).show()
            // Start anyway — transports will handle missing permissions gracefully
            startMeshService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        val app = application as AlertNetApplication

        setContent {
            AlertNetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MeshNavy
                ) {
                    NavGraph(app = app)
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startMeshService()
        }
    }

    private fun startMeshService() {
        MeshForegroundService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            MeshForegroundService.stop(this)
        }
    }
}
