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

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "ymnik_openrouter_key"

    fun saveApiKey(value: String) = saveProfileApiKey("openrouter", value)

    fun getApiKey(): String? = getProfileApiKey("openrouter")

    fun saveProfileApiKey(profileId: String, value: String) = saveSecret(prefKey(profileId), value)

    fun getProfileApiKey(profileId: String): String? = readSecret(prefKey(profileId))

    fun saveProfileImageApiKey(profileId: String, value: String) = saveSecret(imagePrefKey(profileId), value)

    fun getProfileImageApiKey(profileId: String): String? = readSecret(imagePrefKey(profileId))

    fun deleteProfileImageApiKey(profileId: String) {
        prefs.edit().remove(imagePrefKey(profileId)).apply()
    }

    fun deleteProfileApiKey(profileId: String) {
        if (profileId == "openrouter") return
        prefs.edit()
            .remove(prefKey(profileId))
            .remove(imagePrefKey(profileId))
            .apply()
    }

    private fun saveSecret(prefKey: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(prefKey).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(prefKey, payload).apply()
    }

    private fun readSecret(prefKey: String): String? {
        val payload = prefs.getString(prefKey, null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun prefKey(profileId: String): String = if (profileId == "openrouter") {
        "api_key"
    } else {
        "api_key_profile_" + safeId(profileId)
    }

    private fun imagePrefKey(profileId: String): String = "image_api_key_profile_" + safeId(profileId)

    private fun safeId(profileId: String): String = profileId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
