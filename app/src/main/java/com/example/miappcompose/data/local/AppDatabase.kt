package com.example.miappcompose.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FingerprintTemplateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fingerprintDao(): FingerprintDao
}
