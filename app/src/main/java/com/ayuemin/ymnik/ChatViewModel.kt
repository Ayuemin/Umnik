package com.ayuemin.ymnik

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
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
            mode = runCatching {
                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)
            }.getOrDefault(ChatMode.TEXT),
            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",
            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun saveApiKey(apiKey: String?) {
        if (!apiKey.isNullOrBlank()) secrets.saveApiKey(apiKey)
        _state.value = _state.value.copy(
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
            status = "Настройки сохранены"
        )
    }

    fun setMode(mode: ChatMode) {
        prefs.edit().putString("chat_mode", mode.name).apply()
        _state.value = _state.value.copy(mode = mode)
    }

    fun selectModel(mode: ChatMode, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        when (mode) {
            ChatMode.TEXT -> {
                prefs.edit().putString("text_model", clean).apply()
                _state.value = _state.value.copy(textModel = clean)
            }
            ChatMode.IMAGE -> {
                prefs.edit().putString("image_model", clean).apply()
                _state.value = _state.value.copy(imageModel = clean)
            }
        }
    }

    fun refreshModels(mode: ChatMode) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                when (mode) {
                    ChatMode.TEXT -> api.models(key)
                    ChatMode.IMAGE -> api.imageModels(key)
                }
            }.onSuccess { ids ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> _state.value.copy(
                        availableTextModels = ids,
                        isLoading = false,
                        busyLabel = null
                    )
                    ChatMode.IMAGE -> _state.value.copy(
                        availableImageModels = ids,
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

    fun addAttachment(uri: Uri) {
        runCatching { api.attachmentFromUri(uri) }
            .onSuccess { attachment ->
                if (attachment.size > 25L * 1024 * 1024) {
                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")
                } else {
                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                }
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun removeAttachment(uri: String) {
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.uri == uri }
        )
    }

    fun importSkillFile(uri: Uri) {
        runCatching { skills.importFile(uri) }
            .onSuccess { skill ->
                _state.value = _state.value.copy(skills = skills.list(), status = "Навык «${skill.name}» импортирован")
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun importSkillTree(uri: Uri) {
        runCatching { skills.importTree(uri) }
            .onSuccess { skill ->
                _state.value = _state.value.copy(skills = skills.list(), status = "Папка навыка «${skill.name}» импортирована")
            }
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

        if (_state.value.mode == ChatMode.IMAGE && pending.any { !it.mimeType.startsWith("image/") }) {
            _state.value = _state.value.copy(status = "В режиме изображений можно прикладывать только изображения-референсы")
            return
        }

        val before = _state.value.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean.ifBlank {
                if (_state.value.mode == ChatMode.IMAGE) "Создай вариант приложенного изображения" else "[Вложения]"
            },
            attachmentNames = pending.map { it.name }
        )
        val next = before + user
        _state.value = _state.value.copy(
            messages = next,
            pendingAttachments = emptyList(),
            isLoading = true,
            busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",
            status = null
        )
        persistMessages(next)

        val mode = _state.value.mode
        val textModel = _state.value.textModel
        val imageModel = _state.value.imageModel

        viewModelScope.launch {
            val operation = runCatching {
                when (mode) {
                    ChatMode.TEXT -> {
                        val skillText = skills.promptFor(_state.value.activeSkillIds)
                        api.chat(key, textModel, before, clean, pending, buildSystemPrompt(skillText))
                    }
                    ChatMode.IMAGE -> api.generateImage(key, imageModel, clean, pending)
                }
            }

            operation.onSuccess { result ->
                val assistant = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    text = result.text.ifBlank {
                        if (result.files.isNotEmpty()) "Готово." else "Пустой ответ модели."
                    },
                    generatedFiles = result.files
                )
                val messages = _state.value.messages + assistant
                _state.value = _state.value.copy(
                    messages = messages,
                    isLoading = false,
                    busyLabel = null
                )
                persistMessages(messages)
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Ошибка запроса"
                )
            }
        }
    }

    fun exportMessage(message: ChatMessage): GeneratedFile {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "umnik_${message.timestamp}.md"
        val file = File(dir, name)
        file.writeText(message.text)
        return GeneratedFile(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = "text/markdown",
            localPath = file.absolutePath,
            size = file.length()
        )
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
            }.onSuccess {
                _state.value = _state.value.copy(status = "${file.name} сохранён")
            }.onFailure {
                _state.value = _state.value.copy(status = it.message)
            }
        }
    }

    fun dismissStatus() {
        _state.value = _state.value.copy(status = null)
    }

    private fun buildSystemPrompt(skillText: String): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        appendLine("У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.")
        appendLine("Если даёшь отдельный текст, промпт, шаблон, код или фрагмент, который пользователю удобно копировать целиком, помещай его в fenced Markdown-блок с тройными обратными кавычками. Umnik покажет такой блок отдельно и добавит кнопку копирования.")
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(context.applicationContext) as T
    }
}
