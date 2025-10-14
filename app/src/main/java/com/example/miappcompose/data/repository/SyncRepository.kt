package com.example.miappcompose.data.repository

import android.util.Log
import com.example.miappcompose.data.local.FingerprintDao
import com.example.miappcompose.data.local.FingerprintTemplateEntity
import com.example.miappcompose.data.network.FingerprintApi
import com.example.miappcompose.data.network.FingerprintRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * ✅ Resultado estándar para operaciones de sincronización
 */
sealed class SyncResult<out T> {
    data class Success<T>(val data: T) : SyncResult<T>()
    data class Error(val message: String, val code: Int? = null) : SyncResult<Nothing>()
}

class SyncRepository(
    private val fingerprintDao: FingerprintDao,
    private val api: FingerprintApi
) {
    private val TAG = "SyncRepository"

    /**
     * 🧩 Guarda un template en la base de datos local (Room)
     */
    suspend fun saveTemplateLocally(userId: Int, base64: String): SyncResult<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val entity = FingerprintTemplateEntity(userId, base64)
                fingerprintDao.insertTemplate(entity)
                SyncResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error guardando localmente: ${e.message}")
                SyncResult.Error("Error guardando localmente: ${e.message}")
            }
        }
    }

    /**
     * ☁️ Sube un template al servidor central (API REST)
     */
    suspend fun syncTemplateWithServer(userId: Int): SyncResult<Unit> {
        return withContext(Dispatchers.IO) {
            val entity = fingerprintDao.getTemplate(userId)
                ?: return@withContext SyncResult.Error("Template no encontrado en la base local")

            val response = safeApiCall { api.uploadTemplate(FingerprintRequest(userId, entity.templateBase64)) }
            if (response is SyncResult.Success) {
                SyncResult.Success(Unit)
            } else {
                response as SyncResult.Error
            }
        }
    }

    /**
     * 🌐 Descarga template desde el servidor y lo guarda en Room
     */
    suspend fun downloadTemplateFromServer(userId: Int): SyncResult<String> {
        return withContext(Dispatchers.IO) {
            val response = safeApiCall { api.getTemplate(userId) }

            when (response) {
                is SyncResult.Success -> {
                    val base64 = response.data.body()?.templateBase64
                        ?: return@withContext SyncResult.Error("El servidor no devolvió datos válidos")

                    val localSave = saveTemplateLocally(userId, base64)
                    if (localSave is SyncResult.Success) {
                        SyncResult.Success(base64)
                    } else {
                        localSave as SyncResult.Error
                    }
                }
                is SyncResult.Error -> response
            }
        }
    }

    /**
     * 🧰 Utilidad para manejar errores de Retrofit de forma uniforme
     */
    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): SyncResult<Response<T>> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                SyncResult.Success(response)
            } else {
                val code = response.code()
                val errorBody = response.errorBody()?.string() ?: "Sin detalles"
                Log.e(TAG, "❌ Error HTTP $code: $errorBody")
                SyncResult.Error("Error HTTP $code: $errorBody", code)
            }
        } catch (e: Exception) {
            Log.e(TAG, "🌐 Error de red: ${e.message}")
            SyncResult.Error("Error de red: ${e.message}")
        }
    }
}
