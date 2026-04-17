package com.alertnet.app

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alertnet.app.db.SeenMessageStore

class ChatActivity : AppCompatActivity() {

    var lastSenderIP: String? = null
    private lateinit var chatList: ListView
    private lateinit var messageBox: EditText
    private lateinit var sendBtn: Button
    private lateinit var deviceId: String

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

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // Start server
        val server = ServerThread { rawMsg, senderIP ->
            lastSenderIP = senderIP
            onMessageReceived(rawMsg, senderIP)
        }
        server.start()

        sendBtn.setOnClickListener {

            val msg = messageBox.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener

            val host = intent.getStringExtra("host")!!
            val targetIP = lastSenderIP ?: host

            // Create outgoing message with TTL
            val outgoing = Message(
                senderId = deviceId,
                receiverId = targetIP,
                payload = msg,
                ttl = 5
            )

            // Mark as seen immediately (loop prevention)
            SeenMessageStore.markSeen(outgoing.id)

            // Add to queue
            MessageQueue.addNewMessage(outgoing)

            messages.add("Me: $msg")
            adapter.notifyDataSetChanged()
            messageBox.setText("")

            // Attempt send on a background thread
            Thread {
                MessageQueue.attemptSend(outgoing) { message ->
                    sendMessageOverSocket(message)
                }
            }.start()
        }
    }

    /**
     * Core multi-hop receive logic.
     * Handles: duplicate prevention, local delivery, TTL control, forwarding.
     */
    private fun onMessageReceived(rawMsg: String, senderIP: String) {

        // Try to deserialize as a structured Message
        val message = MessageSerializer.deserialize(rawMsg)

        if (message == null) {
            // Legacy plain text — treat as direct message
            val fallback = Message(
                senderId = senderIP,
                receiverId = deviceId,
                payload = rawMsg,
                status = MessageStatus.SENT,
                ttl = 0
            )
            SeenMessageStore.markSeen(fallback.id)
            MessageQueue.addReceivedMessage(fallback)
            runOnUiThread {
                messages.add("Friend: $rawMsg")
                adapter.notifyDataSetChanged()
            }
            return
        }

        // 1. Drop duplicates
        if (SeenMessageStore.hasSeen(message.id)) {
            Log.d("RELAY", "Dropping duplicate: ${message.id}")
            return
        }

        // 2. Mark as seen
        SeenMessageStore.markSeen(message.id)

        // 3. Store message locally
        MessageQueue.addReceivedMessage(message)

        // 4. If I am the receiver → deliver to UI
        if (message.receiverId == deviceId || message.receiverId == null) {
            runOnUiThread {
                messages.add("Friend: ${message.payload}")
                adapter.notifyDataSetChanged()
            }
            return
        }

        // 5. TTL control
        message.ttl -= 1
        if (message.ttl <= 0) {
            Log.d("RELAY", "TTL expired for ${message.id}")
            return
        }

        // 6. Forward message to connected peer
        Log.d("RELAY", "Forwarding ${message.id} | ttl=${message.ttl}")
        MessageQueue.addForwardMessage(message)

        Thread {
            MessageQueue.attemptSend(message) { msg ->
                sendMessageOverSocket(msg)
            }
        }.start()
    }

    /**
     * Synchronous send wrapper around ClientThread.
     * Now sends the full serialized Message JSON instead of just the payload.
     * Returns true if socket write succeeds, false otherwise.
     * Must be called from a background thread.
     */
    private fun sendMessageOverSocket(message: Message): Boolean {
        val target = message.receiverId ?: return false
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