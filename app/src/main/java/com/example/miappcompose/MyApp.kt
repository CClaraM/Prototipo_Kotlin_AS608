package com.example.miappcompose

import android.app.Application
import androidx.room.Room
import com.example.miappcompose.data.local.AppDatabase

class MyApp : Application() {

    companion object {
        lateinit var database: AppDatabase
            private set
    }

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "fingerprint_db"
        ).build()
    }
}
