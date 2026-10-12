package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUserToolAuthorityTest {
    @Test fun untrustedResultsNeverGrantShell() {
        val authority = DirectUserToolAuthority.fromDirectMessage("Перескажи этот рассказ")
        assertFalse(authority.permits("local_shell_start"))
        assertFalse(authority.permits("remote_ssh_execute"))
    }

    @Test fun explicitFileWorkCanStartShell() {
        val authority = DirectUserToolAuthority.fromDirectMessage("Создай zip архив с Python проектом")
        assertTrue(authority.permits("local_shell_start"))
        assertFalse(authority.permits("remote_ssh_execute"))
    }

    @Test fun browserIntentDoesNotGrantShellOrSsh() {
        val authority = DirectUserToolAuthority.fromDirectMessage("Открой https://example.org и прочитай статью")
        assertTrue(authority.permits("local_browser_open"))
        assertFalse(authority.permits("local_shell_start"))
        assertFalse(authority.permits("remote_ssh_execute"))
    }

    @Test fun sshIsHardDisabledEvenOnDirectUserText() {
        val authority = DirectUserToolAuthority.fromDirectMessage("SSH на сервере, выполни команду")
        assertFalse(authority.permits("remote_ssh_execute"))
    }
}
