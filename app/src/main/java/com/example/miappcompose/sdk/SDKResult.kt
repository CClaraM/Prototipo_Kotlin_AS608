package com.example.miappcompose.sdk

data class SDKResult<T>(
    val success: Boolean,
    val data: T? = null,
    val errorCode: Int? = null,
    val message: String = ""
) {
    companion object {
        fun <T> ok(data: T? = null, message: String = ""): SDKResult<T> =
            SDKResult(success = true, data = data, message = message)

        fun <T> fail(code: Int? = null, message: String = ""): SDKResult<T> =
            SDKResult(success = false, errorCode = code, message = message)
    }
}
