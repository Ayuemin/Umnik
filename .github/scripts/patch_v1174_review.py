from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def enable_user_stream_on_main_send(path: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    anchor = "val preparedContext = chatMemoryManager.prepare("
    if text.count(anchor) != 1:
        raise SystemExit(f"{path}: expected one preparedContext anchor, found {text.count(anchor)}")
    anchor_pos = text.index(anchor)
    call_pos = text.index("requestApi.chat(", anchor_pos)
    open_pos = text.index("(", call_pos)

    depth = 0
    quote = None
    escaped = False
    close_pos = None
    i = open_pos
    while i < len(text):
        ch = text[i]
        if quote is not None:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
        else:
            if ch in ('"', "'"):
                quote = ch
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    close_pos = i
                    break
        i += 1
    if close_pos is None:
        raise SystemExit(f"{path}: could not find closing parenthesis for user-facing requestApi.chat")

    segment = text[call_pos:close_pos]
    if "preparedContext.history" not in segment or "streamToUi" in segment:
        raise SystemExit(f"{path}: unexpected user-facing chat segment")

    line_start = text.rfind("\n", 0, close_pos) + 1
    indent = text[line_start:close_pos]
    if indent.strip():
        raise SystemExit(f"{path}: expected closing parenthesis on its own line")
    insertion = ",\n" + indent + "    streamToUi = true"
    text = text[:close_pos] + insertion + text[close_pos:]
    p.write_text(text, encoding="utf-8")


# Only the final answer intended for the current user chat should publish a live preview.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(
    path,
    "        toolsEnabled: Boolean = true,\n        baseUrl: String = DEFAULT_BASE_URL,\n        modelInfo: ModelInfo? = null\n    ): Result = withContext(Dispatchers.IO) {\n",
    "        toolsEnabled: Boolean = true,\n        baseUrl: String = DEFAULT_BASE_URL,\n        modelInfo: ModelInfo? = null,\n        streamToUi: Boolean = false\n    ): Result = withContext(Dispatchers.IO) {\n",
)
replace_once(
    path,
    "            streamCallback(\"\")\n            val completion = requestCompletion(apiKey, baseUrl, payload, allowEmpty = created.isNotEmpty())\n",
    "            if (streamToUi) streamCallback(\"\")\n            val completion = requestCompletion(\n                apiKey, baseUrl, payload, allowEmpty = created.isNotEmpty(), streamToUi = streamToUi\n            )\n",
)
replace_once(
    path,
    "    private suspend fun requestCompletion(apiKey: String, baseUrl: String, payload: JsonObject, allowEmpty: Boolean): OpenRouterResponseParser.Completion {\n",
    "    private suspend fun requestCompletion(\n        apiKey: String,\n        baseUrl: String,\n        payload: JsonObject,\n        allowEmpty: Boolean,\n        streamToUi: Boolean = false\n    ): OpenRouterResponseParser.Completion {\n",
)
old_stream = '''                        var streamAnnounced = false
                        OpenRouterStreamParser.parse(responseBody.source(), allowEmpty) { partial ->
                            if (!streamAnnounced && partial.isNotBlank()) {
                                streamAnnounced = true
                                phaseCallback("Получаю ответ…")
                            }
                            streamCallback(partial)
                        }.also { parsed ->
                            val finalText = extractText(parsed.message.get("content"))
                            if (finalText.isNotBlank()) streamCallback(finalText)
                        }
'''
new_stream = '''                        var streamAnnounced = false
                        val preview = StringBuilder()
                        var lastPreviewAt = 0L
                        var lastPreviewLength = 0
                        OpenRouterStreamParser.parse(responseBody.source(), allowEmpty) { delta ->
                            if (streamToUi && delta.isNotEmpty()) {
                                preview.append(delta)
                                val now = SystemClock.elapsedRealtime()
                                val shouldPublish = lastPreviewLength == 0 ||
                                    now - lastPreviewAt >= STREAM_PREVIEW_INTERVAL_MS ||
                                    preview.length - lastPreviewLength >= STREAM_PREVIEW_MIN_CHARS
                                if (shouldPublish) {
                                    if (!streamAnnounced) {
                                        streamAnnounced = true
                                        phaseCallback("Получаю ответ…")
                                    }
                                    streamCallback(preview.toString())
                                    lastPreviewAt = now
                                    lastPreviewLength = preview.length
                                }
                            }
                        }.also { parsed ->
                            if (streamToUi) {
                                val finalText = extractText(parsed.message.get("content"))
                                if (finalText.isNotBlank() && finalText.length != lastPreviewLength) {
                                    if (!streamAnnounced) phaseCallback("Получаю ответ…")
                                    streamCallback(finalText)
                                }
                            }
                        }
'''
replace_once(path, old_stream, new_stream)
replace_once(
    path,
    "        private const val RECOVERY_WINDOW_MS = 120_000L\n",
    "        private const val RECOVERY_WINDOW_MS = 120_000L\n        private const val STREAM_PREVIEW_INTERVAL_MS = 120L\n        private const val STREAM_PREVIEW_MIN_CHARS = 96\n",
)

enable_user_stream_on_main_send("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")

# The parser emits deltas. The UI layer decides when to materialize the cumulative preview.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterStreamParser.kt",
    "                appendPiece(text, piece)\n                onText(text.toString())\n",
    "                appendPiece(text, piece)\n                onText(piece)\n",
)
replace_once(
    "app/src/test/java/com/ayuemin/ymnik/network/OpenRouterStreamParserTest.kt",
    "        assertEquals(listOf(\"Привет\", \"Привет мир\"), updates)\n",
    "        assertEquals(listOf(\"Привет\", \" мир\"), updates)\n",
)

# Follow a growing streamed response only while its bottom remains near the viewport.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
old_effect = '''    LaunchedEffect(state.currentChatId, state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) {
            delay(180)
            if (isUsageGuide) listState.scrollToItem(0) else listState.scrollToItem(state.messages.size)
        }
    }

'''
new_effect = old_effect + '''    val streamFollowThresholdPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    LaunchedEffect(streamingText.length) {
        if (streamingText.isBlank() || isUsageGuide) return@LaunchedEffect
        val layout = listState.layoutInfo
        val total = layout.totalItemsCount
        val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val streamOrEndVisible = lastVisible.index >= (total - 2).coerceAtLeast(0)
        val bottomDistance = (lastVisible.offset + lastVisible.size - layout.viewportEndOffset).coerceAtLeast(0)
        if (streamOrEndVisible && bottomDistance <= streamFollowThresholdPx && total > 0) {
            listState.scrollToItem(total - 1)
        }
    }

'''
replace_once(path, old_effect, new_effect)

print("v1.17.4 review fixes applied")
