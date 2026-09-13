package com.ayuemin.ymnik

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectOutputPolicyTest {
    @Test
    fun outputIsNotModifiedBasedOnProjectInstructions() {
        val text = "Any model response stays unchanged."
        assertEquals(text, ProjectOutputPolicy.apply(text, "Any project instruction"))
        assertEquals(text, ProjectOutputPolicy.apply(text, null))
    }
}
