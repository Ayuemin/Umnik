package com.ayuemin.ymnik.network

/**
 * A capability permit originates ONLY from the newest direct UI user message.
 * Web pages, tool results, uploaded documents, skills and model instructions
 * must never be passed as directUserText or used to expand these permissions.
 *
 * This is a defense-in-depth boundary, not a semantic proof of user consent:
 * remote mutations additionally require an explicit user confirmation.
 */
internal data class DirectUserToolAuthority private constructor(
    val mayUseShell: Boolean,
    val mayUseBrowserActions: Boolean,
    val mayUseRemoteCommands: Boolean
) {
    companion object {
        fun fromDirectMessage(directUserText: String): DirectUserToolAuthority {
            val text = directUserText.trim()
            if (text.isEmpty()) return DirectUserToolAuthority(false, false, false)
            val shell = ChatToolPolicy.needsCreateFile(text) ||
                Regex("""(?iu)\b(shell|терминал|python|питон|скрипт|архив|zip|проект|репозиторий|код|файлы|файлов|папку|папки|обработай|исправь|создай|сгенерируй)\b""").containsMatchIn(text)
            val browser = Regex("""(?iu)(https?://|\b(сайт|ссылк|страниц|браузер|browser|интернет|поищ|найди|скачай|скачать|открой|перейди|зайди|нажми|заполни|проверь)\b)""").containsMatchIn(text)
            val remote = Regex("""(?iu)\b(ssh|sftp|vps|сервер|сервере|linux|удалённый\s+хост)\b""").containsMatchIn(text)
            return DirectUserToolAuthority(shell, browser, remote)
        }
    }

    fun permits(toolName: String): Boolean = when {
        toolName == "local_shell_start" -> mayUseShell
        toolName.startsWith("local_browser_") -> mayUseBrowserActions
        toolName.startsWith("remote_ssh_") -> false // Deliberately fail-closed until consent UI exists.
        else -> true // Read-only internal helpers retain existing behaviour.
    }
}
