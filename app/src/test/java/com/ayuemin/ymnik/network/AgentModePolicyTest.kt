package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.InternetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModePolicyTest {
    @Test
    fun `agent off keeps AUTO browser unavailable`() {
        assertFalse(AgentModePolicy.browserRequested(false, true, InternetMode.AUTO, "Расскажи о проекте"))
    }

    @Test
    fun `agent on lets AUTO use browser`() {
        assertTrue(AgentModePolicy.browserRequested(true, true, InternetMode.AUTO, "Найди информацию"))
    }

    @Test
    fun `explicit browser mode remains an explicit permission`() {
        assertTrue(AgentModePolicy.browserRequested(false, true, InternetMode.BROWSER, "Найди информацию"))
    }

    @Test
    fun `direct browser instruction works with agent off`() {
        assertTrue(AgentModePolicy.browserRequested(false, false, InternetMode.AUTO, "Открой в браузере эту страницу"))
    }

    @Test
    fun `direct shell instruction works with agent off`() {
        assertTrue(AgentModePolicy.shellRequested(false, "Запусти Local Shell и проверь файл"))
    }

    @Test
    fun `discussion about shell does not itself grant permission`() {
        assertFalse(AgentModePolicy.shellRequested(false, "Что такое Local Shell и зачем он нужен?"))
    }
}
