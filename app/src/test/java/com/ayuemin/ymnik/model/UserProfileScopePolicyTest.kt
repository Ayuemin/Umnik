package com.ayuemin.ymnik.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileScopePolicyTest {
    @Test fun chatsScopeAppliesOnlyToOrdinaryChats() {
        assertTrue(userProfileApplies(UserProfileScope.CHATS, inTeam = false, isSpecialist = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inTeam = true, isSpecialist = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inTeam = true, isSpecialist = true))
    }

    @Test fun offNeverApplies() {
        assertFalse(userProfileApplies(UserProfileScope.OFF, inTeam = false, isSpecialist = false))
        assertFalse(userProfileApplies(UserProfileScope.OFF, inTeam = true, isSpecialist = true))
    }
}
