from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str, minimum: int = 1) -> None:
    text = read(path)
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{path}: expected at least {minimum} matches, got {count}: {old[:100]!r}")
    write(path, text.replace(old, new))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: regex expected one match, got {count}: {pattern[:120]!r}")
    write(path, updated)


# ---------------------------------------------------------------------------
# Version and changelog
# ---------------------------------------------------------------------------
replace_once("app/build.gradle.kts", "// Umnik v1.9.0", "// Umnik v1.10.0")
replace_once("app/build.gradle.kts", "versionCode = 90\n        versionName = \"1.9.0\"", "versionCode = 100\n        versionName = \"1.10.0\"")

changelog = read("CHANGELOG.md")
marker = "## Unreleased\n"
entry = """## Unreleased\n\n## v1.10.0 - 2026-09-14\n\n- Повтор неудачного запроса больше не создаёт вторую карточку того же сообщения: старое состояние ошибки заменяется новым запуском. Сетевые обрывы, ограничения провайдера и несовместимая модель получают понятные сообщения.\n- Проектные чаты убраны из общей истории боковой панели и остаются внутри своего проекта. В строку чата добавлено переименование.\n- Исправлена защита обычного чата от специализированных моделей: Video/Batch и другие нетекстовые модели не отправляются в `/chat/completions`; сохранённые устаревшие быстрые модели очищаются после обновления каталога.\n- Убран оставшийся внутренний лимит 10 быстрых моделей.\n- Обновление фоновых Batch/Video больше не переключает активный чат и не запускает новый worker на каждое событие. Результат синхронизируется с текущим экраном без побочных запросов каталога.\n- Batch получил явный конструктор отдельных заданий и быстрый импорт списка по строкам; помнить разделитель `---` больше не требуется.\n- Раздел Tools + RAG переведён на понятные русские названия. Ручной ввод ID Embeddings/Rerank и Advisor/Subagent убран из обычного интерфейса: модели назначаются из общего каталога OpenRouter.\n- «Памятка по использованию Umnik» превращена в расширенное локальное руководство для новичка и открывается несколькими удобными главами в новом чате без обращения к модели.\n- Версия: 1.10.0 / versionCode 100.\n"""
if marker not in changelog:
    raise SystemExit("CHANGELOG.md: Unreleased marker not found")
changelog = changelog.replace(marker, entry, 1)
write("CHANGELOG.md", changelog)

# ---------------------------------------------------------------------------
# Beginner guide: detailed, local, split into chapters.
# ---------------------------------------------------------------------------
guide = r'''package com.ayuemin.ymnik.help

object UmnikUsageGuide {
    val SECTIONS: List<String> = listOf(
        """
# Памятка по использованию Umnik

Эта памятка встроена в приложение. Она открылась **локально**: запрос к нейросети не отправлялся и деньги в OpenRouter не списывались.

Ниже не справочник для разработчика, а небольшой курс для первого знакомства. Можно читать по порядку или найти нужную главу.

## Старт за 5 минут

1. Откройте **Настройки → OpenRouter** и сохраните API-ключ.
2. Нажмите **Проверить подключение**.
3. Откройте **Настройки → Модели → Каталог и модели OpenRouter**.
4. Выберите недорогую текстовую модель и нажмите **Использовать в чате**.
5. Вернитесь в чат, создайте новый диалог и напишите обычный вопрос.

**Пример:** «Объясни простыми словами, чем Wi‑Fi отличается от мобильного интернета».

Если ответ пришёл, базовая настройка закончена. Остальные функции можно осваивать по мере необходимости.
        """.trimIndent(),
        """
# 1. Чаты и модели

## Обычный чат
Чат хранит вашу переписку и контекст. В одном чате удобно продолжать одну тему, а для другой задачи лучше создать новый.

**Как сделать:** нажмите **Новый чат** внизу боковой панели → выберите модель сверху → напишите сообщение.

## Быстрые модели
Быстрые модели нужны, чтобы не искать часто используемые варианты в большом каталоге. Добавьте модель в быстрые через меню модели в каталоге, после чего она появится в списке сверху чата.

**Когда полезно:** одна дешёвая модель для простых вопросов, одна сильнее для сложных текстов, одна Vision-модель для фотографий.

## Что значит «модель для чата»
Это модель, которая отвечает на обычные текстовые сообщения. Модели видео, изображений, Embeddings, Rerank и Batch имеют другие задачи и назначаются отдельно.

Если Umnik сообщает, что выбранная модель не подходит для обычного чата, откройте каталог и выберите модель категории **Текст**.
        """.trimIndent(),
        """
# 2. Файлы, PDF и фотографии

## Файл
Нажмите **+ → Файл**. Текстовые документы и PDF закрепляются за текущим чатом, поэтому после первого вопроса их обычно не нужно прикреплять повторно.

**Пример:** прикрепите договор → «Кратко перечисли мои обязанности» → затем «А какой срок уведомления указан в документе?».

## PDF
PDF передаётся OpenRouter штатным способом. Большой PDF может отправляться дольше обычного текста и расходовать больше трафика.

## Фото и Vision
Чтобы модель увидела фотографию, она должна поддерживать входные изображения.

**Сценарий:** выберите Vision-модель → **+ → Камера** → сделайте снимок → спросите «Что изображено и на что обратить внимание?».

Если текущая модель не умеет видеть изображения, камера для обычного чата должна быть недоступна. Смените модель, а не отправляйте фото вслепую.
        """.trimIndent(),
        """
# 3. Создание изображений, размышление и поиск в сети

## Создать изображение
Сначала в каталоге назначьте модель **для создания изображений**. Затем откройте **+ → Создать** и опишите картинку.

**Пример:** «Горизонтальная обложка 16:9, смартфон на светлом столе, спокойный технологичный стиль, без текста».

Готовое изображение должно появиться в исходном чате. Хранилище Umnik — дополнительное место для файлов, а не обязательный этап получения результата.

## Размышление
Переключатель **Размышление** полезен для задач, где нужно больше анализа: сравнение вариантов, логика, планирование, сложные вычисления. Он активен только у моделей, которые поддерживают reasoning.

## Поиск в сети
Включите **Поиск в сети**, когда ответ зависит от свежей информации: новости, актуальные версии программ, цены, недавние изменения. Для вечных вопросов поиск обычно не нужен.
        """.trimIndent(),
        """
# 4. Речь: в текст и озвучивание

## В текст
Эта функция превращает готовую аудиозапись в текст.

1. В каталоге назначьте модель **для распознавания речи**.
2. Нажмите **+ → В текст**.
3. Выберите аудиофайл.
4. Дождитесь расшифровки.

Результат добавляется в тот чат, из которого вы начали операцию.

## Озвучить
Эта функция делает аудиофайл из текста.

1. Назначьте модель **для озвучивания**.
2. Нажмите **+ → Озвучить**.
3. Введите текст.
4. Нажмите **Создать аудио**.

Готовую запись можно слушать прямо из сообщения чата. Скачивать её заранее не требуется.
        """.trimIndent(),
        """
# 5. Batch: несколько независимых задач одним пакетом

Batch удобен, когда нужно выполнить **несколько отдельных заданий**, а не вести один разговор.

Сначала в каталоге назначьте модель с вариантом `:batch` **для пакетных задач**. Затем откройте **+ → Пакет задач**.

На экране будут отдельные поля **Задача 1**, **Задача 2** и так далее. Нажимайте **+ Добавить задачу**. Никакие линии-разделители вводить вручную не нужно.

**Пример:**
- Задача 1: «Сделай краткое резюме этого материала».
- Задача 2: «Придумай пять заголовков».
- Задача 3: «Найди три слабых места текста».

Если задач много, вставьте их по одной на строку в поле быстрого импорта и нажмите **Разбить по строкам**.

После запуска можно уйти из этого окна. Результаты должны вернуться в **исходный чат**.
        """.trimIndent(),
        """
# 6. Видео и Shell

## Видео
Назначьте модель **для видео** → **+ → Видео** → напишите короткое описание. Генерация видео может занимать заметно больше времени и стоить дороже текста.

Можно перейти в другой чат или свернуть приложение. Готовый MP4 должен появиться в том чате, откуда была запущена генерация.

## Shell
Shell нужен, когда задаче полезны вычисления или временные файлы в контейнере OpenRouter.

**Примеры:** посчитать таблицу, обработать небольшой файл, создать CSV или другой рабочий файл.

Нажмите **+ → Shell**, опишите задачу и при необходимости добавьте файлы. Ответ и созданные файлы возвращаются в исходный чат.
        """.trimIndent(),
        """
# 7. Проекты и навыки

## Проект
Проект объединяет связанные чаты, общие файлы, навыки и мастер-инструкцию.

**Когда использовать:** вы долго работаете над одной темой — например, каналом, учебой или большим документом — и хотите, чтобы несколько чатов использовали общие правила.

**Сценарий:** создайте проект → добавьте мастер-инструкцию → при необходимости положите общие файлы → создавайте чаты внутри проекта.

Чаты проекта показываются внутри проекта и не должны смешиваться с общей историей обычных чатов.

## Навык
Навык — сохранённый набор инструкций для модели. Его можно включить глобально или подключить к проекту.

**Пример:** навык может требовать определённый стиль текста, структуру ответа или правила проверки результата.
        """.trimIndent(),
        """
# 8. RAG, Embeddings и Rerank простыми словами

RAG нужен, когда у вас есть текстовые материалы и модель должна сначала найти в них подходящие фрагменты, а уже затем отвечать.

- **Embeddings** превращают фрагменты текста в представление для смыслового поиска.
- **Rerank** может дополнительно пересортировать найденные фрагменты и поднять наиболее подходящие выше.
- **RAG** связывает поиск по вашим материалам с обычным ответом модели.

Модели Embeddings и Rerank выбираются в общем **Каталоге моделей OpenRouter**, а включение RAG и количество найденных фрагментов находятся во вкладке **Инструменты и документы**.

**Сценарий:** назначьте Embedding-модель → включите RAG → прикрепите текстовый материал → задайте вопрос, ответ на который есть в документе.

Для обычного небольшого PDF RAG включать необязательно: сначала попробуйте просто прикрепить файл к чату.
        """.trimIndent(),
        """
# 9. Хранилище, диагностика и что делать при ошибке

## Хранилище Umnik
Здесь лежат созданные и импортированные файлы. В нормальной работе готовый результат сначала появляется в чате. В хранилище заходят, когда нужно найти, сохранить или удалить файл позже.

## Диагностика и логи
Если функция ведёт себя странно:
1. Откройте **Настройки → Диагностика и логи**.
2. Очистите старый журнал.
3. Включите запись логов.
4. Повторите проблему.
5. Сохраните или поделитесь журналом.

API-ключи и содержимое личных сообщений в диагностический журнал не записываются.

## Частые причины проблем
- **Модель не видит фото:** выбрана модель без Vision.
- **Не запускается Batch:** не назначена `:batch`-модель.
- **Нет озвучивания или распознавания:** сначала назначьте Speech/Transcription-модель.
- **429 / ограничение провайдера:** попробуйте позже или выберите другую модель/провайдера внутри OpenRouter.
- **Оборвалось соединение:** нажмите повтор один раз; Umnik заменит неудачный запуск, а не должен создавать копии сообщения.

Эту памятку можно оставить в истории как обычный чат. Если какой-то пункт непонятен, напишите вопрос прямо здесь — следующий ответ уже будет запросом к выбранной модели OpenRouter.
        """.trimIndent()
    )

    val TEXT: String = SECTIONS.joinToString("\n\n---\n\n")
}
'''
write("app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt", guide)

# ---------------------------------------------------------------------------
# Sidebar: project chats are not duplicated in global history + rename.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt"
text = read(path)
text = text.replace("import androidx.compose.material.icons.outlined.DeleteSweep\n", "import androidx.compose.material.icons.outlined.DeleteSweep\nimport androidx.compose.material.icons.outlined.Edit\n", 1)
text = text.replace(
    "    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }\n    var clearConfirm by remember { mutableStateOf(false) }",
    "    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }\n    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }\n    var renameValue by remember { mutableStateOf(\"\") }\n    var clearConfirm by remember { mutableStateOf(false) }",
    1,
)
text = text.replace(
    "    val chats = state.chats\n        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })",
    "    val chats = state.chats\n        .filter { it.projectId == null }\n        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })",
    1,
)
old_call = """                                onDelete = { deleteTarget = chat }
                            )"""
new_call = """                                onDelete = { deleteTarget = chat },
                                onRename = {
                                    renameTarget = chat
                                    renameValue = chat.title
                                }
                            )"""
if old_call not in text:
    raise SystemExit("NavigationSidebar.kt: chat row call not found")
text = text.replace(old_call, new_call, 1)
insert_before = """    if (clearConfirm) {
        AlertDialog("""
rename_dialog = """    renameTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateChatProfile(
                            chat.id,
                            renameValue,
                            chat.assignedRole.orEmpty(),
                            chat.masterPrompt.orEmpty()
                        )
                        renameTarget = null
                    },
                    enabled = renameValue.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }

""" + insert_before
if insert_before not in text:
    raise SystemExit("NavigationSidebar.kt: clear dialog anchor not found")
text = text.replace(insert_before, rename_dialog, 1)
text = text.replace(
    """    onOpen: () -> Unit,
    onDelete: () -> Unit
) {""",
    """    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {""",
    1,
)
anchor = """            IconButton(
                onClick = onDelete,
                enabled = !state.isLoading,
                modifier = Modifier.size(36.dp)
            ) {"""
rename_button = """            IconButton(
                onClick = onRename,
                enabled = !state.isLoading,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = "Переименовать чат", modifier = Modifier.size(19.dp))
            }
            IconButton(
                onClick = onDelete,
                enabled = !state.isLoading,
                modifier = Modifier.size(36.dp)
            ) {"""
if anchor not in text:
    raise SystemExit("NavigationSidebar.kt: delete button anchor not found")
text = text.replace(anchor, rename_button, 1)
write(path, text)

# ---------------------------------------------------------------------------
# ChatViewModel: guide chapters, async refresh, retry replacement, text model safety.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)
text = text.replace("import com.ayuemin.ymnik.model.ModelInfo\n", "import com.ayuemin.ymnik.model.ModelInfo\nimport com.ayuemin.ymnik.model.ModelCategory\n", 1)

old_guide = """        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = UmnikUsageGuide.TEXT,
            providerName = "Umnik"
        )
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Памятка по Umnik",
            messages = listOf(message),"""
new_guide = """        val messages = UmnikUsageGuide.SECTIONS.map { section ->
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                text = section,
                providerName = "Umnik"
            )
        }
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Памятка по Umnik",
            messages = messages,"""
if old_guide not in text:
    raise SystemExit("ChatViewModel.kt: guide creation block not found")
text = text.replace(old_guide, new_guide, 1)
text = text.replace("            messages = listOf(message),\n            mode = ChatMode.TEXT,", "            messages = messages,\n            mode = ChatMode.TEXT,", 1)
text = text.replace("            messages = listOf(message),\n            mode = ChatMode.TEXT,", "            messages = messages,\n            mode = ChatMode.TEXT,", 1)
# State copy in openUsageGuide.
text = text.replace("            messages = listOf(message),\n            mode = ChatMode.TEXT,", "            messages = messages,\n            mode = ChatMode.TEXT,", 1)

async_anchor = """    fun deleteChat(id: String) {
"""
async_method = """    fun refreshAsyncResults() {
        val refreshed = chatsRepository.list()
        if (refreshed.isEmpty()) return
        val currentMessages = refreshed.firstOrNull { it.id == _state.value.currentChatId }?.messages
            ?: _state.value.messages
        _state.value = _state.value.copy(
            chats = refreshed,
            messages = currentMessages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
        DiagnosticLog.record(context, "ASYNC", "results refreshed; chat=${_state.value.currentChatId.take(8)}; messages=${currentMessages.size}")
    }

""" + async_anchor
if async_anchor not in text:
    raise SystemExit("ChatViewModel.kt: deleteChat anchor not found")
text = text.replace(async_anchor, async_method, 1)

old_retry = """    fun retryFailedMessage(messageId: String) {
        if (_state.value.isLoading) return
        val previous = _state.value.messages.firstOrNull { it.id == messageId && it.deliveryState == "failed" }
            ?: return
        if (previous.attachmentNames.isNotEmpty()) {
            _state.value = _state.value.copy(status = "Для повтора прикрепите файлы заново и отправьте запрос вручную")
            return
        }
        _state.value = _state.value.copy(mode = if (previous.imageGeneration) ChatMode.IMAGE else ChatMode.TEXT)
        if (previous.imageGeneration) sendImagePrompt(previous.text) else send(previous.text)
    }
"""
new_retry = """    fun retryFailedMessage(messageId: String) {
        if (_state.value.isLoading) return
        val previous = _state.value.messages.firstOrNull { it.id == messageId && it.deliveryState == "failed" }
            ?: return
        if (previous.attachmentNames.isNotEmpty()) {
            _state.value = _state.value.copy(status = "Для повтора прикрепите файлы заново и отправьте запрос вручную")
            return
        }
        val chatId = _state.value.currentChatId
        val cleanedMessages = _state.value.messages.filterNot { it.id == messageId }
        val cleanedChats = replaceChatMessages(_state.value.chats, chatId, cleanedMessages, null)
        chatsRepository.save(cleanedChats)
        _state.value = _state.value.copy(
            chats = cleanedChats,
            messages = cleanedMessages,
            mode = if (previous.imageGeneration) ChatMode.IMAGE else ChatMode.TEXT,
            status = null
        )
        DiagnosticLog.action(context, "retry_failed_message", "chat=${chatId.take(8)}; replaced=true")
        if (previous.imageGeneration) sendImagePrompt(previous.text) else send(previous.text)
    }
"""
if old_retry not in text:
    raise SystemExit("ChatViewModel.kt: retry function not found")
text = text.replace(old_retry, new_retry, 1)

# Remove the hidden 10-model cap that survived previous UI changes.
text = text.replace("            .distinct()\n            .take(10)\n    }.getOrDefault(emptyList())", "            .distinct()\n    }.getOrDefault(emptyList())", 1)

# Only actual text-output, non-batch models belong in the ordinary chat model list.
old_text_models = """        return if (profile.type == ProviderType.OPENROUTER) {
            api.models(key, effectiveTextBaseUrl(profile))
        } else {
            compatibleApi.models(key, effectiveTextBaseUrl(profile))
        }
"""
new_text_models = """        val models = if (profile.type == ProviderType.OPENROUTER) {
            api.models(key, effectiveTextBaseUrl(profile))
        } else {
            compatibleApi.models(key, effectiveTextBaseUrl(profile))
        }
        return models.filter { ModelCategory.TEXT in it.categories && !it.isBatch }
"""
if old_text_models not in text:
    raise SystemExit("ChatViewModel.kt: textModelsForProfile block not found")
text = text.replace(old_text_models, new_text_models, 1)

# Prune stale quick/current selections for OpenRouter too, not only NVIDIA.
text = text.replace(
    """                        if (profile.type == ProviderType.NVIDIA) {
                            val validIds = infos.map { it.id }.toSet()
                            pruneQuickTextModels(profile.id, validIds)
                            clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                        }
""",
    """                        run {
                            val validIds = infos.map { it.id }.toSet()
                            pruneQuickTextModels(profile.id, validIds)
                            clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                        }
""",
    1,
)
text = text.replace(
    """                if (profile.type == ProviderType.NVIDIA) {
                    val validIds = textInfos.map { it.id }.toSet()
                    pruneQuickTextModels(profile.id, validIds)
                    clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                    next = _state.value
                }
""",
    """                run {
                    val validIds = textInfos.map { it.id }.toSet()
                    pruneQuickTextModels(profile.id, validIds)
                    clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                    next = _state.value
                }
""",
    1,
)

# Block stale/specialized model IDs at the last possible point before a normal chat request.
send_anchor = """        val textModel = currentTextModelId()
        val imageModel = _state.value.imageModel
"""
send_guard = """        val textModel = currentTextModelId()
        if (mode == ChatMode.TEXT && textModel != "openrouter/auto") {
            val knownInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }
                ?: _state.value.modelCatalog.firstOrNull { it.id == textModel }
            val absentFromLoadedTextCatalog = _state.value.availableTextModels.isNotEmpty() &&
                _state.value.availableTextModels.none { it.id == textModel }
            if (absentFromLoadedTextCatalog || knownInfo?.isBatch == true || (knownInfo != null && ModelCategory.TEXT !in knownInfo.categories)) {
                val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                _state.value = _state.value.copy(
                    chats = failedChats,
                    messages = failedChats.firstOrNull { it.id == chatId }?.messages.orEmpty(),
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = "Эта модель предназначена не для обычного текстового чата. Выберите текстовую модель в каталоге OpenRouter."
                )
                refreshModels(ChatMode.TEXT)
                return
            }
        }
        val imageModel = _state.value.imageModel
"""
if send_anchor not in text:
    raise SystemExit("ChatViewModel.kt: send model anchor not found")
text = text.replace(send_anchor, send_guard, 1)

# Friendly error mapping.
old_error = """                val friendlyError = if (it is java.net.SocketTimeoutException) {
                    "Сервис не ответил вовремя (тайм-аут). При необходимости включите «Диагностика и логи» и повторите запрос."
                } else {
                    it.message ?: "Ошибка запроса"
                }
"""
new_error = """                val rawError = it.message.orEmpty()
                val friendlyError = when {
                    it is java.net.SocketTimeoutException ->
                        "Сервис не ответил вовремя. Повторите запрос один раз или выберите другую модель."
                    it is java.net.SocketException ->
                        "Соединение оборвалось во время ответа. Нажмите повтор — Umnik заменит неудачный запуск без создания копии сообщения."
                    rawError.contains("429") || rawError.contains("rate limit", ignoreCase = true) ->
                        "Провайдер этой модели временно ограничил запросы. Повторите позже или выберите другую модель в OpenRouter."
                    rawError.contains("video generation model", ignoreCase = true) ||
                        rawError.contains("cannot be used with the chat/completions endpoint", ignoreCase = true) ->
                        "Выбранная модель предназначена для видео, а не для обычного чата. Назначьте её для видео или выберите текстовую модель."
                    else -> rawError.ifBlank { "Ошибка запроса" }
                }
"""
if old_error not in text:
    raise SystemExit("ChatViewModel.kt: friendly error block not found")
text = text.replace(old_error, new_error, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Hub root: async completion refreshes chat state without switchChat/model reload.
# Tools: plain Russian UI. Batch: obvious task builder instead of separators.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = read(path)
old_async = """    LaunchedEffect(asyncSequence) {
        if (asyncSequence <= 0L) return@LaunchedEffect
        repeat(120) {
            val state = viewModel.state.value
            if (!state.isLoading && state.pendingAttachments.isEmpty()) {
                viewModel.switchChat(state.currentChatId)
                controller.refreshJobs()
                return@LaunchedEffect
            }
            delay(1_000L)
        }
    }
"""
new_async = """    LaunchedEffect(asyncSequence) {
        if (asyncSequence <= 0L) return@LaunchedEffect
        viewModel.refreshAsyncResults()
        controller.refreshJobs()
    }
"""
if old_async not in text:
    raise SystemExit("OpenRouterHub.kt: async refresh block not found")
text = text.replace(old_async, new_async, 1)
text = text.replace('item { HubPageChip("Tools + RAG", HubPage.TOOLS, page, onPage) }', 'item { HubPageChip("Инструменты и документы", HubPage.TOOLS, page, onPage) }', 1)
text = text.replace('if (settingsMode) "Каталог, маршрутизация, Tools и RAG" else "Результат возвращается в текущий чат"', 'if (settingsMode) "Каталог, маршрутизация и работа с документами" else "Результат возвращается в текущий чат"', 1)

new_tools = r'''@Composable
private fun ToolsPage(tools: ServerToolSettings, rag: RagSettings, controller: OpenRouterHubController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Инструменты обычного чата", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Эти возможности OpenRouter модель может использовать во время обычного разговора. Включайте только то, что действительно нужно задаче.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Text("Поиск в интернете", fontWeight = FontWeight.SemiBold)
            Text("Авто — модель решает сама, «Всегда» — поиск разрешён для каждого запроса.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchMode.entries) { mode ->
                    FilterChip(
                        selected = tools.webSearch == mode,
                        onClick = { controller.updateTools(tools.copy(webSearch = mode)) },
                        label = { Text(webModeLabel(mode)) }
                    )
                }
            }
        }
        item {
            Text("Сервис интернет-поиска", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(WebSearchEngine.entries) { engine ->
                    FilterChip(
                        selected = tools.webSearchEngine == engine,
                        onClick = { controller.updateTools(tools.copy(webSearchEngine = engine)) },
                        label = { Text(engine.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }
        item { ToggleRow("Открывать найденные веб-страницы", tools.webFetch) { controller.updateTools(tools.copy(webFetch = it)) } }
        item { ToggleRow("Использовать текущие дату и время", tools.datetime) { controller.updateTools(tools.copy(datetime = it)) } }
        item { ToggleRow("Разрешить модели создавать изображения как инструмент", tools.imageGeneration) { controller.updateTools(tools.copy(imageGeneration = it)) } }
        item { ToggleRow("Fusion — объединять работу нескольких инструментов", tools.fusion) { controller.updateTools(tools.copy(fusion = it)) } }
        item { ToggleRow("Разрешить Shell прямо в обычном чате", tools.shell) { controller.updateTools(tools.copy(shell = it)) } }

        item {
            HorizontalDivider()
            Text("Поиск по своим документам (RAG)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(
                "RAG сначала находит подходящие фрагменты ваших текстовых файлов, затем передаёт их основной модели. Модели Embeddings и Rerank выбираются во вкладке «Модели» общего каталога.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { ToggleRow("Включить поиск по документам", rag.enabled) { controller.updateRag(rag.copy(enabled = it)) } }
        item {
            Text("Модель смыслового поиска", fontWeight = FontWeight.SemiBold)
            Text(rag.embeddingModel.ifBlank { "Не выбрана — назначьте Embeddings-модель во вкладке «Модели»" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Модель уточнения результатов", fontWeight = FontWeight.SemiBold)
            Text(rag.rerankModel.ifBlank { "Не выбрана — Rerank необязателен" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Сколько подходящих фрагментов передавать модели: ${rag.topK}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = rag.topK.toFloat(),
                onValueChange = { controller.updateRag(rag.copy(topK = it.toInt().coerceIn(1, 20))) },
                valueRange = 1f..20f,
                steps = 18
            )
            Text(
                "Для небольшого PDF сначала попробуйте обычное прикрепление файла. RAG особенно полезен для набора больших текстовых материалов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}'''
text, n = re.subn(r'@Composable\nprivate fun ToolsPage\(.*?\n}\n\n@Composable\nprivate fun JobsPage', new_tools + '\n\n@Composable\nprivate fun JobsPage', text, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"OpenRouterHub.kt: ToolsPage replacement count={n}")

new_jobs = r'''@Composable
private fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    val tasks = remember { mutableStateListOf("") }
    var bulkInput by remember { mutableStateOf("") }
    val readyCount = tasks.count { it.isNotBlank() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Пакет из нескольких независимых заданий", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Batch удобен, когда задания не зависят друг от друга. Результаты вернутся в тот чат, из которого вы запустили пакет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            CategoryModelPicker(
                title = "Модель для пакетных задач",
                current = state.media.batchModel,
                models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },
                onSelect = { controller.assignModel(it, ModelCategory.TEXT) }
            )
        }

        item {
            Text("Задания", fontWeight = FontWeight.Bold)
            Text("Каждое поле — отдельный запрос. Никакие разделительные линии вводить не нужно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEachIndexed { index, value ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { tasks[index] = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Задача ${index + 1}") },
                                placeholder = { Text(if (index == 0) "Например: сделай краткое резюме текста" else "Введите независимое задание") },
                                minLines = 2,
                                maxLines = 7
                            )
                            if (tasks.size > 1) {
                                TextButton(onClick = { tasks.removeAt(index) }, modifier = Modifier.align(Alignment.End)) { Text("Удалить задачу") }
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = { tasks.add("") }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить задачу") }
            }
        }

        item {
            Text("Быстро добавить списком", fontWeight = FontWeight.SemiBold)
            Text("Если у вас уже есть список коротких задач, вставьте по одной задаче на строку.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = bulkInput,
                onValueChange = { bulkInput = it },
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                label = { Text("Список задач") },
                placeholder = { Text("Задача 1\nЗадача 2\nЗадача 3") },
                minLines = 3,
                maxLines = 8
            )
            FilledTonalButton(
                onClick = {
                    val imported = bulkInput.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                    if (imported.isNotEmpty()) {
                        if (tasks.size == 1 && tasks.first().isBlank()) tasks.clear()
                        tasks.addAll(imported)
                        bulkInput = ""
                    }
                },
                enabled = bulkInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("Разбить по строкам") }
        }

        item {
            Button(
                onClick = {
                    val raw = tasks.map(String::trim).filter(String::isNotBlank).joinToString("\n---\n")
                    controller.submitBatch(raw)
                },
                enabled = state.media.batchModel.endsWith(":batch", true) && readyCount > 0 && !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Запустить пакет · $readyCount") }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("История Batch", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = controller::refreshJobs) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Обновить") }
            }
        }
        if (state.batches.isEmpty()) item { Text("Пока нет Batch-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.batches, key = { it.id }) { job ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(job.title, fontWeight = FontWeight.SemiBold)
                    Text("${batchLabel(job.status)} · ${job.completedItems}/${job.totalItems}", style = MaterialTheme.typography.bodySmall)
                    Text(job.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { HorizontalDivider(); Text("Видео-задания", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
        if (state.videos.isEmpty()) item { Text("Пока нет фоновых видео", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.videos, key = { it.id }) { job ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(job.modelId, fontWeight = FontWeight.SemiBold)
                    Text(videoLabel(job.status), style = MaterialTheme.typography.bodySmall)
                    Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    job.costUsd?.let { Text("Стоимость: ${formatUsdSmall(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}'''
text, n = re.subn(r'@Composable\nprivate fun JobsPage\(.*?\n}\n\n@Composable\nprivate fun MediaPage', new_jobs + '\n\n@Composable\nprivate fun MediaPage', text, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"OpenRouterHub.kt: JobsPage replacement count={n}")
write(path, text)

# ---------------------------------------------------------------------------
# Controller/worker: one persistent worker, no REPLACE storm on every refresh.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
text = read(path)
count = text.count("OpenRouterBackgroundWorker.schedule(context, replace = true)")
if count < 1:
    raise SystemExit("OpenRouterHubController.kt: background schedule anchor not found")
text = text.replace("OpenRouterBackgroundWorker.schedule(context, replace = true)", "OpenRouterBackgroundWorker.schedule(context, replace = false)")
write(path, text)

path = "app/src/main/java/com/ayuemin/ymnik/OpenRouterBackgroundWorker.kt"
text = read(path)
count = text.count("                AsyncJobEvents.notifyChanged()")
if count != 2:
    raise SystemExit(f"OpenRouterBackgroundWorker.kt: expected 2 notify calls, got {count}")
text = text.replace("                AsyncJobEvents.notifyChanged()", "                if (current.status.terminal) AsyncJobEvents.notifyChanged()")
write(path, text)

# ---------------------------------------------------------------------------
# Old secondary chats dialog: project chats also stay out of global history.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = read(path)
old = "    val chats = state.chats.sortedByDescending { it.updatedAt }"
new = "    val chats = state.chats.filter { it.projectId == null }.sortedByDescending { it.updatedAt }"
if old not in text:
    raise SystemExit("YmnikApp.kt: ChatsDialog list anchor not found")
text = text.replace(old, new, 1)
write(path, text)

print("Umnik v1.10.0 source migration applied successfully")
