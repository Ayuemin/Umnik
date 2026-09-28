package com.ayuemin.ymnik.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class UserFontEntry(
    val id: String,
    val name: String,
    val localPath: String
)

data class UserFontState(
    val fonts: List<UserFontEntry> = emptyList(),
    val selectedId: String? = null
) {
    val selected: UserFontEntry?
        get() = fonts.firstOrNull { it.id == selectedId }
}

/**
 * User-imported interface fonts.
 *
 * Files live in a dedicated internal directory which StorageRepository does not expose.
 * They are therefore managed only from Interface settings.
 */
internal object UserFontStore {
    private const val PREFS = "user_interface_fonts"
    private const val KEY_FONTS = "fonts_json"
    private const val KEY_SELECTED = "selected_font_id"
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L

    private val gson = Gson()
    private val stateFlow = MutableStateFlow(UserFontState())
    val state: StateFlow<UserFontState> = stateFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = object : TypeToken<List<UserFontEntry>>() {}.type
        val stored = runCatching {
            gson.fromJson<List<UserFontEntry>>(prefs.getString(KEY_FONTS, "[]"), type)
        }.getOrDefault(emptyList())
            .filter { entry -> File(entry.localPath).isFile }
        val selected = prefs.getString(KEY_SELECTED, null)?.takeIf { id -> stored.any { it.id == id } }
        stateFlow.value = UserFontState(stored, selected)
        persist(app)
        initialized = true
    }

    @Synchronized
    fun importFont(context: Context, uri: Uri): Result<UserFontEntry> = runCatching {
        initialize(context)
        val app = context.applicationContext
        val displayName = queryDisplayName(app, uri).ifBlank { "font" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension == "ttf" || extension == "otf") {
            "Поддерживаются шрифты TTF и OTF"
        }

        val root = File(app.filesDir, "ui_fonts").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val target = File(root, "$id.$extension")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FONT_BYTES) { "Файл шрифта больше 20 МБ" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Не удалось прочитать файл шрифта")

            require(target.length() > 0L) { "Файл шрифта пуст" }
            Typeface.createFromFile(target)

            val entry = UserFontEntry(id = id, name = displayName, localPath = target.absolutePath)
            val next = stateFlow.value.fonts + entry
            stateFlow.value = UserFontState(next, entry.id)
            persist(app)
            entry
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    @Synchronized
    fun select(context: Context, id: String?) {
        initialize(context)
        val app = context.applicationContext
        val validId = id?.takeIf { candidate -> stateFlow.value.fonts.any { it.id == candidate } }
        stateFlow.value = stateFlow.value.copy(selectedId = validId)
        persist(app)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        initialize(context)
        val app = context.applicationContext
        val existing = stateFlow.value.fonts.firstOrNull { it.id == id } ?: return
        runCatching { File(existing.localPath).delete() }
        val remaining = stateFlow.value.fonts.filterNot { it.id == id }
        stateFlow.value = UserFontState(
            fonts = remaining,
            selectedId = stateFlow.value.selectedId?.takeUnless { it == id }
        )
        persist(app)
    }

    private fun persist(context: Context) {
        val value = stateFlow.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FONTS, gson.toJson(value.fonts))
            .apply {
                if (value.selectedId == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, value.selectedId)
            }
            .apply()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index).orEmpty()
            }
        }
        return name.substringAfterLast('/').substringAfterLast('\\').take(160)
    }
}
