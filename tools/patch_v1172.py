from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str, minimum: int = 1) -> int:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{path}: expected >= {minimum} matches, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new))
    return count


# 1) WorkManager: urgent recovery replaces the delayed safety job.
path = "app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt"
replace_once(
    path,
    "import androidx.work.OneTimeWorkRequestBuilder\n",
    "import androidx.work.OneTimeWorkRequestBuilder\nimport androidx.work.OutOfQuotaPolicy\n",
)
replace_once(
    path,
    '''        fun schedule(context: Context, requestId: String, initialDelaySeconds: Long = 45L) {
            val request = OneTimeWorkRequestBuilder<OpenRouterRecoveryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(requestId), ExistingWorkPolicy.KEEP, request
            )
        }
''',
    '''        fun schedule(
            context: Context,
            requestId: String,
            initialDelaySeconds: Long = 45L,
            replaceExisting: Boolean = false,
            expedited: Boolean = false
        ) {
            val builder = OneTimeWorkRequestBuilder<OpenRouterRecoveryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            val delaySeconds = initialDelaySeconds.coerceAtLeast(0L)
            if (expedited && delaySeconds == 0L) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else if (delaySeconds > 0L) {
                builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            }
            val request = builder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(requestId),
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
''',
)

# 2) Request manager: process restore / foreground handoff are urgent.
path = "app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt"
replace_all(
    path,
    "OpenRouterRecoveryWorker.schedule(app, saved.requestId, initialDelaySeconds = 0L)",
    "OpenRouterRecoveryWorker.schedule(app, saved.requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)",
)
replace_all(
    path,
    "OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L)",
    "OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)",
)

# 3) OpenRouter transport: no active HTTP/2 ping; urgent handoff replaces backup work.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(path, "        .pingInterval(30, TimeUnit.SECONDS)\n", "")
replace_all(
    path,
    "OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L)",
    "OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)",
)
replace_all(
    path,
    "OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId)\n",
    "OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, replaceExisting = true)\n",
)
replace_once(
    path,
    "            messages.add(responseMessage.deepCopy())\n            toolCalls.forEach { callElement ->\n",
    "            phaseCallback(\"Выполняю инструменты…\")\n            messages.add(responseMessage.deepCopy())\n            toolCalls.forEach { callElement ->\n",
)
replace_once(
    path,
    '''                    val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val data = root.getAsJsonObject("data") ?: return@use null
                    if (runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true) return@use false
                    val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    if (finish.isNotBlank()) true else null
''',
    '''                    val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val data = root.getAsJsonObject("data") ?: return@use null
                    val cancelled = runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true
                    val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    DiagnosticLog.record(
                        context,
                        "REQUEST_RECOVERY",
                        "generation poll id=${generationId.take(12)} http=${response.code} cancelled=$cancelled finish=${finish.ifBlank { "pending" }} poll=${attempt + 1}"
                    )
                    if (cancelled) return@use false
                    if (finish.isNotBlank()) true else null
''',
)

# 4) Chat phases: expose context preparation before network phases begin.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    path,
    'busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",',
    'busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Готовлю запрос…",',
)
replace_once(
    path,
    "                        network.call(profileId = profile.id, recoverable = true) { requestApi ->\n                            val preparedContext = chatMemoryManager.prepare(\n",
    "                        network.call(profileId = profile.id, recoverable = true) { requestApi ->\n                            network.updatePhase(\"Готовлю контекст…\")\n                            val preparedContext = chatMemoryManager.prepare(\n",
)

# 5) Chat UI: show the live phase beside the pending message, ChatGPT-style.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(
    path,
    "                MessageCard(\n                    message = message,\n                    tts = tts,\n",
    '''                MessageCard(
                    message = message,
                    pendingLabel = if (message.deliveryState == "pending") {
                        if (requestActiveHere && state.messages.lastOrNull { it.deliveryState == "pending" }?.id == message.id) {
                            state.busyLabel ?: "Модель работает…"
                        } else {
                            "Восстанавливаю ответ в фоне…"
                        }
                    } else null,
                    tts = tts,
''',
)
replace_once(
    path,
    "private fun MessageCard(\n    message: ChatMessage,\n    tts: TtsController,\n",
    "private fun MessageCard(\n    message: ChatMessage,\n    pendingLabel: String? = null,\n    tts: TtsController,\n",
)
replace_once(
    path,
    '''                    if (message.deliveryState == "failed" || message.deliveryState == "pending") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (message.deliveryState == "pending") "Ожидается ответ…" else "Ответ не получен. Можно повторить вручную.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
''',
    '''                    if (message.deliveryState == "pending") {
                        Spacer(Modifier.height(7.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                pendingLabel ?: "Модель работает…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (message.deliveryState == "failed") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ответ не получен. Можно повторить вручную.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
''',
)

# 6) Version + changelog.
path = "app/build.gradle.kts"
replace_once(path, "// Umnik v1.17.1", "// Umnik v1.17.2")
replace_once(path, "        versionCode = 124", "        versionCode = 125")
replace_once(path, '        versionName = "1.17.1"', '        versionName = "1.17.2"')

p = Path("CHANGELOG.md")
changelog = p.read_text()
entry = '''## v1.17.2

- Исправлена передача оборванного запроса в WorkManager: срочное восстановление теперь заменяет заранее поставленную двухминутную страховку вместо того, чтобы проигрывать ей из-за `ExistingWorkPolicy.KEEP`.
- Срочный recovery запускается как expedited work с безопасным fallback в обычную очередь, поэтому после реального обрыва соединения восстановление начинается сразу при доступной сети.
- Убран активный HTTP/2 ping OkHttp: на реальном Android-тесте обрыв происходил в том числе внутри `Http2Writer.ping`; сам HTTP/2 пока сохранён.
- В чате добавлены живые этапы работы рядом с ожидающим сообщением: подготовка запроса, подготовка контекста, ответ модели, получение результата, работа инструментов и восстановление связи.
- После передачи запроса в фоновый recovery вместо безликой надписи «Ожидается ответ…» показывается «Восстанавливаю ответ в фоне…».
- Диагностика recovery теперь фиксирует состояние серверной генерации (`pending`, `finish_reason`, `cancelled`) без записи содержимого ответа.
- Версия: 1.17.2 / versionCode 125.

'''
if not changelog.startswith("## v1.17.1"):
    raise SystemExit("Unexpected CHANGELOG header")
p.write_text(entry + changelog)
