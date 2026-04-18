package com.alertnet.app.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MessageEntity::class,
        SeenMessageEntity::class,
        PeerEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun peerDao(): PeerDao
}
