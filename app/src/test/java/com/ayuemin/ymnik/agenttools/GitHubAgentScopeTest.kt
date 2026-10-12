package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitHubAgentScopeTest {
    @Test fun enabledGithubNeedsTrustedChatAndTurn() {
        val g = GitHubAgentScope.fromTrustedUiMessage(
            "chat-1", "msg-1", "Разработай Android приложение и собери APK",
            "Ayuemin/SampleApp", true, 1_000L
        )
        assertNotNull(g)
        assertTrue(g!!.mayWrite)
        assertTrue(g.allows("chat-1", "msg-1", "Ayuemin/SampleApp", true, 2_000))
        assertFalse(g.allows("chat-2", "msg-1", "Ayuemin/SampleApp", true, 2_000))
        assertFalse(g.allows("chat-1", "msg-2", "Ayuemin/SampleApp", true, 2_000))
        assertFalse(g.allows("chat-1", "msg-1", "Other/SampleApp", true, 2_000))
        assertFalse(g.allows("chat-1", "msg-1", "Ayuemin/SampleApp", true, 3_700_000))
    }

    @Test fun readOnlyDirectQuestionsCannotMutate() {
        val g = GitHubAgentScope.fromTrustedUiMessage("c", "m",
            "Что находится в репозитории?", "Ayuemin/SampleApp", true, 0)!!
        assertFalse(g.mayWrite)
        assertTrue(g.allows("c", "m", "Ayuemin/SampleApp", false, 100))
        assertFalse(g.allows("c", "m", "Ayuemin/SampleApp", true, 100))
        assertNull(GitHubAgentScope.fromTrustedUiMessage("c", "m", "сделай приложение",
            "Ayuemin/SampleApp", false, 0))
    }

    @Test fun blankRepositoryPermitsInitialCreationScope() {
        val g = GitHubAgentScope.fromTrustedUiMessage("c", "m", "Создай приложение",
            "", true, 1L)
        assertNotNull(g)
        assertEquals("", g!!.allowedRepository)
        assertTrue(g.mayWrite)
    }

    @Test fun onlyWorkBranchesCanBeWritten() {
        for (b in listOf("main", "master", "stable", "release/v1.0",
            "work/../main", "work//foo", "work/branch.lock", "work/")) {
            assertFalse(b, GitHubAgentScope.isSafeWorkingBranch(b))
        }
        for (b in listOf("work/feature-1", "agent/new-app", "feature/login", "fix/gradle")) {
            assertTrue(b, GitHubAgentScope.isSafeWorkingBranch(b))
        }
    }

    @Test fun rejectsPathTraversalAndSecrets() {
        for (path in listOf("../secrets", "/root/.ssh", "app/../../secret",
            "app//build.gradle.kts", "app\\gradle", "")) {
            assertFalse(path, GitHubAgentScope.isSafeFilePath(path))
        }
        for (path in listOf(".env", "config/.env.production", "id_rsa",
            "app/signing.keystore", "private.pem")) {
            assertTrue(path, GitHubAgentScope.isPrivateFile(path))
        }
        assertTrue(GitHubAgentScope.isSafeFilePath("app/src/main/AndroidManifest.xml"))
    }

    @Test fun adapterIsConnectedToBothToolRoutesAndChat() {
        fun source(path: String): String = sequenceOf(File(path), File("app/$path")).first {
            it.isFile
        }.readText()
        val vm = source("src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
        val client = source("src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
        val settings = source("src/main/java/com/ayuemin/ymnik/ui/SettingsScreen.kt")
        assertTrue(vm.contains("GitHubAgentScope.fromTrustedUiMessage("))
        assertTrue(vm.contains("githubTool.execute(\"repo\""))
        assertTrue(vm.contains("githubTool.execute(\"actions\""))
        assertTrue(vm.contains("persistGitHubApk("))
        assertTrue(client.contains("githubTools().forEach"))
        assertTrue(client.contains("\"github_repo\" ->"))
        assertTrue(client.contains("\"github_actions\" ->"))
        assertTrue(settings.contains("SettingsCategory.GITHUB ->"))
    }
}
