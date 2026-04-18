package com.alertnet.app

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.*
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.util.Log
import android.widget.ListView
import android.widget.ArrayAdapter
import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.alertnet.app.db.DatabaseProvider
import com.alertnet.app.db.SeenMessageStore


class MainActivity : AppCompatActivity() {

    lateinit var receiver: BroadcastReceiver
    lateinit var intentFilter: IntentFilter

    lateinit var deviceListView: ListView
    lateinit var adapter: ArrayAdapter<String>
    val devices = ArrayList<WifiP2pDevice>()
    val deviceNames = ArrayList<String>()

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    private val PERMISSION_CODE = 101


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("WIFI_DEBUG", "Receiver initialized")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database and rehydrate queues
        DatabaseProvider.init(this)
        MessageQueue.rehydrate()
        SeenMessageStore.rehydrate()

        checkPermissions()

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectReceiver(manager, channel, this)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        val btnDiscover = findViewById<Button>(R.id.btnDiscover)

        deviceListView = findViewById(R.id.deviceList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceListView.adapter = adapter
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            connectToDevice(device)
        }

        btnDiscover.setOnClickListener {

            Toast.makeText(this, "Searching...", Toast.LENGTH_SHORT).show()

            if (!hasPermissions()) {
                Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show()
                checkPermissions()
                return@setOnClickListener
            }

            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Discovery Started", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    Toast.makeText(this@MainActivity, "Discovery Failed: $reason", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
    fun connectToDevice(device: WifiP2pDevice) {

        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress

        if (!hasPermissions()) {
            return
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            /*override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
            }*/
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Connection Failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun onConnectionInfoAvailable(host: String) {

        Log.d("WIFI_DEBUG", "ChatActivity should open now")

        runOnUiThread {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("host", host)
            startActivity(intent)
        }
    }

    override fun onResume() {
        Log.d("WIFI_DEBUG", "Receiver registered")
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
    fun updateDeviceList(peerList: Collection<WifiP2pDevice>) {
        devices.clear()
        deviceNames.clear()

        for (device in peerList) {
            devices.add(device)
            deviceNames.add(device.deviceName)
        }

        adapter.notifyDataSetChanged()
    }

    fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_CODE)
    }

    // ✅ Verify Permissions
    fun hasPermissions(): Boolean {
        val location = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= 33) {
            val wifi = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            location && wifi
        } else {
            location
        }
    }

    // 🔍 Discover Devices
    private fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Permission missing!", Toast.LENGTH_SHORT).show()
            return
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Discovery Started", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
