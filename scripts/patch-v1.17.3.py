from pathlib import Path
import re


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# OpenRouterClient: HTTP/1.1 for long-lived mobile chat calls and terminal generation states.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
s = p.read_text()
s = replace_once(s, "import okhttp3.OkHttpClient\n", "import okhttp3.OkHttpClient\nimport okhttp3.Protocol\n", "OpenRouterClient Protocol import")
s = replace_once(
    s,
    "        .retryOnConnectionFailure(true)\n        .connectTimeout(30, TimeUnit.SECONDS)",
    "        .retryOnConnectionFailure(true)\n        // Direct Umnik requests do not multiplex on one client. HTTP/1.1 avoids the\n        // Android background HTTP/2 socket abort reproduced on real devices.\n        .protocols(listOf(Protocol.HTTP_1_1))\n        .connectTimeout(30, TimeUnit.SECONDS)",
    "OpenRouterClient protocol",
)
s = replace_once(
    s,
    "    @Volatile private var activeCall: Call? = null\n",
    "    @Volatile private var activeCall: Call? = null\n\n    private enum class GenerationState { PENDING, COMPLETED, CANCELLED, TERMINAL_FAILURE }\n",
    "GenerationState enum",
)
old = '''                val ready = if (cacheStatus.equals("HIT", ignoreCase = true)) {
                    true
                } else {
                    awaitGenerationFinished(apiKey, baseUrl, generationId.orEmpty(), deadline)
                }
                if (!ready) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation still pending after live recovery window; handing off id=${generationId ?: "none"}")
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Ответ ещё формируется · продолжу восстановление в фоне…")
                        // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }
                recoveryAttempt += 1
'''
new = '''                val generationState = if (cacheStatus.equals("HIT", ignoreCase = true)) {
                    GenerationState.COMPLETED
                } else {
                    awaitGenerationState(apiKey, baseUrl, generationId.orEmpty(), deadline)
                }
                when (generationState) {
                    GenerationState.CANCELLED -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation cancelled; stopping recovery id=${generationId ?: "none"}")
                        phaseCallback("OpenRouter отменил генерацию · запрос остановлен")
                        clearRecovery(recoveryRecord)
                        throw IOException("OpenRouter отменил генерацию после обрыва связи. Повторите запрос.")
                    }
                    GenerationState.TERMINAL_FAILURE -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation cannot be recovered; stopping id=${generationId ?: "none"}")
                        clearRecovery(recoveryRecord)
                        throw IOException("Не удалось безопасно восстановить генерацию OpenRouter. Повторите запрос.")
                    }
                    GenerationState.PENDING -> {
                        DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation still pending after live recovery window; handing off id=${generationId ?: "none"}")
                        if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                            phaseCallback("Ответ ещё формируется · продолжу восстановление в фоне…")
                            // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                            throw error
                        }
                        clearRecovery(recoveryRecord)
                        throw error
                    }
                    GenerationState.COMPLETED -> Unit
                }
                recoveryAttempt += 1
'''
s = replace_once(s, old, new, "requestCompletion generation state")
pattern = re.compile(r'''    private suspend fun awaitGenerationFinished\(.*?\n    private fun message\(role: String, text: String\)''', re.S)
replacement = '''    private suspend fun awaitGenerationState(
        apiKey: String,
        baseUrl: String,
        generationId: String,
        deadlineElapsed: Long = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
    ): GenerationState {
        if (generationId.isBlank()) return GenerationState.TERMINAL_FAILURE
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            if (!awaitNetworkAvailable(deadlineElapsed)) return GenerationState.PENDING
            if (attempt > 0) delay(minOf(10_000L, 1_500L + attempt * 1_000L))
            val request = Request.Builder()
                .url(endpoint(baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val state = try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> GenerationState.PENDING
                        response.code == 401 || response.code == 403 -> GenerationState.TERMINAL_FAILURE
                        !response.isSuccessful -> GenerationState.PENDING
                        else -> {
                            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                            val data = root.getAsJsonObject("data") ?: return@use GenerationState.PENDING
                            val cancelled = runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true
                            val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                            DiagnosticLog.record(
                                context,
                                "REQUEST_RECOVERY",
                                "generation poll id=${generationId.take(12)} http=${response.code} cancelled=$cancelled finish=${finish.ifBlank { "pending" }} poll=${attempt + 1}"
                            )
                            when {
                                cancelled -> GenerationState.CANCELLED
                                finish.isNotBlank() -> GenerationState.COMPLETED
                                else -> GenerationState.PENDING
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                GenerationState.PENDING
            }
            if (state != GenerationState.PENDING) {
                if (state == GenerationState.COMPLETED) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation completed; id=$generationId; poll=${attempt + 1}")
                }
                return state
            }
            attempt += 1
        }
        return GenerationState.PENDING
    }

    private fun message(role: String, text: String)'''
s2, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit("missing patch anchor: awaitGenerationFinished")
p.write_text(s2)

# Recovery worker: terminal cancellation/auth states must stop retrying, not spin for minutes.
p = Path("app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt")
s = p.read_text()
s = replace_once(s, "import okhttp3.OkHttpClient\n", "import okhttp3.OkHttpClient\nimport okhttp3.Protocol\n", "Recovery Protocol import")
s = replace_once(
    s,
    "class OpenRouterRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {\n",
    "class OpenRouterRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {\n    private enum class GenerationState { PENDING, COMPLETED, CANCELLED, TERMINAL_FAILURE }\n    private class TerminalRecoveryException(message: String) : IOException(message)\n\n",
    "Recovery state types",
)
s = replace_once(
    s,
    '''                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery attempt failed request=${requestId.take(8)}", error)
                    Result.retry()
''',
    '''                    if (error is TerminalRecoveryException) {
                        failPending(record, error.message ?: "OpenRouter завершил генерацию без доступного ответа. Повторите запрос вручную.")
                        store.remove(requestId)
                        return@fold Result.success()
                    }
                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery attempt failed request=${requestId.take(8)}", error)
                    Result.retry()
''',
    "terminal recovery onFailure",
)
s = replace_once(
    s,
    "            .retryOnConnectionFailure(true)\n            .connectTimeout(30, TimeUnit.SECONDS)",
    "            .retryOnConnectionFailure(true)\n            .protocols(listOf(Protocol.HTTP_1_1))\n            .connectTimeout(30, TimeUnit.SECONDS)",
    "Recovery protocol",
)
pattern = re.compile(r'''        val deadline = SystemClock\.elapsedRealtime\(\) \+ RECOVERY_WINDOW_MS\n        var poll = 0\n        var ready = false\n\n        while \(SystemClock\.elapsedRealtime\(\) < deadline && !ready\) \{.*?        if \(!ready\) throw IOException\("Generation is not complete yet"\)''', re.S)
replacement = '''        val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
        var poll = 0
        var ready = false

        while (SystemClock.elapsedRealtime() < deadline && !ready) {
            if (!networkAvailable()) {
                delay(1_000L)
                continue
            }
            if (poll > 0) delay(minOf(10_000L, 1_500L + poll * 1_000L))
            val statusRequest = Request.Builder()
                .url(endpoint(record.baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val state = try {
                client.newCall(statusRequest).execute().use { response ->
                    when {
                        response.code == 404 -> GenerationState.PENDING
                        response.code == 401 || response.code == 403 -> GenerationState.TERMINAL_FAILURE
                        !response.isSuccessful -> GenerationState.PENDING
                        else -> {
                            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                            val data = root?.getAsJsonObject("data") ?: return@use GenerationState.PENDING
                            val cancelled = runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true
                            val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                            DiagnosticLog.record(
                                applicationContext,
                                "REQUEST_RECOVERY",
                                "background generation poll id=${generationId.take(12)} http=${response.code} cancelled=$cancelled finish=${finish.ifBlank { "pending" }} poll=${poll + 1}"
                            )
                            when {
                                cancelled -> GenerationState.CANCELLED
                                finish.isNotBlank() -> GenerationState.COMPLETED
                                else -> GenerationState.PENDING
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                GenerationState.PENDING
            }
            when (state) {
                GenerationState.COMPLETED -> ready = true
                GenerationState.CANCELLED -> throw TerminalRecoveryException("OpenRouter отменил генерацию после обрыва связи. Повторите запрос вручную.")
                GenerationState.TERMINAL_FAILURE -> throw TerminalRecoveryException("OpenRouter не разрешил проверить генерацию. Повторите запрос вручную.")
                GenerationState.PENDING -> Unit
            }
            poll += 1
        }
        if (!ready) throw IOException("Generation is not complete yet")'''
s2, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit("missing patch anchor: recovery poll loop")
p.write_text(s2)

# Version bump.
p = Path("app/build.gradle.kts")
s = p.read_text()
s = replace_once(s, "// Umnik v1.17.2", "// Umnik v1.17.3", "version comment")
s = replace_once(s, "versionCode = 125", "versionCode = 126", "versionCode")
s = replace_once(s, 'versionName = "1.17.2"', 'versionName = "1.17.3"', "versionName")
p.write_text(s)

# Changelog.
p = Path("CHANGELOG.md")
s = p.read_text()
entry = '''## v1.17.3

- Исправлен зависший статус старого запроса: если OpenRouter сообщает `cancelled=true`, Umnik теперь сразу прекращает recovery, помечает запрос завершённым с ошибкой и не держит «Восстанавливаю ответ в фоне…» несколько минут.
- Фоновый WorkManager recovery также отличает отменённую/неразрешённую серверную генерацию от временного `pending` и больше не повторяет заведомо безнадёжную проверку.
- Обычные OpenRouter-запросы и recovery переведены на HTTP/1.1. После отключения ping реальный Android-тест снова показал `Software caused connection abort` на HTTP/2; для текущей схемы один клиент = один запрос преимущества мультиплексирования не используются.
- Сохранена защита от двойной оплаты: автоматический POST по-прежнему возможен только в безопасном response-cache recovery и принимается только при `HIT`.
- Версия: 1.17.3 / versionCode 126.

'''
if not s.startswith("## v1.17.2"):
    raise SystemExit("unexpected CHANGELOG head")
p.write_text(entry + s)

print("v1.17.3 patch applied")
