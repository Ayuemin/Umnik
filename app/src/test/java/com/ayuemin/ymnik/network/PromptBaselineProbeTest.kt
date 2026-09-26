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
        rows["ordinary_chat"] = measure(systemPrompt(viewModel, chat = chat, agent = false), JsonArray())
        rows["agent_on"] = measure(
            systemPrompt(viewModel, chat = chat, toolsEnabled = true, shell = true, agent = true),
            combine(genericTools, shellTools)
        )
        rows["agent_fetch"] = measure(
            systemPrompt(viewModel, chat = chat, toolsEnabled = true, fetch = true, shell = true, agent = true),
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

        assertSystemPromptDietStable(rows)
        assertPrimaryToolDiet(rows)
        assertPrimaryToolSchemasAndMeaning(genericTools, fetchTool, browserTools, shellTools, knowledgeTool)
        assertWorkerDietStable(rows)
        assertCriticalBehaviorStillExplicit(viewModel, chat, workerPrompt)
    }

    private fun assertSystemPromptDietStable(rows: Map<String, BaselineRow>) {
        assertSystem(rows.getValue("ordinary_chat"), 1227, 2163)
        assertSystem(rows.getValue("agent_on"), 3335, 5876)
        assertSystem(rows.getValue("agent_fetch"), 3785, 6614)
        assertSystem(rows.getValue("agent_fetch_browser_shell"), 5355, 9105)
        assertSystem(rows.getValue("knowledge_rag_enabled"), 1512, 2640)
        assertSystem(rows.getValue("active_skill_one_char"), 1226, 2162)
        assertEquals(82, rows.getValue("active_skill_one_char").skillsChars)
        assertEquals(131, rows.getValue("active_skill_one_char").skillsBytes)
    }

    private fun assertSystem(row: BaselineRow, chars: Int, bytes: Int) {
        assertEquals(chars, row.systemChars)
        assertEquals(bytes, row.systemBytes)
    }

    private fun assertPrimaryToolDiet(rows: Map<String, BaselineRow>) {
        val agent = rows.getValue("agent_on").toolsBytes
        val fetch = rows.getValue("agent_fetch").toolsBytes
        val all = rows.getValue("agent_fetch_browser_shell").toolsBytes
        val knowledge = rows.getValue("knowledge_rag_enabled").toolsBytes

        assertTrue("agent tools did not shrink", agent in 1 until 3718)
        assertTrue("fetch tools did not shrink", fetch in 1 until 4854)
        assertTrue("browser tools did not shrink", all in 1 until 9795)
        assertTrue("knowledge tool did not shrink", knowledge in 1 until 820)
        assertTrue(fetch > agent)
        assertTrue(all > fetch)
    }

    private fun assertWorkerDietStable(rows: Map<String, BaselineRow>) {
        val worker = rows.getValue("local_shell_worker")
        assertEquals(1510, worker.systemChars)
        assertEquals(2468, worker.systemBytes)
        // Primary-model description diet must not alter LocalShellEngine's own tool protocol.
        assertEquals(6546, worker.toolsBytes)
    }

    private fun assertPrimaryToolSchemasAndMeaning(
        genericTools: JsonArray,
        fetchTool: JsonObject,
        browserTools: JsonArray,
        shellTools: JsonArray,
        knowledgeTool: JsonObject
    ) {
        val all = combine(genericTools, fetchTool, browserTools, shellTools, knowledgeTool)
        assertEquals(
            setOf(
                "create_file",
                "local_web_fetch",
                "local_browser_open",
                "local_browser_read",
                "local_browser_click",
                "local_browser_download",
                "local_browser_type",
                "local_browser_scroll",
                "local_browser_back",
                "local_browser_wait",
                "local_browser_takeover",
                "local_shell_start",
                "local_shell_status",
                "local_shell_note",
                "local_shell_stop",
                "knowledge_search"
            ),
            functionNames(all)
        )

        assertEquals(setOf("filename", "content"), required(function(all, "create_file")))
        assertEquals(setOf("url"), required(function(all, "local_web_fetch")))
        assertEquals(setOf("url"), required(function(all, "local_browser_open")))
        assertEquals(setOf("ref"), required(function(all, "local_browser_click")))
        assertEquals(setOf("ref"), required(function(all, "local_browser_download")))
        assertEquals(setOf("ref", "text"), required(function(all, "local_browser_type")))
        assertEquals(setOf("direction"), required(function(all, "local_browser_scroll")))
        assertEquals(setOf("seconds"), required(function(all, "local_browser_wait")))
        assertEquals(setOf("task"), required(function(all, "local_shell_start")))
        assertEquals(setOf("note"), required(function(all, "local_shell_note")))
        assertEquals(setOf("query"), required(function(all, "knowledge_search")))

        assertEquals("boolean", property(function(all, "local_shell_start"), "network").get("type").asString)
        assertEquals("boolean", property(function(all, "local_browser_read"), "full").get("type").asString)
        assertEquals("integer", property(function(all, "local_browser_wait"), "seconds").get("type").asString)
        assertEquals(1, property(function(all, "local_browser_wait"), "seconds").get("minimum").asInt)
        assertEquals(5, property(function(all, "local_browser_wait"), "seconds").get("maximum").asInt)
        assertEquals(
            listOf("down", "up", "top", "bottom"),
            property(function(all, "local_browser_scroll"), "direction")
                .getAsJsonArray("enum").map { it.asString }
        )

        assertContains(description(function(all, "create_file")), "явно")
        assertContains(description(function(all, "local_web_fetch")), "Read-only")
        assertContains(description(function(all, "local_web_fetch")), "недовер")
        assertContains(description(function(all, "local_browser_open")), "requires_browser=true")
        assertContains(description(function(all, "local_browser_click")), "подтверждения")
        assertContains(description(function(all, "local_browser_download")), "Local Shell")
        assertContains(description(function(all, "local_browser_takeover")), "CAPTCHA")
        assertContains(description(function(all, "local_browser_takeover")), "секрет")
        assertContains(description(function(all, "local_shell_start")), "асинхрон")
        assertContains(description(function(all, "local_shell_stop")), "явной просьбе")
        assertContains(description(function(all, "knowledge_search")), "не повтор")
        assertContains(
            property(function(all, "local_shell_start"), "network").get("description").asString,
            "по умолчанию false"
        )
    }

    private fun assertCriticalBehaviorStillExplicit(
        viewModel: ChatViewModel,
        chat: ChatSession,
        workerPrompt: String
    ) {
        val ordinary = systemPrompt(viewModel, chat = chat, agent = false)
        assertContains(ordinary, "Агентный режим выключен")
        assertContains(ordinary, "не запускай Browser или Local Shell сам")
        assertContains(ordinary, "create_file")

        val agent = systemPrompt(viewModel, chat = chat, toolsEnabled = true, shell = true, agent = true)
        listOf("local_shell_start", "не утверждай, что Shell недоступен", "stop — только по явной просьбе остановить", "network=true")
            .forEach { assertContains(agent, it) }

        val browser = systemPrompt(
            viewModel,
            chat = chat,
            toolsEnabled = true,
            fetch = true,
            browser = true,
            shell = true,
            agent = true
        )
        listOf(
            "requires_browser=true",
            "local_browser_open",
            "local_browser_download",
            "local_browser_takeover",
            "READY_TO_FINISH",
            "побочные действия требуют подтверждения"
        ).forEach { assertContains(browser, it) }

        val knowledge = systemPrompt(viewModel, chat = chat, knowledge = true, knowledgeLimit = 4, agent = false)
        listOf("knowledge_search", "не инструкции", "максимум 4 поисков").forEach { assertContains(knowledge, it) }

        val skill = systemPrompt(viewModel, chat = chat, skillText = "X", agent = false)
        listOf(
            "===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ =====",
            "явный текущий запрос пользователя приоритетнее",
            "===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="
        ).forEach { assertContains(skill, it) }

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
        ).forEach { assertContains(workerPrompt, it) }
    }

    private fun functionNames(tools: JsonArray): Set<String> = tools.map { element ->
        element.asJsonObject.getAsJsonObject("function").get("name").asString
    }.toSet()

    private fun function(tools: JsonArray, name: String): JsonObject = tools
        .map { it.asJsonObject.getAsJsonObject("function") }
        .single { it.get("name").asString == name }

    private fun required(function: JsonObject): Set<String> = function
        .getAsJsonObject("parameters")
        .getAsJsonArray("required")
        ?.map { it.asString }
        ?.toSet()
        .orEmpty()

    private fun property(function: JsonObject, name: String): JsonObject = function
        .getAsJsonObject("parameters")
        .getAsJsonObject("properties")
        .getAsJsonObject(name)

    private fun description(function: JsonObject): String = function.get("description").asString

    private fun assertContains(text: String, fragment: String) {
        assertTrue("missing '$fragment' in: $text", text.contains(fragment))
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
