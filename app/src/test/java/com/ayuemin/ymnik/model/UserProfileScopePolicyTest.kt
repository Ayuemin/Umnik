package com.ayuemin.ymnik.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileScopePolicyTest {
    @Test fun chatsScopeAppliesOnlyToOrdinaryChats() {
        assertTrue(userProfileApplies(UserProfileScope.CHATS, inProject = false, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inProject = true, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inProject = true, isAgent = true))
    }

    @Test fun offNeverApplies() {
        assertFalse(userProfileApplies(UserProfileScope.OFF, inProject = false, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.OFF, inProject = true, isAgent = true))
    }
}
