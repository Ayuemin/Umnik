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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.ayuemin.ymnik.model.KnowledgeBaseSettings
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.ModelCategory
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
    val context = LocalContext.current
    val current = vm.knowledgeSettings(kind, ownerId)
    val documents = vm.knowledgeDocuments(kind, ownerId)
    var expanded by remember(ownerId) { mutableStateOf(false) }
    var enabled by remember(ownerId, current.enabled) { mutableStateOf(current.enabled) }
    var modelId by remember(ownerId, current.embeddingModelId) { mutableStateOf(current.embeddingModelId) }
    var topK by remember(ownerId, current.topK) { mutableStateOf(current.topK) }
    var modelMenu by remember(ownerId) { mutableStateOf(false) }

    // Use exactly the same complete OpenRouter catalog as the OpenRouter Hub.
    // The normal ChatViewModel catalog intentionally contains text models only,
    // so it cannot be used to populate the Embeddings picker.
    val openRouterCatalog = remember(context, vm) { OpenRouterHubController(context, vm) }
    val catalogState by openRouterCatalog.state.collectAsState()
    DisposableEffect(openRouterCatalog) {
        onDispose { openRouterCatalog.close() }
    }
    LaunchedEffect(expanded) {
        if (expanded && catalogState.catalog.isEmpty() && !catalogState.loading) {
            openRouterCatalog.refreshCatalog()
        }
    }

    val embeddingCatalog = catalogState.catalog
        .filter { ModelCategory.EMBEDDINGS in it.categories }

    val modelChoices = (
        listOf(
            modelId,
            KnowledgeBaseSettings.DEFAULT_EMBEDDING_MODEL,
            "baai/bge-m3",
            "openai/text-embedding-3-small"
        ) + embeddingCatalog.map { it.id }
    ).filter(String::isNotBlank).distinct()

    val addDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addKnowledgeDocuments(kind, ownerId, uris, modelId)
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SettingsExpander(
            title = title,
            subtitle = when {
                documents.isEmpty() -> "Нет источников"
                !enabled -> "${documents.size} источн. · автопоиск выключен"
                else -> "${documents.size} источн. · RAG включён"
            },
            expanded = expanded,
            onToggle = { expanded = !expanded }
        )
        if (!expanded) return@Column

        Text(
            "Большие книги и справочники индексируются один раз. При запросе Umnik находит только подходящие фрагменты и добавляет их в контекст модели, не отправляя весь документ заново.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Использовать автоматически", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Text("Embedding-модель", fontWeight = FontWeight.SemiBold)
        Box {
            FilledTonalButton(
                onClick = { modelMenu = true },
                enabled = !state.isLoading && !state.requestActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                modelChoices.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { modelId = id; modelMenu = false }
                    )
                }
            }
        }
        when {
            catalogState.loading && embeddingCatalog.isEmpty() -> Text(
                "Загружаю полный список Embeddings из OpenRouter…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            embeddingCatalog.isNotEmpty() -> Text(
                "Доступно Embeddings в OpenRouter: ${embeddingCatalog.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !catalogState.status.isNullOrBlank() -> Text(
                catalogState.status.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            "Модель применяется к новым и переиндексируемым источникам. Уже готовые индексы продолжают работать со своей embedding-моделью.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
                            enabled = !state.isLoading && !state.requestActive
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Переиндексировать")
                        }
                        IconButton(
                            onClick = { vm.deleteKnowledgeDocument(document.id) },
                            enabled = !state.isLoading && !state.requestActive
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить из базы знаний")
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { addDocuments.launch(arrayOf("*/*")) },
            enabled = !state.isLoading && !state.requestActive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Добавить источник знаний")
        }
        Text(
            "Сейчас поддерживаются PDF с текстовым слоем, EPUB, DOCX, TXT/MD, HTML/XML, JSON/CSV/YAML и текстовые файлы кода. Сканированные PDF потребуют OCR в будущем.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun knowledgeSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
