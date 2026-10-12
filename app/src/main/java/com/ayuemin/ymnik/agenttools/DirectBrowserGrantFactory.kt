package com.ayuemin.ymnik.agenttools

import java.net.URI
import java.util.Locale

/**
 * Constructs narrow, exact-origin browse grants only at the trusted UI
 * submission boundary. It must never be invoked for model-generated text,
 * DOM, OCR, tools, downloaded files or history.
 *
 * This intentionally does NOT grant clicks, downloads or Shell execution.
 * Browser search without a user-specified origin needs a separate explicit
 * public-web-read authorization design, not an implicit wildcard.
 */
internal object DirectBrowserGrantFactory {
    private const val MAX_AGE_MS = 15 * 60 * 1_000L
    private val URL = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

    fun fromUiSubmission(
        taskId: String,
        directUserMessageId: String,
        directUserText: String,
        issuedAtMillis: Long
    ): TaskGrant? {
        val origins = URL.findAll(directUserText).mapNotNull {
            origin(it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}'))
        }.distinct().take(8).toList()
        if (origins.isEmpty()) return null
        val allowed = origins.flatMap { origin ->
            val resource = TaskResource(ToolResourceKind.WEB_ORIGIN, origin)
            listOf(
                TaskAllowance("local_browser_open", ToolAction.NAVIGATE, resource),
                TaskAllowance("local_browser_read", ToolAction.READ, resource),
                TaskAllowance("local_browser_scroll", ToolAction.READ, resource),
                TaskAllowance("local_browser_wait", ToolAction.READ, resource),
                TaskAllowance("local_browser_back", ToolAction.NAVIGATE, resource)
            )
        }.toSet()
        return TaskGrant.fromTrustedUserMessage(
            taskId = taskId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + MAX_AGE_MS,
            allowedOperations = allowed
        )
    }

    /** Only syntactic normalization. The Browser runtime must still enforce SSRF. */
    fun origin(url: String): String? = runCatching {
        val parsed = URI(url.trim())
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") return null
        if (parsed.userInfo != null) return null
        val host = parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?.takeIf { it.isNotBlank() && !it.contains('*') } ?: return null
        val port = parsed.port
        if (port !in -1..65535 || port == 0) return null
        val normalizedPort = when {
            scheme == "https" && port == 443 -> -1
            scheme == "http" && port == 80 -> -1
            else -> port
        }
        URI(scheme, null, host, normalizedPort, null, null, null).toString()
    }.getOrNull()
}
