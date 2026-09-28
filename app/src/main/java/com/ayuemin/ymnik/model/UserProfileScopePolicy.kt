package com.ayuemin.ymnik.model

internal fun userProfileApplies(
    scope: UserProfileScope,
    inTeam: Boolean,
    isSpecialist: Boolean
): Boolean =
    scope == UserProfileScope.CHATS && !inTeam && !isSpecialist
