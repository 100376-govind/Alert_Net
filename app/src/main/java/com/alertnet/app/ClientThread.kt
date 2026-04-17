package com.alertnet.app

import android.util.Log
import java.io.PrintWriter
import java.net.Socket

class ClientThread(
    private val host: String,
    private val message: String,
    private val onSuccess: (() -> Unit)? = null,
    private val onFailure: (() -> Unit)? = null
) : Thread() {

    override fun run() {
        try {
            val socket = Socket(host, 8888)

            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(message)

            Log.d("MSG", "Sent: $message")

            writer.close()
            socket.close()

            onSuccess?.invoke()

        } catch (e: Exception) {
            Log.e("CLIENT", e.toString())
            onFailure?.invoke()
        }
    }
}