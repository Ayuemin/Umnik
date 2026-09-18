package com.ayuemin.ymnik.model

internal fun userProfileApplies(
    scope: UserProfileScope,
    inProject: Boolean,
    isAgent: Boolean
): Boolean = when (scope) {
    UserProfileScope.OFF -> false
    UserProfileScope.CHATS -> !inProject && !isAgent
    UserProfileScope.PROJECTS -> inProject
    UserProfileScope.EVERYWHERE -> true
}
