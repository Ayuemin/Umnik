package com.ayuemin.ymnik.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.UiState
import java.io.File
import java.util.UUID

/**
 * Skill picker used by project specialists. Project-level skillIds are intentionally
 * not used as an allow-list: every specialist can select any skill from the global
 * library, import a new one, or create a small text skill in place.
 */
@Composable
fun ProjectChatSkillsSection(
    state: UiState,
    vm: ChatViewModel,
    selectedIds: Set<String>,
    onSelectedIdsChange: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var createOpen by remember { mutableStateOf(false) }

    fun importAndEnable(action: () -> Unit) {
        val before = vm.state.value.skills.mapTo(mutableSetOf()) { it.id }
        action()
        val added = vm.state.value.skills.map { it.id }.filterNot { it in before }.toSet()
        if (added.isNotEmpty()) onSelectedIdsChange(selectedIds + added)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importAndEnable { vm.importSkillFile(it) } }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importAndEnable { vm.importSkillTree(it) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Навыки выбираются отдельно для этого специалиста. Предварительно добавлять их в проект не нужно.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.skills.isEmpty()) {
            Text(
                "В библиотеке пока нет навыков. Создайте первый здесь или импортируйте файл/папку.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.skills.forEach { skill ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(skill.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Switch(
                        checked = skill.id in selectedIds,
                        onCheckedChange = { enabled ->
                            onSelectedIdsChange(if (enabled) selectedIds + skill.id else selectedIds - skill.id)
                        }
                    )
                }
            }
        }

        FilledTonalButton(onClick = { createOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Создать навык")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml", "application/x-yaml")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Файл")
            }
            FilledTonalButton(onClick = { treePicker.launch(null) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Папка")
            }
        }
    }

    if (createOpen) {
        InlineSkillCreateDialog(
            onDismiss = { createOpen = false },
            onCreate = { name, body ->
                val id = createInlineSkill(context, vm, name, body)
                if (id != null) onSelectedIdsChange(selectedIds + id)
                createOpen = false
            }
        )
    }
}

@Composable
fun InlineSkillCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый навык") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Инструкция навыка") },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), body.trim()) },
                enabled = name.isNotBlank() && body.isNotBlank()
            ) { Text("Создать и включить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Creates a tiny temporary markdown document and feeds it through the same import path as any other skill. */
fun createInlineSkill(context: Context, vm: ChatViewModel, name: String, body: String): String? {
    if (name.isBlank() || body.isBlank()) return null
    val before = vm.state.value.skills.mapTo(mutableSetOf()) { it.id }
    val root = File(context.cacheDir, "skills-temp/${UUID.randomUUID()}").apply { mkdirs() }
    val safeName = name.trim().replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_").ifBlank { "Навык" }
    val file = File(root, "$safeName.md")
    return try {
        file.writeText(body.trim())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        vm.importSkillFile(uri)
        vm.state.value.skills.firstOrNull { it.id !in before }?.id
    } finally {
        root.deleteRecursively()
    }
}
