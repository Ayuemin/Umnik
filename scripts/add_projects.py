from pathlib import Path
import textwrap

ROOT = Path(__file__).resolve().parents[1]


def write(rel: str, content: str):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")


def replace_once(rel: str, old: str, new: str):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Pattern not found in {rel}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


write("app/src/main/java/com/ayuemin/ymnik/model/Models.kt", r'''
package com.ayuemin.ymnik.model

enum class ChatMode {
    TEXT,
    IMAGE
}

enum class ThemeChoice {
    DYNAMIC,
    GRAPHITE,
    OCEAN,
    FOREST,
    AMBER
}

data class Skill(
    val id: String,
    val name: String,
    val files: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

data class PendingAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val localPath: String? = null
)

data class GeneratedFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long
)

data class ProjectFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis()
)

data class Project(
    val id: String,
    val name: String,
    val role: String = "",
    val masterPrompt: String = "",
    val isFavorite: Boolean = false,
    val skillIds: Set<String> = emptySet(),
    val files: List<ProjectFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val generatedFiles: List<GeneratedFile> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val projectId: String? = null,
    val isFavorite: Boolean = false,
    val assignedRole: String? = null,
    val masterPrompt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoredFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val modifiedAt: Long,
    val category: String,
    val deletable: Boolean = true
)

data class StorageStats(
    val generatedBytes: Long = 0L,
    val exportBytes: Long = 0L,
    val skillBytes: Long = 0L,
    val projectBytes: Long = 0L,
    val chatBytes: Long = 0L
) {
    val totalBytes: Long
        get() = generatedBytes + exportBytes + skillBytes + projectBytes + chatBytes
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val chats: List<ChatSession> = emptyList(),
    val projects: List<Project> = emptyList(),
    val currentChatId: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val activeSkillIds: Set<String> = emptySet(),
    val mode: ChatMode = ChatMode.TEXT,
    val textModel: String = "openrouter/auto",
    val imageModel: String = "bytedance-seed/seedream-4.5",
    val webSearchEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val busyLabel: String? = null,
    val status: String? = null,
    val availableTextModels: List<String> = emptyList(),
    val availableImageModels: List<String> = emptyList(),
    val answerSoundEnabled: Boolean = true,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,
    val storedFiles: List<StoredFile> = emptyList(),
    val storageStats: StorageStats = StorageStats()
)
''')

write("app/src/main/java/com/ayuemin/ymnik/data/ProjectRepository.kt", r'''
package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class ProjectRepository(private val context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val metadata = File(root, "projects.json")

    fun list(): List<Project> = runCatching {
        if (!metadata.exists()) return emptyList()
        val type = object : TypeToken<List<Project>>() {}.type
        gson.fromJson<List<Project>>(metadata.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun save(projects: List<Project>) {
        root.mkdirs()
        metadata.writeText(gson.toJson(projects))
    }

    fun importFile(projectId: String, uri: Uri): ProjectFile {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                    size = cursor.getLong(it)
                }
            }
        }
        if (size > 25L * 1024 * 1024) error("Один файл проекта пока ограничен 25 МБ")

        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val dir = File(root, safeId(projectId)).resolve("files").apply { mkdirs() }
        val displayName = safeName(name).ifBlank { "file" }
        val target = File(dir, "${UUID.randomUUID()}_$displayName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать $name")

        return ProjectFile(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = mime,
            localPath = target.absolutePath,
            size = target.length()
        )
    }

    fun deleteFile(file: ProjectFile): Boolean {
        val target = File(file.localPath)
        if (!isInside(target, root)) return false
        return !target.exists() || target.delete()
    }

    fun deleteProjectFiles(projectId: String) {
        File(root, safeId(projectId)).deleteRecursively()
    }

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)

    private fun isInside(file: File, parent: File): Boolean = runCatching {
        val f = file.canonicalFile
        val p = parent.canonicalFile
        f.path.startsWith(p.path + File.separator)
    }.getOrDefault(false)
}
''')

write("app/src/main/java/com/ayuemin/ymnik/data/StorageRepository.kt", r'''
package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.StorageStats
import com.ayuemin.ymnik.model.StoredFile
import java.io.File
import java.util.UUID

class StorageRepository(private val context: Context) {
    private val generatedRoot = File(context.filesDir, "generated").apply { mkdirs() }
    private val exportsRoot = File(context.cacheDir, "exports").apply { mkdirs() }
    private val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
    private val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")

    fun list(): List<StoredFile> {
        val items = mutableListOf<StoredFile>()
        collect(generatedRoot, "Сгенерировано", true, items)
        collect(exportsRoot, "Экспорт", true, items)
        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")
        collect(projectsRoot, "Проекты", false, items, skipName = "projects.json")
        return items.sortedByDescending { it.modifiedAt }
    }

    fun stats(): StorageStats = StorageStats(
        generatedBytes = sizeOf(generatedRoot),
        exportBytes = sizeOf(exportsRoot),
        skillBytes = sizeOf(skillsRoot),
        projectBytes = sizeOf(projectsRoot),
        chatBytes = if (chatsFile.exists()) chatsFile.length() else 0L
    )

    fun delete(path: String): Boolean {
        val target = File(path)
        if (!isInside(target, generatedRoot) && !isInside(target, exportsRoot)) return false
        return target.delete()
    }

    fun clearWorkingFiles() {
        generatedRoot.deleteRecursively()
        exportsRoot.deleteRecursively()
        generatedRoot.mkdirs()
        exportsRoot.mkdirs()
    }

    private fun collect(
        root: File,
        category: String,
        deletable: Boolean,
        out: MutableList<StoredFile>,
        skipName: String? = null
    ) {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (skipName != null && file.name == skipName) return@forEach
            val displayName = if (category == "Сгенерировано") {
                file.name.substringAfter('_', file.name)
            } else {
                file.name.substringAfter('_', file.name)
            }
            out += StoredFile(
                id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(),
                name = displayName,
                mimeType = mimeFor(file),
                localPath = file.absolutePath,
                size = file.length(),
                modifiedAt = file.lastModified(),
                category = category,
                deletable = deletable
            )
        }
    }

    private fun sizeOf(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun isInside(file: File, root: File): Boolean = runCatching {
        val canonicalFile = file.canonicalFile
        val canonicalRoot = root.canonicalFile
        canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
    }.getOrDefault(false)

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "yaml", "yml" -> "application/yaml"
        else -> "application/octet-stream"
    }
}
''')

# OpenRouter can read both picker URIs and persistent project files.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''    private fun readAttachment(attachment: PendingAttachment): ByteArray {\n        val uri = Uri.parse(attachment.uri)\n        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }\n            ?: error("Не удалось прочитать ${attachment.name}")\n    }''',
    '''    private fun readAttachment(attachment: PendingAttachment): ByteArray {\n        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->\n            val file = File(path)\n            if (!file.exists()) error("Файл проекта не найден: ${attachment.name}")\n            return file.readBytes()\n        }\n        val uri = Uri.parse(attachment.uri)\n        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }\n            ?: error("Не удалось прочитать ${attachment.name}")\n    }'''
)

# ViewModel imports and repositories.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    "import com.ayuemin.ymnik.data.ChatRepository\n",
    "import com.ayuemin.ymnik.data.ChatRepository\nimport com.ayuemin.ymnik.data.ProjectRepository\n"
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    "import com.ayuemin.ymnik.model.GeneratedFile\n",
    "import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.PendingAttachment\nimport com.ayuemin.ymnik.model.Project\nimport com.ayuemin.ymnik.model.ProjectFile\n"
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    "    private val chatsRepository = ChatRepository(context)\n    private val storageRepository = StorageRepository(context)\n",
    "    private val chatsRepository = ChatRepository(context)\n    private val projectsRepository = ProjectRepository(context)\n    private val storageRepository = StorageRepository(context)\n"
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    "            chats = initialChats,\n            currentChatId = initialChatId,\n",
    "            chats = initialChats,\n            projects = projectsRepository.list(),\n            currentChatId = initialChatId,\n"
)

# Chat creation now optionally belongs to a project and returns its id.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun createChat() {\n        if (_state.value.isLoading) return\n        val chat = ChatSession(\n            id = UUID.randomUUID().toString(),\n            title = "Новый чат"\n        )\n        val next = listOf(chat) + _state.value.chats\n        chatsRepository.save(next)\n        prefs.edit().putString("current_chat_id", chat.id).apply()\n        _state.value = _state.value.copy(\n            chats = next,\n            currentChatId = chat.id,\n            messages = emptyList(),\n            pendingAttachments = emptyList(),\n            storageStats = storageRepository.stats()\n        )\n    }''',
    '''    fun createChat(projectId: String? = null): String {\n        if (_state.value.isLoading) return _state.value.currentChatId\n        val chat = ChatSession(\n            id = UUID.randomUUID().toString(),\n            title = "Новый чат",\n            projectId = projectId\n        )\n        val next = listOf(chat) + _state.value.chats\n        chatsRepository.save(next)\n        prefs.edit().putString("current_chat_id", chat.id).apply()\n        _state.value = _state.value.copy(\n            chats = next,\n            currentChatId = chat.id,\n            messages = emptyList(),\n            pendingAttachments = emptyList(),\n            storageStats = storageRepository.stats()\n        )\n        return chat.id\n    }'''
)

# Insert profiles, favorites and project management before model refresh.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    "\n    fun refreshModels(mode: ChatMode) {",
    r'''

    fun updateChatProfile(id: String, title: String, role: String, masterPrompt: String) {
        if (_state.value.isLoading) return
        val chats = _state.value.chats.map { chat ->
            if (chat.id == id) chat.copy(
                title = title.trim().ifBlank { "Новый чат" },
                assignedRole = role.trim().takeIf { it.isNotBlank() },
                masterPrompt = masterPrompt.trim().takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(chats = chats, messages = current?.messages ?: _state.value.messages)
    }

    fun setChatFavorite(id: String, favorite: Boolean) {
        val chats = _state.value.chats.map { chat ->
            if (chat.id == id) chat.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(chats = chats)
    }

    fun createProject(
        name: String,
        role: String = "",
        masterPrompt: String = "",
        favorite: Boolean = false
    ): String {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Новый проект" },
            role = role.trim(),
            masterPrompt = masterPrompt.trim(),
            isFavorite = favorite
        )
        val projects = listOf(project) + _state.value.projects
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
        return project.id
    }

    fun updateProject(id: String, name: String, role: String, masterPrompt: String, favorite: Boolean) {
        val now = System.currentTimeMillis()
        val projects = _state.value.projects.map { project ->
            if (project.id == id) project.copy(
                name = name.trim().ifBlank { "Проект" },
                role = role.trim(),
                masterPrompt = masterPrompt.trim(),
                isFavorite = favorite,
                updatedAt = now
            ) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects, storageStats = storageRepository.stats())
    }

    fun setProjectFavorite(id: String, favorite: Boolean) {
        val projects = _state.value.projects.map { project ->
            if (project.id == id) project.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
    }

    fun toggleProjectSkill(projectId: String, skillId: String) {
        val projects = _state.value.projects.map { project ->
            if (project.id != projectId) project else {
                val next = project.skillIds.toMutableSet().apply { if (!add(skillId)) remove(skillId) }
                project.copy(skillIds = next, updatedAt = System.currentTimeMillis())
            }
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
    }

    fun addProjectFile(projectId: String, uri: Uri) {
        runCatching { projectsRepository.importFile(projectId, uri) }
            .onSuccess { file ->
                val projects = _state.value.projects.map { project ->
                    if (project.id == projectId) project.copy(
                        files = project.files + file,
                        updatedAt = System.currentTimeMillis()
                    ) else project
                }
                projectsRepository.save(projects)
                _state.value = _state.value.copy(
                    projects = projects,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Файл «${file.name}» добавлен в проект"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить файл") }
    }

    fun deleteProjectFile(projectId: String, fileId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val file = project.files.firstOrNull { it.id == fileId } ?: return
        projectsRepository.deleteFile(file)
        val projects = _state.value.projects.map {
            if (it.id == projectId) it.copy(
                files = it.files.filterNot { f -> f.id == fileId },
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun importProjectPromptFile(projectId: String, uri: Uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: error("Не удалось прочитать файл")
        }.onSuccess { text ->
            val current = _state.value.projects.firstOrNull { it.id == projectId } ?: return@onSuccess
            updateProject(current.id, current.name, current.role, text, current.isFavorite)
            _state.value = _state.value.copy(status = "Мастер-промпт загружен из файла")
        }.onFailure {
            _state.value = _state.value.copy(status = it.message ?: "Не удалось загрузить мастер-промпт")
        }
    }

    fun deleteProject(projectId: String) {
        if (_state.value.isLoading) return
        projectsRepository.deleteProjectFiles(projectId)
        val projects = _state.value.projects.filterNot { it.id == projectId }
        projectsRepository.save(projects)

        // Диалоги не уничтожаем: после удаления проекта они становятся обычными чатами.
        val chats = _state.value.chats.map { chat ->
            if (chat.projectId == projectId) chat.copy(projectId = null) else chat
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(
            projects = projects,
            chats = chats,
            messages = current?.messages ?: _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Проект удалён. Его чаты сохранены как обычные."
        )
    }

    fun refreshModels(mode: ChatMode) {'''
)

# Enrich send() with project context and persistent files.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val chatId = _state.value.currentChatId\n        val before = _state.value.messages\n''',
    '''        val chatId = _state.value.currentChatId\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }\n        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }\n        val before = _state.value.messages\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''                    ChatMode.TEXT -> {\n                        val skillText = skills.promptFor(_state.value.activeSkillIds)\n                        api.chat(\n                            key,\n                            textModel,\n                            before,\n                            clean,\n                            pending,\n                            buildSystemPrompt(skillText),\n                            webSearchEnabled,\n                            reasoningEnabled\n                        )\n                    }\n                    ChatMode.IMAGE -> api.generateImage(key, imageModel, clean, pending)''',
    '''                    ChatMode.TEXT -> {\n                        val skillIds = _state.value.activeSkillIds + (currentProject?.skillIds ?: emptySet())\n                        val skillText = skills.promptFor(skillIds)\n                        val projectFiles = currentProject?.files.orEmpty().map { file ->\n                            PendingAttachment(\n                                uri = "project://${file.id}",\n                                name = file.name,\n                                mimeType = file.mimeType,\n                                size = file.size,\n                                localPath = file.localPath\n                            )\n                        }\n                        api.chat(\n                            key,\n                            textModel,\n                            before,\n                            clean,\n                            pending + projectFiles,\n                            buildSystemPrompt(skillText, currentProject, currentChat),\n                            webSearchEnabled,\n                            reasoningEnabled\n                        )\n                    }\n                    ChatMode.IMAGE -> {\n                        val projectImages = currentProject?.files.orEmpty()\n                            .filter { it.mimeType.startsWith("image/") }\n                            .map { file -> PendingAttachment(\n                                uri = "project://${file.id}",\n                                name = file.name,\n                                mimeType = file.mimeType,\n                                size = file.size,\n                                localPath = file.localPath\n                            ) }\n                        val projectPrefix = buildImageProjectPrompt(currentProject, currentChat)\n                        api.generateImage(key, imageModel, listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\\n\\n"), pending + projectImages)\n                    }'''
)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    private fun buildSystemPrompt(skillText: String): String = buildString {\n        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")''',
    '''    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?): String = buildString {\n        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n        if (project != null) {\n            appendLine("\\n===== ПРОЕКТ: ${project.name} =====")\n            if (project.role.isNotBlank()) appendLine("Роль в проекте: ${project.role}")\n            if (project.masterPrompt.isNotBlank()) {\n                appendLine("Мастер-инструкция проекта:")\n                appendLine(project.masterPrompt)\n            }\n            if (project.files.isNotEmpty()) {\n                appendLine("Постоянные файлы проекта приложены к текущему запросу. Используй их как рабочий контекст, когда они релевантны.")\n            }\n            appendLine("===== КОНЕЦ НАСТРОЕК ПРОЕКТА =====")\n        }\n        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {\n            appendLine("\\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")\n            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }\n            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let {\n                appendLine("Мастер-инструкция диалога:")\n                appendLine(it)\n            }\n            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")\n        }'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    private fun replaceChatMessages(\n''',
    '''    private fun buildImageProjectPrompt(project: Project?, chat: ChatSession?): String = buildString {\n        project?.let {\n            if (it.role.isNotBlank()) appendLine("Роль/стиль: ${it.role}")\n            if (it.masterPrompt.isNotBlank()) appendLine(it.masterPrompt)\n        }\n        chat?.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль: $it") }\n        chat?.masterPrompt?.takeIf { it.isNotBlank() }?.let { appendLine(it) }\n    }.trim()\n\n    private fun replaceChatMessages(\n'''
)

# Project UI is separate to keep the main screen manageable.
write("app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt", r'''
package com.ayuemin.ymnik.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var editorId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    val chats = state.chats.sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = chats.filter { it.isFavorite }
    val others = chats.filterNot { it.isFavorite }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Чаты") },
        text = {
            Column {
                FilledTonalButton(
                    onClick = {
                        vm.createChat()
                        onDismiss()
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Новый чат")
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 470.dp)) {
                    if (favorites.isNotEmpty()) {
                        item { SectionTitle("Избранные") }
                        items(favorites, key = { it.id }) { chat ->
                            ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                        }
                    }
                    if (others.isNotEmpty()) {
                        item { SectionTitle(if (favorites.isEmpty()) "Все чаты" else "Остальные") }
                        items(others, key = { it.id }) { chat ->
                            ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    state.chats.firstOrNull { it.id == editorId }?.let { chat ->
        ChatProfileDialog(chat, vm) { editorId = null }
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${chat.title}» будет удалён вместе с его локальными сгенерированными файлами.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ChatHubRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val projectName = chat.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                vm.switchChat(chat.id)
                onDismiss()
            },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    chat.title,
                    fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (projectName != null) append("$projectName · ")
                        append("${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { vm.setChatFavorite(chat.id, !chat.isFavorite) }) {
            Icon(
                if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (chat.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Настроить чат")
        }
        IconButton(onClick = onDelete, enabled = !state.isLoading) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить чат")
        }
    }
    HorizontalDivider()
}

@Composable
private fun ChatProfileDialog(chat: ChatSession, vm: ChatViewModel, onDismiss: () -> Unit) {
    var title by remember(chat.id) { mutableStateOf(chat.title) }
    var role by remember(chat.id) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var prompt by remember(chat.id) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id) { mutableStateOf(chat.isFavorite) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки диалога") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                }
                item {
                    OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Роль") }, placeholder = { Text("Например: главный редактор") })
                }
                item {
                    OutlinedTextField(
                        prompt,
                        { prompt = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Мастер-промпт") },
                        placeholder = { Text("Постоянная инструкция только для этого чата") },
                        minLines = 5,
                        maxLines = 12
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Избранное", Modifier.weight(1f))
                        Switch(favorite, { favorite = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.updateChatProfile(chat.id, title, role, prompt)
                vm.setChatFavorite(chat.id, favorite)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ProjectsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var openProjectId by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    val projects = state.projects.sortedWith(compareByDescending<Project> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = projects.filter { it.isFavorite }
    val others = projects.filterNot { it.isFavorite }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проекты") },
        text = {
            Column {
                FilledTonalButton(onClick = { createOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Новый проект")
                }
                Spacer(Modifier.height(10.dp))
                if (projects.isEmpty()) {
                    Text(
                        "Проект объединяет мастер-промпт, роль, постоянные файлы, навыки и несколько отдельных чатов.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 470.dp)) {
                        if (favorites.isNotEmpty()) {
                            item { SectionTitle("Избранные") }
                            items(favorites, key = { it.id }) { project ->
                                ProjectRow(project, state, vm) { openProjectId = project.id }
                            }
                        }
                        if (others.isNotEmpty()) {
                            item { SectionTitle(if (favorites.isEmpty()) "Все проекты" else "Остальные") }
                            items(others, key = { it.id }) { project ->
                                ProjectRow(project, state, vm) { openProjectId = project.id }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    if (createOpen) {
        ProjectEditorDialog(project = null, onDismiss = { createOpen = false }) { name, role, prompt, favorite ->
            openProjectId = vm.createProject(name, role, prompt, favorite)
            createOpen = false
        }
    }

    state.projects.firstOrNull { it.id == openProjectId }?.let { project ->
        ProjectDetailDialog(
            project = project,
            state = state,
            vm = vm,
            onDismiss = { openProjectId = null },
            onOpenChat = { chatId ->
                vm.switchChat(chatId)
                openProjectId = null
                onDismiss()
            },
            onCreateChat = {
                vm.createChat(project.id)
                openProjectId = null
                onDismiss()
            }
        )
    }
}

@Composable
private fun ProjectRow(project: Project, state: UiState, vm: ChatViewModel, onOpen: () -> Unit) {
    val count = state.chats.count { it.projectId == project.id }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$count чатов · ${project.files.size} файлов · ${project.skillIds.size} навыков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { vm.setProjectFavorite(project.id, !project.isFavorite) }) {
            Icon(
                if (project.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (project.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ProjectDetailDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCreateChat: () -> Unit
) {
    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })

    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addProjectFile(project.id, it) }
    }
    val importPrompt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.importProjectPromptFile(project.id, it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (project.role.isNotBlank()) {
                    item {
                        Text("Роль: ${project.role}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (project.masterPrompt.isNotBlank()) {
                    item {
                        Text(
                            project.masterPrompt,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onCreateChat, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text("Новый чат")
                        }
                        IconButton(onClick = { editOpen = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Настройки проекта")
                        }
                    }
                }

                item { SectionTitle("Чаты проекта") }
                if (projectChats.isEmpty()) {
                    item { Text("Пока нет диалогов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(projectChats, key = { it.id }) { chat ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpenChat(chat.id) }, modifier = Modifier.weight(1f)) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { vm.setChatFavorite(chat.id, !chat.isFavorite) }) {
                                Icon(
                                    if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Избранное"
                                )
                            }
                        }
                    }
                }

                item { SectionTitle("Навыки проекта") }
                if (state.skills.isEmpty()) {
                    item { Text("Импортируйте навыки во вкладке с пазлом", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.skills, key = { it.id }) { skill ->
                                FilterChip(
                                    selected = skill.id in project.skillIds,
                                    onClick = { vm.toggleProjectSkill(project.id, skill.id) },
                                    label = { Text(skill.name, maxLines = 1) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(17.dp))
                                    }
                                )
                            }
                        }
                    }
                }

                item { SectionTitle("Постоянные файлы") }
                if (project.files.isEmpty()) {
                    item { Text("Нет файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(project.files, key = { it.id }) { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(projectSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.deleteProjectFile(project.id, file.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
                            }
                        }
                    }
                }
                item {
                    FilledTonalButton(onClick = { addFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить постоянные файлы")
                    }
                }
                item {
                    TextButton(
                        onClick = { importPrompt.launch(arrayOf("text/plain", "text/markdown", "application/json")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Загрузить мастер-промпт из файла")
                    }
                }
                item {
                    TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Удалить проект")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    if (editOpen) {
        ProjectEditorDialog(project, { editOpen = false }) { name, role, prompt, favorite ->
            vm.updateProject(project.id, name, role, prompt, favorite)
            editOpen = false
        }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить проект?") },
            text = { Text("Мастер-промпт и постоянные файлы проекта будут удалены. Его чаты сохранятся и станут обычными чатами.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(project.id)
                    deleteConfirm = false
                    onDismiss()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var role by remember(project?.id) { mutableStateOf(project?.role.orEmpty()) }
    var prompt by remember(project?.id) { mutableStateOf(project?.masterPrompt.orEmpty()) }
    var favorite by remember(project?.id) { mutableStateOf(project?.isFavorite ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "Новый проект" else "Настройки проекта") },
        text = {
            LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                }
                item {
                    OutlinedTextField(
                        role,
                        { role = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Роль") },
                        placeholder = { Text("Например: главный редактор IT-канала") }
                    )
                }
                item {
                    OutlinedTextField(
                        prompt,
                        { prompt = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Мастер-промпт") },
                        placeholder = { Text("Эта инструкция автоматически добавляется ко всем чатам проекта") },
                        minLines = 6,
                        maxLines = 14
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Избранное", Modifier.weight(1f))
                        Switch(favorite, { favorite = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, role, prompt, favorite) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 9.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun projectDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun projectSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
''')

# Minimal patch of the main UI: add a Projects launcher next to Chats and use the new chat hub.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    "    var chatsOpen by remember { mutableStateOf(false) }\n    val listState = rememberLazyListState()\n",
    "    var chatsOpen by remember { mutableStateOf(false) }\n    var projectsOpen by remember { mutableStateOf(false) }\n    val listState = rememberLazyListState()\n"
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''            onChats = { chatsOpen = true },\n            onSelectMode = { mode ->''',
    '''            onChats = { chatsOpen = true },\n            onProjects = { projectsOpen = true },\n            onSelectMode = { mode ->'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''        ChatsDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { chatsOpen = false }\n        )''',
    '''        ChatsHubDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { chatsOpen = false }\n        )'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    if (chatsOpen) {\n        ChatsHubDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { chatsOpen = false }\n        )\n    }\n}''',
    '''    if (chatsOpen) {\n        ChatsHubDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { chatsOpen = false }\n        )\n    }\n\n    if (projectsOpen) {\n        ProjectsDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { projectsOpen = false }\n        )\n    }\n}'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    onChats: () -> Unit,\n    onSelectMode: (ChatMode) -> Unit,''',
    '''    onChats: () -> Unit,\n    onProjects: () -> Unit,\n    onSelectMode: (ChatMode) -> Unit,'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                Spacer(Modifier.weight(1f))\n\n                IconButton(\n                    onClick = onChats,''',
    '''                Spacer(Modifier.weight(1f))\n\n                IconButton(\n                    onClick = onProjects,\n                    enabled = !state.isLoading,\n                    modifier = Modifier.size(36.dp)\n                ) {\n                    val currentProjectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId\n                    Icon(\n                        Icons.Outlined.FolderOpen,\n                        contentDescription = "Проекты",\n                        tint = if (currentProjectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                }\n\n                IconButton(\n                    onClick = onChats,'''
)

# Storage summary mentions project files too.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''"Навыки: ${humanSize(state.storageStats.skillBytes)} · история чатов: ${humanSize(state.storageStats.chatBytes)}",''',
    '''"Навыки: ${humanSize(state.storageStats.skillBytes)} · проекты: ${humanSize(state.storageStats.projectBytes)}\\n" +\n                            "История чатов: ${humanSize(state.storageStats.chatBytes)}",'''
)

# Release metadata.
replace_once(
    "app/build.gradle.kts",
    '''        versionCode = 8\n        versionName = "0.5.1"''',
    '''        versionCode = 9\n        versionName = "0.6.0"'''
)

workflow = ROOT / ".github/workflows/android-release.yml"
w = workflow.read_text(encoding="utf-8")
w = w.replace('VERSION: "0.5.1"', 'VERSION: "0.6.0"')
start = w.find('          gh release create')
if start == -1:
    raise RuntimeError("Release block not found")
# Keep the shell command structure, but replace only release notes body from --notes onward.
marker = '            --notes "'
pos = w.find(marker, start)
if pos == -1:
    raise RuntimeError("Release notes marker not found")
end = w.find('"\n', pos + len(marker))
# Notes are multiline, so find the final quote at end of file instead.
last_quote = w.rfind('"')
if last_quote <= pos:
    raise RuntimeError("Release notes closing quote not found")
notes = '''Umnik v0.6.0.\n\n          Главное:\n          - добавлены полноценные проекты: роль, мастер-промпт, постоянные файлы, навыки и несколько чатов внутри проекта\n          - отдельные кнопки Чаты и Проекты находятся в верхней панели\n          - и в чатах, и в проектах избранные всегда показываются сверху\n          - обычному чату тоже можно задать собственную роль и мастер-промпт\n          - постоянные файлы проекта автоматически передаются модели вместе с запросом\n          - мастер-промпт проекта можно загрузить из txt/md файла\n          - удаление проекта не уничтожает его диалоги: они становятся обычными чатами\n          - файлы проектов учитываются во встроенном хранилище\n          - веб-поиск, размышление, навыки, TTS, генерация изображений и несколько чатов сохранены'''
w = w[:pos + len(marker)] + notes + w[last_quote:]
workflow.write_text(w, encoding="utf-8")

print("Projects patch applied")
