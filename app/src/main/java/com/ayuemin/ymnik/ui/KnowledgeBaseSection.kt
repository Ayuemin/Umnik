package com.ayuemin.ymnik.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.KnowledgeBaseSettings
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
    val enabledDocumentCount = documents.count { vm.isKnowledgeDocumentEnabled(it.id) }
    val knowledgeTask = vm.knowledgeTaskLabel(kind, ownerId)
    val knowledgeFailure = vm.knowledgeFailure(kind, ownerId)
    val indexedEmbeddingModels = documents.map { it.embeddingModelId }.filter { it.isNotBlank() }.distinct()
    var expanded by remember(ownerId) { mutableStateOf(false) }
    var enabled by remember(ownerId, current.enabled) { mutableStateOf(current.enabled) }
    var modelId by remember(ownerId, current.embeddingModelId) { mutableStateOf(current.embeddingModelId) }
    var topK by remember(ownerId, current.topK) { mutableStateOf(current.topK) }
    val addDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addKnowledgeDocuments(kind, ownerId, uris, modelId)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsExpander(
            title = title,
            subtitle = when {
                knowledgeTask != null -> "Идёт индексация · можно продолжать работу"
                documents.isEmpty() -> "Нет источников"
                !enabled -> "${documents.size} источн. · автопоиск выключен"
                enabledDocumentCount == 0 -> "${documents.size} источн. · все отключены"
                enabledDocumentCount < documents.size -> "${documents.size} источн. · в поиске $enabledDocumentCount"
                else -> "${documents.size} источн. · автопоиск включён"
            },
            expanded = expanded,
            onToggle = { expanded = !expanded },
            icon = Icons.Outlined.MenuBook,
            info = "Книги и справочники индексируются один раз. При запросе Umnik автоматически находит подходящие фрагменты и добавляет только их в контекст модели. Полный документ заново не отправляется."
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
            Text("Использовать автоматически", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        UmnikModelIdField(
            label = "Embedding-модель",
            value = modelId,
            onValueChange = { modelId = it.trim() },
            onPick = {
                com.ayuemin.ymnik.AsyncJobEvents.requestHub(
                    "models-settings",
                    if (kind == KnowledgeOwnerKind.AGENT) "Настройки агента" else "Настройки чата"
                )
            },
            info = "Это модель для новых и переиндексируемых источников. Каждый уже готовый источник сохраняет ту Embeddings-модель, которой был проиндексирован. Поэтому в одной базе технически могут одновременно работать несколько Embeddings-моделей: при каждом вопросе Umnik делает отдельный embedding запроса для каждой используемой модели. Для скорости и более однородной оценки релевантности лучше по возможности держать одну модель на базу и переиндексировать старые источники после смены."
        )

        if (indexedEmbeddingModels.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "В готовых индексах используются ${indexedEmbeddingModels.size} Embeddings-модели",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                UmnikInfoHint(
                    title = "Несколько Embeddings-моделей",
                    text = "Umnik умеет искать по таким источникам: запрос отдельно преобразуется каждой моделью, а результаты затем объединяются. Это добавляет сетевые запросы и может сделать оценки релевантности менее однородными. Если это не было задумано специально, переиндексируйте старые источники текущей моделью."
                )
            }
        }

        Text("Фрагментов в запрос: $topK", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 8).forEach { value ->
                FilterChip(
                    selected = topK == value,
                    onClick = { topK = value },
                    label = { Text(value.toString()) }
                )
            }
        }

        FilledTonalButton(
            onClick = {
                vm.saveKnowledgeSettings(
                    kind,
                    ownerId,
                    KnowledgeBaseSettings(modelId, enabled, topK)
                )
            },
            enabled = modelId.isNotBlank() && !state.isLoading && !state.requestActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить настройки базы знаний")
        }

        if (documents.isEmpty()) {
            Text("Источников пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            documents.forEach { document ->
                var documentEnabled by remember(document.id) {
                    mutableStateOf(vm.isKnowledgeDocumentEnabled(document.id))
                }
                UmnikPanel {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(document.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${document.chunkCount} фрагм. · ${knowledgeSize(document.size)} · ${document.embeddingModelId.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { vm.reindexKnowledgeDocument(document.id, modelId) },
                                enabled = knowledgeTask == null && !state.isLoading && !state.requestActive
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Переиндексировать")
                            }
                            IconButton(
                                onClick = { vm.deleteKnowledgeDocument(document.id) },
                                enabled = knowledgeTask == null && !state.isLoading && !state.requestActive
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить из базы знаний")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Использовать в поиске",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            UmnikInfoHint(
                                title = "Источник в поиске",
                                text = "Если выключить источник, Umnik перестанет брать из него фрагменты для ответов. Сам документ и готовый индекс останутся на месте, поэтому источник можно включить обратно без переиндексации."
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = documentEnabled,
                                onCheckedChange = { value ->
                                    documentEnabled = value
                                    vm.setKnowledgeDocumentEnabled(document.id, value)
                                },
                                enabled = knowledgeTask == null && !state.isLoading && !state.requestActive
                            )
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { addDocuments.launch(arrayOf("*/*")) },
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
