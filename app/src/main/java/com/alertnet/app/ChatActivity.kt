package com.alertnet.app

import android.os.Bundle
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

        // Start server
        val server = ServerThread { msg, senderIP ->

            lastSenderIP = senderIP  // 🔥 store client IP

            runOnUiThread {
                messages.add("Friend: $msg")
                adapter.notifyDataSetChanged()
            }
        }
        server.start()
        sendBtn.setOnClickListener {

            val msg = messageBox.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener

            messages.add("Me: $msg")
            adapter.notifyDataSetChanged()

            val host = intent.getStringExtra("host")!!

            val targetIP = lastSenderIP ?: host
            // 🔥 If someone already sent → reply to them
            // 🔥 Else send to host

            ClientThread(targetIP, msg).start()

            messageBox.setText("")
        }
    }
}