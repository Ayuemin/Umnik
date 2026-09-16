from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Anchor not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


cvm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"

replace_once(
    cvm,
    "import com.ayuemin.ymnik.data.ChatFileRepository\n",
    "import com.ayuemin.ymnik.data.ChatFileRepository\n"
    "import com.ayuemin.ymnik.data.ChatMemoryManager\n"
    "import com.ayuemin.ymnik.data.ChatMemoryRepository\n"
)
replace_once(
    cvm,
    "import com.ayuemin.ymnik.model.ChatMode\n",
    "import com.ayuemin.ymnik.model.ChatMode\n"
    "import com.ayuemin.ymnik.model.ChatContextMode\n"
    "import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings\n"
    "import com.ayuemin.ymnik.model.ChatMemoryStats\n"
)
replace_once(
    cvm,
    "    private val chatFilesRepository = ChatFileRepository(context)\n"
    "    private val knowledgeBase = KnowledgeBaseRepository(context)\n",
    "    private val chatFilesRepository = ChatFileRepository(context)\n"
    "    private val chatMemory = ChatMemoryRepository(context)\n"
    "    private val knowledgeBase = KnowledgeBaseRepository(context)\n"
)
replace_once(
    cvm,
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n"
    "    private val projectsRepository = ProjectRepository(context)\n",
    "    private val embeddingApi = OpenRouterEmbeddingClient(context)\n"
    "    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n"
    "    private val projectsRepository = ProjectRepository(context)\n"
)

memory_api = r'''    fun globalOpenRouterTools() = openRouterFeaturePrefs.tools()

    fun chatMemorySettings(): ChatMemoryGlobalSettings = chatMemory.settings()

    fun saveChatMemorySettings(settings: ChatMemoryGlobalSettings) {
        if (_state.value.isLoading || _state.value.requestActive) return
        chatMemory.saveSettings(settings)
        _state.value = _state.value.copy(status = "Настройки памяти и контекста сохранены")
    }

    fun chatContextMode(chatId: String): ChatContextMode = chatMemory.mode(chatId)

    fun setChatContextMode(chatId: String, mode: ChatContextMode) {
        if (_state.value.isLoading || _state.value.requestActive) return
        if (_state.value.chats.none { it.id == chatId }) return
        chatMemory.saveMode(chatId, mode)
        val label = when (mode) {
            ChatContextMode.AUTO -> "Автоматический"
            ChatContextMode.FULL -> "Всегда полный"
            ChatContextMode.ECONOMY -> "Экономный"
        }
        _state.value = _state.value.copy(status = "Контекст чата: $label")
    }

    fun chatMemoryStats(chatId: String): ChatMemoryStats = chatMemory.stats(chatId)

    fun totalChatMemoryBytes(): Long = chatMemory.totalBytes()

    fun clearChatMemory(chatId: String) {
        if (_state.value.isLoading || _state.value.requestActive) return
        chatMemory.clearMemory(chatId)
        _state.value = _state.value.copy(status = "Служебная память чата очищена. Переписка сохранена.")
    }

    fun clearAllChatMemory() {
        if (_state.value.isLoading || _state.value.requestActive) return
        chatMemory.clearAllMemory()
        _state.value = _state.value.copy(status = "Служебная память всех чатов очищена. Переписка сохранена.")
    }

    fun rebuildChatMemory(chatId: String) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        if (chatMemory.mode(chatId) == ChatContextMode.FULL) {
            _state.value = _state.value.copy(status = "В режиме «Всегда полный» долговременная память не используется")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Перестраиваю память чата…", status = null)
            try {
                val (apiKey, baseUrl) = knowledgeOpenRouterCredentials()
                chatMemoryManager.rebuild(chat, apiKey, baseUrl)
                val stats = chatMemory.stats(chatId)
                _state.value = _state.value.copy(
                    status = if (stats.chunks == 0)
                        "История пока слишком короткая для долговременной памяти"
                    else
                        "Память перестроена: ${stats.checkpoints} checkpoint, ${stats.chunks} фрагментов"
                )
            } catch (error: Throwable) {
                DiagnosticLog.record(context, "CHAT_MEMORY", "manual rebuild failed chat=${chatId.take(8)}", error)
                _state.value = _state.value.copy(status = error.message ?: "Не удалось перестроить память чата")
            } finally {
                _state.value = _state.value.copy(isLoading = false, busyLabel = null)
            }
        }
    }
'''
replace_once(cvm, "    fun globalOpenRouterTools() = openRouterFeaturePrefs.tools()\n", memory_api)

replace_once(
    cvm,
    "        knowledgeBase.deleteOwner(KnowledgeOwnerKind.CHAT, id)\n"
    "        var remaining = _state.value.chats.filterNot { it.id == id }\n",
    "        knowledgeBase.deleteOwner(KnowledgeOwnerKind.CHAT, id)\n"
    "        chatMemory.deleteChat(id)\n"
    "        var remaining = _state.value.chats.filterNot { it.id == id }\n"
)
replace_once(
    cvm,
    "            knowledgeBase.deleteOwner(KnowledgeOwnerKind.CHAT, chat.id)\n"
    "            projectAutomation.deleteChat(chat.id)\n",
    "            knowledgeBase.deleteOwner(KnowledgeOwnerKind.CHAT, chat.id)\n"
    "            chatMemory.deleteChat(chat.id)\n"
    "            projectAutomation.deleteChat(chat.id)\n"
)
replace_once(
    cvm,
    "        val chatId = _state.value.currentChatId\n"
    "        chatFilesRepository.deleteChat(chatId)\n",
    "        val chatId = _state.value.currentChatId\n"
    "        chatFilesRepository.deleteChat(chatId)\n"
    "        chatMemory.clearMemory(chatId)\n"
)
replace_once(
    cvm,
    "        knowledgeBase.deleteOwner(KnowledgeOwnerKind.PROJECT, projectId)\n"
    "        val projects = _state.value.projects.filterNot { it.id == projectId }\n",
    "        knowledgeBase.deleteOwner(KnowledgeOwnerKind.PROJECT, projectId)\n"
    "        _state.value.chats.filter { it.projectId == projectId }.forEach { chatMemory.deleteChat(it.id) }\n"
    "        val projects = _state.value.projects.filterNot { it.id == projectId }\n"
)

replace_once(
    cvm,
    '        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n',
    '        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n'
    '        appendLine("Считай текущий запрос продолжением этого диалога. Ссылки вроде «это», «предыдущий текст», «эта статья», «второй вариант», «сделай короче» относятся к уже переданной истории или памяти чата, если из контекста понятно, о чём речь.")\n'
    '        appendLine("Не проси пользователя повторно прислать материал, если нужный текст, результат или сведения уже присутствуют в переданной истории, долговременной памяти, базе знаний или приложенных файлах.")\n'
)

old = '''                        val knowledgeContext = knowledgeSystemContext(currentProject, currentChat, clean)
                        if (profile.type == ProviderType.OPENROUTER) {
                            api.chat(
                                key,
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true) + knowledgeContext,
                                webSearchEnabled,
                                actualReasoning,
                                effort,
                                modelInfo?.supportsTools == true,
                                effectiveTextBaseUrl(profile),
                                requestModelInfo
                            )
                        } else {
                            compatibleApi.chat(
                                key,
                                effectiveTextBaseUrl(profile),
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, false) + knowledgeContext,
                                requestModelInfo
                            )
                        }
'''
new = '''                        val knowledgeContext = knowledgeSystemContext(currentProject, currentChat, clean)
                        val memoryCredentials = runCatching { knowledgeOpenRouterCredentials() }.getOrNull()
                        val preparedContext = chatMemoryManager.prepare(
                            chat = currentChat,
                            fullHistory = before,
                            query = clean,
                            apiKey = memoryCredentials?.first,
                            baseUrl = memoryCredentials?.second
                        )
                        if (profile.type == ProviderType.OPENROUTER) {
                            api.chat(
                                key,
                                textModel,
                                preparedContext.history,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true) +
                                    preparedContext.systemContext + knowledgeContext,
                                webSearchEnabled,
                                actualReasoning,
                                effort,
                                modelInfo?.supportsTools == true,
                                effectiveTextBaseUrl(profile),
                                requestModelInfo
                            )
                        } else {
                            compatibleApi.chat(
                                key,
                                effectiveTextBaseUrl(profile),
                                textModel,
                                preparedContext.history,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, false) +
                                    preparedContext.systemContext + knowledgeContext,
                                requestModelInfo
                            )
                        }
'''
replace_once(cvm, old, new)

for path in [
    "app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt",
    "app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt",
    "app/src/main/java/com/ayuemin/ymnik/ui/ProjectChatAutomationDialog.kt",
]:
    replace_once(
        path,
        '''            item {
                KnowledgeBaseSection(
                    kind = KnowledgeOwnerKind.CHAT,
''',
        '''            item { ChatContextSettingsSection(chat, state, vm) }
            item {
                KnowledgeBaseSection(
                    kind = KnowledgeOwnerKind.CHAT,
'''
    )

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''            item {
                val imageConnectionName = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"
''',
    '''            item { ChatMemoryGlobalSettingsSection(state, vm) }

            item {
                val imageConnectionName = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"
'''
)

help_path = "app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt"
replace_once(
    help_path,
    "Там можно изменить название, задать роль, постоянную инструкцию, добавить чат в избранное и настроить его персональную базу знаний.\n",
    "Там можно изменить название, задать роль, постоянную инструкцию, добавить чат в избранное, выбрать режим **Контекста чата** и настроить его персональную базу знаний.\n"
)
memory_topic = r'''        Topic(
            "Память и контекст длинного чата",
            """
# Память и контекст длинного чата

**Что это:** способ не отправлять дорогой текстовой модели всю многомесячную переписку при каждом новом сообщении.

**Где выбрать режим для чата:** боковая панель → **⋮ → Настройки чата → Контекст чата**. У проектного чата тот же блок находится в его настройках.

Есть три режима:

- **Автоматический** — сначала передаётся полная история, а после заданного порога Umnik оставляет свежие сообщения целиком, добавляет короткую карточку состояния и находит через Embeddings только подходящие старые фрагменты.
- **Всегда полный** — использовать максимум исходной истории, который помещается в окно модели. Служебная долговременная память при формировании запроса не используется.
- **Экономный** — перейти на гибридную память раньше и держать меньший свежий хвост.

**Как устроена память:** старая часть диалога остаётся исходной перепиской на телефоне. Umnik создаёт неизменяемые checkpoint-конспекты отдельных участков, отдельную компактную карточку текущего состояния и embeddings исходных старых фрагментов. Конспект помогает понять, что было решено, а embeddings позволяют вернуть точный старый эпизод.

**Пример:** после сотни сообщений спросите «Как мы тогда решили назвать кнопку?». Вместо всей переписки Umnik может отправить модели свежий разговор, карточку состояния и несколько старых фрагментов именно про название кнопки.

**Общие параметры:** **Настройки → Память и контекст**. Там выбираются Embedding-модель, модель конспекта, пороги Автоматического и Экономного режимов, размер свежего хвоста и дополнительные параметры поиска.

**Удаление:** память хранится отдельно от обычных файлов и базы знаний. **Очистить память** не удаляет переписку. При удалении самого чата его checkpoint-конспекты, embeddings и карточка состояния удаляются автоматически.
            """.trimIndent()
        ),
'''
replace_once(
    help_path,
    '''        Topic(
            "Файлы, PDF и фотографии",
''',
    memory_topic + '''        Topic(
            "Файлы, PDF и фотографии",
'''
)

build = "app/build.gradle.kts"
replace_once(build, "// Umnik v1.15.3", "// Umnik v1.16.0")
replace_once(
    build,
    '        versionCode = 118\n        versionName = "1.15.3"',
    '        versionCode = 119\n        versionName = "1.16.0"'
)

changelog = Path("CHANGELOG.md")
if changelog.exists():
    text = changelog.read_text(encoding="utf-8")
    if "## v1.16.0" not in text:
        entry = '''## v1.16.0

- Добавлена гибридная память длинных чатов: свежая история + карточка состояния + Embeddings старой переписки.
- В настройках каждого чата появился блок «Контекст чата»: Автоматический, Всегда полный, Экономный.
- В общих настройках появился блок «Память и контекст» с моделями, порогами, размером свежего хвоста и дополнительными параметрами.
- Добавлены checkpoint-конспекты, перестроение и очистка служебной памяти без удаления переписки.
- Память хранится отдельно от обычных файлов и базы знаний и автоматически удаляется вместе с чатом.
- Усилено продолжение диалога: модель не должна повторно просить материал, уже присутствующий в истории, памяти, базе знаний или вложениях.
- Памятка Umnik обновлена новым разделом о памяти и контексте.

'''
        changelog.write_text(entry + text, encoding="utf-8")

Path(".github/workflows/apply-chat-memory-v1.16.yml").unlink(missing_ok=True)
Path(".github/scripts/apply_chat_memory_v116.py").unlink(missing_ok=True)
