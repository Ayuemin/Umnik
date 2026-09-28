package com.ayuemin.ymnik.network

import java.security.MessageDigest
import java.util.Base64

internal object ShellAttachmentEnvelope {
    data class Encoded(
        val transportName: String,
        val bytes: ByteArray,
        val sha256: String
    )

    fun encode(
        originalName: String,
        mimeType: String,
        bytes: ByteArray,
        index: Int
    ): Encoded {
        require(bytes.isNotEmpty()) { "Файл пуст" }
        val cleanName = originalName.trim().ifBlank { "attachment.bin" }
        val cleanMime = mimeType.trim().ifBlank { "application/octet-stream" }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val nameBase64 = Base64.getEncoder().encodeToString(cleanName.toByteArray(Charsets.UTF_8))
        val mimeBase64 = Base64.getEncoder().encodeToString(cleanMime.toByteArray(Charsets.UTF_8))
        val payload = Base64.getMimeEncoder(76, "\n".toByteArray())
            .encodeToString(bytes)

        val text = buildString {
            appendLine("UMNIK_BASE64_ATTACHMENT_V1")
            appendLine("original_name_base64=$nameBase64")
            appendLine("mime_base64=$mimeBase64")
            appendLine("size=${bytes.size}")
            appendLine("sha256=$sha")
            appendLine("data_base64_begin")
            appendLine(payload)
            appendLine("data_base64_end")
        }
        return Encoded(
            transportName = "umnik_attachment_${index + 1}.b64.txt",
            bytes = text.toByteArray(Charsets.UTF_8),
            sha256 = sha
        )
    }
}
