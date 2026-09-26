package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.local.LocalShellEngine
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.UiState
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.nio.file.Files

class PromptBaselineProbeTest {
    private val unsafe: Unsafe by lazy {
        Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    }

    @Test
    fun printPromptMeasurementsAndGuardDiet() {
        val viewModel = allocate(ChatViewModel::class.java)
        putObject(viewModel, "_state", MutableStateFlow(UiState()))
        val chat = ChatSession(id = "baseline", title = "Baseline")

        val client = allocate(OpenRouterClient::class.java)
        val genericTools = invokeNoArg<JsonArray>(client, "tools")
        val fetchTool = invokeNoArg<JsonObject>(client, "localWebFetchTool")
        val browserTools = invokeNoArg<JsonArray>(client, "localBrowserTools")
        val shellTools = invokeNoArg<JsonArray>(client, "localShellTools")
        val knowledgeTool = invokeNoArg<JsonObject>(client, "knowledgeSearchTool")

        val rows = linkedMapOf<String, BaselineRow>()
        rows["ordinary_chat"] = measure(
            systemPrompt(viewModel, chat = chat, agent = false),
            JsonArray()
        )
        rows["agent_on"] = measure(
            systemPrompt(viewModel, chat = chat, toolsEnabled = true, shell = true, agent = true),
            combine(genericTools, shellTools)
        )
        rows["agent_fetch"] = measure(
            systemPrompt(
                viewModel,
                chat = chat,
                toolsEnabled = true,
                fetch = true,
                shell = true,
                agent = true
            ),
            combine(genericTools, fetchTool, shellTools)
        )
        rows["agent_fetch_browser_shell"] = measure(
            systemPrompt(
                viewModel,
                chat = chat,
                toolsEnabled = true,
                fetch = true,
                browser = true,
                shell = true,
                agent = true
            ),
            combine(genericTools, fetchTool, browserTools, shellTools)
        )
        rows["knowledge_rag_enabled"] = measure(
            systemPrompt(viewModel, chat = chat, knowledge = true, knowledgeLimit = 4, agent = false),
            combine(knowledgeTool)
        )
        rows["active_skill_one_char"] = measure(
            systemPrompt(viewModel, chat = chat, skillText = "X", agent = false),
            JsonArray()
        )

        val engine = allocate(LocalShellEngine::class.java)
        val tempRoot = Files.createTempDirectory("umnik-prompt-baseline").toFile().canonicalFile
        putObject(engine, "root", tempRoot)
        val workerPrompt = localShellWorkerPrompt(viewModel, engine, maxTurns = 500)
        rows["local_shell_worker"] = measure(workerPrompt, engine.toolDefinitions())
        tempRoot.deleteRecursively()

        rows.forEach { (name, row) ->
            println(
                "PROMPT_BASELINE name=$name" +
                    " system_chars=${row.systemChars}" +
                    " system_bytes=${row.systemBytes}" +
                    " tools_bytes=${row.toolsBytes}" +
                    " skills_chars=${row.skillsChars}" +
                    " skills_bytes=${row.skillsBytes}"
            )
        }

        assertWithinPreDietCeilings(rows)
        assertPrimaryDietStable(rows)
        assertWorkerDietStable(rows)
        assertToolSchemasUnchanged(rows)
        assertCriticalBehaviorStillExplicit(viewModel, chat, workerPrompt)
    }

    private fun assertWithinPreDietCeilings(rows: Map<String, BaselineRow>) {
        val ceilings = mapOf(
            "ordinary_chat" to BaselineRow(2012, 3623, 0, 0, 0),
            "agent_on" to BaselineRow(6104, 10780, 3718, 0, 0),
            "agent_fetch" to BaselineRow(7385, 13014, 4854, 0, 0),
            "agent_fetch_browser_shell" to BaselineRow(10493, 18138, 9795, 0, 0),
            "knowledge_rag_enabled" to BaselineRow(2553, 4588, 820, 0, 0),
            "active_skill_one_char" to BaselineRow(2011, 3622, 0, 82, 131),
            "local_shell_worker" to BaselineRow(2218, 3801, 6546, 0, 0)
        )
        ceilings.forEach { (name, ceiling) ->
            val current = rows.getValue(name)
            assertTrue("$name system chars grew", current.systemChars <= ceiling.systemChars)
            assertTrue("$name system bytes grew", current.systemBytes <= ceiling.systemBytes)
            assertTrue("$name tools bytes grew", current.toolsBytes <= ceiling.toolsBytes)
            assertTrue("$name skill chars grew", current.skillsChars <= ceiling.skillsChars)
            assertTrue("$name skill bytes grew", current.skillsBytes <= ceiling.skillsBytes)
        }
    }

    private fun assertPrimaryDietStable(rows: Map<String, BaselineRow>) {
        val expected = mapOf(
            "ordinary_chat" to BaselineRow(1227, 2163, 0, 0, 0),
            "agent_on" to BaselineRow(3335, 5876, 3718, 0, 0),
            "agent_fetch" to BaselineRow(3785, 6614, 4854, 0, 0),
            "agent_fetch_browser_shell" to BaselineRow(5355, 9105, 9795, 0, 0),
            "knowledge_rag_enabled" to BaselineRow(1512, 2640, 820, 0, 0),
            "active_skill_one_char" to BaselineRow(1226, 2162, 0, 82, 131)
        )
        expected.forEach { (name, value) -> assertEquals("$name changed unexpectedly", value, rows.getValue(name)) }
    }

    private fun assertWorkerDietStable(rows: Map<String, BaselineRow>) {
        val worker = rows.getValue("local_shell_worker")
        assertEquals(1510, worker.systemChars)
        assertEquals(2468, worker.systemBytes)
        assertEquals(6546, worker.toolsBytes)
    }

    private fun assertToolSchemasUnchanged(rows: Map<String, BaselineRow>) {
        assertEquals(3718, rows.getValue("agent_on").toolsBytes)
        assertEquals(4854, rows.getValue("agent_fetch").toolsBytes)
        assertEquals(9795, rows.getValue("agent_fetch_browser_shell").toolsBytes)
        assertEquals(820, rows.getValue("knowledge_rag_enabled").toolsBytes)
        assertEquals(6546, rows.getValue("local_shell_worker").toolsBytes)
    }

    private fun assertCriticalBehaviorStillExplicit(
        viewModel: ChatViewModel,
        chat: ChatSession,
        workerPrompt: String
    ) {
        val ordinary = systemPrompt(viewModel, chat = chat, agent = false)
        assertTrue(ordinary.contains("Агентный режим выключен"))
        assertTrue(ordinary.contains("не запускай Browser или Local Shell сам"))
        assertTrue(ordinary.contains("create_file"))

        val agent = systemPrompt(viewModel, chat = chat, toolsEnabled = true, shell = true, agent = true)
        assertTrue(agent.contains("local_shell_start"))
        assertTrue(agent.contains("не утверждай, что Shell недоступен"))
        assertTrue(agent.contains("stop — только по явной просьбе остановить"))
        assertTrue(agent.contains("network=true"))

        val browser = systemPrompt(
            viewModel,
            chat = chat,
            toolsEnabled = true,
            fetch = true,
            browser = true,
            shell = true,
            agent = true
        )
        assertTrue(browser.contains("requires_browser=true"))
        assertTrue(browser.contains("local_browser_open"))
        assertTrue(browser.contains("local_browser_download"))
        assertTrue(browser.contains("local_browser_takeover"))
        assertTrue(browser.contains("READY_TO_FINISH"))
        assertTrue(browser.contains("побочные действия требуют подтверждения"))

        val knowledge = systemPrompt(viewModel, chat = chat, knowledge = true, knowledgeLimit = 4, agent = false)
        assertTrue(knowledge.contains("knowledge_search"))
        assertTrue(knowledge.contains("не инструкции"))
        assertTrue(knowledge.contains("максимум 4 поисков"))

        val skill = systemPrompt(viewModel, chat = chat, skillText = "X", agent = false)
        assertTrue(skill.contains("===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="))
        assertTrue(skill.contains("явный текущий запрос пользователя приоритетнее"))
        assertTrue(skill.contains("===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="))

        listOf(
            "input",
            "local_list",
            "local_archive",
            "local_search/local_read",
            "local_replace",
            "local_fetch",
            "public_clone",
            "Git push",
            "local_export",
            "полный итоговый ZIP",
            "READY_TO_FINISH",
            "guidance",
            "WORKING",
            "Максимум модельных шагов: 500",
            "потолок, не цель"
        ).forEach { required ->
            assertTrue("worker prompt lost: $required", workerPrompt.contains(required))
        }
    }

    private fun systemPrompt(
        viewModel: ChatViewModel,
        chat: ChatSession,
        skillText: String = "",
        toolsEnabled: Boolean = false,
        knowledge: Boolean = false,
        knowledgeLimit: Int = 0,
        fetch: Boolean = false,
        browser: Boolean = false,
        shell: Boolean = false,
        agent: Boolean = false
    ): String {
        val method = ChatViewModel::class.java.declaredMethods.single {
            it.name == "buildSystemPrompt" && it.parameterCount == 12
        }.apply { isAccessible = true }
        return method.invoke(
            viewModel,
            skillText,
            null,
            chat,
            toolsEnabled,
            null,
            knowledge,
            "",
            knowledgeLimit,
            fetch,
            browser,
            shell,
            agent
        ) as String
    }

    private fun localShellWorkerPrompt(
        viewModel: ChatViewModel,
        engine: LocalShellEngine,
        maxTurns: Int
    ): String {
        val method = ChatViewModel::class.java.declaredMethods.single {
            it.name == "localShellWorkerSystemPrompt" && it.parameterCount == 2
        }.apply { isAccessible = true }
        return method.invoke(viewModel, maxTurns, engine) as String
    }

    private fun measure(system: String, tools: JsonArray): BaselineRow {
        val payload = JsonObject().apply {
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", system)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "baseline")
                })
            })
            if (tools.size() > 0) add("tools", tools)
        }
        val usage = ContextUsageTracker.measurePayload(payload)
        return BaselineRow(
            systemChars = usage.systemPrompt.chars,
            systemBytes = usage.systemPrompt.bytes,
            toolsBytes = usage.tools.bytes,
            skillsChars = usage.skills.chars,
            skillsBytes = usage.skills.bytes
        )
    }

    private fun combine(vararg items: JsonElement): JsonArray = JsonArray().apply {
        items.forEach { item ->
            if (item.isJsonArray) item.asJsonArray.forEach(::add) else add(item)
        }
    }

    private inline fun <reified T> invokeNoArg(instance: Any, name: String): T {
        val method = instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == 0 }
            .apply { isAccessible = true }
        return method.invoke(instance) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T = unsafe.allocateInstance(type) as T

    private fun putObject(instance: Any, fieldName: String, value: Any) {
        val field = instance.javaClass.getDeclaredField(fieldName)
        unsafe.putObject(instance, unsafe.objectFieldOffset(field), value)
    }

    private data class BaselineRow(
        val systemChars: Int,
        val systemBytes: Int,
        val toolsBytes: Int,
        val skillsChars: Int,
        val skillsBytes: Int
    )
}
