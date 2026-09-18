package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest

/** Builds an idempotency key for one concrete model step sent through the personal server. */
internal object ServerRequestIdentity {
    private val gson = Gson()

    fun build(requestId: String?, payloadJson: String): String {
        val payload = runCatching { gson.fromJson(payloadJson, JsonObject::class.java) }
            .getOrElse { JsonObject().apply { addProperty("payload", payloadJson) } }
        val actionId = requestId?.takeIf { it.isNotBlank() }
            ?: payload.getAsJsonObject("metadata")
                ?.get("umnik_request_id")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
            ?: "anonymous"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
        return "${actionId.take(80)}-$digest".take(128)
    }
}
