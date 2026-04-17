package com.alertnet.app.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, SeenMessageEntity::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun seenMessageDao(): SeenMessageDao
}
