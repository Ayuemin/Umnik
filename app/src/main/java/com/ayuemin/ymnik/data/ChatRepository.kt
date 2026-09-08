package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ChatSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ChatRepository(context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "chats").apply { mkdirs() }
    private val metadata = File(root, "chats.json")

    fun list(): List<ChatSession> = runCatching {
        if (!metadata.exists()) return emptyList()
        val type = object : TypeToken<List<ChatSession>>() {}.type
        gson.fromJson<List<ChatSession>>(metadata.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun save(chats: List<ChatSession>) {
        root.mkdirs()
        metadata.writeText(gson.toJson(chats))
    }

    fun dataSize(): Long = if (metadata.exists()) metadata.length() else 0L
}
