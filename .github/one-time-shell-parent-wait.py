from pathlib import Path


def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 match, found {n}")
    return text.replace(old, new, 1)


# OpenRouter: local_shell_start waits on the process-local terminal bridge instead
# of handing control back to the model for paid local_shell_status polling.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.ayuemin.ymnik.OpenRouterRecoveryWorker\n",
    "import com.ayuemin.ymnik.OpenRouterRecoveryWorker\nimport com.ayuemin.ymnik.LocalShellRuntime\n",
    "OpenRouter import",
)
old = '''                    "local_shell_start" -> {
                        val callback = localShellStart
                        if (callback == null) {
                            gson.toJson(mapOf("ok" to false, "error" to "Local Shell недоступен"))
                        } else {
                            runCatching {
                                val args = gson.fromJson(argsRaw, JsonObject::class.java)
                                val task = args.get("task")?.asString.orEmpty().trim()
                                require(task.isNotBlank()) { "Не передана задача для Local Shell" }
                                val network = runCatching { args.get("network")?.asBoolean ?: false }.getOrDefault(false)
                                val files = args.getAsJsonArray("files")
                                    ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim() }
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty()
                                callback(task, network, files)
                            }.getOrElse {
                                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось запустить Local Shell")))
                            }
                        }
                    }
'''
new = '''                    "local_shell_start" -> {
                        val callback = localShellStart
                        if (callback == null) {
                            gson.toJson(mapOf("ok" to false, "error" to "Local Shell недоступен"))
                        } else {
                            runCatching {
                                val args = gson.fromJson(argsRaw, JsonObject::class.java)
                                val task = args.get("task")?.asString.orEmpty().trim()
                                require(task.isNotBlank()) { "Не передана задача для Local Shell" }
                                val network = runCatching { args.get("network")?.asBoolean ?: false }.getOrDefault(false)
                                val files = args.getAsJsonArray("files")
                                    ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim() }
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty()
                                val startResult = callback(task, network, files)
                                val startObject = runCatching { gson.fromJson(startResult, JsonObject::class.java) }.getOrNull()
                                val started = runCatching { startObject?.get("started")?.asBoolean }.getOrNull() == true
                                val startOk = runCatching { startObject?.get("ok")?.asBoolean }.getOrNull()
                                if (!started || startOk == false) {
                                    startResult
                                } else {
                                    LocalShellRuntime.markParentConsumesTerminal()
                                    phaseCallback("Local Shell работает…")
                                    DiagnosticLog.record(context, "LOCAL_SHELL_PARENT_WAIT", "suspend; request=$requestRunId; step=$loops")
                                    val terminal = LocalShellRuntime.awaitTerminal()
                                    terminal.costUsd?.let { shellCost ->
                                        costSink?.invoke(RequestCostKind.PRIMARY, shellCost.toString())
                                    }
                                    terminal.files.forEach { file ->
                                        if (created.none { existing -> existing.id == file.id }) created += file
                                    }
                                    DiagnosticLog.record(
                                        context,
                                        "LOCAL_SHELL_PARENT_WAIT",
                                        "resume; state=${terminal.state.name}; turns=${terminal.turns}; tools=${terminal.toolCalls}; files=${terminal.files.size}; request=$requestRunId"
                                    )
                                    gson.toJson(
                                        linkedMapOf<String, Any?>(
                                            "ok" to terminal.ok,
                                            "source" to "local_shell",
                                            "state" to terminal.state.name,
                                            "result" to terminal.text.takeIf { it.isNotBlank() },
                                            "error" to terminal.error?.takeIf { it.isNotBlank() },
                                            "model" to terminal.modelId,
                                            "turns" to terminal.turns,
                                            "tool_calls" to terminal.toolCalls,
                                            "files" to terminal.files.map { file ->
                                                mapOf("name" to file.name, "mime_type" to file.mimeType, "size" to file.size)
                                            }
                                        ).filterValues { it != null }
                                    )
                                }
                            }.getOrElse {
                                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось запустить Local Shell")))
                            }
                        }
                    }
'''
s = replace_once(s, old, new, "OpenRouter local_shell_start")
s = replace_once(
    s,
    'description = "Запустить асинхронный Local Shell на устройстве пользователя. Используй, когда пользователь явно просит выполнить, реализовать, исправить, собрать или проверить работу, которую разумно передать локальному агенту. Не запускай только потому, что это могло бы быть полезно. Сформулируй task из уже согласованного контекста диалога.",',
    'description = "Запустить Local Shell на устройстве пользователя. Worker асинхронен для UI, но этот tool-вызов сам дождётся завершения без платного status-поллинга и вернёт итог. После собственного local_shell_start не вызывай local_shell_status в этом же ответе. Сформулируй task из уже согласованного контекста диалога.",',
    "start description",
)
s = replace_once(
    s,
    'description = "Получить компактный статус Local Shell, запущенного в этом чате: этап, модель, шаги и локальные действия.",',
    'description = "Получить статус уже работающего Local Shell из другого шага диалога. Не используй сразу после собственного local_shell_start: тот вызов сам ждёт терминальный результат.",',
    "status description",
)
p.write_text(s)


# Suppress the legacy second assistant message when the parent Agent consumes the
# same terminal result. Cleanup after onSuccess/onFailure still runs normally.
p = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
s = p.read_text()
anchor = s.index("localShellClient.run(")
marker = "            }.onSuccess { result ->\n"
i = s.index(marker, anchor)
inject = marker + '''                if (LocalShellRuntime.parentConsumesTerminal()) {
                    DiagnosticLog.record(context, "LOCAL_SHELL_CHAT", "terminal result delegated to parent Agent; duplicate assistant suppressed")
                    return@onSuccess
                }
'''
s = s[:i] + inject + s[i + len(marker):]
marker = "            }.onFailure { error ->\n"
i = s.index(marker, i + len(inject))
inject = marker + '''                if (LocalShellRuntime.parentConsumesTerminal()) {
                    DiagnosticLog.record(context, "LOCAL_SHELL_CHAT", "terminal failure delegated to parent Agent; duplicate assistant suppressed", error)
                    return@onFailure
                }
'''
s = s[:i] + inject + s[i + len(marker):]
p.write_text(s)


# Carry real Local Shell usage through the terminal bridge so the parent request
# keeps accurate cost accounting.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/LocalShellAgentClient.kt")
s = p.read_text()
s = replace_once(
    s,
    '''                            modelId = result.model,
                            turns = result.turns,
                            toolCalls = result.toolCalls
''',
    '''                            modelId = result.model,
                            turns = result.turns,
                            toolCalls = result.toolCalls,
                            costUsd = result.costUsd,
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens
''',
    "DONE usage",
)
old = '''                    modelId = returnedModel ?: model,
                    turns = turn,
                    toolCalls = toolCalls,
                    error = '''
new = '''                    modelId = returnedModel ?: model,
                    turns = turn,
                    toolCalls = toolCalls,
                    costUsd = totalCost.takeIf { costObserved },
                    inputTokens = totalInputTokens.takeIf { it > 0 },
                    outputTokens = totalOutputTokens.takeIf { it > 0 },
                    error = '''
if s.count(old) != 2:
    raise SystemExit(f"partial usage expected 2 matches, found {s.count(old)}")
p.write_text(s.replace(old, new))


# Prompt Diet remains compact while explicitly forbidding status-as-a-timer after
# a start initiated by the same model turn.
p = Path("app/src/main/java/com/ayuemin/ymnik/PromptDiet.kt")
s = p.read_text()
s = replace_once(
    s,
    '"Local Shell — асинхронный локальный исполнитель на устройстве пользователя. Он может работать после завершения твоего текущего ответа, а пользователь может продолжать этот же диалог." to "Local Shell — асинхронный локальный исполнитель; он может работать после твоего ответа, пока диалог продолжается.",',
    '"Local Shell — асинхронный локальный исполнитель на устройстве пользователя. Он может работать после завершения твоего текущего ответа, а пользователь может продолжать этот же диалог." to "Local Shell асинхронен для UI; после local_shell_start Umnik сам ждёт итог worker без model/status-поллинга.",',
    "Prompt async",
)
s = replace_once(
    s,
    '"Если Local Shell уже работает в этом чате, не запускай второй. Используй local_shell_status для проверки состояния, local_shell_note для передачи нового ограничения/уточнения пользователя, local_shell_stop — только по явной просьбе остановить." to "Если Shell уже работает, второй не запускай: status — проверить, note — передать уточнение, stop — только по явной просьбе остановить.",',
    '"Если Local Shell уже работает в этом чате, не запускай второй. Используй local_shell_status для проверки состояния, local_shell_note для передачи нового ограничения/уточнения пользователя, local_shell_stop — только по явной просьбе остановить." to "Если Shell работал до текущего шага, второй не запускай: status — отдельная проверка, note — уточнение, stop — явная остановка. После собственного local_shell_start status не опрашивай: итог вернётся этим же tool-вызовом.",',
    "Prompt status",
)
p.write_text(s)


Path("app/src/test/java/com/ayuemin/ymnik/LocalShellRuntimeTest.kt").write_text(r'''package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellRuntimeTest {
    @After fun cleanup() {
        AsyncJobEvents.markLocalShellFinished("test-chat")
        LocalShellRuntime.clear()
    }

    @Test fun slowChildSuspendsParentWithoutPolling() = runBlocking {
        LocalShellRuntime.prepareForStart()
        val startedAt = System.nanoTime()
        val child = launch(Dispatchers.Default) {
            delay(250L)
            LocalShellRuntime.completeTerminal(LocalShellTerminalResult(
                state = LocalShellTerminalState.DONE,
                text = "AGENT TEST OK",
                files = listOf(GeneratedFile("f1", "agent_test.txt", "text/plain", "/tmp/agent_test.txt", 13L)),
                turns = 4,
                toolCalls = 5
            ))
        }
        val result = LocalShellRuntime.awaitTerminal()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        child.join()
        assertTrue("parent resumed too early: ${elapsedMs}ms", elapsedMs >= 180L)
        assertEquals(LocalShellTerminalState.DONE, result.state)
        assertEquals("AGENT TEST OK", result.text)
        assertEquals(4, result.turns)
        assertEquals(5, result.toolCalls)
        assertEquals("agent_test.txt", result.files.single().name)
    }

    @Test fun failedAndStoppedAreTerminalResults() = runBlocking {
        LocalShellRuntime.prepareForStart()
        LocalShellRuntime.completeTerminal(LocalShellTerminalResult(LocalShellTerminalState.FAILED, error = "boom"))
        assertEquals(LocalShellTerminalState.FAILED, LocalShellRuntime.awaitTerminal().state)
        LocalShellRuntime.prepareForStart()
        LocalShellRuntime.completeTerminal(LocalShellTerminalResult(LocalShellTerminalState.STOPPED, error = "stopped"))
        val stopped = LocalShellRuntime.awaitTerminal()
        assertFalse(stopped.ok)
        assertEquals(LocalShellTerminalState.STOPPED, stopped.state)
    }

    @Test fun guidanceUsesSameRuntimeAndParentFlagResets() {
        LocalShellRuntime.prepareForStart()
        AsyncJobEvents.markLocalShellRunning("test-chat", "model", 0, 500)
        assertTrue(LocalShellRuntime.addGuidance("same worker"))
        assertEquals(listOf("same worker"), LocalShellRuntime.drainGuidance())
        LocalShellRuntime.markParentConsumesTerminal()
        assertTrue(LocalShellRuntime.parentConsumesTerminal())
        LocalShellRuntime.prepareForStart()
        assertFalse(LocalShellRuntime.parentConsumesTerminal())
    }
}
''')

Path("app/src/test/java/com/ayuemin/ymnik/network/AgentLocalShellAwaitRegressionTest.kt").write_text(r'''package com.ayuemin.ymnik.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalShellAwaitRegressionTest {
    @Test fun localShellStartAwaitsTerminalInsteadOfPollingStatus() {
        val source = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
        ).first { it.isFile }.readText()
        val start = source.indexOf("\"local_shell_start\" ->")
        val end = source.indexOf("\"local_shell_status\" ->", start)
        require(start >= 0 && end > start)
        val branch = source.substring(start, end)
        assertTrue(branch.contains("LocalShellRuntime.awaitTerminal()"))
        assertTrue(branch.contains("LocalShellRuntime.markParentConsumesTerminal()"))
        assertFalse(branch.contains("localShellStatus"))
        assertTrue(source.contains("localShellToolsEnabled -> 8"))
        assertTrue(source.contains("AgentToolLoopGuard()"))
    }
}
''')
