from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 match, got {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str, minimum: int = 1) -> int:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{path}: expected >= {minimum} matches, got {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new))
    return count


# The live client must not start expedited recovery while its RequestExecutionManager runtime
# is still registered; otherwise the worker can observe a live snapshot and immediately retry.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_all(
    path,
    '''                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)
                        throw error
''',
    '''                        // RequestExecutionManager performs the urgent handoff after its live runtime is unregistered.
                        throw error
''',
    minimum=3,
)

path = "app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt"
replace_once(
    path,
    '''            } catch (error: Throwable) {
                DiagnosticLog.record(app, "REQUEST", "Background execution failed request=${requestId.take(8)} chat=${chatId.take(8)}", error)
                fail(requestId, error.message ?: "Запрос прерван")
            } finally {
                val recoveryPending = OpenRouterRecoveryStore(app).get(requestId) != null
                if (recoveryPending) {
                    OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)
                    DiagnosticLog.record(app, "REQUEST_RECOVERY", "Foreground request handed to WorkManager request=${requestId.take(8)} chat=${chatId.take(8)}")
                } else {
                    runCatching {
                        ChatRepository(app).updateMessage(chatId, messageId) {
                            if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                        }
                    }
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
                val remaining = synchronized(lock) {
                    runtimes.remove(requestId)
                    reservations.entries.removeAll { it.value == requestId }
                    publishLocked()
                    runtimes.size
                }
                if (remaining == 0) {
''',
    '''            } catch (error: Throwable) {
                val recoveryPending = OpenRouterRecoveryStore(app).get(requestId) != null
                if (recoveryPending) {
                    DiagnosticLog.record(
                        app,
                        "REQUEST_RECOVERY",
                        "Foreground transport interrupted; preserving pending request=${requestId.take(8)} chat=${chatId.take(8)}",
                        error
                    )
                    updatePhase(app, requestId, "Восстанавливаю ответ в фоне…")
                } else {
                    DiagnosticLog.record(app, "REQUEST", "Background execution failed request=${requestId.take(8)} chat=${chatId.take(8)}", error)
                    fail(requestId, error.message ?: "Запрос прерван")
                }
            } finally {
                val recoveryPending = OpenRouterRecoveryStore(app).get(requestId) != null
                if (!recoveryPending) {
                    runCatching {
                        ChatRepository(app).updateMessage(chatId, messageId) {
                            if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                        }
                    }
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
                val remaining = synchronized(lock) {
                    runtimes.remove(requestId)
                    reservations.entries.removeAll { it.value == requestId }
                    publishLocked()
                    runtimes.size
                }
                // Schedule only after publishing the runtime removal. An expedited worker can now
                // start immediately without mistaking the just-finished foreground job for a live owner.
                if (recoveryPending) {
                    OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)
                    DiagnosticLog.record(app, "REQUEST_RECOVERY", "Foreground request handed to WorkManager request=${requestId.take(8)} chat=${chatId.take(8)}")
                }
                if (remaining == 0) {
''',
)

p = Path("CHANGELOG.md")
text = p.read_text()
needle = "- Срочный recovery запускается как expedited work с безопасным fallback в обычную очередь, поэтому после реального обрыва соединения восстановление начинается сразу при доступной сети.\n"
replacement = needle + "- Перед запуском срочного recovery живой request сначала снимается из реестра foreground-работы, чтобы быстрый worker не попал в ложный retry из-за гонки состояний.\n"
if text.count(needle) != 1:
    raise SystemExit("CHANGELOG recovery line not found exactly once")
p.write_text(text.replace(needle, replacement, 1))
