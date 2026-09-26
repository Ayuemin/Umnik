from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
client_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/LocalShellAgentClient.kt"
guard_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/LocalShellLoopGuard.kt"
test_path = ROOT / "app/src/test/java/com/ayuemin/ymnik/network/LocalShellLoopGuardTest.kt"

text = client_path.read_text(encoding="utf-8")
original = text

# SHA-256 keeps large tool arguments/results out of the in-memory trace while making
# the loop signature depend on both the requested action and the actual observed state.
anchor = "import java.util.UUID\nimport java.util.concurrent.TimeUnit"
replacement = "import java.security.MessageDigest\nimport java.util.UUID\nimport java.util.concurrent.TimeUnit"
if anchor not in text:
    raise SystemExit("Import anchor not found")
text = text.replace(anchor, replacement, 1)

old_state = """        var returnedModel: String? = null
        var lastToolSignature: String? = null
        var repeatedToolSignature = 0
        var lastCompactionTurn = -100
"""
new_state = """        var returnedModel: String? = null
        val loopGuard = LocalShellLoopGuard()
        var lastCompactionTurn = -100
"""
if old_state not in text:
    raise SystemExit("Old watchdog state anchor not found")
text = text.replace(old_state, new_state, 1)

# A new explicit user instruction changes the goal and therefore starts a fresh
# loop-detection epoch. Repeating an action after guidance may be intentional.
guidance_anchor = """                if (guidance.isNotEmpty()) {
                    val note = guidance.joinToString("\\n\\n") { "- " + it }
"""
guidance_replacement = """                if (guidance.isNotEmpty()) {
                    loopGuard.reset()
                    val note = guidance.joinToString("\\n\\n") { "- " + it }
"""
if guidance_anchor not in text:
    raise SystemExit("Guidance anchor not found")
text = text.replace(guidance_anchor, guidance_replacement, 1)

old_loop = """                messages.add(assistant.deepCopy())
                for (element in calls) {
                    if (!element.isJsonObject) continue
                    if (toolCalls >= maxToolCalls) {
                        error("Локальный Shell достиг лимита $maxToolCalls вызовов инструментов")
                    }
                    val call = element.asJsonObject
                    val callId = call.string("id") ?: UUID.randomUUID().toString()
                    val function = call.getAsJsonObject("function")
                    val name = function?.string("name").orEmpty()
                    val args = function?.string("arguments") ?: "{}"
                    toolCalls += 1
                    onProgress(Progress(toolLabel(name), turn, toolCalls))
                    val signature = name + "\\n" + args.trim()
                    if (signature == lastToolSignature) repeatedToolSignature += 1 else {
                        lastToolSignature = signature
                        repeatedToolSignature = 1
                    }
                    val wasReadyBeforeTool = taskState == TaskState.READY_TO_FINISH
                    val resultText = if (repeatedToolSignature >= 3) {
                        DiagnosticLog.record(context, "LOCAL_SHELL_WATCHDOG", "Repeated identical tool call blocked; tool=$name; turn=$turn")
                        gson.toJson(mapOf(
                            "ok" to false,
                            "warning" to "Одинаковое локальное действие повторено несколько раз. Оно не выполнено снова: пересмотри план и выбери следующий полезный шаг."
                        ))
                    } else {
                        engine.execute(name, args)
                    }
                    val resultObject = runCatching { gson.fromJson(resultText, JsonObject::class.java) }.getOrNull()
"""
new_loop = """                messages.add(assistant.deepCopy())
                var loopDecisionForTurn: LocalShellLoopDecision? = null
                for (element in calls) {
                    if (!element.isJsonObject) continue
                    if (toolCalls >= maxToolCalls) {
                        error("Локальный Shell достиг аварийного предела $maxToolCalls вызовов инструментов")
                    }
                    val call = element.asJsonObject
                    val callId = call.string("id") ?: UUID.randomUUID().toString()
                    val function = call.getAsJsonObject("function")
                    val name = function?.string("name").orEmpty()
                    val args = function?.string("arguments") ?: "{}"
                    toolCalls += 1
                    onProgress(Progress(toolLabel(name), turn, toolCalls))
                    val wasReadyBeforeTool = taskState == TaskState.READY_TO_FINISH
                    val resultText = engine.execute(name, args)

                    // The guard is state-aware: repeating the same read/search is allowed when
                    // its real result changes. Only a repeated action+result pattern counts.
                    val actionKey = name + ":" + stableFingerprint(normalizeJson(args))
                    val stateKey = stableFingerprint(normalizeJson(resultText))
                    val loopDecision = loopGuard.observe(actionKey, stateKey)
                    if (loopDecision != null) {
                        DiagnosticLog.record(
                            context,
                            "LOCAL_SHELL_LOOP_GUARD",
                            "pattern=${loopDecision.patternSize}; strike=${loopDecision.strike}; stop=${loopDecision.shouldStop}; " +
                                "tool=$name; turn=$turn; action=$actionKey; state=$stateKey"
                        )
                        if (loopDecision.shouldStop) {
                            error(
                                "Local Shell остановлен: повторяющийся цикл не изменяет состояние после попытки перестроить план. " +
                                    "Аварийный лимит шагов не достигнут."
                            )
                        }
                        loopDecisionForTurn = loopDecision
                    }
                    val resultObject = runCatching { gson.fromJson(resultText, JsonObject::class.java) }.getOrNull()
"""
if old_loop not in text:
    raise SystemExit("Old tool watchdog block not found")
text = text.replace(old_loop, new_loop, 1)

# Add the recovery instruction only after all tool_call results from the same assistant
# message, keeping the tool protocol well-formed.
post_tool_anchor = """                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", callId)
                        addProperty("content", resultText)
                    })
                }
            }
"""
post_tool_replacement = """                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", callId)
                        addProperty("content", resultText)
                    })
                }
                loopDecisionForTurn?.let { decision ->
                    messages.add(message(
                        "system",
                        "===== ЗАЩИТА LOCAL SHELL ОТ ЗАЦИКЛИВАНИЯ =====\\n" +
                            "Обнаружен повторяющийся цикл из ${decision.patternSize} локальных действий без изменения результата. " +
                            "Это первое предупреждение, задача НЕ остановлена. Перестрой план: выбери другой инструмент, " +
                            "другой запрос, файл, диапазон или способ проверки. Не повторяй тот же цикл. " +
                            "Если такой цикл возникнет снова до заметного прогресса, Local Shell будет остановлен.\\n" +
                            "===== КОНЕЦ ПРЕДУПРЕЖДЕНИЯ ====="
                    ))
                    onProgress(Progress("Перестраиваю план после повторяющегося цикла", turn, toolCalls))
                }
            }
"""
if post_tool_anchor not in text:
    raise SystemExit("Post-tool anchor not found")
text = text.replace(post_tool_anchor, post_tool_replacement, 1)

helper_anchor = """    private fun toolLabel(name: String): String = when (name) {
"""
helper_code = """    private fun normalizeJson(raw: String): String = runCatching {
        gson.toJson(gson.fromJson(raw.ifBlank { "null" }, JsonElement::class.java))
    }.getOrDefault(raw.trim())

    private fun stableFingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun toolLabel(name: String): String = when (name) {
"""
if helper_anchor not in text:
    raise SystemExit("Helper anchor not found")
text = text.replace(helper_anchor, helper_code, 1)

if 'private const val DEFAULT_MAX_TURNS = 24' not in text:
    raise SystemExit("DEFAULT_MAX_TURNS=24 anchor not found")
text = text.replace('private const val DEFAULT_MAX_TURNS = 24', 'private const val DEFAULT_MAX_TURNS = 500', 1)

client_path.write_text(text, encoding="utf-8")

# Pure Kotlin guard: no Android dependency, so behavior is unit-testable.
guard_path.write_text(r'''package com.ayuemin.ymnik.network

internal data class LocalShellLoopDecision(
    val patternSize: Int,
    val strike: Int,
    val shouldStop: Boolean
)

/**
 * Detects repeated Local Shell action+observed-state patterns.
 *
 * A repeated tool name alone is not a loop: callers include normalized arguments and
 * a fingerprint of the actual tool result. The first detected loop asks the model to
 * re-plan. A second loop before sustained progress stops the run. After enough distinct
 * progress the old strike is forgotten so two unrelated dead ends do not kill a long job.
 */
internal class LocalShellLoopGuard(
    private val maxTrace: Int = 24,
    private val resetAfterProgress: Int = 20
) {
    private val trace = mutableListOf<String>()
    private var strikes = 0
    private var progressSinceLoop = 0

    fun observe(actionKey: String, stateKey: String): LocalShellLoopDecision? {
        trace += "$actionKey|$stateKey"
        while (trace.size > maxTrace.coerceAtLeast(12)) trace.removeAt(0)

        val patternSize = repeatedPattern()
        if (patternSize == null) {
            if (strikes > 0) {
                progressSinceLoop += 1
                if (progressSinceLoop >= resetAfterProgress.coerceAtLeast(1)) {
                    strikes = 0
                    progressSinceLoop = 0
                }
            }
            return null
        }

        strikes += 1
        progressSinceLoop = 0
        trace.clear()
        return LocalShellLoopDecision(
            patternSize = patternSize,
            strike = strikes,
            shouldStop = strikes >= 2
        )
    }

    fun reset() {
        trace.clear()
        strikes = 0
        progressSinceLoop = 0
    }

    private fun repeatedPattern(): Int? {
        if (trace.size >= 4) {
            val tail = trace.takeLast(4)
            if (tail.distinct().size == 1) return 1
        }
        for (patternSize in 2..4) {
            val repeats = 3
            val needed = patternSize * repeats
            if (trace.size < needed) continue
            val tail = trace.takeLast(needed)
            val pattern = tail.take(patternSize)
            if ((1 until repeats).all { repeatIndex ->
                    val from = repeatIndex * patternSize
                    tail.subList(from, from + patternSize) == pattern
                }
            ) return patternSize
        }
        return null
    }
}
''', encoding="utf-8")

test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellLoopGuardTest {
    @Test
    fun fourIdenticalActionStatesTriggerRecoveryWarning() {
        val guard = LocalShellLoopGuard()
        repeat(3) { assertNull(guard.observe("read:a", "same")) }
        val decision = guard.observe("read:a", "same")
        requireNotNull(decision)
        assertEquals(1, decision.patternSize)
        assertEquals(1, decision.strike)
        assertFalse(decision.shouldStop)
    }

    @Test
    fun sameActionWithChangingStateIsProgress() {
        val guard = LocalShellLoopGuard()
        repeat(12) { index ->
            assertNull(guard.observe("read:a", "state-$index"))
        }
    }

    @Test
    fun alternatingPatternRepeatedThreeTimesIsDetected() {
        val guard = LocalShellLoopGuard()
        val sequence = listOf("A", "B", "A", "B", "A", "B")
        var decision: LocalShellLoopDecision? = null
        sequence.forEach { action -> decision = guard.observe(action, "unchanged-$action") ?: decision }
        requireNotNull(decision)
        assertEquals(2, decision!!.patternSize)
        assertFalse(decision!!.shouldStop)
    }

    @Test
    fun secondLoopBeforeProgressStopsRun() {
        val guard = LocalShellLoopGuard()
        repeat(4) { guard.observe("read:a", "same") }
        var second: LocalShellLoopDecision? = null
        repeat(4) { second = guard.observe("read:a", "same") ?: second }
        requireNotNull(second)
        assertEquals(2, second!!.strike)
        assertTrue(second!!.shouldStop)
    }

    @Test
    fun sustainedProgressForgetsOldStrike() {
        val guard = LocalShellLoopGuard(resetAfterProgress = 3)
        repeat(4) { guard.observe("read:a", "same") }
        repeat(3) { index -> assertNull(guard.observe("progress-$index", "state-$index")) }
        var later: LocalShellLoopDecision? = null
        repeat(4) { later = guard.observe("read:b", "same-b") ?: later }
        requireNotNull(later)
        assertEquals(1, later!!.strike)
        assertFalse(later!!.shouldStop)
    }
}
''', encoding="utf-8")

# Print any explicit run-site maxTurns overrides to CI logs. We intentionally do not
# rewrite user-configurable limits blindly; DEFAULT_MAX_TURNS is the emergency default.
print("Patched", client_path.relative_to(ROOT))
for path in ROOT.rglob("*.kt"):
    if "build" in path.parts:
        continue
    body = path.read_text(encoding="utf-8", errors="ignore")
    if "maxTurns" in body or "local_shell_max" in body.lower():
        hits = [line.strip() for line in body.splitlines() if "maxTurns" in line or "local_shell_max" in line.lower()]
        print("MAX_TURNS_REF", path.relative_to(ROOT), " :: ", " | ".join(hits[:12]))

if original == text:
    raise SystemExit("No LocalShellAgentClient changes were made")
