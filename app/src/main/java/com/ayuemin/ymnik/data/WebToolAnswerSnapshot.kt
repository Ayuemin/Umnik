package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.ServerToolSettings

internal fun snapshotWebToolAnswerMetadata(
    message: ChatMessage,
    settings: ServerToolSettings
): ChatMessage {
    if (message.webSearchEnabled != true || message.internetMode == InternetMode.BROWSER.name) return message

    return message.copy(
        webSearchPreset = settings.webSearchPreset.name,
        webSearchEngine = settings.webSearchEngine.name,
        webFetchEngine = settings.webFetchEngine.name
    )
}
