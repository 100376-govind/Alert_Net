package com.alertnet.app.db

import android.util.Log

object SeenMessageStore {

    private val seenIds = mutableSetOf<String>()

    fun markSeen(messageId: String) {
        seenIds.add(messageId)
        DatabaseProvider.db.seenMessageDao().insertSeen(SeenMessageEntity(messageId))
        Log.d("SeenMessageStore", "Marked $messageId as seen")
    }

    fun hasSeen(messageId: String): Boolean {
        return seenIds.contains(messageId)
    }

    /** Load seen IDs from DB into memory. Call once on startup. */
    fun rehydrate() {
        val all = DatabaseProvider.db.seenMessageDao().getAllSeen()
        seenIds.addAll(all.map { it.id })
        Log.d("SeenMessageStore", "Rehydrated ${seenIds.size} seen message IDs")
    }
}
