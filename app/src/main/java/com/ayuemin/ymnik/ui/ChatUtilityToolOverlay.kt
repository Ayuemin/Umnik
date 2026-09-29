package com.ayuemin.ymnik.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel

/**
 * Keeps the compact chat utility controller alive while the app is on screen.
 * Requests for STT/Speech/Video/Batch are routed here by AsyncJobEvents so the
 * tools use the same floating chrome as Local Shell instead of the old
 * full-screen Hub presentation.
 */
@Composable
internal fun ChatUtilityToolOverlay(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val appState by viewModel.state.collectAsState()
    UserFontStore.initialize(context.applicationContext)
    val userFontState by UserFontStore.state.collectAsState()
    val request by AsyncJobEvents.chatUtilityRequest.collectAsState()
    val sequence by AsyncJobEvents.sequence.collectAsState()
    val controller = remember(viewModel) {
        OpenRouterHubController(context.applicationContext, viewModel)
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    LaunchedEffect(sequence) {
        if (sequence > 0L) {
            viewModel.refreshAsyncResults()
            controller.refreshJobs()
        }
    }

    val tool = when (request) {
        "stt", "transcription" -> ChatUtilityTool.TRANSCRIPTION
        "speech", "tts" -> ChatUtilityTool.SPEECH
        "video" -> ChatUtilityTool.VIDEO
        "jobs", "batch" -> ChatUtilityTool.BATCH
        else -> null
    }

    if (tool != null) {
        UmnikTheme(
            choice = appState.themeChoice,
            customColor = appState.customThemeColor,
            customFontPath = userFontState.selected?.localPath
        ) {
            ChatUtilityToolPanelHost(
                tool = tool,
                controller = controller,
                onDismiss = AsyncJobEvents::consumeChatUtilityRequest
            )
        }
    }
}
