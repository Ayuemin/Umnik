package com.ayuemin.ymnik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOutputPolicyTest {
    @Test
    fun explicitLongDashBanIsDetected() {
        assertTrue(ProjectOutputPolicy.forbidsLongDash("Запрещено применять длинное тире (дефис - норма)."))
        assertTrue(ProjectOutputPolicy.forbidsLongDash("Пиши без длинного тире."))
    }

    @Test
    fun unrelatedPromptDoesNotEnableReplacement() {
        assertFalse(ProjectOutputPolicy.forbidsLongDash("Используй тире там, где это уместно."))
    }

    @Test
    fun longAndMediumDashesAreReplacedWhenExplicitlyForbidden() {
        assertEquals(
            "Один - два - три",
            ProjectOutputPolicy.apply("Один — два – три", "Запрещено использовать длинное тире")
        )
    }
}
