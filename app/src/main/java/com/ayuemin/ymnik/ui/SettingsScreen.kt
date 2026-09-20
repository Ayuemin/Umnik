package com.ayuemin.ymnik.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.ThemeChoice
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.UserProfileScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun SkillLibrarySettings(state: UiState, vm: ChatViewModel) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importSkillFile)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::importSkillTree)
    }
    var quickSkillText by remember { mutableStateOf("") }

    OutlinedTextField(
        value = quickSkillText,
        onValueChange = { quickSkillText = it.take(12_000) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Короткий навык") },
        placeholder = { Text("Например: отвечай кратко, без канцелярита, с примерами") },
        minLines = 3,
        maxLines = 6
    )
    Spacer(Modifier.height(7.dp))
    FilledTonalButton(
        onClick = {
            vm.createTextSkill(quickSkillText)
            quickSkillText = ""
        },
        enabled = quickSkillText.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Spacer(Modifier.width(7.dp))
        Text("Создать короткий навык")
    }
    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) {
            Icon(Icons.Outlined.Description, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Файл")
        }
        FilledTonalButton(onClick = { treePicker.launch(null) }) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Папка")
        }
    }
    Spacer(Modifier.height(9.dp))
    if (state.skills.isEmpty()) {
        Text("Пока навыков нет. Импортируйте SKILL.md или папку навыка.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        state.skills.forEach { skill ->
            UmnikPanel(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Файлов: ${skill.files.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                    }
                }
            }
        }
    }
}

private enum class SettingsCategory(val title: String, val subtitle: String) {
    CONNECTION("Подключение", "API-ключ OpenRouter"),
    MODELS("Модели", "Чат, изображения, reasoning и речь"),
    CONTEXT("Чаты и контекст", "Память, навыки и профиль"),
    INTERFACE("Интерфейс", "Оформление и звук"),
    DATA("Данные", "Локальное хранилище и файлы"),
    ABOUT("Диагностика и о приложении", "Логи, памятка и версия")
}

private fun settingsCategoryIcon(category: SettingsCategory): ImageVector = when (category) {
    SettingsCategory.CONNECTION -> Icons.Outlined.Language
    SettingsCategory.MODELS -> Icons.Outlined.TextFields
    SettingsCategory.CONTEXT -> Icons.Outlined.Description
    SettingsCategory.INTERFACE -> Icons.Outlined.Palette
    SettingsCategory.DATA -> Icons.Outlined.Storage
    SettingsCategory.ABOUT -> Icons.Outlined.Settings
}

@Composable
private fun SettingsCategoryCard(category: SettingsCategory, onClick: () -> Unit) {
    UmnikPanel(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(UmnikPanelPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(settingsCategoryIcon(category), contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }
    var settingsCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var storageOpen by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var defaultChatModelExpanded by remember { mutableStateOf(false) }
    var quickModelsExpanded by remember { mutableStateOf(false) }
    var imageModelsExpanded by remember { mutableStateOf(false) }
    var imageParametersOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    var skillsLibraryExpanded by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    var openRouterSpeechExpanded by remember { mutableStateOf(false) }
    var openRouterDocumentSpeechExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var connectionsExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var diagnosticLoggingEnabled by remember { mutableStateOf(vm.isDiagnosticLoggingEnabled()) }
    var diagnosticLogBytes by remember { mutableStateOf(vm.diagnosticLogSize()) }
    var diagnosticFileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    val editingProfile = state.connectionProfiles.first()
    var connectionKey by remember { mutableStateOf("") }
    var connectionAdvancedExpanded by remember { mutableStateOf(false) }
    var connectionContextWindow by remember(editingProfile.contextLimitTokens) {
        mutableStateOf(editingProfile.contextLimitTokens?.toString().orEmpty())
    }
    var customColorText by remember(state.customThemeColor) {
        mutableStateOf("#%06X".format(state.customThemeColor and 0xFFFFFF))
    }
    var profileName by remember(state.userProfile.name) { mutableStateOf(state.userProfile.name) }
    var profileGender by remember(state.userProfile.gender) { mutableStateOf(state.userProfile.gender) }
    var profileAge by remember(state.userProfile.age) { mutableStateOf(state.userProfile.age) }
    var profileOccupation by remember(state.userProfile.occupation) { mutableStateOf(state.userProfile.occupation) }
    var profileNote by remember(state.userProfile.note) { mutableStateOf(state.userProfile.note) }
    val themes = ThemeChoice.entries
    val importedSounds = state.storedFiles.filter { it.category == "Звуки" }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAnswerSound)
    }
    val diagnosticSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val file = diagnosticFileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        diagnosticFileToSave = null
        diagnosticLogBytes = vm.diagnosticLogSize()
    }

    Column(Modifier.fillMaxSize()) {
        val activeCategory = settingsCategory
        PinnedBackHeader(
            title = activeCategory?.title ?: "Настройки",
            onBack = {
                if (activeCategory == null) onBack() else settingsCategory = null
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (settingsCategory == null) {
                SettingsCategory.entries.forEach { category ->
                    item(key = category.name) {
                        SettingsCategoryCard(category = category, onClick = { settingsCategory = category })
                    }
                }
            } else {
                when (settingsCategory) {
                    SettingsCategory.CONNECTION -> {
                item {
                    val openRouterSettingsProfile = state.connectionProfiles.first()
                    ExpandableSettingsCard(
                        title = "OpenRouter",
                        subtitle = "API-ключ и соединение",
                        icon = Icons.Outlined.Language,
                        expanded = connectionsExpanded,
                        onToggle = { connectionsExpanded = !connectionsExpanded },
                        info = "Единственное сетевое подключение Umnik. Здесь хранится API-ключ OpenRouter и проверяется связь. Выбор моделей находится в отдельном разделе «Модели»."
                    ) {
                        OutlinedTextField(
                            value = connectionKey,
                            onValueChange = { connectionKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API-ключ OpenRouter") },
                            placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                            visualTransformation = PasswordVisualTransformation(),
                            trailingIcon = {
                                UmnikInfoHint(
                                    title = "API-ключ OpenRouter",
                                    text = "Личный API-ключ OpenRouter. Он хранится локально на устройстве и шифруется через Android Keystore. Пустое поле не заменяет уже сохранённый ключ."
                                )
                            },
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
                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(
                            onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("tools") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Language, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Инструменты и веб-поиск", fontWeight = FontWeight.Medium)
                                Text(
                                    "Сервис поиска и расширенные параметры OpenRouter",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
                            Text(
                                "Адрес API фиксирован на официальном OpenRouter. Здесь можно только вручную ограничить размер контекста для моделей, где это необходимо.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = connectionContextWindow,
                                onValueChange = { value -> connectionContextWindow = value.filter(Char::isDigit).take(7) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Ручной предел контекста, токенов") },
                                placeholder = { Text("Необязательно · обычно определяется по модели") },
                                trailingIcon = {
                                    UmnikInfoHint(
                                        title = "Ручной предел контекста",
                                        text = "Обычно Umnik получает размер контекста из данных модели. Укажите число только если конкретную модель нужно ограничить вручную. В остальных случаях оставьте поле пустым."
                                    )
                                },
                                singleLine = true
                            )
                            Spacer(Modifier.height(9.dp))
                            FilledTonalButton(
                                onClick = { vm.saveOpenRouterContextLimit(connectionContextWindow.toIntOrNull()) },
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

                    }
                    SettingsCategory.MODELS -> {
                item {
                    ExpandableSettingsCard(
                        title = "Модели",
                        subtitle = state.textModel.substringAfterLast('/'),
                        icon = Icons.Outlined.TextFields,
                        expanded = modelsExpanded,
                        onToggle = { modelsExpanded = !modelsExpanded },
                        info = "Здесь задаются модели приложения: основная модель чата, быстрые модели, генерация изображений и параметры размышления. Настройки отдельных агентов находятся внутри самих агентов."
                    ) {
                        FilledTonalButton(
                            onClick = { defaultChatModelExpanded = !defaultChatModelExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.TextFields, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Чат по умолчанию", fontWeight = FontWeight.Medium)
                                Text(
                                    state.textModel,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                if (defaultChatModelExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        if (defaultChatModelExpanded) {
                            if (state.textModel != "openrouter/auto") {
                                TextButton(
                                    onClick = { vm.selectDefaultTextModel("openrouter", "openrouter/auto") },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Сбросить модель чата на Auto") }
                            } else {
                                Text(
                                    "Для чата по умолчанию уже используется Auto.",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(
                            onClick = { quickModelsExpanded = !quickModelsExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Быстрые модели", fontWeight = FontWeight.Medium)
                                Text(
                                    if (state.quickTextModels.isEmpty()) "Не выбраны" else "Выбрано: ${state.quickTextModels.size}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Icon(
                                if (quickModelsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        if (quickModelsExpanded) {
                            if (state.quickTextModels.isEmpty()) {
                                Text(
                                    "Быстрые модели добавляются в «Каталог и модели OpenRouter».",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Spacer(Modifier.height(5.dp))
                                state.quickTextModels.forEach { ref ->
                                    val modelId = quickModelId(ref)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            modelId,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        TextButton(onClick = {
                                            vm.toggleQuickTextModelForConnection(quickModelConnectionId(ref), modelId)
                                        }) {
                                            Text("Убрать")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(
                            onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Каталог и модели OpenRouter", fontWeight = FontWeight.Medium)
                                Text(
                                    "Единое место выбора и назначения моделей",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                item {
                    val imageConnectionName = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"
                    ExpandableSettingsCard(
                        title = "Генерация изображений",
                        subtitle = "${state.imageModel.substringAfterLast('/').ifBlank { "не выбрана" }} · $imageConnectionName",
                        icon = Icons.Outlined.Image,
                        expanded = imageModelsExpanded,
                        onToggle = { imageModelsExpanded = !imageModelsExpanded },
                        info = "Модель и параметры, которые используются режимом генерации изображений. Эти настройки не меняют обычную текстовую модель чата."
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = UmnikItemShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Image, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Модель изображений", fontWeight = FontWeight.Medium)
                                    Text(
                                        state.imageModel.ifBlank { "Не выбрана" },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(
                            onClick = { imageParametersOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Параметры изображения", fontWeight = FontWeight.Medium)
                                Text(
                                    imageParameterSummary(state),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                item {
                    ReasoningSettingsCard(
                        state = state,
                        vm = vm,
                        expanded = reasoningExpanded,
                        onToggle = { reasoningExpanded = !reasoningExpanded }
                    )
                }

                item {
                    ExpandableSettingsCard(
                        title = "Озвучивание ответов OpenRouter",
                        subtitle = when {
                            state.openRouterSpeechModel.isBlank() -> "Модель не выбрана"
                            else -> buildList {
                                add(state.openRouterSpeechModel.substringAfterLast('/'))
                                if (state.openRouterSpeechVoice.isNotBlank()) add(state.openRouterSpeechVoice)
                                add(state.openRouterSpeechResponseFormat.ifBlank { "Авто" }.uppercase())
                            }.joinToString(" · ")
                        },
                        icon = Icons.Outlined.VolumeUp,
                        expanded = openRouterSpeechExpanded,
                        onToggle = { openRouterSpeechExpanded = !openRouterSpeechExpanded },
                        info = "Эта модель озвучивает уже готовые ответы нейросети по кнопке OR под сообщением. Она не используется для режима «+ → Озвучить»."
                    ) {
                        FilledTonalButton(
                            onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("reply-speech") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Настроить модель и параметры")
                        }
                    }
                }

                item {
                    ExpandableSettingsCard(
                        title = "Озвучивание текста и документов",
                        subtitle = "Отдельная модель и параметры",
                        icon = Icons.Outlined.Description,
                        expanded = openRouterDocumentSpeechExpanded,
                        onToggle = { openRouterDocumentSpeechExpanded = !openRouterDocumentSpeechExpanded },
                        info = "Отдельная настройка для режима «+ → Озвучить»: чтение введённого текста или документа. Не влияет на кнопку озвучивания готовых ответов."
                    ) {
                        FilledTonalButton(
                            onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть настройки озвучивания")
                        }
                    }
                }

                    }
                    SettingsCategory.CONTEXT -> {
                item { ChatMemoryGlobalSettingsSection(state, vm) }

                item {
                    ExpandableSettingsCard(
                        title = "Навыки",
                        subtitle = if (state.skills.isEmpty()) "Библиотека пуста" else "${state.skills.size} навыков",
                        icon = Icons.Outlined.Extension,
                        expanded = skillsLibraryExpanded,
                        onToggle = { skillsLibraryExpanded = !skillsLibraryExpanded },
                        info = "Общая библиотека навыков. Импортированный навык сам по себе не влияет на ответы: его нужно отдельно включить в нужном обычном чате. У агентов есть собственные навыки."
                    ) {
                        SkillLibrarySettings(state, vm)
                    }
                }

                item {
                    ExpandableSettingsCard(
                        title = "Коротко обо мне",
                        subtitle = if (state.userProfile.isEmpty()) "Не задано" else "Профиль заполнен · ${profileScopeLabel(state.userProfileScope)}",
                        icon = Icons.Outlined.Description,
                        expanded = profileExpanded,
                        onToggle = { profileExpanded = !profileExpanded },
                        info = "Необязательный краткий профиль пользователя. Он передаётся модели только в выбранной области. Проекты и агенты могут быть исключены, чтобы личный контекст не попадал туда автоматически."
                    ) {
                        OutlinedTextField(profileName, { profileName = it }, Modifier.fillMaxWidth(), label = { Text("Имя") }, singleLine = true)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(profileGender, { profileGender = it }, Modifier.weight(1f), label = { Text("Пол") }, singleLine = true)
                            OutlinedTextField(profileAge, { profileAge = it }, Modifier.weight(1f), label = { Text("Возраст") }, singleLine = true)
                        }
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(profileOccupation, { profileOccupation = it }, Modifier.fillMaxWidth(), label = { Text("Род занятий") }, singleLine = true)
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            profileNote,
                            { profileNote = it.take(240) },
                            Modifier.fillMaxWidth(),
                            label = { Text("Короткая установка") },
                            minLines = 2,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(9.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Использовать в обычных чатах", fontWeight = FontWeight.Medium)
                                Text(
                                    "В проекты и агентам этот профиль не передаётся.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.userProfileScope == UserProfileScope.CHATS,
                                onCheckedChange = { enabled ->
                                    vm.setUserProfileScope(if (enabled) UserProfileScope.CHATS else UserProfileScope.OFF)
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { vm.saveUserProfile(profileName, profileGender, profileAge, profileOccupation, profileNote) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Сохранить профиль")
                        }
                    }
                }

                    }
                    SettingsCategory.INTERFACE -> {
                item {
                    ExpandableSettingsCard(
                        title = "Звук готового ответа",
                        subtitle = when {
                            !state.answerSoundEnabled -> "Выключен"
                            state.answerSoundChoice == AnswerSoundChoice.CUSTOM -> state.answerSoundCustomName ?: "Свой звук"
                            else -> "Основной сигнал"
                        },
                        icon = Icons.Outlined.VolumeUp,
                        expanded = soundExpanded,
                        onToggle = { soundExpanded = !soundExpanded }
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Уведомлять после ответа", modifier = Modifier.weight(1f))
                            Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                        }
                        if (state.answerSoundEnabled) {
                            Spacer(Modifier.height(10.dp))
                            FilterChip(
                                selected = state.answerSoundChoice == AnswerSoundChoice.DEFAULT,
                                onClick = { vm.setAnswerSoundChoice(AnswerSoundChoice.DEFAULT) },
                                label = { Text("Основной") },
                                leadingIcon = if (state.answerSoundChoice == AnswerSoundChoice.DEFAULT) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                            if (importedSounds.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(importedSounds, key = { it.id }) { sound ->
                                        FilterChip(
                                            selected = state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM,
                                            onClick = { vm.selectAnswerSound(sound) },
                                            label = { Text(sound.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = if (state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
                                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = { soundPicker.launch(arrayOf("audio/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Добавить звук с телефона")
                            }
                            Text(
                                "Выбранный файл копируется в память Umnik и остаётся доступным, пока вы не удалите его в «Хранилище Umnik».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 7.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Громкость", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${state.answerSoundVolume}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Slider(
                                value = state.answerSoundVolume.toFloat(),
                                onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },
                                valueRange = 0f..100f
                            )
                            FilledTonalButton(
                                onClick = vm::previewAnswerSound,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Проверить звук")
                            }
                        }
                    }
                }

                item {
                    ExpandableSettingsCard(
                        title = "Цветовая схема",
                        subtitle = if (state.themeChoice == ThemeChoice.CUSTOM) {
                            "Свой цвет · #%06X".format(state.customThemeColor and 0xFFFFFF)
                        } else {
                            themeLabel(state.themeChoice)
                        },
                        icon = Icons.Outlined.Palette,
                        expanded = themeExpanded,
                        onToggle = { themeExpanded = !themeExpanded }
                    ) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(themes) { choice ->
                                FilterChip(
                                    selected = state.themeChoice == choice,
                                    onClick = { vm.setThemeChoice(choice) },
                                    label = { Text(themeLabel(choice)) },
                                    leadingIcon = if (choice == ThemeChoice.CUSTOM) {
                                        {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(state.customThemeColor),
                                                modifier = Modifier.size(14.dp)
                                            ) {}
                                        }
                                    } else null
                                )
                            }
                        }
                        if (state.themeChoice == ThemeChoice.CUSTOM) {
                            Spacer(Modifier.height(10.dp))
                            val cleanHex = customColorText.trim().removePrefix("#")
                            val parsedColor = cleanHex
                                .takeIf { value ->
                                    value.length == 6 && value.all { ch -> ch.isDigit() || ch.uppercaseChar() in 'A'..'F' }
                                }
                                ?.toLongOrNull(16)
                                ?.let { rgb -> (0xFF000000L or rgb).toInt() }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(parsedColor ?: state.customThemeColor),
                                    modifier = Modifier.size(38.dp)
                                ) {}
                                OutlinedTextField(
                                    value = customColorText,
                                    onValueChange = { customColorText = it.trim().uppercase().take(7) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("HEX-код") },
                                    placeholder = { Text("#6750A4") },
                                    singleLine = true,
                                    isError = customColorText.isNotBlank() && parsedColor == null
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = { parsedColor?.let(vm::setCustomThemeColor) },
                                enabled = parsedColor != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Применить цвет")
                            }
                            Text(
                                "Введите стандартный HEX-код цвета, например #1E88E5.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }

                    }
                    SettingsCategory.DATA -> {
                item {
                    val userFiles = state.storedFiles.filter { it.deletable }
                    val userBytes = userFiles.sumOf { it.size }
                    UmnikPanel {
                        TextButton(
                            onClick = {
                                vm.refreshStorage()
                                storageOpen = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Outlined.Storage, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Хранилище Umnik", fontWeight = FontWeight.Bold)
                                Text(
                                    "${userFiles.size} файлов · ${humanSize(userBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }

                    }
                    SettingsCategory.ABOUT -> {
                item {
                    UmnikPanel {
                        TextButton(
                            onClick = {
                                vm.openUsageGuide()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(Icons.Outlined.Description, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Памятка Umnik", fontWeight = FontWeight.Bold)
                                Text("Краткое руководство", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item {
                    ExpandableSettingsCard(
                        title = "Диагностика и логи",
                        subtitle = if (diagnosticLoggingEnabled)
                            "Запись включена · ${humanSize(diagnosticLogBytes)}"
                        else
                            "Выключено · включайте только при поиске ошибки",
                        icon = Icons.Outlined.Description,
                        expanded = diagnosticsExpanded,
                        onToggle = {
                            diagnosticsExpanded = !diagnosticsExpanded
                            diagnosticLoggingEnabled = vm.isDiagnosticLoggingEnabled()
                            diagnosticLogBytes = vm.diagnosticLogSize()
                        },
                        info = "Диагностический журнал нужен только для поиска ошибок. Он фиксирует технические стадии запросов, действия, модели и фоновые задачи, но не должен записывать тексты сообщений, содержимое файлов и API-ключи."
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Запись логов", fontWeight = FontWeight.Medium)
                                Text(
                                    "Включите, повторите действия с ошибкой и затем отправьте лог.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = diagnosticLoggingEnabled,
                                onCheckedChange = { enabled ->
                                    vm.setDiagnosticLoggingEnabled(enabled)
                                    diagnosticLoggingEnabled = enabled
                                    diagnosticLogBytes = vm.diagnosticLogSize()
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Для ручной проверки журнал фиксирует сетевые стадии, действия, выбранную модель, типы вложений, фоновые задания и возврат результатов в чат. Тексты сообщений, содержимое файлов и API-ключи не записываются. Журнал хранит до ~8 МБ последних событий.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val file = vm.diagnosticLogFile()
                                    if (file != null) {
                                        shareGeneratedFile(context, file)
                                        diagnosticLogBytes = vm.diagnosticLogSize()
                                    }
                                },
                                enabled = diagnosticLogBytes > 0L,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Поделиться", maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = {
                                    val file = vm.diagnosticLogFile()
                                    if (file != null) {
                                        diagnosticFileToSave = file
                                        diagnosticSave.launch(file.name)
                                    }
                                },
                                enabled = diagnosticLogBytes > 0L,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Сохранить", maxLines = 1)
                            }
                        }
                        TextButton(
                            onClick = {
                                vm.clearDiagnosticLog()
                                diagnosticLogBytes = 0L
                            },
                            enabled = diagnosticLogBytes > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Очистить лог")
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Umnik", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Версия $appVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                    }
                    null -> Unit
                }
            }
        }
    }

    if (storageOpen) StorageDialog(state = state, vm = vm, onDismiss = { storageOpen = false })
    if (imageParametersOpen) {
        ImageParametersDialog(state = state, vm = vm, onDismiss = { imageParametersOpen = false })
    }
}

@Composable
private fun ImageParametersDialog(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    val info = state.availableImageModels.firstOrNull { it.id == state.imageModel }
    val aspectRatios = info?.parameterValues("aspect_ratio").orEmpty()
    val resolutions = info?.parameterValues("resolution").orEmpty()
    val imageProfile = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }

    LaunchedEffect(state.imageConnectionProfileId, state.imageModel) {
        if (state.imageModel.isNotBlank() && info == null && !state.isLoading) {
            vm.refreshModels(ChatMode.IMAGE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Параметры изображения") },
        text = {
            Column {
                Text(
                    "${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageProfile?.name ?: "Подключение"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Text("Соотношение сторон", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Авто") + aspectRatios) { option ->
                        val auto = option == "Авто"
                        FilterChip(
                            selected = if (auto) state.imageAspectRatio == null else state.imageAspectRatio == option,
                            onClick = { vm.setImageAspectRatio(if (auto) null else option) },
                            label = { Text(option) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Разрешение", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Авто") + resolutions) { option ->
                        val auto = option == "Авто"
                        FilterChip(
                            selected = if (auto) state.imageResolution == null else state.imageResolution == option,
                            onClick = { vm.setImageResolution(if (auto) null else option) },
                            label = { Text(option) }
                        )
                    }
                }
                if (aspectRatios.isEmpty() && resolutions.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Модель не сообщила доступные параметры размера. Umnik оставит режим «Авто».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
        dismissButton = {
            if (state.imageAspectRatio != null || state.imageResolution != null) {
                TextButton(onClick = {
                    vm.setImageAspectRatio(null)
                    vm.setImageResolution(null)
                }) { Text("Сбросить") }
            }
        }
    )
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    info: String? = null,
    content: @Composable () -> Unit
) {
    UmnikPanel {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = UmnikPanelPadding
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (info != null) {
                Spacer(Modifier.width(6.dp))
                UmnikInfoHint(title = title, text = info)
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            HorizontalDivider(color = umnikDividerColor())
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun ReasoningSettingsCard(
    state: UiState,
    vm: ChatViewModel,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val currentId = state.currentChatTextModel ?: state.textModel
    val activeQuickModels = state.quickTextModels
        .filter { quickModelConnectionId(it, state.activeConnectionProfileId) == state.activeConnectionProfileId }
        .map(::quickModelId)
    val modelIds = (listOf(currentId, state.textModel) + activeQuickModels)
        .filter { it.isNotBlank() }
        .distinct()
    val imageInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }

    UmnikPanel {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Psychology, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Сила размышления", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                Text(
                    "Отдельная настройка для каждой быстрой модели",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            UmnikInfoHint(
                title = "Сила размышления",
                text = "Уровень reasoning задаётся отдельно для каждой модели. Более высокий уровень может улучшать сложные ответы, но обычно увеличивает время работы и стоимость. Если модель не поддерживает управление уровнем, она выберет режим сама."
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }

        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.availableTextModels.isEmpty()) {
                    Text(
                        "Сведения о возможностях моделей ещё не загружены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { vm.refreshModels(ChatMode.TEXT) }) { Text("Обновить модели") }
                }
                modelIds.forEach { id ->
                    val info = state.availableTextModels.firstOrNull { it.id == id }
                    val selected = state.reasoningEffortsByModel[id]
                        ?: if (id == currentId) state.reasoningEffort else ReasoningEffort.MEDIUM
                    ReasoningModelRow(
                        modelId = id,
                        info = info,
                        selected = selected,
                        subtitle = when {
                            id == currentId -> "Текущая модель чата"
                            id == state.textModel -> "По умолчанию"
                            else -> "Быстрая модель"
                        },
                        onSelect = { effort -> vm.setReasoningEffortForModel(id, effort) }
                    )
                }

                if (imageInfo?.supportsReasoning == true) {
                    HorizontalDivider()
                    ReasoningModelRow(
                        modelId = state.imageModel,
                        info = imageInfo,
                        selected = null,
                        subtitle = "Модель генерации изображений · возможности API",
                        onSelect = null
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningModelRow(
    modelId: String,
    info: ModelInfo?,
    selected: ReasoningEffort?,
    subtitle: String,
    onSelect: ((ReasoningEffort) -> Unit)?
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            modelId.substringAfter('/').ifBlank { modelId },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        when {
            info == null -> Text(
                "Возможности не загружены",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !info.supportsReasoning -> Text(
                "Размышление не поддерживается",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !info.supportsReasoningEffort -> Text(
                "Размышление поддерживается, но уровень выбирает сама модель",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ReasoningEffort.entries) { effort ->
                        val supported = info.reasoningEfforts.isEmpty() || effort.apiValue in info.reasoningEfforts
                        FilterChip(
                            selected = selected == effort,
                            onClick = { if (supported) onSelect?.invoke(effort) },
                            enabled = supported && onSelect != null,
                            label = { Text(reasoningEffortShortLabel(effort)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = if (supported) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                disabledContainerColor = if (supported) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun reasoningEffortShortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.MINIMAL -> "Мин"
    ReasoningEffort.LOW -> "Низк"
    ReasoningEffort.MEDIUM -> "Средн"
    ReasoningEffort.HIGH -> "Высок"
    ReasoningEffort.XHIGH -> "Макс"
}

@Composable
private fun StorageDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<StoredFile?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteSelectedConfirm by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(vm.storedFileAsGenerated(file), uri)
        fileToSave = null
    }

    val q = query.trim()
    val workingFiles = remember(state.storedFiles, q) {
        state.storedFiles.filter { file ->
            file.deletable && (q.isBlank() || file.name.contains(q, true) || file.category.contains(q, true))
        }
    }
    FullScreenPanel(title = "Хранилище Umnik", onBack = onDismiss) {
        val workingBytes = workingFiles.sumOf { it.size }
        Text(
            "Рабочие файлы · ${workingFiles.size} · ${humanSize(workingBytes)}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Найти файл") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = vm::refreshStorage) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Обновить")
            }
            if (selectedIds.isNotEmpty()) {
                TextButton(onClick = { deleteSelectedConfirm = true }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить (${selectedIds.size})")
                }
            } else {
                TextButton(onClick = { clearConfirm = true }, enabled = !state.isLoading && workingFiles.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Очистить все")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (workingFiles.isEmpty()) {
                item {
                    Text(
                        if (q.isBlank()) "Нет отдельных рабочих файлов" else "Рабочих файлов не найдено",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(workingFiles, key = { it.id }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = file.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + file.id else selectedIds - file.id
                            }
                        )
                        Icon(
                            if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${file.category} · ${humanSize(file.size)} · ${formatDate(file.modifiedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            fileToSave = file
                            save.launch(file.name)
                        }) { Icon(Icons.Outlined.Download, contentDescription = "Сохранить копию") }
                        IconButton(onClick = {
                            vm.deleteStoredFile(file)
                            selectedIds = selectedIds - file.id
                        }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
                        }
                    }
                    HorizontalDivider()
                }
            }

        }
    }

    if (deleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { deleteSelectedConfirm = false },
            title = { Text("Удалить выбранные файлы?") },
            text = { Text("Будет удалено файлов: ${selectedIds.size}.") },
            confirmButton = {
                TextButton(onClick = {
                    state.storedFiles.filter { it.deletable && it.id in selectedIds }.forEach(vm::deleteStoredFile)
                    selectedIds = emptySet()
                    deleteSelectedConfirm = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteSelectedConfirm = false }) { Text("Отмена") } }
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Очистить рабочие файлы?") },
            text = { Text("Будут удалены сохранённые внутри Umnik изображения, сгенерированные файлы и экспорт. Чаты, проекты, навыки и API-ключ останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearWorkingFiles()
                    selectedIds = emptySet()
                    clearConfirm = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Отмена") } }
        )
    }
}

private fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.MINIMAL -> "Минимальная"
    ReasoningEffort.LOW -> "Низкая"
    ReasoningEffort.MEDIUM -> "Средняя"
    ReasoningEffort.HIGH -> "Высокая"
    ReasoningEffort.XHIGH -> "Максимальная"
}

private const val SETTINGS_QUICK_MODEL_SEPARATOR = "\u001F"

private fun quickModelConnectionId(ref: String, fallback: String = "openrouter"): String =
    if (SETTINGS_QUICK_MODEL_SEPARATOR in ref) ref.substringBefore(SETTINGS_QUICK_MODEL_SEPARATOR) else fallback

private fun quickModelId(ref: String): String =
    if (SETTINGS_QUICK_MODEL_SEPARATOR in ref) ref.substringAfter(SETTINGS_QUICK_MODEL_SEPARATOR) else ref

private fun imageParameterSummary(state: UiState): String =
    listOfNotNull(state.imageAspectRatio, state.imageResolution)
        .ifEmpty { listOf("Авто") }
        .joinToString(" · ")

private fun profileScopeLabel(scope: UserProfileScope): String = when (scope) {
    UserProfileScope.OFF -> "выключен"
    UserProfileScope.CHATS -> "включён"
}

private fun themeLabel(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.DYNAMIC -> "Material You"
    ThemeChoice.CUSTOM -> "Свой цвет"
    ThemeChoice.GRAPHITE -> "Графит"
    ThemeChoice.OCEAN -> "Синяя"
    ThemeChoice.FOREST -> "Зелёная"
    ThemeChoice.AMBER -> "Янтарная"
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}

private fun shareGeneratedFile(context: Context, file: GeneratedFile) {
    val localFile = File(file.localPath)
    if (!localFile.isFile) {
        Toast.makeText(context, "Файл больше недоступен", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
}
