from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise SystemExit(f'marker not found in {path}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))


def regex_once(path, pattern, repl):
    text = read(path)
    next_text, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'pattern count {count} in {path}: {pattern[:100]!r}')
    write(path, next_text)


# --- Models: provider/profile state -------------------------------------------------
models = 'app/src/main/java/com/ayuemin/ymnik/model/Models.kt'
replace_once(models,
'''enum class UserProfileScope {
    OFF,
    PROJECTS,
    EVERYWHERE
}
''',
'''enum class UserProfileScope {
    OFF,
    PROJECTS,
    EVERYWHERE
}

enum class ProviderType {
    OPENROUTER,
    OPENAI_COMPATIBLE
}

data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String
)
''')
replace_once(models,
'''    val mode: ChatMode = ChatMode.TEXT,
    val textModel: String = "openrouter/auto",
''',
'''    val mode: ChatMode = ChatMode.TEXT,
    val connectionProfiles: List<ConnectionProfile> = listOf(
        ConnectionProfile("openrouter", "OpenRouter", ProviderType.OPENROUTER, "https://openrouter.ai/api/v1")
    ),
    val activeConnectionProfileId: String = "openrouter",
    val textModel: String = "openrouter/auto",
''')

# --- SecretStore: one encrypted key per profile, legacy OpenRouter key preserved ----
secret = 'app/src/main/java/com/ayuemin/ymnik/data/SecretStore.kt'
write(secret, r'''package com.ayuemin.ymnik.data

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

    fun saveProfileApiKey(profileId: String, value: String) {
        val prefKey = prefKey(profileId)
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

    fun getProfileApiKey(profileId: String): String? {
        val payload = prefs.getString(prefKey(profileId), null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun deleteProfileApiKey(profileId: String) {
        if (profileId == "openrouter") return
        prefs.edit().remove(prefKey(profileId)).apply()
    }

    private fun prefKey(profileId: String): String = if (profileId == "openrouter") {
        "api_key"
    } else {
        "api_key_profile_" + profileId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }

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
''')

# --- OpenRouter: keep every current capability, but make its base URL configurable ---
client = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(client,
'''    suspend fun models(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, "https://openrouter.ai/api/v1/models")
    }

    suspend fun imageModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, "https://openrouter.ai/api/v1/images/models")
    }
''',
'''    suspend fun models(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, endpoint(baseUrl, "models"))
    }

    suspend fun imageModels(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, endpoint(baseUrl, "images/models"))
    }
''')
replace_once(client,
'''        reasoningEffort: String? = "medium",
        toolsEnabled: Boolean = true
    ): Result = withContext(Dispatchers.IO) {
''',
'''        reasoningEffort: String? = "medium",
        toolsEnabled: Boolean = true,
        baseUrl: String = DEFAULT_BASE_URL
    ): Result = withContext(Dispatchers.IO) {
''')
replace_once(client,
'''            val responseMessage = requestCompletion(apiKey, payload)
''',
'''            val responseMessage = requestCompletion(apiKey, baseUrl, payload)
''')
replace_once(client,
'''        prompt: String,
        attachments: List<PendingAttachment>
    ): Result = withContext(Dispatchers.IO) {
''',
'''        prompt: String,
        attachments: List<PendingAttachment>,
        baseUrl: String = DEFAULT_BASE_URL
    ): Result = withContext(Dispatchers.IO) {
''')
replace_once(client,
'''            .url("https://openrouter.ai/api/v1/images")
''',
'''            .url(endpoint(baseUrl, "images"))
''')
replace_once(client,
'''    private fun requestCompletion(apiKey: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
''',
'''    private fun requestCompletion(apiKey: String, baseUrl: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
''')
text = read(client)
if 'const val DEFAULT_BASE_URL' not in text:
    pos = text.rfind('\n}')
    if pos < 0:
        raise SystemExit('OpenRouterClient final brace not found')
    addition = '''

    private fun endpoint(baseUrl: String, path: String): String {
        val root = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return "$root/${path.trimStart('/')}"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
'''
    text = text[:pos] + addition + text[pos:]
    write(client, text)

# --- Generic OpenAI-compatible text client ------------------------------------------
compatible = 'app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt'
write(compatible, r'''package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.PendingAttachment
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class CompatibleApiClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()
    @Volatile private var activeCall: Call? = null

    fun cancelActiveRequest() {
        activeCall?.cancel()
        http.dispatcher.cancelAll()
    }

    suspend fun models(apiKey: String, baseUrl: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(baseUrl, "models")).get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonElement::class.java)
            val data = when {
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
                root.isJsonArray -> root.asJsonArray
                else -> null
            } ?: return@withContext emptyList()
            data.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = item.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Generic /models normally does not advertise multimodal/reasoning capabilities.
                // Stay conservative: text is enabled, everything else remains disabled unless
                // a compatible server exposes OpenRouter-style metadata.
                val modalities = item.getAsJsonObject("architecture")
                    ?.getAsJsonArray("input_modalities")
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                    ?.toSet().orEmpty().ifEmpty { setOf("text") }
                val supported = item.getAsJsonArray("supported_parameters")
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                    ?.toSet().orEmpty()
                val efforts = item.getAsJsonObject("reasoning")
                    ?.getAsJsonArray("supported_efforts")
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                    ?.toSet().orEmpty()
                ModelInfo(id, modalities, supported, efforts)
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    suspend fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        val messages = JsonArray()
        if (systemPrompt.isNotBlank()) messages.add(message("system", systemPrompt))
        history.takeLast(30).forEach { item -> messages.add(message(item.role, item.text)) }
        messages.add(message("user", userText(prompt, attachments)))

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("max_tokens", 6000)
        }
        val builder = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = http.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val content = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")?.get("content")
                val text = extractText(content)
                if (text.isBlank()) error("Совместимый API вернул пустой ответ")
                OpenRouterClient.Result(text, emptyList())
            }
        } finally {
            activeCall = null
        }
    }

    private fun userText(prompt: String, attachments: List<PendingAttachment>): String = buildString {
        append(prompt.ifBlank { "Изучи вложения и помоги мне с ними." })
        attachments.forEach { attachment ->
            val mime = attachment.mimeType.lowercase()
            val name = attachment.name.lowercase()
            val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
                name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
            if (textLike) {
                val value = runCatching { String(readAttachment(attachment), Charsets.UTF_8) }.getOrDefault("")
                append("\n\n--- Вложение: ${attachment.name} ---\n")
                append(value)
                append("\n--- Конец вложения ---")
            }
        }
    }

    private fun readAttachment(attachment: PendingAttachment): ByteArray {
        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) return file.readBytes()
        }
        val uri = Uri.parse(attachment.uri)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать ${attachment.name}")
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun extractText(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.mapNotNull { part ->
            when {
                part.isJsonPrimitive -> part.asString
                part.isJsonObject -> part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            }
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            gson.fromJson(body, JsonObject::class.java).getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull().orEmpty()
        return if (message.isBlank()) "Ошибка API $code" else "Ошибка API $code: $message"
    }
}
''')

# --- ViewModel: profiles, routing, migration ----------------------------------------
vm = 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
replace_once(vm,
'''import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
''',
'''import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ConnectionProfile
''')
replace_once(vm,
'''import com.ayuemin.ymnik.model.ProjectFile
import com.ayuemin.ymnik.model.ReasoningEffort
''',
'''import com.ayuemin.ymnik.model.ProjectFile
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ReasoningEffort
''')
replace_once(vm,
'''import com.ayuemin.ymnik.network.OpenRouterClient
''',
'''import com.ayuemin.ymnik.network.CompatibleApiClient
import com.ayuemin.ymnik.network.OpenRouterClient
''')
replace_once(vm,
'''    private val api = OpenRouterClient(context)
    private val gson = Gson()
''',
'''    private val api = OpenRouterClient(context)
    private val compatibleApi = CompatibleApiClient(context)
    private val gson = Gson()
''')
replace_once(vm,
'''    private val initialChats = loadInitialChats()
''',
'''    private val initialProfiles = loadConnectionProfiles()
    private val initialProfileId = prefs.getString("active_connection_profile", "openrouter")
        ?.takeIf { id -> initialProfiles.any { it.id == id } }
        ?: "openrouter"
    private val initialProfile = initialProfiles.first { it.id == initialProfileId }
    private val initialChats = loadInitialChats()
''')
replace_once(vm,
'''            mode = initialChat.mode ?: runCatching {
                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)
            }.getOrDefault(ChatMode.TEXT),
            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",
            currentChatTextModel = initialChat.textModelOverride,
            quickTextModels = loadQuickTextModels(),
            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",
''',
'''            mode = (initialChat.mode ?: runCatching {
                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)
            }.getOrDefault(ChatMode.TEXT)).let { if (initialProfile.type == ProviderType.OPENROUTER) it else ChatMode.TEXT },
            connectionProfiles = initialProfiles,
            activeConnectionProfileId = initialProfileId,
            textModel = loadTextModelForProfile(initialProfile),
            currentChatTextModel = initialChat.textModelOverride,
            quickTextModels = loadQuickTextModels(initialProfileId),
            imageModel = loadImageModelForProfile(initialProfileId),
''')
replace_once(vm,
'''            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
''',
'''            apiKeyConfigured = isProfileConfigured(initialProfile),
''')
replace_once(vm,
'''    init {
        if (!secrets.getApiKey().isNullOrBlank()) refreshModelCapabilities()
    }
''',
'''    init {
        if (isProfileConfigured(initialProfile)) refreshModelCapabilities()
    }
''')
# Preserve public legacy method but make it apply to active profile.
regex_once(vm,
r'''    fun saveApiKey\(apiKey: String\?\) \{.*?\n    \}\n\n    fun setMode''',
'''    fun saveApiKey(apiKey: String?) {
        val profile = activeConnectionProfile()
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profile.id, apiKey)
        _state.value = _state.value.copy(
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Настройки сохранены"
        )
        if (_state.value.apiKeyConfigured) refreshModelCapabilities()
    }

    fun selectConnectionProfile(profileId: String) {
        if (_state.value.isLoading) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        prefs.edit().putString("active_connection_profile", profile.id).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(textModelOverride = null) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            connectionProfiles = _state.value.connectionProfiles,
            activeConnectionProfileId = profile.id,
            chats = chats,
            currentChatTextModel = null,
            textModel = loadTextModelForProfile(profile),
            quickTextModels = loadQuickTextModels(profile.id),
            imageModel = loadImageModelForProfile(profile.id),
            availableTextModels = emptyList(),
            availableImageModels = emptyList(),
            mode = if (profile.type == ProviderType.OPENROUTER) _state.value.mode else ChatMode.TEXT,
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) _state.value.webSearchEnabled else false,
            reasoningEnabled = false,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Профиль «${profile.name}» выбран"
        )
        if (isProfileConfigured(profile)) refreshModelCapabilities()
    }

    fun addCompatibleProfile(): String {
        val id = UUID.randomUUID().toString()
        val profile = ConnectionProfile(id, "Другой API", ProviderType.OPENAI_COMPATIBLE, "")
        val profiles = _state.value.connectionProfiles + profile
        saveConnectionProfiles(profiles)
        _state.value = _state.value.copy(connectionProfiles = profiles)
        selectConnectionProfile(id)
        return id
    }

    fun saveConnectionProfile(profileId: String, name: String, baseUrl: String, apiKey: String?) {
        val old = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val cleanUrl = normalizeBaseUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            _state.value = _state.value.copy(status = "Укажите адрес API")
            return
        }
        val updated = old.copy(
            name = if (old.type == ProviderType.OPENROUTER) "OpenRouter" else name.trim().ifBlank { "Другой API" },
            baseUrl = cleanUrl
        )
        val profiles = _state.value.connectionProfiles.map { if (it.id == profileId) updated else it }
        saveConnectionProfiles(profiles)
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profileId, apiKey)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            apiKeyConfigured = if (_state.value.activeConnectionProfileId == profileId) isProfileConfigured(updated) else _state.value.apiKeyConfigured,
            availableTextModels = if (_state.value.activeConnectionProfileId == profileId) emptyList() else _state.value.availableTextModels,
            availableImageModels = if (_state.value.activeConnectionProfileId == profileId) emptyList() else _state.value.availableImageModels,
            status = "Профиль сохранён"
        )
        if (_state.value.activeConnectionProfileId == profileId && isProfileConfigured(updated)) refreshModelCapabilities()
    }

    fun deleteConnectionProfile(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.type == ProviderType.OPENROUTER) return
        secrets.deleteProfileApiKey(profileId)
        val profiles = _state.value.connectionProfiles.filterNot { it.id == profileId }
        saveConnectionProfiles(profiles)
        _state.value = _state.value.copy(connectionProfiles = profiles)
        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile("openrouter")
    }

    fun setMode''')
replace_once(vm,
'''    fun setMode(mode: ChatMode) {
        prefs.edit().putString("chat_mode", mode.name).apply()
''',
'''    fun setMode(mode: ChatMode) {
        if (mode == ChatMode.IMAGE && activeConnectionProfile().type != ProviderType.OPENROUTER) {
            _state.value = _state.value.copy(status = "Генерация изображений сейчас доступна через профиль OpenRouter")
            return
        }
        prefs.edit().putString("chat_mode", mode.name).apply()
''')
replace_once(vm,
'''                    .putString("text_model", clean)
''',
'''                    .putString(profilePrefKey("text_model", _state.value.activeConnectionProfileId), clean)
''')
replace_once(vm,
'''                prefs.edit().putString("image_model", clean).apply()
''',
'''                prefs.edit().putString(profilePrefKey("image_model", _state.value.activeConnectionProfileId), clean).apply()
''')
replace_once(vm,
'''        prefs.edit().putString("quick_text_models_json", gson.toJson(next)).apply()
''',
'''        prefs.edit().putString(profilePrefKey("quick_text_models_json", _state.value.activeConnectionProfileId), gson.toJson(next)).apply()
''')
replace_once(vm,
'''    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }
''',
'''    fun setWebSearchEnabled(enabled: Boolean) {
        if (enabled && activeConnectionProfile().type != ProviderType.OPENROUTER) {
            _state.value = _state.value.copy(status = "Поиск в сети сейчас поддерживается профилем OpenRouter")
            return
        }
        prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }
''')
# Replace model refresh functions with provider-aware versions.
regex_once(vm,
r'''    fun refreshModels\(mode: ChatMode\) \{.*?\n    \}\n\n    private fun refreshModelCapabilities\(\) \{.*?\n    \}\n\n    private fun currentTextModelId''',
'''    fun refreshModels(mode: ChatMode) {
        val profile = activeConnectionProfile()
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        if (mode == ChatMode.IMAGE && profile.type != ProviderType.OPENROUTER) {
            _state.value = _state.value.copy(status = "Этот профиль пока поддерживает только текстовый OpenAI-совместимый API")
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                when {
                    profile.type == ProviderType.OPENROUTER && mode == ChatMode.TEXT -> api.models(key, profile.baseUrl)
                    profile.type == ProviderType.OPENROUTER && mode == ChatMode.IMAGE -> api.imageModels(key, profile.baseUrl)
                    else -> compatibleApi.models(key, profile.baseUrl)
                }
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        var selectedModel = _state.value.textModel
                        if (profile.type == ProviderType.OPENAI_COMPATIBLE && infos.none { it.id == selectedModel }) {
                            selectedModel = infos.firstOrNull()?.id.orEmpty()
                            if (selectedModel.isNotBlank()) {
                                prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                            }
                        }
                        val effectiveId = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } } ?: selectedModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            textModel = selectedModel,
                            currentChatTextModel = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } },
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> _state.value.copy(
                        availableImageModels = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Не удалось загрузить модели"
                )
            }
        }
    }

    private fun refreshModelCapabilities() {
        val profile = activeConnectionProfile()
        if (!isProfileConfigured(profile)) return
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        viewModelScope.launch {
            val textInfos = runCatching {
                if (profile.type == ProviderType.OPENROUTER) api.models(key, profile.baseUrl)
                else compatibleApi.models(key, profile.baseUrl)
            }.getOrNull()
            val imageInfos = if (profile.type == ProviderType.OPENROUTER) {
                runCatching { api.imageModels(key, profile.baseUrl) }.getOrNull()
            } else emptyList()
            var next = _state.value
            if (textInfos != null) {
                var selectedModel = next.textModel
                if (profile.type == ProviderType.OPENAI_COMPATIBLE && textInfos.none { it.id == selectedModel }) {
                    selectedModel = textInfos.firstOrNull()?.id.orEmpty()
                    if (selectedModel.isNotBlank()) prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                }
                val effectiveId = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } } ?: selectedModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    textModel = selectedModel,
                    currentChatTextModel = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } },
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            }
            next = next.copy(availableImageModels = imageInfos ?: emptyList())
            _state.value = next
        }
    }

    private fun currentTextModelId''')
# Generic profiles cannot safely assume file/PDF or image extensions.
replace_once(vm,
'''        if (mime == "application/pdf" || name.endsWith(".pdf")) return true to null
''',
'''        if (mime == "application/pdf" || name.endsWith(".pdf")) {
            return if (activeConnectionProfile().type == ProviderType.OPENROUTER) true to null
            else false to "PDF через произвольный совместимый API пока не включён: его формат передачи зависит от сервера"
        }
''')
# Stop both HTTP clients.
replace_once(vm,
'''        api.cancelActiveRequest()
        activeRequestJob?.cancel()
''',
'''        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''')
# Provider-aware send preamble.
replace_once(vm,
'''    fun send(text: String) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Укажите API-ключ OpenRouter в настройках")
            return
        }
''',
'''    fun send(text: String) {
        val profile = activeConnectionProfile()
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        if (_state.value.mode == ChatMode.IMAGE && profile.type != ProviderType.OPENROUTER) {
            _state.value = _state.value.copy(status = "Генерация изображений сейчас доступна через профиль OpenRouter")
            return
        }
''')
# Route text and image requests.
old_call = '''                        api.chat(
                            key,
                            textModel,
                            before,
                            clean,
                            (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)
                                .distinctBy { it.localPath ?: it.uri },
                            buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),
                            webSearchEnabled,
                            actualReasoning,
                            effort,
                            modelInfo?.supportsTools == true
                        )
'''
new_call = '''                        val allAttachments = (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)
                            .distinctBy { it.localPath ?: it.uri }
                        if (profile.type == ProviderType.OPENROUTER) {
                            api.chat(
                                key,
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),
                                webSearchEnabled,
                                actualReasoning,
                                effort,
                                modelInfo?.supportsTools == true,
                                profile.baseUrl
                            )
                        } else {
                            compatibleApi.chat(
                                key,
                                profile.baseUrl,
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, false)
                            )
                        }
'''
replace_once(vm, old_call, new_call)
replace_once(vm,
'''                        api.generateImage(key, imageModel, listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\n\n"), pending + projectImages)
''',
'''                        api.generateImage(
                            key,
                            imageModel,
                            listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\n\n"),
                            pending + projectImages,
                            profile.baseUrl
                        )
''')
# onCleared cancellation (second occurrence only if not already modified by stop replacement).
text = read(vm)
old = '''    override fun onCleared() {
        api.cancelActiveRequest()
        activeRequestJob?.cancel()
'''
if old in text:
    text = text.replace(old, '''    override fun onCleared() {
        api.cancelActiveRequest()
        compatibleApi.cancelActiveRequest()
        activeRequestJob?.cancel()
''', 1)
    write(vm, text)
# Profile helpers and profile-specific preferences near bottom.
replace_once(vm,
'''    private fun loadQuickTextModels(): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(prefs.getString("quick_text_models_json", "[]") ?: "[]", type)
''',
'''    private fun loadQuickTextModels(profileId: String): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(prefs.getString(profilePrefKey("quick_text_models_json", profileId), "[]") ?: "[]", type)
''')
insert_marker = '''    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {
'''
helpers = r'''    private fun defaultOpenRouterProfile() = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = OpenRouterClient.DEFAULT_BASE_URL
    )

    private fun loadConnectionProfiles(): List<ConnectionProfile> = runCatching {
        val type = object : TypeToken<List<ConnectionProfile>>() {}.type
        val stored = gson.fromJson<List<ConnectionProfile>>(
            prefs.getString("connection_profiles_json", "[]") ?: "[]",
            type
        ).orEmpty().filter { it.id.isNotBlank() }
        val openRouter = stored.firstOrNull { it.id == "openrouter" }?.copy(
            name = "OpenRouter",
            type = ProviderType.OPENROUTER,
            baseUrl = normalizeBaseUrl(stored.first { it.id == "openrouter" }.baseUrl).ifBlank { OpenRouterClient.DEFAULT_BASE_URL }
        ) ?: defaultOpenRouterProfile()
        listOf(openRouter) + stored.filterNot { it.id == "openrouter" }
    }.getOrElse { listOf(defaultOpenRouterProfile()) }

    private fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        prefs.edit().putString("connection_profiles_json", gson.toJson(profiles)).apply()
    }

    private fun activeConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }
            ?: _state.value.connectionProfiles.firstOrNull { it.id == "openrouter" }
            ?: defaultOpenRouterProfile()

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

    private fun isProfileConfigured(profile: ConnectionProfile): Boolean = when (profile.type) {
        ProviderType.OPENROUTER -> profile.baseUrl.isNotBlank() && !secrets.getProfileApiKey(profile.id).isNullOrBlank()
        ProviderType.OPENAI_COMPATIBLE -> profile.baseUrl.isNotBlank()
    }

    private fun connectionSetupMessage(profile: ConnectionProfile): String = when (profile.type) {
        ProviderType.OPENROUTER -> "Откройте «Профили подключения» и сохраните адрес и API-ключ OpenRouter"
        ProviderType.OPENAI_COMPATIBLE -> "Откройте «Профили подключения» и укажите адрес совместимого API"
    }

    private fun profilePrefKey(base: String, profileId: String): String =
        if (profileId == "openrouter") base else "${base}_profile_$profileId"

    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        return prefs.getString(profilePrefKey("text_model", profile.id), fallback) ?: fallback
    }

    private fun loadImageModelForProfile(profileId: String): String =
        prefs.getString(profilePrefKey("image_model", profileId), "bytedance-seed/seedream-4.5")
            ?: "bytedance-seed/seedream-4.5"

'''
text = read(vm)
if insert_marker not in text:
    raise SystemExit('reasoning helper marker missing')
write(vm, text.replace(insert_marker, helpers + insert_marker, 1))

# --- UI: connection profiles card + capability gating -------------------------------
ui = 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
replace_once(ui,
'''import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.GeneratedFile
''',
'''import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.GeneratedFile
''')
replace_once(ui,
'''import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ReasoningEffort
''',
'''import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ReasoningEffort
''')
replace_once(ui,
'''    val activeTextModel = state.currentChatTextModel ?: state.textModel
''',
'''    val activeProfile = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }
        ?: state.connectionProfiles.first()
    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER
    val activeTextModel = state.currentChatTextModel ?: state.textModel
''')
replace_once(ui,
'''                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.setMode(if (state.mode == ChatMode.TEXT) ChatMode.IMAGE else ChatMode.TEXT)
''',
'''                        enabled = !state.isLoading && (state.mode == ChatMode.IMAGE || openRouterProfile),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.setMode(if (state.mode == ChatMode.TEXT) ChatMode.IMAGE else ChatMode.TEXT)
''')
replace_once(ui,
'''                        subtitle = "OpenRouter web search",
                        checked = state.webSearchEnabled,
                        enabled = true,
''',
'''                        subtitle = if (openRouterProfile) "OpenRouter web search" else "Недоступно для этого профиля",
                        checked = state.webSearchEnabled,
                        enabled = openRouterProfile,
''')
# Settings state variables.
replace_once(ui,
'''    var key by remember { mutableStateOf("") }
    var storageOpen by remember { mutableStateOf(false) }
''',
'''    var storageOpen by remember { mutableStateOf(false) }
''')
replace_once(ui,
'''    var themeExpanded by remember { mutableStateOf(false) }
    var apiExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }
''',
'''    var themeExpanded by remember { mutableStateOf(false) }
    var connectionsExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }
    var editingProfileId by remember(state.activeConnectionProfileId) { mutableStateOf(state.activeConnectionProfileId) }
    val editingProfile = state.connectionProfiles.firstOrNull { it.id == editingProfileId }
        ?: state.connectionProfiles.first()
    var connectionName by remember(editingProfile.id, editingProfile.name) { mutableStateOf(editingProfile.name) }
    var connectionUrl by remember(editingProfile.id, editingProfile.baseUrl) { mutableStateOf(editingProfile.baseUrl) }
    var connectionKey by remember(editingProfile.id) { mutableStateOf("") }
''')
# Replace the old API key card wholesale.
regex_once(ui,
r'''            item \{\n                ExpandableSettingsCard\(\n                    title = "API-ключ OpenRouter",.*?\n            \}\n\n            item \{\n                Column\(''',
'''            item {
                ExpandableSettingsCard(
                    title = "Профили подключения",
                    subtitle = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }?.let { profile ->
                        profile.name + if (state.apiKeyConfigured) " · готов" else " · настройте"
                    } ?: "OpenRouter",
                    icon = Icons.Outlined.Language,
                    expanded = connectionsExpanded,
                    onToggle = { connectionsExpanded = !connectionsExpanded }
                ) {
                    Text(
                        "OpenRouter сохраняет все расширенные возможности Umnik. Другой профиль использует стандартные /models и /chat/completions и включает только подтверждённые возможности.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(state.connectionProfiles, key = { it.id }) { profile ->
                            FilterChip(
                                selected = state.activeConnectionProfileId == profile.id,
                                onClick = {
                                    editingProfileId = profile.id
                                    connectionName = profile.name
                                    connectionUrl = profile.baseUrl
                                    connectionKey = ""
                                    vm.selectConnectionProfile(profile.id)
                                },
                                label = { Text(profile.name, maxLines = 1) },
                                leadingIcon = if (state.activeConnectionProfileId == profile.id) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val id = vm.addCompatibleProfile()
                            editingProfileId = id
                            connectionName = "Другой API"
                            connectionUrl = ""
                            connectionKey = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить другой")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (editingProfile.type == ProviderType.OPENROUTER) "OpenRouter" else "OpenAI-совместимый профиль",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (editingProfile.type != ProviderType.OPENROUTER) {
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = connectionName,
                            onValueChange = { connectionName = it.take(60) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = connectionUrl,
                        onValueChange = { connectionUrl = it.trim().take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Адрес API") },
                        placeholder = {
                            Text(if (editingProfile.type == ProviderType.OPENROUTER) "https://openrouter.ai/api/v1" else "https://example.com/v1")
                        },
                        singleLine = true
                    )
                    Text(
                        if (editingProfile.type == ProviderType.OPENROUTER)
                            "Адрес можно изменить, если OpenRouter перенесёт API. Обычно менять его не нужно."
                        else
                            "Umnik добавит /models и /chat/completions к этому адресу. Для первого варианта используйте HTTPS-адрес.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = connectionKey,
                        onValueChange = { connectionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API-ключ") },
                        placeholder = { Text(if (state.apiKeyConfigured && editingProfile.id == state.activeConnectionProfileId) "Ключ уже сохранён · введите только для замены" else "Необязательно для локального API") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveConnectionProfile(
                                editingProfile.id,
                                connectionName,
                                connectionUrl,
                                connectionKey.takeIf { it.isNotBlank() }
                            )
                            connectionKey = ""
                        },
                        enabled = connectionUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить профиль") }
                    if (editingProfile.type != ProviderType.OPENROUTER) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                vm.deleteConnectionProfile(editingProfile.id)
                                editingProfileId = "openrouter"
                                val openRouter = state.connectionProfiles.first { it.id == "openrouter" }
                                connectionName = openRouter.name
                                connectionUrl = openRouter.baseUrl
                                connectionKey = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить этот профиль")
                        }
                    }
                    Text(
                        "Ключи хранятся локально и шифруются через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            item {
                Column(''')

# --- Version, changelog, docs --------------------------------------------------------
build = 'app/build.gradle.kts'
replace_once(build, '// Umnik v1.0.0', '// Umnik v1.1.0')
replace_once(build, 'versionCode = 27', 'versionCode = 28')
replace_once(build, 'versionName = "1.0.0"', 'versionName = "1.1.0"')

change = 'CHANGELOG.md'
replace_once(change, '## Unreleased\n', '''## Unreleased

## v1.1.0 - 2026-09-11

- В настройках появились профили подключения: встроенный OpenRouter и дополнительные OpenAI-совместимые API.
- Для OpenRouter теперь можно изменить базовый адрес API; все существующие возможности OpenRouter, включая метаданные моделей, reasoning, web search и генерацию изображений, сохранены.
- Дополнительные профили загружают модели через `/models` и работают с текстовым `/chat/completions`; неподтверждённые возможности намеренно не включаются автоматически.
- API-ключ каждого профиля хранится отдельно и шифруется через Android Keystore.
''')

readme = 'README.md'
text = read(readme)
text = text.replace('**Umnik** — Android-клиент для OpenRouter: мультимодальный чат, проекты, навыки, файлы и быстрая смена моделей без потери контекста.',
                    '**Umnik** — Android-клиент для OpenRouter и OpenAI-совместимых API: мультимодальный чат, проекты, навыки, файлы и быстрая смена моделей без потери контекста.', 1)
needle = '- OpenRouter `chat/completions` и выбор моделей;\n'
if needle in text:
    text = text.replace(needle, needle + '- профили подключения: OpenRouter с полным набором возможностей и дополнительные OpenAI-совместимые API;\n- настраиваемый базовый адрес OpenRouter на случай изменения API endpoint;\n', 1)
write(readme, text)

privacy = 'PRIVACY.md'
text = read(privacy)
text = text.replace('Umnik задуман как локальный Android-клиент для работы с OpenRouter.',
                    'Umnik задуман как локальный Android-клиент для работы с OpenRouter и другими настроенными пользователем OpenAI-совместимыми API.', 1)
text = text.replace('Пользователь вводит собственный API-ключ OpenRouter.',
                    'Пользователь вводит собственный API-ключ OpenRouter или другого выбранного профиля подключения. Для API, не требующих авторизации, ключ может не задаваться.', 1)
text = text.replace('Когда пользователь запускает запрос, Umnik отправляет необходимые данные в OpenRouter.',
                    'Когда пользователь запускает запрос, Umnik отправляет необходимые данные в активный профиль подключения. Для профиля OpenRouter данные отправляются в OpenRouter; для дополнительного профиля — на указанный пользователем API-адрес.', 1)
write(privacy, text)

print('v1.1.0 patch applied')
