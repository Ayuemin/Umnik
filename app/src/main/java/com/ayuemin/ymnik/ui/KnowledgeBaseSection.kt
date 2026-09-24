package com.ayuemin.ymnik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.UiState
import java.util.Locale

@Composable
fun KnowledgeBaseSection(
    kind: KnowledgeOwnerKind,
    ownerId: String,
    state: UiState,
    vm: ChatViewModel,
    title: String = "База знаний"
) {
    val current = vm.knowledgeSettings(kind, ownerId)
    val documents = vm.knowledgeDocuments(kind, ownerId)
    val knowledgeTask = vm.knowledgeTaskLabel(kind, ownerId)
    val knowledgeFailure = vm.knowledgeFailure(kind, ownerId)
    val context = LocalContext.current
    var expanded by remember(ownerId) { mutableStateOf(false) }
    var enabled by remember(ownerId, current.enabled) { mutableStateOf(current.enabled) }
    var modelInstruction by remember(ownerId, current.modelInstruction) { mutableStateOf(current.modelInstruction) }
    var modelSearchLimit by remember(ownerId, current.effectiveModelSearchLimit) {
        mutableStateOf(current.effectiveModelSearchLimit)
    }
    val addDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addKnowledgeDocuments(kind, ownerId, uris)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsExpander(
            title = title,
            subtitle = when {
                knowledgeTask != null -> "Идёт индексация · можно продолжать работу"
                !vm.systemModelConfigured() && documents.isEmpty() -> "Сначала выберите системную модель"
                !vm.systemModelConfigured() -> "${documents.size} источн. · нужна системная модель"
                documents.isEmpty() -> "Нет источников"
                !enabled -> "${documents.size} источн. · база выключена"
                else -> "${documents.size} источн. · база включена"
            },
            expanded = expanded,
            onToggle = { expanded = !expanded },
            icon = Icons.Outlined.MenuBook,
            info = "База знаний работает в двух режимах одновременно: вы можете прямо задавать модели вопросы по её документам, а совместимая модель может сама обращаться к базе во время выполнения любой задачи, когда ей нужны дополнительные сведения. Для смыслового поиска используется общая Embeddings-модель, а системная модель помогает понимать естественные формулировки, продолжения и запросы «только по книге». Документы индексируются один раз, в модель передаются только подходящие фрагменты."
        )
        if (!expanded) return@Column

        if (knowledgeTask != null) {
            UmnikPanel {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(knowledgeTask, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Можно перейти в другой чат или погасить экран. Прогресс сохраняется после каждой партии и продолжится с последнего checkpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (knowledgeTask == null && knowledgeFailure != null) {
            UmnikPanel {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Индексация остановлена", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(
                        knowledgeFailure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = { vm.retryKnowledgeIndexing(kind, ownerId) },
                        enabled = !state.isLoading && !state.requestActive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Продолжить с checkpoint")
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Использовать базу знаний", Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    vm.saveKnowledgeSettings(
                        kind,
                        ownerId,
                        current.copy(enabled = checked)
                    )
                },
                enabled = !state.isLoading && !state.requestActive
            )
        }

        SettingTitleWithInfo(
            title = "Самостоятельный поиск модели",
            info = "Это дополнительная возможность, а не отдельный тип базы. Вы по-прежнему можете напрямую спрашивать модель о содержимом базы. Совместимая модель также может сама искать в ней сведения во время любой задачи. Инструкция ниже уточняет, когда и как ей делать самостоятельный поиск. Лимит задаёт только максимум таких дополнительных обращений за один ответ."
        )
        OutlinedTextField(
            value = modelInstruction,
            onValueChange = { modelInstruction = it.take(4000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Инструкция для работы с базой · необязательно") },
            placeholder = { Text("Например: перед важными выводами проверяй требования и факты в базе") },
            minLines = 2,
            maxLines = 5
        )
        SettingTitleWithInfo(
            title = "Лимит самостоятельных поисков",
            info = "От 0 до 10 за один ответ. Это максимум, а не обязательное количество: модель может обратиться к базе меньше раз или не обращаться совсем. 0 отключает только самостоятельные обращения модели. Обычные вопросы пользователя по базе и тихий RAG продолжают работать."
        )
        UmnikPanel {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { modelSearchLimit = (modelSearchLimit - 1).coerceAtLeast(0) },
                    enabled = modelSearchLimit > 0 && !state.isLoading && !state.requestActive
                ) {
                    Text("−", style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        modelSearchLimit.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (modelSearchLimit == 0) "Самостоятельный поиск выключен" else "максимум за один ответ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { modelSearchLimit = (modelSearchLimit + 1).coerceAtMost(10) },
                    enabled = modelSearchLimit < 10 && !state.isLoading && !state.requestActive
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Увеличить лимит")
                }
            }
        }
        FilledTonalButton(
            onClick = {
                vm.saveKnowledgeSettings(
                    kind,
                    ownerId,
                    current.copy(
                        enabled = enabled,
                        modelInstruction = modelInstruction,
                        modelSearchLimit = modelSearchLimit
                    )
                )
            },
            enabled = (
                modelInstruction.trim() != current.modelInstruction.trim() ||
                    modelSearchLimit != current.effectiveModelSearchLimit
                ) && !state.isLoading && !state.requestActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить настройки поиска")
        }

        if (documents.isEmpty()) {
            Text("Источников пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            documents.forEach { document ->
                UmnikPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${document.chunkCount} фрагм. · ${knowledgeSize(document.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { vm.deleteKnowledgeDocument(document.id) },
                            enabled = knowledgeTask == null && !state.isLoading && !state.requestActive
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить из базы знаний")
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = {
                if (!vm.systemModelConfigured()) {
                    Toast.makeText(
                        context,
                        "Сначала выберите системную модель: Настройки → Модели → Системная модель",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    addDocuments.launch(arrayOf("*/*"))
                }
            },
            enabled = knowledgeTask == null && !state.isLoading && !state.requestActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Добавить источник знаний")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Поддерживаемые источники", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            UmnikInfoHint(
                title = "Поддерживаемые источники",
                text = "PDF с текстовым слоем, EPUB, FB2, DOCX, TXT/MD, HTML/XML, JSON/CSV/YAML и текстовые файлы кода. Сканированные PDF потребуют OCR в будущем."
            )
        }
    }
}

private fun knowledgeSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
