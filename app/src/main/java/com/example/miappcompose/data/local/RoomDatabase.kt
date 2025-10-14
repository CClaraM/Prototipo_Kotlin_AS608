package com.example.miappcompose.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "fingerprint_templates")
data class FingerprintTemplateEntity(
    @PrimaryKey val userId: Int,
    val templateBase64: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface FingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: FingerprintTemplateEntity)

    @Query("SELECT * FROM fingerprint_templates WHERE userId = :userId")
    suspend fun getTemplate(userId: Int): FingerprintTemplateEntity?

    @Query("SELECT * FROM fingerprint_templates")
    suspend fun getAllTemplates(): List<FingerprintTemplateEntity>
}