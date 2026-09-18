package com.ayuemin.ymnik.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class RequestRouteMode {
    DIRECT,
    SERVER
}

data class ServerConnectionConfig(
    val mode: RequestRouteMode = RequestRouteMode.DIRECT,
    val baseUrl: String = "",
    val tokenConfigured: Boolean = false
)

/**
 * Stores the optional personal Umnik server connection independently from the
 * OpenRouter profile. This is intentional: switching back to DIRECT must restore
 * exactly the same OpenRouter settings the user had before enabling the server.
 */
class ServerConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun config(): ServerConnectionConfig = ServerConnectionConfig(
        mode = runCatching {
            RequestRouteMode.valueOf(prefs.getString(KEY_MODE, RequestRouteMode.DIRECT.name).orEmpty())
        }.getOrDefault(RequestRouteMode.DIRECT),
        baseUrl = normalizedStoredBaseUrl(),
        tokenConfigured = readToken() != null
    )

    fun setMode(mode: RequestRouteMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun saveServer(baseUrl: String, token: String?) {
        val cleanUrl = ServerEndpointPolicy.normalize(baseUrl)
        prefs.edit().putString(KEY_BASE_URL, cleanUrl).apply()
        if (!token.isNullOrBlank()) saveToken(token.trim())
    }

    fun baseUrl(): String = normalizedStoredBaseUrl()

    fun token(): String? = readToken()

    fun clearServer() {
        prefs.edit()
            .putString(KEY_MODE, RequestRouteMode.DIRECT.name)
            .remove(KEY_BASE_URL)
            .remove(KEY_TOKEN)
            .apply()
    }

    private fun normalizedStoredBaseUrl(): String {
        val stored = prefs.getString(KEY_BASE_URL, "").orEmpty()
        if (stored.isBlank()) return ""
        return runCatching { ServerEndpointPolicy.normalize(stored) }.getOrDefault("")
    }

    private fun saveToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKEN, payload).apply()
    }

    private fun readToken(): String? {
        val payload = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "umnik_server_connection"
        private const val KEY_MODE = "route_mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "server_token"
        private const val KEYSTORE_ALIAS = "umnik_server_token"
    }
}
