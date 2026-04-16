package com.alertnet.app

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class ServerThread(
    private val onMessageReceived: (String, String) -> Unit
) : Thread() {

    override fun run() {
        try {
            val serverSocket = ServerSocket(8888)

            while (true) {
                val socket = serverSocket.accept()

                val senderIP = socket.inetAddress.hostAddress  // 🔥 KEY LINE

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val message = reader.readLine()

                if (message != null) {
                    Log.d("MSG", "From $senderIP : $message")
                    onMessageReceived(message, senderIP)
                }

                socket.close()
            }

        } catch (e: Exception) {
            Log.e("SERVER", e.toString())
        }
    }
}