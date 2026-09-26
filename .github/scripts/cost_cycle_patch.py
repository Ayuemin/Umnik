from pathlib import Path

ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")

ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace(
    '    var serviceCostsOpen by remember(message.id) { mutableStateOf(false) }\n',
    ''
)
old_cost_block = '''                val costs = message.costBreakdown
                if (costs != null) {
                    costs.primaryUsd?.let {
                        AnswerInfoRow("Основной ответ", formatExactUsd(it))
                    }

                    if (costs.systemCalls + costs.embeddingCalls > 0) {
                        TextButton(
                            onClick = { serviceCostsOpen = !serviceCostsOpen },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text("Служебные операции")
                            costs.serviceUsd?.let {
                                Text(
                                    "  " + formatExactUsd(it),
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                            Icon(
                                if (serviceCostsOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (serviceCostsOpen) {
                            if (costs.systemCalls > 0) {
                                AnswerInfoRow(
                                    "Системная модель",
                                    costs.systemUsd?.let(::formatExactUsd) ?: "Стоимость не определена"
                                )
                                AnswerInfoRow("Вызовов системы", costs.systemCalls.toString())
                            }
                            if (costs.embeddingCalls > 0) {
                                AnswerInfoRow(
                                    "Embeddings",
                                    costs.embeddingsUsd?.let(::formatExactUsd) ?: "Стоимость не определена"
                                )
                                AnswerInfoRow("Embeddings-вызовов", costs.embeddingCalls.toString())
                            }
                        }
                    }

                    costs.knownTotalUsd?.let {
                        AnswerInfoRow(
                            if (costs.incomplete) "Учтено" else "Итого за ответ",
                            formatExactUsd(it)
                        )
                    }
                    if (costs.incomplete) {
                        Text(
                            "OpenRouter не сообщил стоимость части операций, поэтому показана только точно известная сумма.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                } else {
                    message.costUsd?.takeIf { it >= 0.0 }?.let {
                        AnswerInfoRow("Стоимость", formatAnswerCost(it))
                    }
                }
'''
new_cost_block = '''                val costs = message.costBreakdown
                if (costs != null) {
                    costs.knownTotalUsd?.let {
                        AnswerInfoRow(
                            if (costs.incomplete) "Учтено за запрос" else "Стоимость запроса",
                            formatExactUsd(it)
                        )
                    }
                    if (costs.incomplete) {
                        Text(
                            "OpenRouter не сообщил стоимость части операций, поэтому показана только точно известная сумма.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                } else {
                    message.costUsd?.takeIf { it >= 0.0 }?.let {
                        AnswerInfoRow("Стоимость запроса", formatAnswerCost(it))
                    }
                }
'''
if old_cost_block not in ui:
    raise SystemExit("AnswerInfo cost block not found")
ui = ui.replace(old_cost_block, new_cost_block, 1)
ui_path.write_text(ui, encoding="utf-8")

vm = vm_path.read_text(encoding="utf-8")
import_anchor = 'import com.ayuemin.ymnik.model.ProviderUsage\n'
if 'import com.ayuemin.ymnik.model.RequestCostBreakdown\n' not in vm:
    if import_anchor not in vm:
        raise SystemExit("RequestCostBreakdown import anchor not found")
    vm = vm.replace(import_anchor, import_anchor + 'import com.ayuemin.ymnik.model.RequestCostBreakdown\n', 1)

call_old = '''                                        startLocalShellFromChat(
                                            chatId = chatId,
                                            taskRaw = task,
'''
call_new = '''                                        startLocalShellFromChat(
                                            chatId = chatId,
                                            originUserMessageId = user.id,
                                            taskRaw = task,
'''
if call_old not in vm:
    raise SystemExit("Local Shell call anchor not found")
vm = vm.replace(call_old, call_new, 1)

sig_old = '''    private suspend fun startLocalShellFromChat(
        chatId: String,
        taskRaw: String,
'''
sig_new = '''    private suspend fun startLocalShellFromChat(
        chatId: String,
        originUserMessageId: String? = null,
        taskRaw: String,
'''
if sig_old not in vm:
    raise SystemExit("Local Shell signature anchor not found")
vm = vm.replace(sig_old, sig_new, 1)

success_old = '''            }.onSuccess { result ->
                val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                val files = engine.exportedFiles()
                val assistant = ChatMessage(
'''
success_new = '''            }.onSuccess { result ->
                val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                val files = engine.exportedFiles()
                var parentWaits = 0
                while (RequestExecutionManager.hasActiveChat(chatId) && parentWaits < 100) {
                    delay(50L)
                    parentWaits += 1
                }
                val combinedCost = combinedLocalShellCost(
                    chatId = chatId,
                    originUserMessageId = originUserMessageId,
                    shellCostUsd = result.costUsd
                )
                val assistant = ChatMessage(
'''
if success_old not in vm:
    raise SystemExit("Local Shell success anchor not found")
vm = vm.replace(success_old, success_new, 1)

assistant_old = '''                    connectionName = profile.name,
                    requestId = "local-shell:" + taskId
                )
'''
assistant_new = '''                    connectionName = profile.name,
                    requestId = "local-shell:" + taskId,
                    costBreakdown = combinedCost
                )
'''
if assistant_old not in vm:
    raise SystemExit("Local Shell assistant cost anchor not found")
vm = vm.replace(assistant_old, assistant_new, 1)

helper_anchor = '''    private fun localShellStatusForChat(chatId: String): String {
'''
helper = '''    private fun combinedLocalShellCost(
        chatId: String,
        originUserMessageId: String?,
        shellCostUsd: Double?
    ): RequestCostBreakdown? {
        val messages = chatsRepository.list().firstOrNull { it.id == chatId }?.messages.orEmpty()
        val originIndex = originUserMessageId
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        val parentAssistant = originIndex?.let { index ->
            messages.drop(index + 1).firstOrNull { message ->
                message.role == "assistant" && !message.requestId.orEmpty().startsWith("local-shell:")
            }
        }
        val parentBreakdown = parentAssistant?.costBreakdown
        val parentAmount = parentBreakdown?.knownTotalUsd
            ?.let { raw -> runCatching { java.math.BigDecimal(raw) }.getOrNull() }
            ?: parentAssistant?.costUsd
                ?.takeIf { it >= 0.0 }
                ?.let(java.math.BigDecimal::valueOf)
        val shellAmount = shellCostUsd
            ?.takeIf { it >= 0.0 }
            ?.let(java.math.BigDecimal::valueOf)
        val total = listOfNotNull(parentAmount, shellAmount)
            .takeIf { it.isNotEmpty() }
            ?.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
            ?: return null
        val exact = total.stripTrailingZeros().let { value ->
            if (value.compareTo(java.math.BigDecimal.ZERO) == 0) "0" else value.toPlainString()
        }
        return RequestCostBreakdown(
            knownTotalUsd = exact,
            incomplete = parentBreakdown?.incomplete == true || shellCostUsd == null
        )
    }

'''
if helper_anchor not in vm:
    raise SystemExit("Local Shell helper anchor not found")
vm = vm.replace(helper_anchor, helper + helper_anchor, 1)
vm_path.write_text(vm, encoding="utf-8")
