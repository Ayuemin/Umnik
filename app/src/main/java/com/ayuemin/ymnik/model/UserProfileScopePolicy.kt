package com.ayuemin.ymnik.model

internal fun userProfileApplies(
    scope: UserProfileScope,
    inProject: Boolean,
    isAgent: Boolean
): Boolean =
    scope == UserProfileScope.CHATS && !inProject && !isAgent
