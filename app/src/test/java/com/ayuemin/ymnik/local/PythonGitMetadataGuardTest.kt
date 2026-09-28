package com.ayuemin.ymnik.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellPythonGuardRegressionTest {
    @Test
    fun `python low level open flags cannot bypass git metadata guard`() {
        val source = sequenceOf(
            File("src/main/python/umnik_local_runtime.py"),
            File("app/src/main/python/umnik_local_runtime.py")
        ).first { it.isFile }.readText()
        assertTrue(source.contains("_WRITE_FLAGS"))
        assertTrue(source.contains("flags & _WRITE_FLAGS"))
        assertTrue(source.contains("_reject_git_write"))
        assertTrue(source.contains("os.mknod"))
    }
}
