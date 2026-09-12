from pathlib import Path


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(path: Path, old: str, new: str, expected: int):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")

root = Path(__file__).resolve().parents[2]
models = root / "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
client = root / "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
vm = root / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
ui = root / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
build = root / "app/build.gradle.kts"
changelog = root / "CHANGELOG.md"

# Model/state
replace_once(
    models,
    "enum class ProviderType {\n    OPENROUTER,\n    OPENAI_COMPATIBLE\n}\n\ndata class ConnectionProfile(",
    "enum class ProviderType {\n    OPENROUTER,\n    OPENAI_COMPATIBLE\n}\n\ndata class ProviderUsage(\n    val providerName: String,\n    val daily: Double,\n    val weekly: Double,\n    val monthly: Double,\n    val total: Double,\n    val updatedAt: Long = System.currentTimeMillis()\n)\n\ndata class ConnectionProfile("
)
replace_once(
    models,
    "    val modelCatalogConnectionId: String? = null,\n    val modelCatalog: List<ModelInfo> = emptyList(),\n    val answerSoundEnabled: Boolean = true,",
    "    val modelCatalogConnectionId: String? = null,\n    val modelCatalog: List<ModelInfo> = emptyList(),\n    val providerUsage: ProviderUsage? = null,\n    val answerSoundEnabled: Boolean = true,"
)

# OpenRouter usage endpoint
replace_once(
    client,
    "    data class Result(val text: String, val files: List<GeneratedFile>)\n",
    "    data class Result(val text: String, val files: List<GeneratedFile>)\n\n    data class KeyUsage(\n        val daily: Double,\n        val weekly: Double,\n        val monthly: Double,\n        val total: Double\n    )\n"
)
replace_once(
    client,
    "    suspend fun imageModels(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {\n        getModelInfos(apiKey, endpoint(baseUrl, \"images/models\"))\n    }\n\n    private fun getModelInfos(apiKey: String, url: String): List<ModelInfo> {",
    "    suspend fun imageModels(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): List<ModelInfo> = withContext(Dispatchers.IO) {\n        getModelInfos(apiKey, endpoint(baseUrl, \"images/models\"))\n    }\n\n    suspend fun keyUsage(apiKey: String, baseUrl: String = DEFAULT_BASE_URL): KeyUsage = withContext(Dispatchers.IO) {\n        val request = Request.Builder()\n            .url(endpoint(baseUrl, \"key\"))\n            .header(\"Authorization\", \"Bearer $apiKey\")\n            .header(\"X-Title\", \"Umnik Android\")\n            .get()\n            .build()\n        http.newCall(request).execute().use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            val data = root.getAsJsonObject(\"data\") ?: error(\"OpenRouter не вернул статистику ключа\")\n            fun value(name: String): Double = runCatching {\n                data.get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0\n            }.getOrDefault(0.0)\n            KeyUsage(\n                daily = value(\"usage_daily\"),\n                weekly = value(\"usage_weekly\"),\n                monthly = value(\"usage_monthly\"),\n                total = value(\"usage\")\n            )\n        }\n    }\n\n    private fun getModelInfos(apiKey: String, url: String): List<ModelInfo> {"
)

# ViewModel wiring
replace_once(
    vm,
    "import com.ayuemin.ymnik.model.ProjectFile\nimport com.ayuemin.ymnik.model.ProviderType\n",
    "import com.ayuemin.ymnik.model.ProjectFile\nimport com.ayuemin.ymnik.model.ProviderType\nimport com.ayuemin.ymnik.model.ProviderUsage\n"
)
replace_once(
    vm,
    "    init {\n        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()\n    }",
    "    init {\n        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()\n        refreshProviderUsage()\n    }"
)
replace_once(
    vm,
    "    private fun refreshModelCapabilities() {\n",
    "    fun refreshProviderUsage() {\n        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }\n            ?: return\n        if (profile.id in _state.value.disabledConnectionIds || !isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(providerUsage = null)\n            return\n        }\n        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        viewModelScope.launch {\n            runCatching { api.keyUsage(key, profile.baseUrl) }\n                .onSuccess { usage ->\n                    _state.value = _state.value.copy(\n                        providerUsage = ProviderUsage(\n                            providerName = \"OpenRouter\",\n                            daily = usage.daily,\n                            weekly = usage.weekly,\n                            monthly = usage.monthly,\n                            total = usage.total\n                        )\n                    )\n                }\n        }\n    }\n\n    private fun refreshModelCapabilities() {\n"
)
replace_count(
    vm,
    "                playReadySound()\n            }.onFailure {",
    "                playReadySound()\n                if (profile.type == ProviderType.OPENROUTER) refreshProviderUsage()\n            }.onFailure {",
    2
)
# Refresh when OpenRouter connection details/key are saved.
replace_once(
    vm,
    "        if ((active || profileId == _state.value.imageConnectionProfileId) && profileId !in _state.value.disabledConnectionIds && isProfileConfigured(updated)) refreshModelCapabilities()\n    }",
    "        if ((active || profileId == _state.value.imageConnectionProfileId) && profileId !in _state.value.disabledConnectionIds && isProfileConfigured(updated)) refreshModelCapabilities()\n        if (updated.type == ProviderType.OPENROUTER) refreshProviderUsage()\n    }"
)

# UI: formatter and compact model label + usage chip/dialog
replace_once(
    ui,
    "private fun imageParameterSummary(state: UiState): String =\n    listOfNotNull(state.imageAspectRatio, state.imageResolution)\n        .ifEmpty { listOf(\"Авто\") }\n        .joinToString(\" · \")\n",
    "private fun imageParameterSummary(state: UiState): String =\n    listOfNotNull(state.imageAspectRatio, state.imageResolution)\n        .ifEmpty { listOf(\"Авто\") }\n        .joinToString(\" · \")\n\nprivate fun formatUsd(value: Double): String =\n    \"$\" + \"%.2f\".format(Locale.US, value.coerceAtLeast(0.0))\n"
)
replace_once(
    ui,
    "    var quickModelsOpen by remember { mutableStateOf(false) }\n    var hubOpen by remember { mutableStateOf(false) }\n    val activeTextModel = state.currentChatTextModel ?: state.textModel\n",
    "    var quickModelsOpen by remember { mutableStateOf(false) }\n    var hubOpen by remember { mutableStateOf(false) }\n    var usageOpen by remember { mutableStateOf(false) }\n    val activeProfile = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }\n    val activeUsage = state.providerUsage?.takeIf { activeProfile?.type == ProviderType.OPENROUTER }\n    val activeTextModel = state.currentChatTextModel ?: state.textModel\n"
)
replace_once(
    ui,
    "            Box(modifier = Modifier.weight(1f)) {\n                TextButton(\n                    onClick = { quickModelsOpen = true },\n                    enabled = !state.isLoading,\n                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)\n                ) {",
    "            Row(\n                modifier = Modifier.weight(1f),\n                verticalAlignment = Alignment.CenterVertically\n            ) {\n                Box(modifier = Modifier.weight(1f)) {\n                TextButton(\n                    onClick = { quickModelsOpen = true },\n                    enabled = !state.isLoading,\n                    modifier = Modifier.fillMaxWidth(),\n                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)\n                ) {"
)
replace_once(
    ui,
    "                        style = MaterialTheme.typography.titleMedium,\n                        fontWeight = FontWeight.SemiBold,",
    "                        style = MaterialTheme.typography.titleSmall,\n                        fontWeight = FontWeight.SemiBold,"
)
replace_once(
    ui,
    "                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = \"Выбрать модель\", modifier = Modifier.size(22.dp))",
    "                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = \"Выбрать модель\", modifier = Modifier.size(20.dp))"
)
replace_once(
    ui,
    "                }\n            }\n\n            IconButton(onClick = onNewChat, enabled = !state.isLoading, modifier = Modifier.size(42.dp)) {",
    "                }\n                }\n                activeUsage?.let { usage ->\n                    Spacer(Modifier.width(4.dp))\n                    Surface(\n                        onClick = {\n                            usageOpen = true\n                            vm.refreshProviderUsage()\n                        },\n                        shape = RoundedCornerShape(10.dp),\n                        color = MaterialTheme.colorScheme.surfaceContainerHigh\n                    ) {\n                        Text(\n                            \"Сегодня ${formatUsd(usage.daily)}\",\n                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),\n                            style = MaterialTheme.typography.labelSmall,\n                            color = MaterialTheme.colorScheme.onSurfaceVariant,\n                            maxLines = 1\n                        )\n                    }\n                }\n            }\n\n            IconButton(onClick = onNewChat, enabled = !state.isLoading, modifier = Modifier.size(42.dp)) {"
)
replace_once(
    ui,
    "        }\n    }\n}\n\n@Composable\nprivate fun ComposerActionTile(",
    "        }\n    }\n\n    if (usageOpen && activeUsage != null) {\n        AlertDialog(\n            onDismissRequest = { usageOpen = false },\n            title = { Text(activeUsage.providerName) },\n            text = {\n                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                    listOf(\n                        \"Сегодня\" to activeUsage.daily,\n                        \"Неделя\" to activeUsage.weekly,\n                        \"Месяц\" to activeUsage.monthly,\n                        \"Всего этим ключом\" to activeUsage.total\n                    ).forEach { (label, value) ->\n                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {\n                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                            Text(formatUsd(value), fontWeight = FontWeight.SemiBold)\n                        }\n                    }\n                    Text(\n                        \"Периоды OpenRouter считаются по UTC. Данные берутся напрямую для текущего API-ключа.\",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                }\n            },\n            confirmButton = {\n                TextButton(onClick = { usageOpen = false }) { Text(\"Закрыть\") }\n            },\n            dismissButton = {\n                TextButton(onClick = { vm.refreshProviderUsage() }) {\n                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))\n                    Spacer(Modifier.width(5.dp))\n                    Text(\"Обновить\")\n                }\n            }\n        )\n    }\n}\n\n@Composable\nprivate fun ComposerActionTile("
)

# Version and changelog
replace_once(build, "// Umnik v1.2.0", "// Umnik v1.2.1-beta.1")
replace_once(build, "        versionCode = 37\n        versionName = \"1.2.0\"", "        versionCode = 38\n        versionName = \"1.2.1-beta.1\"")
replace_once(
    changelog,
    "## Unreleased\n\n## v1.2.0 - 2026-09-11",
    "## Unreleased\n\n## v1.2.1-beta.1 - 2026-09-12\n\n- Рядом с выбранной моделью OpenRouter добавлен компактный показатель расходов за сегодня.\n- По нажатию показываются расходы текущего API-ключа за день, неделю, месяц и всё время.\n- Статистика берётся напрямую из `GET /api/v1/key`, поэтому не обнуляется при закрытии или обновлении Umnik.\n- Показатель обновляется при запуске приложения, после успешного запроса через OpenRouter и вручную из окна статистики.\n- Название выбранной модели в верхней панели сделано компактнее, чтобы модель, статистика и кнопки не толкались на узком экране.\n\n## v1.2.0 - 2026-09-11"
)

print("Provider usage beta patch applied")
