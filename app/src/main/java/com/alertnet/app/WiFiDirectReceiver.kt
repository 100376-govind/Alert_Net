package com.alertnet.app

import android.util.Log
import android.net.NetworkInfo
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.*


class WiFiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        Log.d("WIFI_DEBUG", "Action: ${intent.action}")

        when (intent.action) {

            // 🔹 When peers change (device discovery)
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {

                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                manager.requestPeers(channel) { peers ->
                    activity.updateDeviceList(peers.deviceList)
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {

                val networkInfo =
                    intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                if (networkInfo?.isConnected == true) {

                    Log.d("WIFI_DEBUG", "Connection established")

                    manager.requestConnectionInfo(channel) { info ->

                        Log.d("WIFI_DEBUG", "Group formed: ${info.groupFormed}")

                        if (info.groupFormed) {

                            val hostAddress = info.groupOwnerAddress.hostAddress

                            Log.d("WIFI_DEBUG", "Host: $hostAddress")

                            // Retry pending messages on new connection
                            Thread {
                                MessageQueue.retryPendingMessages { message ->
                                    sendMessageOverSocket(message, hostAddress)
                                }
                            }.start()

                            // 🔥 THIS IS THE FIX
                            activity.runOnUiThread {
                                activity.onConnectionInfoAvailable(hostAddress)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronous send wrapper for retry logic.
     * Uses hostAddress as the target since we know the connected peer.
     */
    private fun sendMessageOverSocket(message: Message, hostAddress: String): Boolean {
        val target = message.receiverId ?: hostAddress
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)

        val serialized = MessageSerializer.serialize(message)

        ClientThread(target, serialized,
            onSuccess = {
                success = true
                latch.countDown()
            },
            onFailure = {
                success = false
                latch.countDown()
            }
        ).start()

        latch.await()
        return success
    }
}