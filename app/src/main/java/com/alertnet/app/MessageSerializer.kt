package com.alertnet.app

import android.util.Log
import org.json.JSONObject

object MessageSerializer {

    fun serialize(message: Message): String {
        val json = JSONObject()
        json.put("id", message.id)
        json.put("senderId", message.senderId)
        json.put("receiverId", message.receiverId ?: "")
        json.put("payload", message.payload)
        json.put("timestamp", message.timestamp)
        json.put("ttl", message.ttl)
        return json.toString()
    }

    fun deserialize(raw: String): Message? {
        return try {
            val json = JSONObject(raw)
            Message(
                id = json.getString("id"),
                senderId = json.getString("senderId"),
                receiverId = json.optString("receiverId", null),
                payload = json.getString("payload"),
                timestamp = json.getLong("timestamp"),
                ttl = json.optInt("ttl", 5),
                status = MessageStatus.SENT
            )
        } catch (e: Exception) {
            Log.e("MessageSerializer", "Failed to deserialize: $e")
            null
        }
    }
}
