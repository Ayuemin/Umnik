from pathlib import Path
import re


def must_replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{label}: expected at least {count}, found {found}")
    return text.replace(old, new, count)


# Remove Umnik's explicit attachment limit and artificial context headroom/cap.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/ConversationContext.kt")
s = p.read_text()
s = s.replace("    private const val DEFAULT_WINDOW = 128_000\n    private const val MAX_WINDOW = 1_048_576\n\n", "")
s = must_replace(
    s,
    '''    fun checkTransferSize(attachments: List<PendingAttachment>) {
        val total = attachments.sumOf { attachment ->
            attachment.localPath?.let { File(it).takeIf(File::isFile)?.length() }
                ?: attachment.size.coerceAtLeast(0L)
        }
        require(total <= 32L * 1024 * 1024) {
            "Общий объём файлов в одном запросе превышает 32 МБ. Уберите часть файлов из чата или проекта."
        }
    }
''',
    '''    /** No Umnik size cap: provider/model/device limits are authoritative. */
    fun checkTransferSize(attachments: List<PendingAttachment>) = Unit
''',
    "ConversationContext transfer cap",
)
s = must_replace(
    s,
    '''        val window = (contextLength ?: DEFAULT_WINDOW).coerceIn(8_192, MAX_WINDOW)
        val inputBudget = ((window - outputTokens - 1_024).coerceAtLeast(0) * 0.85).toInt()
        val fixed = estimateTokens(systemPrompt).toLong() + estimateTokens(prompt) + attachmentTokens + 256
        require(fixed <= inputBudget) {
            "Инструкции, запрос и файлы превышают контекстное окно модели. Уберите часть файлов из чата/проекта или выберите модель с большим контекстом."
        }

        val completed = completedTextTurns(history)
''',
    '''        val completed = completedTextTurns(history)
        // Unknown window: send the complete stored conversation and let the provider decide.
        // Known window: trim only the oldest completed turns. Fixed prompt/files are never
        // blocked by Umnik; provider/model limits remain authoritative.
        val inputBudget = contextLength?.takeIf { it > 0 }?.toLong() ?: return completed
        val fixed = estimateTokens(systemPrompt).toLong() + estimateTokens(prompt) + attachmentTokens + 256
''',
    "ConversationContext context policy",
)
p.write_text(s)

# OpenRouter: no client max_tokens and no transfer-size rejection.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
s = p.read_text()
s = must_replace(
    s,
    '''        ConversationContext.checkTransferSize(attachments)
        val outputTokens = (if (reasoningEnabled || modelInfo?.reasoningMandatory == true) 12_000 else 8_000)
            .coerceAtMost(modelInfo?.maxCompletionTokens ?: 12_000)
        val selectedHistory = ConversationContext.select(
            history, systemPrompt, prompt, ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength, outputTokens
        )''',
    '''        val selectedHistory = ConversationContext.select(
            history, systemPrompt, prompt, ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength, 0
        )''',
    "OpenRouter output budget",
)
s = s.replace(
    'DiagnosticLog.record(context, "CONTEXT", "OpenRouter model=$model; stored=${history.size}; sent=${selectedHistory.size}; window=${modelInfo?.contextLength ?: 128_000}; output=$outputTokens; attachments=${attachments.size}")',
    'DiagnosticLog.record(context, "CONTEXT", "OpenRouter model=$model; stored=${history.size}; sent=${selectedHistory.size}; window=${modelInfo?.contextLength ?: "provider"}; output=provider; attachments=${attachments.size}")',
)
s = must_replace(s, '                addProperty("max_tokens", outputTokens)\n', "", "OpenRouter max_tokens")
p.write_text(s)

# Batch follows the same provider-owned limit policy.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterBatchBodyBuilder.kt")
s = p.read_text()
s = must_replace(
    s,
    '''        ConversationContext.checkTransferSize(attachments)
        val outputTokens = (if (reasoningEnabled || modelInfo?.reasoningMandatory == true) 12_000 else 8_000)
            .coerceAtMost(modelInfo?.maxCompletionTokens ?: 12_000)
        val selectedHistory = ConversationContext.select(
            history,
            systemPrompt,
            prompt,
            ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength,
            outputTokens
        )''',
    '''        val selectedHistory = ConversationContext.select(
            history,
            systemPrompt,
            prompt,
            ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength,
            0
        )''',
    "Batch output budget",
)
s = must_replace(s, '            addProperty("max_tokens", outputTokens)\n', "", "Batch max_tokens")
p.write_text(s)

# Compatible/NVIDIA chat: provider owns output size too.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt")
s = p.read_text()
s = s.replace("            ConversationContext.checkTransferSize(attachments)\n", "")
s = must_replace(
    s,
    '''            val selectedHistory = ConversationContext.select(
                history, systemPrompt, prompt, ConversationContext.attachmentTokens(attachments),
                modelInfo?.contextLength, if (isNvidia) 8_192 else 4_096
            )''',
    '''            val selectedHistory = ConversationContext.select(
                history, systemPrompt, prompt, ConversationContext.attachmentTokens(attachments),
                modelInfo?.contextLength, 0
            )''',
    "Compatible context budget",
)
s = s.replace('                if (!isNvidia) addProperty("max_tokens", 4096)\n', "")
p.write_text(s)

# Composer Batch shortcut currently opens the old bulk screen. Remove it: selecting a :batch
# model and pressing the normal Send button is the canonical single-request flow.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
s = p.read_text()
pattern = re.compile(
    r'''\n\s*ComposerActionTile\(\n\s*icon = Icons\.Outlined\.SwapHoriz,\n\s*label = "Batch",\n\s*enabled = !state\.isLoading && !imagePromptMode && \(state\.currentChatTextModel \?: state\.textModel\)\.endsWith\(":batch", ignoreCase = true\),\n\s*modifier = Modifier\.weight\(1f\),\n\s*onClick = \{\n\s*actionsOpen = false\n\s*com\.ayuemin\.ymnik\.AsyncJobEvents\.requestHub\("jobs"\)\n\s*\}\n\s*\)'''
)
s, n = pattern.subn("", s, count=1)
if n != 1:
    raise SystemExit(f"YmnikApp Batch shortcut: expected 1, removed {n}")
p.write_text(s)

# Hub Jobs remains the advanced multi-request tool and now says so explicitly.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt")
s = p.read_text()
s = s.replace('Text("Umnik 1.6.2 · полный каталог и возможности"', 'Text("Umnik 1.6.4 · полный каталог и возможности"')
s = s.replace(
    'Text("Пакетная обработка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)',
    'Text("Пакет из нескольких заданий", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)',
)
marker = 'Text("Модель: ${state.media.batchModel.ifBlank { "не выбрана" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)'
addition = marker + '\n                Text("Один Batch-запрос отправляйте прямо из обычного чата кнопкой отправки. Этот экран нужен только для нескольких независимых заданий.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)'
s = must_replace(s, marker, addition, "OpenRouterHub batch help")
p.write_text(s)

# Regression tests for the no-client-cap policy.
p = Path("app/src/test/java/com/ayuemin/ymnik/network/ConversationContextTest.kt")
s = p.read_text().replace("import org.junit.Assert.assertTrue\n", "")
s = must_replace(
    s,
    '''    @Test fun oversizedFixedContextFailsClearly() {
        val error = runCatching {
            ConversationContext.select(emptyList(), "", "привет", 80_000, 32_000, 8_000)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("файлы"))
    }
''',
    '''    @Test fun oversizedFixedContextIsSentToProviderWithoutClientRejection() {
        val selected = ConversationContext.select(emptyList(), "", "привет", 80_000, 32_000, 8_000)
        assertEquals(emptyList<ChatMessage>(), selected)
    }

    @Test fun unknownProviderWindowKeepsCompleteHistory() {
        val history = (1..90).flatMap { listOf(user(it), answer(it)) }
        val selected = ConversationContext.select(history, "инструкция", "вопрос", 0, null, 0)
        assertEquals(180, selected.size)
    }
''',
    "ConversationContextTest cap regression",
)
p.write_text(s)

# Release metadata.
p = Path("app/build.gradle.kts")
s = p.read_text().replace("// Umnik v1.6.3", "// Umnik v1.6.4", 1)
s = s.replace("versionCode = 63", "versionCode = 64", 1).replace('versionName = "1.6.3"', 'versionName = "1.6.4"', 1)
p.write_text(s)

p = Path("CHANGELOG.md")
s = p.read_text()
heading = "## Unreleased\n\n"
section = '''## v1.6.4 - 2026-09-14

- Убраны искусственные лимиты Umnik на 8K/12K выходных токенов OpenRouter и 4K для совместимых API: приложение больше не отправляет свой `max_tokens` и оставляет лимит ответа модели/провайдеру.
- Убран потолок контекстного окна Umnik в 1 048 576 токенов и 85-процентный запас. При известном окне модели сокращается только самая старая завершённая история; фиксированный запрос и файлы клиент больше не блокирует.
- Полностью снят лимит Umnik в 32 МБ на суммарный размер вложений. Ограничения памяти Android и самого API/провайдера по-прежнему могут применяться.
- Batch-модель больше не имеет отдельной кнопки «Batch» в меню `+`: для одного Batch-запроса используется обычное поле чата, вложения и стандартная кнопка отправки.
- Hub → «Задания» теперь явно обозначен как инструмент только для нескольких независимых Batch-заданий.
- Версия: 1.6.4 / versionCode 64.

'''
if heading not in s:
    raise SystemExit("CHANGELOG Unreleased heading not found")
p.write_text(s.replace(heading, heading + section, 1))

print("v1.6.4 patch applied")
