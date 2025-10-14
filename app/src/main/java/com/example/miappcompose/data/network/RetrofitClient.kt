package com.example.miappcompose.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
interface FingerprintApi {
    @POST("/api/fingerprint/enroll")
    suspend fun uploadTemplate(
        @Body request: FingerprintRequest
    ): Response<ApiResponse>    // 👈 Aquí tipo explícito

    @GET("/api/fingerprint/{userId}")
    suspend fun getTemplate(
        @Path("userId") userId: Int
    ): Response<FingerprintResponse>
}

data class FingerprintRequest(
    val userId: Int,
    val templateBase64: String
)

data class FingerprintResponse(
    val userId: Int,
    val templateBase64: String
)

data class ApiResponse(
    val success: Boolean,
    val message: String
)
