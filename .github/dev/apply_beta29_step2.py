from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"

replace_once(
    path,
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\n",
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineStart\nimport kotlinx.coroutines.Dispatchers\n",
)

replace_once(
    path,
    '''                                            requestAttachments = pending,
                                            networkEnabled = allowNetwork,
                                            fallbackModel = textModel
''',
    '''                                            requestAttachments = pending,
                                            networkEnabled = allowNetwork,
                                            fallbackModel = textModel,
                                            requestNetwork = network
''',
)

replace_once(
    path,
    '''        requestAttachments: List<PendingAttachment> = emptyList(),
        networkEnabled: Boolean,
        fallbackModel: String
    ): String {
''',
    '''        requestAttachments: List<PendingAttachment> = emptyList(),
        networkEnabled: Boolean,
        fallbackModel: String,
        requestNetwork: RequestNetworkSession? = null
    ): String {
''',
)

replace_once(
    path,
    '''        val startedAt = System.currentTimeMillis()
        LocalShellRuntime.scope.launch {
            var lastKeepAliveRefreshAt = 0L
            val parentOwned = originUserMessageId != null
            val parentWatcher = if (parentOwned) {
                launch {
                    while (RequestExecutionManager.hasActiveChat(chatId)) {
                        delay(100L)
                    }
                    cancelRequested.set(true)
                    localShellClient.cancelActive()
                    DiagnosticLog.record(
                        context,
                        "LOCAL_SHELL_LIFECYCLE",
                        "parent request ended; cancelling child shell chat=${chatId.take(8)} task=${taskId.take(8)}"
                    )
                }
            } else {
                null
            }
            runCatching {
''',
    '''        val childCancellationId = "local-shell:$taskId"
        val startedAt = System.currentTimeMillis()
        lateinit var shellJob: Job
        shellJob = LocalShellRuntime.scope.launch(start = CoroutineStart.LAZY) {
            var lastKeepAliveRefreshAt = 0L
            runCatching {
''',
)

replace_once(
    path,
    '''            parentWatcher?.cancel()
            AsyncJobEvents.markLocalShellFinished(chatId)
            runCatching { RequestKeepAliveService.update(context) }
            AsyncJobEvents.notifyChanged()
            LocalShellRuntime.clear()
        }

        return gson.toJson(
''',
    '''            requestNetwork?.unregisterChildCancellation(childCancellationId)
            AsyncJobEvents.markLocalShellFinished(chatId)
            runCatching { RequestKeepAliveService.update(context) }
            AsyncJobEvents.notifyChanged()
            LocalShellRuntime.clear()
        }

        val parentAccepted = requestNetwork?.registerChildCancellation(childCancellationId) {
            cancelRequested.set(true)
            shellJob.cancel(CancellationException("Parent request cancelled"))
            localShellClient.cancelActive()
            DiagnosticLog.record(
                context,
                "LOCAL_SHELL_LIFECYCLE",
                "parent cancellation cascaded to child shell chat=${chatId.take(8)} task=${taskId.take(8)}"
            )
        } ?: true
        if (!parentAccepted) {
            AsyncJobEvents.markLocalShellFinished(chatId)
            runCatching { RequestKeepAliveService.update(context) }
            LocalShellRuntime.clear()
            return gson.toJson(
                mapOf(
                    "ok" to false,
                    "error" to "Родительский запрос уже остановлен; Local Shell не запущен"
                )
            )
        }
        shellJob.start()

        return gson.toJson(
''',
)

print("beta.29 step2 parent-child cancellation patch applied")
