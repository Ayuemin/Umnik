package com.ayuemin.ymnik.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * Guards model-facing tool capabilities without pinning prompt wording or byte counts.
 *
 * Prompts and descriptions may be shortened freely. This test only protects schema and
 * a few critical semantics that the model must still know about.
 */
class ToolPromptContractTest {
    private val unsafe: Unsafe by lazy {
        Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    }

    @Test
    fun primaryToolContractsKeepCriticalCapabilities() {
        val client = allocate(OpenRouterClient::class.java)
        val genericTools = invokeNoArg<JsonArray>(client, "tools")
        val fetchTool = invokeNoArg<JsonObject>(client, "localWebFetchTool")
        val browserTools = invokeNoArg<JsonArray>(client, "localBrowserTools")
        val shellTools = invokeNoArg<JsonArray>(client, "localShellTools")
        val knowledgeTool = invokeNoArg<JsonObject>(client, "knowledgeSearchTool")
        val all = combine(genericTools, fetchTool, browserTools, shellTools, knowledgeTool)

        val names = functionNames(all)
        assertTrue(
            names.containsAll(
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
                )
            )
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
        assertEquals(15, property(function(all, "local_browser_wait"), "seconds").get("maximum").asInt)
        assertEquals("boolean", property(function(all, "local_browser_type"), "submit").get("type").asString)

        val waitModes = property(function(all, "local_browser_wait"), "mode")
            .getAsJsonArray("enum").map { it.asString }.toSet()
        assertTrue(waitModes.containsAll(setOf("dom_stable", "selector_present", "text_present", "ref_present")))

        assertContains(description(function(all, "local_web_fetch")), "Read-only")
        assertContains(description(function(all, "local_web_fetch")), "недовер")
        assertContains(description(function(all, "local_browser_open")), "requires_browser=true")
        assertContains(description(function(all, "local_browser_download")), "Local Shell")
        assertContains(description(function(all, "local_browser_read")), "DOM dump")
        assertContains(description(function(all, "local_browser_type")), "submit=true")
        assertContains(description(function(all, "local_browser_takeover")), "CAPTCHA")
        assertContains(description(function(all, "local_browser_takeover")), "секрет")
        assertContains(description(function(all, "local_shell_start")), "асинхрон")
        assertContains(
            property(function(all, "local_shell_start"), "network").get("description").asString,
            "умолчанию false"
        )
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
}
