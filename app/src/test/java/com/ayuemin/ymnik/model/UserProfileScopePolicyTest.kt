package com.ayuemin.ymnik.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileScopePolicyTest {
    @Test fun chatsOnlyAppliesOnlyToOrdinaryChats() {
        assertTrue(userProfileApplies(UserProfileScope.CHATS, inProject = false, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inProject = true, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.CHATS, inProject = true, isAgent = true))
    }

    @Test fun projectsAppliesToProjectAgentsAndProjectChats() {
        assertTrue(userProfileApplies(UserProfileScope.PROJECTS, inProject = true, isAgent = true))
        assertTrue(userProfileApplies(UserProfileScope.PROJECTS, inProject = true, isAgent = false))
        assertFalse(userProfileApplies(UserProfileScope.PROJECTS, inProject = false, isAgent = false))
    }

    @Test fun everywhereIncludesAgentsAndOrdinaryChats() {
        assertTrue(userProfileApplies(UserProfileScope.EVERYWHERE, inProject = false, isAgent = false))
        assertTrue(userProfileApplies(UserProfileScope.EVERYWHERE, inProject = true, isAgent = true))
    }
}
