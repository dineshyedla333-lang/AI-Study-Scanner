package com.aistudyscanner.agent.network

import org.json.JSONObject

/**
 * The backend reports quota and sign-in problems as
 * `{"detail": {"code": "...", "message": "..."}}`; older errors use a plain string.
 */
object ApiErrors {
    const val REGISTRATION_REQUIRED = "registration_required"

    data class ServerError(val code: String, val message: String)

    fun parse(body: String?): ServerError? {
        if (body.isNullOrBlank()) return null
        val detail = runCatching { JSONObject(body).optJSONObject("detail") }.getOrNull()
            ?: return null
        val code = detail.optString("code")
        val message = detail.optString("message")
        return if (code.isNotEmpty() && message.isNotEmpty()) ServerError(code, message) else null
    }
}
