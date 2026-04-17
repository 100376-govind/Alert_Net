package com.alertnet.app

import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ChatActivity : AppCompatActivity() {

    var lastSenderIP: String? = null
    private lateinit var chatList: ListView
    private lateinit var messageBox: EditText
    private lateinit var sendBtn: Button

    private val messages = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatList = findViewById(R.id.chatList)
        messageBox = findViewById(R.id.messageBox)
        sendBtn = findViewById(R.id.sendBtn)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        chatList.adapter = adapter

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // Start server
        val server = ServerThread { msg, senderIP ->

            lastSenderIP = senderIP  // 🔥 store client IP

            // Queue received message
            val received = Message(
                senderId = senderIP,
                receiverId = deviceId,
                payload = msg,
                status = MessageStatus.SENT
            )
            MessageQueue.addReceivedMessage(received)

            runOnUiThread {
                messages.add("Friend: $msg")
                adapter.notifyDataSetChanged()
            }
        }
        server.start()
        sendBtn.setOnClickListener {

            val msg = messageBox.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener

            val host = intent.getStringExtra("host")!!
            val targetIP = lastSenderIP ?: host

            // Create and queue outgoing message
            val outgoing = Message(
                senderId = deviceId,
                receiverId = targetIP,
                payload = msg
            )
            MessageQueue.addNewMessage(outgoing)

            messages.add("Me: $msg")
            adapter.notifyDataSetChanged()

            // Send over socket, mark as SENT on success
            ClientThread(targetIP, msg) {
                MessageQueue.markAsSent(outgoing.id)
            }.start()

            messageBox.setText("")
        }
    }
}