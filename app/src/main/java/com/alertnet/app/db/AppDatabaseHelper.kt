package com.alertnet.app.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "alertnet.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                senderId TEXT NOT NULL,
                targetId TEXT,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                fileName TEXT,
                mimeType TEXT,
                timestamp INTEGER NOT NULL,
                ttl INTEGER NOT NULL,
                hopCount INTEGER NOT NULL,
                hopPath TEXT NOT NULL,
                status TEXT NOT NULL,
                ackForMessageId TEXT
            )
        """)

        db.execSQL("""
            CREATE INDEX index_messages_senderId ON messages(senderId)
        """)
        db.execSQL("""
            CREATE INDEX index_messages_targetId ON messages(targetId)
        """)
        db.execSQL("""
            CREATE INDEX index_messages_status ON messages(status)
        """)
        db.execSQL("""
            CREATE INDEX index_messages_timestamp ON messages(timestamp)
        """)

        db.execSQL("""
            CREATE TABLE seen_messages (
                id TEXT PRIMARY KEY,
                seenAt INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE peers (
                deviceId TEXT PRIMARY KEY,
                displayName TEXT NOT NULL,
                lastSeen INTEGER NOT NULL,
                rssi INTEGER,
                transportType TEXT NOT NULL,
                discoveryType TEXT NOT NULL DEFAULT 'BLE',
                ipAddress TEXT,
                macAddress TEXT
            )
        """)
        
        db.execSQL("""
            CREATE INDEX index_peers_lastSeen ON peers(lastSeen)
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Peers are transient (5-min staleness), safe to recreate
            db.execSQL("DROP TABLE IF EXISTS peers")
        }
        // Recreate all tables from scratch
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS seen_messages")
        db.execSQL("DROP TABLE IF EXISTS peers")
        onCreate(db)
    }
}
