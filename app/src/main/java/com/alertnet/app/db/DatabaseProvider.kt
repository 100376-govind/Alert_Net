package com.alertnet.app.db

import android.content.Context
import androidx.room.Room

object DatabaseProvider {

    lateinit var db: AppDatabase
        private set

    fun init(context: Context) {
        db = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "alertnet-db"
        ).allowMainThreadQueries()
         .fallbackToDestructiveMigration()
         .build()
    }
}
