package com.alertnet.app.db

import android.content.Context
import androidx.room.Room

/**
 * Singleton provider for the Room database instance.
 * Must be initialized in Application.onCreate() before any DAO access.
 */
object DatabaseProvider {

    lateinit var db: AppDatabase
        private set

    fun init(context: Context) {
        db = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "alertnet-db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
}
