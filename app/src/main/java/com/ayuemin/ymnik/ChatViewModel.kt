package com.ayuemin.ymnik

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.network.OpenRouterClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatViewModel(private val context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val skills = SkillRepository(context)
    private val api = OpenRouterClient(context)
    private val gson = Gson()

    private val _state = MutableStateFlow(
        UiState(
            messages = loadMessages(),
            skills = skills.list(),
            activeSkillIds = prefs.getStringSet("active_skills", emptySet())?.toSet() ?: emptySet(),
            model = prefs.getString("model", "openrouter/auto") ?: "openrouter/auto",
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun saveSettings(apiKey: String?, model: String) {
        if (apiKey != null) secrets.saveApiKey(apiKey)
        prefs.edit().putString("model", model.trim().ifBlank { "openrouter/auto" }).apply()
        _state.value = _state.value.copy(
            model = model.trim().ifBlank { "openrouter/auto" },
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
            status = "Настройки сохранены"
        )
    }

    fun refreshModels() {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, status = null)
            runCatching { api.models(key) }
                .onSuccess { _state.value = _state.value.copy(availableModels = it, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, status = it.message) }
        }
    }

    fun addAttachment(uri: Uri) {
        runCatching { api.attachmentFromUri(uri) }
            .onSuccess { a ->
                if (a.size > 25L * 1024 * 1024) {
                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")
                } else {
                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + a)
                }
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun removeAttachment(uri: String) {
        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments.filterNot { it.uri == uri })
    }

    fun importSkillFile(uri: Uri) {
        runCatching { skills.importFile(uri) }
            .onSuccess { skill -> _state.value = _state.value.copy(skills = skills.list(), status = "Навык «${skill.name}» импортирован") }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun importSkillTree(uri: Uri) {
        runCatching { skills.importTree(uri) }
            .onSuccess { skill -> _state.value = _state.value.copy(skills = skills.list(), status = "Папка навыка «${skill.name}» импортирована") }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun toggleSkill(id: String) {
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("active_skills", next).apply()
        _state.value = _state.value.copy(activeSkillIds = next)
    }

    fun deleteSkill(id: String) {
        skills.delete(id)
        val next = _state.value.activeSkillIds - id
        prefs.edit().putStringSet("active_skills", next).apply()
        _state.value = _state.value.copy(skills = skills.list(), activeSkillIds = next)
    }

    fun send(text: String) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Укажите API-ключ OpenRouter в настройках")
            return
        }
        val clean = text.trim()
        val pending = _state.value.pendingAttachments
        if (clean.isBlank() && pending.isEmpty()) return
        if (_state.value.isLoading) return

        val before = _state.value.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean.ifBlank { "[Вложения]" },
            attachmentNames = pending.map { it.name }
        )
        val next = before + user
        _state.value = _state.value.copy(messages = next, pendingAttachments = emptyList(), isLoading = true, status = null)
        persistMessages(next)

        viewModelScope.launch {
            val skillText = skills.promptFor(_state.value.activeSkillIds)
            val system = buildSystemPrompt(skillText)
            runCatching {
                api.chat(key, _state.value.model, before, clean, pending, system)
            }.onSuccess { result ->
                val assistant = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    text = result.text.ifBlank { if (result.files.isNotEmpty()) "Файл создан." else "Пустой ответ модели." },
                    generatedFiles = result.files
                )
                val messages = _state.value.messages + assistant
                _state.value = _state.value.copy(messages = messages, isLoading = false)
                persistMessages(messages)
            }.onFailure {
                _state.value = _state.value.copy(isLoading = false, status = it.message ?: "Ошибка запроса")
            }
        }
    }

    fun clearChat() {
        _state.value.generatedPaths().forEach { File(it).delete() }
        _state.value = _state.value.copy(messages = emptyList(), status = "Чат очищен")
        persistMessages(emptyList())
    }

    fun saveGeneratedFile(file: GeneratedFile, destination: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    File(file.localPath).inputStream().use { input -> input.copyTo(output) }
                } ?: error("Не удалось открыть место сохранения")
            }.onSuccess { _state.value = _state.value.copy(status = "${file.name} сохранён") }
                .onFailure { _state.value = _state.value.copy(status = it.message) }
        }
    }

    fun dismissStatus() { _state.value = _state.value.copy(status = null) }

    private fun buildSystemPrompt(skillText: String): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        appendLine("У тебя есть локальный инструмент create_file. Когда пользователь просит создать файл для скачивания, готовый .md/.txt/.json или другой текстовый артефакт, используй create_file вместо имитации ссылки.")
        appendLine("Подключённые навыки ниже выбраны пользователем. Следуй их инструкциям как рабочим правилам, если они не противоречат явному текущему запросу пользователя.")
        appendLine("Не утверждай, что исполнил код из папки навыка: Umnik передаёт навыкам только разрешённые текстовые материалы.")
        if (skillText.isNotBlank()) {
            appendLine("\n===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
            appendLine(skillText)
            appendLine("===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
        }
    }

    private fun persistMessages(messages: List<ChatMessage>) {
        prefs.edit().putString("messages", gson.toJson(messages.takeLast(80))).apply()
    }

    private fun loadMessages(): List<ChatMessage> = runCatching {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        val type = object : TypeToken<List<ChatMessage>>() {}.type
        gson.fromJson<List<ChatMessage>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun UiState.generatedPaths() = messages.flatMap { it.generatedFiles }.map { it.localPath }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(context.applicationContext) as T
    }
}
