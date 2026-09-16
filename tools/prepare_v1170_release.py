from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, got {count}")
    return text.replace(old, new, 1)


# Preserve the real request start time when returning to an already-running chat.
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''    LaunchedEffect(requestActiveHere) {
        if (!requestActiveHere) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
''',
    '''    LaunchedEffect(requestActiveHere, state.currentChatId) {
        if (!requestActiveHere) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = vm.activeRequestStartedAt(state.currentChatId) ?: System.currentTimeMillis()
''',
    "request timer origin",
)
path.write_text(text, encoding="utf-8")


# Android version.
path = Path("app/build.gradle.kts")
text = path.read_text(encoding="utf-8")
text = replace_once(text, "// Umnik v1.16.3", "// Umnik v1.17.0", "version comment")
text = replace_once(text, "versionCode = 122", "versionCode = 123", "version code")
text = replace_once(text, 'versionName = "1.16.3"', 'versionName = "1.17.0"', "version name")
path.write_text(text, encoding="utf-8")


# Changelog release section.
path = Path("CHANGELOG.md")
text = path.read_text(encoding="utf-8")
release = '''## v1.17.0

- Добавлена настоящая параллельная работа между чатами: запрос в одном чате больше не мешает сразу отправить другой запрос во втором, третьем и следующих чатах.
- Внутри одного чата по-прежнему выполняется не более одного верхнеуровневого запроса, поэтому ответы, отмена и история не смешиваются.
- Каждый активный запрос получил собственную сетевую сессию OpenRouter, отмену и привязку результата к исходному чату; Batch также привязан к конкретному requestId.
- Кнопка остановки, таймер и индикатор работы относятся к открытому чату. В боковой панели работающие чаты помечаются «Отвечает», а таймер сохраняет реальное время после переключения между чатами.
- Foreground-служба Android теперь обслуживает несколько одновременных запросов и остаётся активной, пока не завершится последний; уведомление показывает количество текущих работ.
- Независимые исполнители Оркестратора запускаются параллельно без искусственного деления по три. Шаги, которым нужен предыдущий результат, остаются последовательными.
- Запись истории исполнителей сделана атомарной, чтобы параллельно завершившиеся чаты не перетирали результаты друг друга. Оркестратор не запускает работу в обычном чате, если там уже идёт ручной запрос.
- Ограничение числа одновременных вызовов OpenRouter по умолчанию отключено (`0 = без ограничений`); архитектура не вводит собственного фиксированного потолка.
- Многозадачная ветка v1.17.0 работает через OpenRouter; отдельная поддержка NVIDIA в этой архитектуре не используется.
- Версия: 1.17.0 / versionCode 123.

'''
text = replace_once(text, "## v1.16.3\n", release + "## v1.16.3\n", "v1.17.0 changelog insertion")
path.write_text(text, encoding="utf-8")


# README user-facing behavior.
path = Path("README.md")
text = path.read_text(encoding="utf-8")
text = replace_once(text, "Текущая версия: **1.15.3**.", "Текущая версия: **1.17.0**.", "README version")
text = replace_once(
    text,
    "- последовательно запустить несколько чатов;",
    "- запускать независимых исполнителей параллельно, а зависимые шаги последовательно;",
    "README Orchestrator parallel bullet",
)
text = replace_once(
    text,
    "- сохранить обычную линейную и контролируемую человеком логику выполнения.",
    "- сохранять зависимости между шагами и контролируемую человеком логику выполнения.",
    "README Orchestrator control wording",
)
path.write_text(text, encoding="utf-8")


# Orchestrator documentation.
path = Path("docs/ORCHESTRATOR.md")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "Пользователь формулирует задачу обычным разговорным языком, а Оркестратор превращает её в ограниченный линейный план действий внутри проекта.",
    "Пользователь формулирует задачу обычным разговорным языком, а Оркестратор превращает её в ограниченный план действий внутри проекта.",
    "Orchestrator overview wording",
)
text = replace_once(
    text,
    "Вместо ручного перехода между чатами Umnik выполняет нужные целевые чаты в фоне. Каждый исполнитель использует собственный рабочий профиль: модель, reasoning, веб-поиск, OpenRouter tools, навыки, роль, инструкции, постоянные файлы, базу знаний и историю.",
    "Вместо ручного перехода между чатами Umnik выполняет нужные целевые чаты в фоне. Независимые исполнители могут работать параллельно; если шаг использует результат предыдущего, зависимая часть остаётся последовательной. Каждый исполнитель использует собственный рабочий профиль: модель, reasoning, веб-поиск, OpenRouter tools, навыки, роль, инструкции, постоянные файлы, базу знаний и историю.",
    "Orchestrator parallel explanation",
)
text = replace_once(
    text,
    "- запускать сохранённый ручной сценарий Оркестратора.",
    "- запускать сохранённый ручной сценарий Оркестратора;\n- параллельно запускать независимых исполнителей без фиксированного размера группы.",
    "Orchestrator capability bullet",
)
path.write_text(text, encoding="utf-8")


# In-app guide.
path = Path("app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt")
text = path.read_text(encoding="utf-8")
text = replace_once(
    text,
    "Во время активного запроса Umnik использует системную фоновую службу Android и показывает постоянное уведомление. В чате и уведомлении отображается текущий этап: запрос отправлен, модель формирует ответ или Umnik восстанавливает соединение.",
    "Во время активного запроса Umnik использует системную фоновую службу Android и показывает постоянное уведомление. Можно перейти в другой чат и сразу отправить ещё один запрос: разные чаты работают независимо, а внутри одного чата одновременно выполняется не более одного запроса. В боковой панели работающий чат помечается «Отвечает». В чате и уведомлении отображается текущий этап: запрос отправлен, модель формирует ответ или Umnik восстанавливает соединение.",
    "usage guide multi-chat behavior",
)
path.write_text(text, encoding="utf-8")

print("v1.17.0 release metadata and timer prepared")
