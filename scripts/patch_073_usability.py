from pathlib import Path
import re

# Models.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/model/Models.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('''enum class ThemeChoice {\n    DYNAMIC,\n    GRAPHITE,''', '''enum class ThemeChoice {\n    DYNAMIC,\n    CUSTOM,\n    GRAPHITE,''', 1)
s = s.replace('''    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,\n    val storedFiles:''', '''    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,\n    val customThemeColor: Int = 0xFF6750A4.toInt(),\n    val storedFiles:''', 1)
p.write_text(s, encoding='utf-8')

# ChatViewModel.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
s = p.read_text(encoding='utf-8')
old = '''            themeChoice = runCatching {\n                ThemeChoice.valueOf(prefs.getString("theme_choice", ThemeChoice.DYNAMIC.name) ?: ThemeChoice.DYNAMIC.name)\n            }.getOrDefault(ThemeChoice.DYNAMIC),\n            storedFiles = storageRepository.list(),'''
new = '''            themeChoice = runCatching {\n                ThemeChoice.valueOf(prefs.getString("theme_choice", ThemeChoice.DYNAMIC.name) ?: ThemeChoice.DYNAMIC.name)\n            }.getOrDefault(ThemeChoice.DYNAMIC),\n            customThemeColor = prefs.getInt("custom_theme_color", 0xFF6750A4.toInt()),\n            storedFiles = storageRepository.list(),'''
if old not in s:
    raise SystemExit('ChatViewModel theme init anchor not found')
s = s.replace(old, new, 1)
old = '''    fun setThemeChoice(choice: ThemeChoice) {\n        prefs.edit().putString("theme_choice", choice.name).apply()\n        _state.value = _state.value.copy(themeChoice = choice)\n    }'''
new = '''    fun setThemeChoice(choice: ThemeChoice) {\n        prefs.edit().putString("theme_choice", choice.name).apply()\n        _state.value = _state.value.copy(themeChoice = choice)\n    }\n\n    fun setCustomThemeColor(color: Int) {\n        prefs.edit()\n            .putInt("custom_theme_color", color)\n            .putString("theme_choice", ThemeChoice.CUSTOM.name)\n            .apply()\n        _state.value = _state.value.copy(\n            customThemeColor = color,\n            themeChoice = ThemeChoice.CUSTOM\n        )\n    }'''
if old not in s:
    raise SystemExit('ChatViewModel theme setter anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# UmnikTheme.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/UmnikTheme.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.lerp\n', 1)
s = s.replace('fun UmnikTheme(choice: ThemeChoice, content: @Composable () -> Unit) {', 'fun UmnikTheme(choice: ThemeChoice, customColor: Int, content: @Composable () -> Unit) {', 1)
s = s.replace('''        ThemeChoice.DYNAMIC -> when {\n            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)\n            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)\n            dark -> graphiteDark()\n            else -> graphiteLight()\n        }\n        ThemeChoice.GRAPHITE''', '''        ThemeChoice.DYNAMIC -> when {\n            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)\n            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)\n            dark -> graphiteDark()\n            else -> graphiteLight()\n        }\n        ThemeChoice.CUSTOM -> if (dark) customDark(Color(customColor)) else customLight(Color(customColor))\n        ThemeChoice.GRAPHITE''', 1)
append_marker = '\nprivate fun graphiteLight() = baseLight('
custom_funcs = '''\nprivate fun customLight(primary: Color) = baseLight(\n    primary = primary,\n    primaryContainer = lerp(primary, Color.White, 0.82f),\n    onPrimaryContainer = lerp(primary, Color.Black, 0.78f),\n    secondary = lerp(primary, Color.Gray, 0.42f),\n    tertiary = lerp(primary, Color(0xFF7D5260), 0.38f)\n)\n\nprivate fun customDark(primary: Color): ColorScheme {\n    val bright = lerp(primary, Color.White, 0.46f)\n    return baseDark(\n        primary = bright,\n        primaryContainer = lerp(primary, Color.Black, 0.34f),\n        onPrimaryContainer = lerp(primary, Color.White, 0.82f),\n        secondary = lerp(primary, Color.White, 0.56f),\n        tertiary = lerp(primary, Color(0xFFFFD8E4), 0.45f)\n    )\n}\n'''
if append_marker not in s:
    raise SystemExit('UmnikTheme custom anchor not found')
s = s.replace(append_marker, custom_funcs + append_marker, 1)
p.write_text(s, encoding='utf-8')

# ProjectDialogs.kt: project skill checkmark and explanation
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('import androidx.compose.material.icons.outlined.AttachFile\n', 'import androidx.compose.material.icons.outlined.AttachFile\nimport androidx.compose.material.icons.outlined.Check\n', 1)
old = '''            item { SectionTitle("Навыки проекта") }\n            if (state.skills.isEmpty()) {'''
new = '''            item { SectionTitle("Навыки проекта") }\n            item {\n                Text(\n                    "Отмеченный навык автоматически добавляет свои инструкции и текстовые материалы к каждому запросу в чатах этого проекта.",\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            }\n            if (state.skills.isEmpty()) {'''
if old not in s:
    raise SystemExit('project skill title anchor not found')
s = s.replace(old, new, 1)
old = '''                        items(state.skills, key = { it.id }) { skill ->\n                            FilterChip(\n                                selected = skill.id in project.skillIds,\n                                onClick = { vm.toggleProjectSkill(project.id, skill.id) },\n                                label = { Text(skill.name, maxLines = 1) },\n                                leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(17.dp)) }\n                            )\n                        }'''
new = '''                        items(state.skills, key = { it.id }) { skill ->\n                            val selected = skill.id in project.skillIds\n                            FilterChip(\n                                selected = selected,\n                                onClick = { vm.toggleProjectSkill(project.id, skill.id) },\n                                label = { Text(skill.name, maxLines = 1) },\n                                leadingIcon = {\n                                    Icon(\n                                        if (selected) Icons.Outlined.Check else Icons.Outlined.Extension,\n                                        contentDescription = if (selected) "Навык активен" else null,\n                                        modifier = Modifier.size(17.dp)\n                                    )\n                                }\n                            )\n                        }'''
if old not in s:
    raise SystemExit('project skill chip anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# YmnikApp.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt')
s = p.read_text(encoding='utf-8')
# imports
s = s.replace('import androidx.compose.foundation.shape.RoundedCornerShape\n', 'import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.shape.RoundedCornerShape\n', 1)
s = s.replace('import androidx.compose.material.icons.outlined.CameraAlt\n', 'import androidx.compose.material.icons.outlined.CameraAlt\nimport androidx.compose.material.icons.outlined.Check\n', 1)
s = s.replace('import androidx.compose.material3.CardDefaults\n', 'import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.Checkbox\n', 1)
s = s.replace('import androidx.compose.ui.graphics.asImageBitmap\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.asImageBitmap\n', 1)
# theme call
s = s.replace('UmnikTheme(state.themeChoice) {', 'UmnikTheme(state.themeChoice, state.customThemeColor) {', 1)
# skills explanation
old = '"SKILL.md и папки с текстовыми материалами. Подключённые навыки применяются в текстовом режиме."'
new = '"Навык — это постоянная инструкция и текстовые материалы для модели. После подключения Umnik добавляет их к каждому текстовому запросу. Навык сам ничего не запускает и не изменяет файлы."'
if old not in s:
    raise SystemExit('skills explanation anchor not found')
s = s.replace(old, new, 1)
# connected skill check icon
old = 'leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp)) }'
new = '''leadingIcon = {\n                                    Icon(\n                                        if (skill.id in state.activeSkillIds) Icons.Outlined.Check else Icons.Outlined.Extension,\n                                        contentDescription = if (skill.id in state.activeSkillIds) "Навык активен" else null,\n                                        modifier = Modifier.size(18.dp)\n                                    )\n                                }'''
if old not in s:
    raise SystemExit('global skill icon anchor not found')
s = s.replace(old, new, 1)
# custom theme state var
old = 'var apiExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }\n'
new = old + '    var customColorExpanded by remember { mutableStateOf(state.themeChoice == ThemeChoice.CUSTOM) }\n'
if old not in s:
    raise SystemExit('settings custom color state anchor not found')
s = s.replace(old, new, 1)
# theme card replace
old_theme = '''                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                            items(themes) { choice ->\n                                FilterChip(\n                                    selected = state.themeChoice == choice,\n                                    onClick = { vm.setThemeChoice(choice) },\n                                    label = { Text(themeLabel(choice)) }\n                                )\n                            }\n                        }'''
new_theme = '''                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                            items(themes) { choice ->\n                                FilterChip(\n                                    selected = state.themeChoice == choice,\n                                    onClick = {\n                                        vm.setThemeChoice(choice)\n                                        if (choice == ThemeChoice.CUSTOM) customColorExpanded = true\n                                    },\n                                    label = { Text(themeLabel(choice)) },\n                                    leadingIcon = if (choice == ThemeChoice.CUSTOM) {\n                                        {\n                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {\n                                                listOf(0xFFE53935, 0xFF7E57C2, 0xFF1E88E5, 0xFF43A047).forEach { c ->\n                                                    Surface(shape = CircleShape, color = Color(c.toInt()), modifier = Modifier.size(5.dp)) {}\n                                                }\n                                            }\n                                        }\n                                    } else null\n                                )\n                            }\n                        }\n                        if (state.themeChoice == ThemeChoice.CUSTOM || customColorExpanded) {\n                            Spacer(Modifier.height(8.dp))\n                            Text(\n                                "Свой цвет",\n                                style = MaterialTheme.typography.bodySmall,\n                                color = MaterialTheme.colorScheme.onSurfaceVariant\n                            )\n                            Spacer(Modifier.height(6.dp))\n                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                                val palette = listOf(\n                                    0xFFD32F2F.toInt(), 0xFFF57C00.toInt(), 0xFFF9A825.toInt(),\n                                    0xFF388E3C.toInt(), 0xFF00897B.toInt(), 0xFF0288D1.toInt(),\n                                    0xFF1976D2.toInt(), 0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(),\n                                    0xFFC2185B.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt()\n                                )\n                                items(palette) { colorValue ->\n                                    FilterChip(\n                                        selected = state.customThemeColor == colorValue,\n                                        onClick = { vm.setCustomThemeColor(colorValue) },\n                                        label = {\n                                            Surface(\n                                                shape = CircleShape,\n                                                color = Color(colorValue),\n                                                modifier = Modifier.size(22.dp)\n                                            ) {}\n                                        },\n                                        leadingIcon = if (state.customThemeColor == colorValue) {\n                                            { Icon(Icons.Outlined.Check, contentDescription = "Выбран") }\n                                        } else null\n                                    )\n                                }\n                            }\n                        }'''
if old_theme not in s:
    raise SystemExit('theme card anchor not found')
s = s.replace(old_theme, new_theme, 1)
# StorageDialog complete replacement
pattern = re.compile(r'@Composable\nprivate fun StorageDialog\(state: UiState, vm: ChatViewModel, onDismiss: \(\) -> Unit\) \{.*?\n\}\n\nprivate fun reasoningEffortLabel', re.S)
match = pattern.search(s)
if not match:
    raise SystemExit('StorageDialog block not found')
new_storage = r'''@Composable
private fun StorageDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<StoredFile?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteSelectedConfirm by remember { mutableStateOf(false) }
    var protectedExpanded by remember { mutableStateOf(false) }
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
    val protectedFiles = remember(state.storedFiles, q) {
        state.storedFiles.filter { file ->
            !file.deletable && (q.isBlank() || file.name.contains(q, true) || file.category.contains(q, true))
        }
    }
    val protectedTotal = state.storedFiles.filterNot { it.deletable }
    val protectedBytes = protectedTotal.sumOf { it.size }
    val showProtectedContents = protectedExpanded || q.isNotBlank()

    FullScreenPanel(title = "Хранилище Umnik", onBack = onDismiss) {
        Text(
            "Всего ${humanSize(state.storageStats.totalBytes)}",
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

            if (protectedTotal.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        TextButton(
                            onClick = { protectedExpanded = !protectedExpanded },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Системные данные", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Навыки, проекты и файлы чатов · ${protectedTotal.size} · ${humanSize(protectedBytes)}",
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                if (showProtectedContents) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (showProtectedContents) "Свернуть" else "Развернуть"
                            )
                        }
                    }
                }
                if (showProtectedContents) {
                    if (protectedFiles.isEmpty()) {
                        item { Text("В системных данных совпадений нет", modifier = Modifier.padding(12.dp)) }
                    } else {
                        items(protectedFiles, key = { "protected-${it.id}" }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${file.category} · ${humanSize(file.size)}",
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
                            }
                            HorizontalDivider()
                        }
                    }
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

private fun reasoningEffortLabel'''
s = s[:match.start()] + new_storage + s[match.end():]
# theme label custom
old = '''private fun themeLabel(choice: ThemeChoice): String = when (choice) {\n    ThemeChoice.DYNAMIC -> "Material You"\n    ThemeChoice.GRAPHITE'''
new = '''private fun themeLabel(choice: ThemeChoice): String = when (choice) {\n    ThemeChoice.DYNAMIC -> "Material You"\n    ThemeChoice.CUSTOM -> "Свой цвет"\n    ThemeChoice.GRAPHITE'''
if old not in s:
    raise SystemExit('themeLabel anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

print('v0.7.3 usability patch prepared')
