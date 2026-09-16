from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Anchor not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# ChatViewModel: nullable per-chat override + inherited global default.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun chatContextMode(chatId: String): ChatContextMode = chatMemory.mode(chatId)\n\n    fun setChatContextMode(chatId: String, mode: ChatContextMode) {\n        if (_state.value.isLoading || _state.value.requestActive) return\n        if (_state.value.chats.none { it.id == chatId }) return\n        chatMemory.saveMode(chatId, mode)\n        val label = when (mode) {\n            ChatContextMode.AUTO -> "Автоматический"\n            ChatContextMode.FULL -> "Всегда полный"\n            ChatContextMode.ECONOMY -> "Экономный"\n        }\n        _state.value = _state.value.copy(status = "Контекст чата: $label")\n    }\n''',
    '''    fun chatContextModeOverride(chatId: String): ChatContextMode? = chatMemory.modeOverride(chatId)\n\n    fun chatContextMode(chatId: String): ChatContextMode = chatMemory.mode(chatId)\n\n    fun setChatContextMode(chatId: String, mode: ChatContextMode?) {\n        if (_state.value.isLoading || _state.value.requestActive) return\n        if (_state.value.chats.none { it.id == chatId }) return\n        chatMemory.saveMode(chatId, mode)\n        val effective = chatMemory.mode(chatId)\n        val effectiveLabel = when (effective) {\n            ChatContextMode.AUTO -> "Автоматический"\n            ChatContextMode.FULL -> "Всегда полный"\n            ChatContextMode.ECONOMY -> "Экономный"\n        }\n        val label = if (mode == null) "По умолчанию ($effectiveLabel)" else effectiveLabel\n        _state.value = _state.value.copy(status = "Контекст чата: $label")\n    }\n'''
)

# Version bump.
replace_once("app/build.gradle.kts", "// Umnik v1.16.0", "// Umnik v1.16.1")
replace_once("app/build.gradle.kts", "versionCode = 119", "versionCode = 120")
replace_once("app/build.gradle.kts", 'versionName = "1.16.0"', 'versionName = "1.16.1"')

# Update the in-app memory guide without touching other topics.
guide = Path("app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt")
text = guide.read_text(encoding="utf-8")
start = text.index('        Topic(\n            "Память и контекст длинного чата",')
end = text.index('        Topic(\n            "Файлы, PDF и фотографии",', start)
new_topic = '''        Topic(\n            "Память и контекст длинного чата",\n            """\n# Память и контекст длинного чата\n\n**Что это:** способ не отправлять дорогой текстовой модели всю многомесячную переписку при каждом новом сообщении.\n\n**Где выбрать режим для чата:** боковая панель → **⋮ → Настройки чата → Контекст чата**. У проектного чата тот же блок находится в его настройках.\n\nУ каждого чата есть четыре варианта выбора:\n\n- **По умолчанию** — чат наследует общий режим из **Настройки → Память и контекст**. Если общий режим поменять, такой чат переключится вместе с ним.\n- **Автоматический** — сначала передаётся полная история, а после заданного порога Umnik оставляет свежие сообщения целиком, добавляет короткую карточку состояния и находит через Embeddings только подходящие старые фрагменты.\n- **Всегда полный** — использовать максимум исходной истории, который помещается в окно модели. Служебная долговременная память при формировании запроса не используется.\n- **Экономный** — перейти на гибридную память раньше и держать меньший свежий хвост.\n\n**Общий режим по умолчанию:** в **Настройки → Память и контекст** можно выбрать Автоматический, Всегда полный или Экономный. Индивидуально переопределённые чаты от этого не меняются.\n\n**Как устроена память:** старая часть диалога остаётся исходной перепиской на телефоне. Umnik создаёт неизменяемые checkpoint-конспекты отдельных участков, отдельную компактную карточку текущего состояния и embeddings исходных старых фрагментов. Конспект помогает понять, что было решено, а embeddings позволяют вернуть точный старый эпизод.\n\n**Адаптивные фрагменты:** Umnik читает из каталога OpenRouter размер контекста выбранной Embedding-модели и не отправляет ей фрагмент больше безопасного рабочего предела. Для моделей с маленьким окном размер автоматически уменьшается. Соседние фрагменты немного перекрываются, а при совпадении можно добавить соседний контекст, поэтому мысль на границе фрагментов не должна пропадать.\n\n**Пример:** если Embedding-модель допускает только 512 токенов, Umnik при желаемом размере 1200 автоматически уменьшит рабочий фрагмент примерно до 384 токенов и сохранит перекрытие. При смене на модель с большим окном искусственный предел 384 не остаётся.\n\n**Пример поиска:** после сотни сообщений спросите «Как мы тогда решили назвать кнопку?». Вместо всей переписки Umnik может отправить модели свежий разговор, карточку состояния и несколько старых фрагментов именно про название кнопки вместе с соседним контекстом.\n\n**Общие параметры:** **Настройки → Память и контекст**. Там выбираются Embedding-модель, модель конспекта, режим по умолчанию, пороги Автоматического и Экономного режимов, размер свежего хвоста и дополнительные параметры поиска.\n\n**Удаление:** память хранится отдельно от обычных файлов и базы знаний. **Очистить память** не удаляет переписку. При удалении самого чата его checkpoint-конспекты, embeddings и карточка состояния удаляются автоматически.\n            """.trimIndent()\n        ),\n'''
guide.write_text(text[:start] + new_topic + text[end:], encoding="utf-8")

# Changelog entry.
changelog = Path("CHANGELOG.md")
old = changelog.read_text(encoding="utf-8")
entry = '''## v1.16.1\n\n- Добавлен общий режим контекста по умолчанию: Автоматический, Всегда полный или Экономный. Чаты могут наследовать его либо иметь собственное переопределение.\n- Фрагменты истории для Embeddings теперь адаптируются к окну выбранной модели из каталога OpenRouter и используют безопасный запас по токенам.\n- Добавлено перекрытие соседних фрагментов и подмешивание соседнего контекста к найденным совпадениям, чтобы смысл не терялся на границах.\n- Поисковый запрос к Embedding-модели тоже автоматически ограничивается её рабочим окном.\n- Для `liquid/lfm-2.5-embedding-350m(:free)` добавлена безопасная миграция с лимитом 512 токенов даже до повторного сохранения настроек.\n- Карточка «Память и контекст» приведена к общей цветовой стилистике настроек.\n- В расширенных настройках памяти добавлены перекрытие фрагментов и соседний контекст; памятка обновлена.\n- Версия: 1.16.1 / versionCode 120.\n\n'''
changelog.write_text(entry + old, encoding="utf-8")
