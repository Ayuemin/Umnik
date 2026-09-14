from pathlib import Path


def load(path): return Path(path).read_text(encoding="utf-8")
def save(path, text): Path(path).write_text(text, encoding="utf-8")
def one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)
def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0: raise SystemExit(f"{label}: start marker missing")
    b = text.find(end, a)
    if b < 0: raise SystemExit(f"{label}: end marker missing")
    return text[:a] + replacement + text[b:]

p = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
s = load(p)
start = '''            item {
                val enabledCount = state.connectionProfiles.count { it.id !in state.disabledConnectionIds }
                ExpandableSettingsCard(
                    title = "OpenRouter",
'''
end = '''            item {
                ExpandableSettingsCard(
                    title = "Диагностика и логи",
'''
replacement = r'''            item {
                val openRouterSettingsProfile = state.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }
                    ?: state.connectionProfiles.first()
                ExpandableSettingsCard(
                    title = "OpenRouter",
                    subtitle = "API-ключ и соединение",
                    icon = Icons.Outlined.Language,
                    expanded = connectionsExpanded,
                    onToggle = { connectionsExpanded = !connectionsExpanded }
                ) {
                    Text(
                        "Umnik работает через OpenRouter. Здесь настраивается единственное подключение приложения; выбор моделей находится в разделе «Модели».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = connectionKey,
                        onValueChange = { connectionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API-ключ OpenRouter") },
                        placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveApiKey(connectionKey.takeIf { it.isNotBlank() })
                            connectionKey = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Сохранить API-ключ")
                    }
                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { vm.checkConnection(openRouterSettingsProfile.id) },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Проверить подключение")
                    }
                    Spacer(Modifier.height(5.dp))
                    TextButton(
                        onClick = { connectionAdvancedExpanded = !connectionAdvancedExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (connectionAdvancedExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Технические настройки OpenRouter")
                    }
                    if (connectionAdvancedExpanded) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Автоматический адрес API")
                                Text(
                                    "Рекомендуется. Umnik использует актуальный стандартный адрес OpenRouter.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = connectionUseProviderDefaults,
                                onCheckedChange = { connectionUseProviderDefaults = it }
                            )
                        }
                        if (!connectionUseProviderDefaults) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = connectionUrl,
                                onValueChange = { connectionUrl = it.trim().take(300) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Адрес API OpenRouter") },
                                placeholder = { Text("https://openrouter.ai/api/v1") },
                                singleLine = true
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = connectionContextWindow,
                            onValueChange = { value -> connectionContextWindow = value.filter(Char::isDigit).take(7) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ручной предел контекста, токенов") },
                            placeholder = { Text("Необязательно · обычно определяется по модели") },
                            singleLine = true
                        )
                        Text(
                            "Оставьте поле пустым, если не требуется вручную ограничивать контекст.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Spacer(Modifier.height(9.dp))
                        FilledTonalButton(
                            onClick = {
                                vm.saveConnectionProfile(
                                    profileId = openRouterSettingsProfile.id,
                                    name = "OpenRouter",
                                    baseUrl = connectionUrl,
                                    apiKey = connectionKey.takeIf { it.isNotBlank() },
                                    imageEnabled = true,
                                    imageBaseUrl = null,
                                    imageProtocol = ImageApiProtocol.AUTO,
                                    useSameImageApiKey = true,
                                    imageApiKey = null,
                                    useProviderDefaults = connectionUseProviderDefaults,
                                    contextLimitTokens = connectionContextWindow.toIntOrNull()
                                )
                                connectionKey = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сохранить технические настройки") }
                    }
                    Text(
                        "API-ключ хранится локально и шифруется через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Памятка по использованию Umnik", fontWeight = FontWeight.Bold)
                                Text(
                                    "Короткие сценарии по возможностям приложения",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Откроется в новом чате. Памятка встроена в Umnik и не расходует API при открытии.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = {
                                vm.openUsageGuide()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Открыть памятку в новом чате")
                        }
                    }
                }
            }

'''
s = between(s, start, end, replacement, "OpenRouter-only settings")
s = one(s,
    '                        "Когда запись выключена, она практически не влияет на работу приложения. В лог не пишутся тексты сообщений, содержимое файлов и API-ключи: сохраняются технические события, модель, адрес сервиса без параметров, HTTP-код, время запроса и текст ошибки.",\n',
    '                        "Для ручной проверки журнал фиксирует сетевые стадии, действия, выбранную модель, типы вложений, фоновые задания и возврат результатов в чат. Тексты сообщений, содержимое файлов и API-ключи не записываются. Журнал хранит до ~8 МБ последних событий.",\n',
    "diagnostic copy")
save(p, s)

p = "app/build.gradle.kts"
s = load(p)
s = one(s, "// Umnik v1.8.0", "// Umnik v1.9.0", "version comment")
s = one(s, "versionCode = 80", "versionCode = 90", "version code")
s = one(s, 'versionName = "1.8.0"', 'versionName = "1.9.0"', "version name")
save(p, s)

p = "CHANGELOG.md"
s = load(p)
notes = '''## Unreleased

## v1.9.0 - 2026-09-14

- Настройки подключения окончательно упрощены до одного OpenRouter: убраны добавление других провайдеров, включение/выключение подключения и отдельный Image API. В обычном интерфейсе остаются API-ключ OpenRouter, проверка соединения и технические параметры OpenRouter.
- В «Настройки» добавлена «Памятка по использованию Umnik». Она локально открывается в новом чате, не вызывает модель и содержит короткие сценарии для чата, файлов, Vision, изображений, reasoning, web search, STT, TTS, видео, Batch, Shell, проектов, навыков, RAG и диагностики.
- Диагностический журнал расширен с 1 МБ до 8 МБ, получил идентификатор сессии и последовательные номера событий, а при исключениях сохраняет stack trace с очисткой секретов.
- В диагностику добавлены действия пользователя без содержимого сообщений: новый/выбранный чат, вложения, камера, голос, reasoning, web search и запуск расширенных возможностей OpenRouter.
- Логи явно фиксируют возврат результатов Speech/STT/Shell в исходный чат, а фоновый worker отмечает доставку Batch и Video в чат.
- Версия: 1.9.0 / versionCode 90.

'''
s = one(s, "## Unreleased\n\n", notes, "changelog")
save(p, s)

Path("tools/patch_v1_9_0_ui.py").unlink(missing_ok=True)
