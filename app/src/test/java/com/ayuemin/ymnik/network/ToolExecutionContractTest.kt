package com.ayuemin.ymnik.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionContractTest {
    @Test
    fun noLocalExecutionToolsMeansNoExtraPrompt() {
        val text = ToolExecutionContract.prompt(
            localWebFetchAvailable = false,
            localBrowserAvailable = false,
            localShellAvailable = false
        )
        assertTrue(text.isBlank())
    }

    @Test
    fun availableHandsGetUserIntentTranslationContract() {
        val text = ToolExecutionContract.prompt(
            localWebFetchAvailable = false,
            localBrowserAvailable = true,
            localShellAvailable = true
        )

        assertTrue(text.contains("Browser"))
        assertTrue(text.contains("Local Shell"))
        assertFalse(text.contains("Fetch"))
        assertTrue(text.contains("Для обычного вопроса отвечай как обычно"))
        assertTrue(text.contains("единственная точка связи пользователя"))
        assertTrue(text.contains("однозначное самодостаточное задание"))
        assertTrue(text.contains("Не придумывай неизвестное"))
        assertTrue(text.contains("критерий достаточности"))
        assertTrue(text.contains("с исходным намерением пользователя"))
        assertTrue(text.contains("Если нужных рук для действия нет"))
    }

    @Test
    fun localFetchBecomesFallbackWhenServerFetchIsAvailable() {
        val text = ToolExecutionContract.prompt(
            localWebFetchAvailable = true,
            localBrowserAvailable = true,
            localShellAvailable = false
        )

        assertTrue(text.contains("openrouter:web_fetch"))
        assertTrue(text.contains("сначала используй его"))
        assertTrue(text.contains("local_web_fetch — резерв"))
        assertTrue(text.contains("Не вызывай оба Fetch без необходимости"))
    }

    @Test
    fun requestEnhancerInjectsContractOnlyFromActualLocalToolDefinitions() {
        val source = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/network/OpenRouterRequestEnhancer.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRequestEnhancer.kt")
        ).first { it.isFile }.readText()

        val apply = source.indexOf("applyToolExecutionContract(payload)")
        val usage = source.indexOf("ContextUsageTracker.capture(requestId, payload)")
        assertTrue(apply >= 0)
        assertTrue(usage > apply)
        assertTrue(source.contains("\"local_web_fetch\" in names"))
        assertTrue(source.contains("it.startsWith(\"local_browser_\")"))
        assertTrue(source.contains("it.startsWith(\"local_shell_\")"))
        assertTrue(source.contains("if (contract.isBlank()) return"))
        assertTrue(source.contains("systemMessage.addProperty(\"content\", original + \"\\n\\n\" + contract)"))
        assertTrue(source.contains("\"TOOL_CONTRACT\""))
    }
}
