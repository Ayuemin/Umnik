package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.local.LocalShellEngine
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.UiState
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files

class PromptBaselineProbeTest {
    private val unsafe: Unsafe by lazy {
        Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    }

    @Test
    fun printPreDietPromptBaselines() {
        val viewModel = allocate(ChatViewModel::class.java)
        putObject(viewModel, "_state", MutableStateFlow(UiState()))
        val chat = ChatSession(id = "baseline", title = "Baseline")

        val client = allocate(OpenRouterClient::class.java)
        val fetchTool = invokeNoArg<JsonObject>(client, "localWebFetchTool")
        val browserTools = invokeNoArg<JsonArray>(client, "localBrowserTools")
        val shellTools = invokeNoArg<JsonArray>(client, "localShellTools")
        val knowledgeTool = invokeNoArg<JsonObject>(client, "knowledgeSearchTool")

        val rows = linkedMapOf<String, BaselineRow>()
        rows["ordinary_chat"] = measure(
            system = systemPrompt(viewModel, chat = chat, agent = false),
            tools = JsonArray()
        )
        rows["agent_on"] = measure(
            system = systemPrompt(viewModel, chat = chat, shell = true, agent = true),
            tools = combine(shellTools)
        )
        rows["agent_fetch"] = measure(
            system = systemPrompt(viewModel, chat = chat, fetch = true, shell = true, agent = true),
            tools = combine(fetchTool, shellTools)
        )
        rows["agent_fetch_browser_shell"] = measure(
            system = systemPrompt(viewModel, chat = chat, fetch = true, browser = true, shell = true, agent = true),
            tools = combine(fetchTool, browserTools, shellTools)
        )
        rows["knowledge_rag_enabled"] = measure(
            system = systemPrompt(viewModel, chat = chat, knowledge = true, knowledgeLimit = 4, agent = false),
            tools = combine(knowledgeTool)
        )
        rows["active_skill_one_char"] = measure(
            system = systemPrompt(viewModel, chat = chat, skillText = "X", agent = false),
            tools = JsonArray()
        )

        val engine = allocate(LocalShellEngine::class.java)
        val tempRoot = Files.createTempDirectory("umnik-prompt-baseline").toFile().canonicalFile
        putObject(engine, "root", tempRoot)
        val workerPrompt = localShellWorkerPrompt(viewModel, engine, maxTurns = 500)
        val workerTools = engine.toolDefinitions()
        rows["local_shell_worker"] = measure(workerPrompt, workerTools)
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

        assertTrue(rows.values.all { it.systemChars > 0 && it.systemBytes > 0 })
        assertTrue(rows["agent_on"]!!.toolsBytes > 0)
        assertTrue(rows["agent_fetch_browser_shell"]!!.toolsBytes > rows["agent_fetch"]!!.toolsBytes)
        assertTrue(rows["active_skill_one_char"]!!.skillsBytes > 1)
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
