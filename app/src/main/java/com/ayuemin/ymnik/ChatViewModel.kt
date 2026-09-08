package com.ayuemin.ymnik

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.data.StorageRepository
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.ThemeChoice
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
    private val chatsRepository = ChatRepository(context)
    private val storageRepository = StorageRepository(context)
    private val api = OpenRouterClient(context)
    private val gson = Gson()

    private val initialChats = loadInitialChats()
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }

    private val _state = MutableStateFlow(
        UiState(
            messages = initialChat.messages,
            chats = initialChats,
            currentChatId = initialChatId,
            skills = skills.list(),
            activeSkillIds = prefs.getStringSet("active_skills", emptySet())?.toSet() ?: emptySet(),
            mode = runCatching {
                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)
            }.getOrDefault(ChatMode.TEXT),
            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",
            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
            answerSoundEnabled = prefs.getBoolean("answer_sound", true),
            themeChoice = runCatching {
                ThemeChoice.valueOf(prefs.getString("theme_choice", ThemeChoice.DYNAMIC.name) ?: ThemeChoice.DYNAMIC.name)
            }.getOrDefault(ThemeChoice.DYNAMIC),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
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

    fun setAnswerSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("answer_sound", enabled).apply()
        _state.value = _state.value.copy(answerSoundEnabled = enabled)
        if (enabled) playReadySound()
    }

    fun setThemeChoice(choice: ThemeChoice) {
        prefs.edit().putString("theme_choice", choice.name).apply()
        _state.value = _state.value.copy(themeChoice = choice)
    }

    fun createChat() {
        if (_state.value.isLoading) return
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат"
        )
        val next = listOf(chat) + _state.value.chats
        chatsRepository.save(next)
        prefs.edit().putString("current_chat_id", chat.id).apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats()
        )
    }

    fun switchChat(id: String) {
        if (_state.value.isLoading) return
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        prefs.edit().putString("current_chat_id", id).apply()
        _state.value = _state.value.copy(
            currentChatId = id,
            messages = chat.messages,
            pendingAttachments = emptyList()
        )
    }

    fun deleteChat(id: String) {
        if (_state.value.isLoading) return
        val target = _state.value.chats.firstOrNull { it.id == id } ?: return
        target.messages.flatMap { it.generatedFiles }.forEach { File(it.localPath).delete() }

        var remaining = _state.value.chats.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(ChatSession(UUID.randomUUID().toString(), "Новый чат"))
        }

        val selected = if (_state.value.currentChatId == id) remaining.first() else
            remaining.firstOrNull { it.id == _state.value.currentChatId } ?: remaining.first()

        chatsRepository.save(remaining)
        prefs.edit().putString("current_chat_id", selected.id).apply()
        _state.value = _state.value.copy(
            chats = remaining,
            currentChatId = selected.id,
            messages = selected.messages,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Диалог удалён"
        )
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
                _state.value = _state.value.copy(
                    skills = skills.list(),
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Навык «${skill.name}» импортирован"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun importSkillTree(uri: Uri) {
        runCatching { skills.importTree(uri) }
            .onSuccess { skill ->
                _state.value = _state.value.copy(
                    skills = skills.list(),
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Папка навыка «${skill.name}» импортирована"
                )
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
        _state.value = _state.value.copy(
            skills = skills.list(),
            activeSkillIds = next,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
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

        val chatId = _state.value.currentChatId
        val before = _state.value.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean.ifBlank {
                if (_state.value.mode == ChatMode.IMAGE) "Создай вариант приложенного изображения" else "[Вложения]"
            },
            attachmentNames = pending.map { it.name }
        )
        val nextMessages = before + user
        val title = if (before.isEmpty()) makeChatTitle(clean, pending.map { it.name }) else null
        val nextChats = replaceChatMessages(_state.value.chats, chatId, nextMessages, title)
        chatsRepository.save(nextChats)

        _state.value = _state.value.copy(
            messages = nextMessages,
            chats = nextChats,
            pendingAttachments = emptyList(),
            isLoading = true,
            busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",
            status = null,
            storageStats = storageRepository.stats()
        )

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
                val chats = replaceChatMessages(_state.value.chats, chatId, messages, null)
                chatsRepository.save(chats)
                _state.value = _state.value.copy(
                    messages = messages,
                    chats = chats,
                    isLoading = false,
                    busyLabel = null,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
                playReadySound()
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
        val generated = GeneratedFile(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = "text/markdown",
            localPath = file.absolutePath,
            size = file.length()
        )
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
        return generated
    }

    fun clearChat() {
        if (_state.value.isLoading) return
        _state.value.messages.flatMap { it.generatedFiles }.forEach { File(it.localPath).delete() }
        val chats = replaceChatMessages(_state.value.chats, _state.value.currentChatId, emptyList(), "Новый чат")
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            messages = emptyList(),
            chats = chats,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Чат очищен"
        )
    }

    fun refreshStorage() {
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun deleteStoredFile(file: StoredFile) {
        if (!file.deletable) {
            _state.value = _state.value.copy(status = "Файлы навыков удаляются во вкладке «Навыки»")
            return
        }
        if (!storageRepository.delete(file.localPath)) {
            _state.value = _state.value.copy(status = "Не удалось удалить файл")
            return
        }
        removeFileReferences(setOf(file.localPath))
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Файл удалён"
        )
    }

    fun clearWorkingFiles() {
        if (_state.value.isLoading) return
        val generatedPaths = _state.value.chats
            .flatMap { it.messages }
            .flatMap { it.generatedFiles }
            .map { it.localPath }
            .toSet()
        storageRepository.clearWorkingFiles()
        removeFileReferences(generatedPaths)
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Сгенерированные файлы и экспорт очищены"
        )
    }

    fun storedFileAsGenerated(file: StoredFile): GeneratedFile = GeneratedFile(
        id = file.id,
        name = file.name,
        mimeType = file.mimeType,
        localPath = file.localPath,
        size = file.size
    )

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

    private fun playReadySound() {
        if (!_state.value.answerSoundEnabled) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 28)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
            }, 180)
        }
    }

    private fun buildSystemPrompt(skillText: String): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        appendLine("У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.")
        appendLine("Если пользователь просит текст в отдельном, изолированном или удобном для копирования блоке, ОБЯЗАТЕЛЬНО используй ровно такой синтаксис:")
        appendLine(":::copy")
        appendLine("текст блока")
        appendLine(":::")
        appendLine("Umnik распознаёт :::copy как отдельную карточку с кнопкой копирования. Не утверждай, что показал отдельный блок, если не использовал этот синтаксис.")
        appendLine("Для кода используй обычные fenced Markdown-блоки с тройными обратными кавычками.")
        appendLine("Подключённые навыки ниже выбраны пользователем. Следуй их инструкциям как рабочим правилам, если они не противоречат явному текущему запросу пользователя.")
        appendLine("Не утверждай, что исполнил код из папки навыка: Umnik передаёт навыкам только разрешённые текстовые материалы.")
        if (skillText.isNotBlank()) {
            appendLine("\n===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
            appendLine(skillText)
            appendLine("===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
        }
    }

    private fun replaceChatMessages(
        chats: List<ChatSession>,
        chatId: String,
        messages: List<ChatMessage>,
        titleOverride: String?
    ): List<ChatSession> {
        val now = System.currentTimeMillis()
        return chats.map { chat ->
            if (chat.id == chatId) chat.copy(
                title = titleOverride ?: chat.title,
                messages = messages.takeLast(120),
                updatedAt = now
            ) else chat
        }
    }

    private fun removeFileReferences(paths: Set<String>) {
        if (paths.isEmpty()) return
        val chats = _state.value.chats.map { chat ->
            chat.copy(messages = chat.messages.map { message ->
                message.copy(generatedFiles = message.generatedFiles.filterNot { it.localPath in paths })
            })
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(
            chats = chats,
            messages = current?.messages ?: emptyList()
        )
    }

    private fun makeChatTitle(text: String, attachmentNames: List<String>): String {
        val source = text.trim().ifBlank { attachmentNames.firstOrNull().orEmpty() }.ifBlank { "Новый чат" }
        val oneLine = source.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 38) oneLine else oneLine.take(38).trimEnd() + "…"
    }

    private fun loadInitialChats(): List<ChatSession> {
        val existing = chatsRepository.list()
        if (existing.isNotEmpty()) return existing

        val legacy = loadLegacyMessages()
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = if (legacy.isEmpty()) "Новый чат" else makeChatTitle(
                legacy.firstOrNull { it.role == "user" }?.text.orEmpty(),
                legacy.firstOrNull { it.role == "user" }?.attachmentNames ?: emptyList()
            ),
            messages = legacy
        )
        chatsRepository.save(listOf(chat))
        prefs.edit().remove("messages").apply()
        return listOf(chat)
    }

    private fun loadLegacyMessages(): List<ChatMessage> = runCatching {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        val type = object : TypeToken<List<ChatMessage>>() {}.type
        gson.fromJson<List<ChatMessage>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(context.applicationContext) as T
    }
}
