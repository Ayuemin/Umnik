package com.ayuemin.ymnik.agenttools

import android.content.Context
import android.util.Base64
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Direct, on-device GitHub REST client for the main agent. No browser automation
 * and no token transmission through an LLM tool argument/result.
 *
 * Only the trusted current UI turn can create [GitHubAgentScope]. The adapter
 * checks it before EVERY operation, including every commit and workflow action.
 * Existing default/protected branches, releases and repository deletion are
 * deliberately excluded from implicit write authority.
 */
internal class GitHubAgentTool(
    private val context: Context,
    private val secrets: SecretStore = SecretStore(context)
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Bound to this particular agent request. Never shared with a future turn.
    private var createdRepository: String? = null

    suspend fun checkConnection(repository: String): String = withContext(Dispatchers.IO) {
        val token = secrets.getGitHubToken()?.takeIf { it.isNotBlank() }
            ?: return@withContext "Токен GitHub не настроен"
        return@withContext runCatching {
            val user = api("GET", "user", token).asJsonObject
            val login = user.value("login")
            val repo = GitHubAgentScope.normalizeRepo(repository)
            if (repo != null) {
                api("GET", "repos/${segments(repo)}", token)
                "GitHub подключён: $login; репозиторий $repo доступен"
            } else {
                "GitHub подключён: $login. Репозиторий можно создать через агента."
            }
        }.getOrElse { error ->
            if (error is GitHubApiException) "GitHub: HTTP ${error.status}. Проверьте права токена и репозиторий"
            else "GitHub: соединение не удалось"
        }
    }

    suspend fun execute(
        kind: String,
        arguments: String,
        scope: GitHubAgentScope?,
        trustedChatId: String,
        trustedMessageId: String,
        directUserText: String
    ): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var operation = "unknown"
        val result = runCatching {
            require(scope != null) { "github_connection_not_authorized" }
            require(scope.chatId == trustedChatId && scope.directMessageId == trustedMessageId)
            require(System.currentTimeMillis() in scope.issuedAtMillis until scope.expiresAtMillis) { "task_grant_expired" }
            val input = JsonParser.parseString(arguments).asJsonObject
            operation = input.value("operation").lowercase()
            val p = input.getAsJsonObject("params") ?: JsonObject()
            val readOps = if (kind == "actions") actionReadOps else repoReadOps
            val writeOps = if (kind == "actions") actionWriteOps else repoWriteOps
            require(operation in readOps || operation in writeOps) { "unsupported_operation" }
            val mutation = operation in writeOps
            if (mutation) require(scope.mayWrite) { "write_needs_direct_user_instruction" }
            val token = secrets.getGitHubToken()?.takeIf { it.isNotBlank() }
                ?: error("github_token_not_configured")
            // No caller-controlled URL or hostname: only GitHub API with a fixed root.
            val repo = createdRepository ?: scope.allowedRepository
            val needsRepo = operation !in setOf("list_repos", "create_repo", "viewer")
            if (needsRepo) {
                require(GitHubAgentScope.normalizeRepo(repo) != null) { "select_or_create_repository_first" }
            }
            when (kind) {
                "repo" -> handleRepository(operation, p, repo, token, scope, directUserText)
                "actions" -> handleActions(operation, p, repo, token)
                else -> error("unsupported_tool")
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                JsonObject().apply {
                    addProperty("ok", false)
                    // Never echo provider response bodies, secrets, URL query or tool content.
                    addProperty("reason", if (error is GitHubApiException) "github_http_${error.status}" else
                        error.message?.takeIf { it in allowedErrors } ?: "github_operation_failed")
                    if (error is GitHubApiException && error.status == 403) {
                        addProperty("hint", "Проверьте права PAT на Contents/Actions/Issues/Pull requests и ограничения репозитория")
                    }
                }
            }
        )
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        DiagnosticLog.record(context, "GITHUB_TOOL",
            "kind=${kind.take(12)}; op=${operation.take(32)}; ok=${result.get("ok")?.asBoolean}; elapsedMs=$elapsedMs")
        gson.toJson(result)
    }

    private fun handleRepository(
        op: String, p: JsonObject, repo: String, token: String,
        scope: GitHubAgentScope, directUserText: String
    ): JsonObject {
        val base = "repos/${segments(repo)}"
        return when (op) {
            "viewer" -> ok(api("GET", "user", token).compact())
            "list_repos" -> ok(api("GET", "user/repos?sort=updated&per_page=50&page=${page(p)}", token).compact())
            "create_repo" -> {
                require(scope.allowedRepository.isBlank() || scope.mayWrite) { "write_needs_direct_user_instruction" }
                val name = p.value("name")
                require(Regex("^[A-Za-z0-9_.-]{1,100}$").matches(name) && !name.contains("..")) { "invalid_repo_name" }
                val private = p.boolean("private", true)
                val response = api("POST", "user/repos", token, obj(
                    "name" to name,
                    "description" to p.value("description").take(500),
                    "private" to private,
                    "auto_init" to true
                )).asJsonObject
                val full = response.value("full_name")
                require(GitHubAgentScope.normalizeRepo(full) != null) { "github_invalid_new_repo" }
                createdRepository = full
                ok(mapOf("repository" to full, "default_branch" to response.value("default_branch"),
                    "created" to true, "next_step" to "create_branch"))
            }
            "repo_info" -> ok(api("GET", base, token).compact())
            "list_tree" -> {
                val ref = p.value("ref").ifBlank { "HEAD" }
                val tree = api("GET", "$base/git/trees/${encode(ref)}?recursive=1", token).asJsonObject
                val entries = tree.getAsJsonArray("tree") ?: JsonArray()
                val output = JsonArray()
                entries.take(p.int("limit", 300).coerceIn(1, 1000)).forEach { entry ->
                    val o = entry.asJsonObject
                    output.add(obj("path" to o.value("path"), "type" to o.value("type"), "sha" to o.value("sha")))
                }
                ok(mapOf("entries" to output, "total_returned" to entries.size(),
                    "truncated" to (tree.get("truncated")?.asBoolean == true || entries.size() > output.size())))
            }
            "read_file" -> {
                val path = requireFile(p.value("path"), read = true)
                val ref = p.value("ref")
                val query = if (ref.isBlank()) "" else "?ref=${encode(ref)}"
                val obj = api("GET", "$base/contents/${pathUrl(path)}$query", token).asJsonObject
                require(obj.value("type") == "file") { "not_a_file" }
                val encoded = obj.value("content")
                val content = if (obj.value("encoding") == "base64") {
                    String(Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                } else error("binary_or_large_file_not_supported")
                val start = p.int("offset", 0).coerceIn(0, content.length)
                val length = p.int("length", 24_000).coerceIn(100, 48_000)
                ok(mapOf("path" to path, "sha" to obj.value("sha"), "size" to content.length,
                    "offset" to start, "next_offset" to (start + length).coerceAtMost(content.length),
                    "content" to content.substring(start, (start + length).coerceAtMost(content.length))))
            }
            "list_branches" -> ok(api("GET", "$base/branches?per_page=100&page=${page(p)}", token).compact())
            "create_branch" -> {
                val name = workingBranch(p.value("branch"))
                val baseRef = p.value("base").ifBlank { repoDefaultBranch(base, token) }
                val refData = api("GET", "$base/git/ref/heads/${pathUrl(baseRef)}", token).asJsonObject
                val sha = refData.getAsJsonObject("object")?.value("sha").orEmpty()
                require(sha.matches(Regex("[0-9a-f]{40,64}"))) { "invalid_ref_sha" }
                ok(api("POST", "$base/git/refs", token, obj(
                    "ref" to "refs/heads/$name", "sha" to sha
                )).compact())
            }
            "commit_files" -> commitFiles(p, base, token, directUserText)
            "list_commits" -> {
                val branch = p.value("branch").ifBlank { repoDefaultBranch(base, token) }
                ok(api("GET", "$base/commits?sha=${encode(branch)}&per_page=30&page=${page(p)}", token).compact())
            }
            "compare" -> {
                val older = p.value("base"); val newer = p.value("head")
                require(older.isNotBlank() && newer.isNotBlank()) { "missing_commit_refs" }
                ok(api("GET", "$base/compare/${encode(older)}...${encode(newer)}", token).compact())
            }
            "list_issues" -> ok(api("GET", "$base/issues?state=all&per_page=30&page=${page(p)}", token).compact())
            "read_issue" -> ok(api("GET", "$base/issues/${number(p)}", token).compact())
            "create_issue" -> ok(api("POST", "$base/issues", token, obj(
                "title" to p.value("title").take(200),
                "body" to p.value("body").take(24_000)
            )).compact())
            "comment_issue" -> ok(api("POST", "$base/issues/${number(p)}/comments", token,
                obj("body" to p.value("body").take(18_000))).compact())
            "list_prs" -> ok(api("GET", "$base/pulls?state=all&per_page=30&page=${page(p)}", token).compact())
            "read_pr" -> ok(api("GET", "$base/pulls/${number(p)}", token).compact())
            "create_pr" -> {
                val head = workingBranch(p.value("head"))
                val target = p.value("base").ifBlank { repoDefaultBranch(base, token) }
                ok(api("POST", "$base/pulls", token, obj(
                    "title" to p.value("title").take(200),
                    "head" to head, "base" to target,
                    "body" to p.value("body").take(24_000)
                )).compact())
            }
            // Merging into main, deleting remote data, publishing a release and
            // altering repository permissions require a separate trusted confirmation UI.
            else -> error("unsupported_operation")
        }
    }

    private fun commitFiles(p: JsonObject, base: String, token: String, directUserText: String): JsonObject {
        val branch = workingBranch(p.value("branch"))
        val files = p.getAsJsonArray("files") ?: error("missing_files")
        require(files.size() in 1..60) { "too_many_files" }
        var total = 0
        val paths = mutableSetOf<String>()
        val instructions = mutableListOf<Triple<String, String?, Boolean>>()
        files.forEach { element ->
            val file = element.asJsonObject
            val path = requireFile(file.value("path"), read = false)
            require(paths.add(path)) { "duplicate_file_path" }
            val delete = file.boolean("delete", false)
            if (delete) {
                // File deletion on a remote server is a destructive operation.
                require(Regex("(?i)(удал|delete|remove)").containsMatchIn(directUserText)) {
                    "deletion_needs_explicit_user_instruction"
                }
                instructions += Triple(path, null, true)
            } else {
                val content = file.value("content")
                total += content.toByteArray(Charsets.UTF_8).size
                require(content.length <= 150_000 && total <= 750_000) { "source_payload_too_large" }
                instructions += Triple(path, content, false)
            }
        }
        val ref = api("GET", "$base/git/ref/heads/${pathUrl(branch)}", token).asJsonObject
        val parent = ref.getAsJsonObject("object")?.value("sha").orEmpty()
        require(parent.matches(Regex("[0-9a-f]{40,64}"))) { "invalid_ref_sha" }
        val previous = api("GET", "$base/git/commits/$parent", token).asJsonObject
        val treeSha = previous.getAsJsonObject("tree")?.value("sha").orEmpty()
        require(treeSha.isNotBlank()) { "missing_parent_tree" }
        val nodes = JsonArray()
        instructions.forEach { (path, content, delete) ->
            val element = obj("path" to path, "mode" to "100644", "type" to "blob")
            if (delete) element.add("sha", JsonNull.INSTANCE)
            else {
                val blob = api("POST", "$base/git/blobs", token,
                    obj("content" to content.orEmpty(), "encoding" to "utf-8")).asJsonObject
                element.addProperty("sha", blob.value("sha"))
            }
            nodes.add(element)
        }
        val newTree = api("POST", "$base/git/trees", token, JsonObject().apply {
            addProperty("base_tree", treeSha); add("tree", nodes)
        }).asJsonObject.value("sha")
        val message = p.value("message").take(240).ifBlank { "Apply requested Android project changes" }
        val commit = api("POST", "$base/git/commits", token, JsonObject().apply {
            addProperty("message", message)
            addProperty("tree", newTree)
            add("parents", JsonArray().apply { add(parent) })
        }).asJsonObject
        val sha = commit.value("sha")
        // force=false protects concurrent commits and avoids a remote hard reset.
        api("PATCH", "$base/git/refs/heads/${pathUrl(branch)}", token,
            obj("sha" to sha, "force" to false))
        return ok(mapOf("commit" to sha, "branch" to branch, "files" to paths.size,
            "url" to "https://github.com/${base.removePrefix("repos/")}/commit/$sha"))
    }

    private suspend fun handleActions(op: String, p: JsonObject, repo: String, token: String): JsonObject {
        val base = "repos/${segments(repo)}"
        return when (op) {
            "list_workflows" -> ok(api("GET", "$base/actions/workflows?per_page=100", token).compact())
            "dispatch" -> {
                val workflow = p.value("workflow")
                require(workflow.matches(Regex("[A-Za-z0-9._-]{1,120}")) || workflow.matches(Regex("[0-9]{1,12}"))) { "invalid_workflow" }
                val ref = workingBranch(p.value("ref"))
                val inputs = p.getAsJsonObject("inputs") ?: JsonObject()
                require(inputs.size() <= 10) { "too_many_inputs" }
                ok(api("POST", "$base/actions/workflows/${encode(workflow)}/dispatches", token,
                    JsonObject().apply {
                        addProperty("ref", ref)
                        add("inputs", inputs)
                    }).compact())
            }
            "list_runs" -> {
                val ref = p.value("branch")
                val query = if (ref.isBlank()) "" else "&branch=${encode(ref)}"
                ok(api("GET", "$base/actions/runs?per_page=30&page=${page(p)}$query", token).compact())
            }
            "run" -> ok(api("GET", "$base/actions/runs/${number(p, "run_id")}", token).compact())
            "jobs" -> ok(api("GET", "$base/actions/runs/${number(p, "run_id")}/jobs?per_page=100", token).compact())
            "job_logs" -> ok(mapOf("logs" to readJobLog(base, number(p, "job_id"), token)))
            "wait_run" -> {
                val id = number(p, "run_id")
                val deadline = System.nanoTime() + p.int("timeout_seconds", 240).coerceIn(10, 480) * 1_000_000_000L
                var latest = JsonObject()
                while (true) {
                    latest = api("GET", "$base/actions/runs/$id", token).asJsonObject
                    if (latest.value("status") == "completed" || System.nanoTime() >= deadline) break
                    delay(10_000)
                }
                ok(mapOf("run_id" to id, "status" to latest.value("status"),
                    "conclusion" to latest.value("conclusion"),
                    "html_url" to latest.value("html_url"),
                    "completed" to (latest.value("status") == "completed")))
            }
            "rerun_failed" -> ok(api("POST", "$base/actions/runs/${number(p, "run_id")}/rerun-failed-jobs", token).compact())
            "list_artifacts" -> {
                val id = p.value("run_id")
                val path = if (id.isBlank()) "$base/actions/artifacts?per_page=100" else
                    "$base/actions/runs/${number(p, "run_id")}/artifacts?per_page=100"
                ok(api("GET", path, token).compact())
            }
            "download_apk" -> downloadApk(base, number(p, "artifact_id"), token)
            else -> error("unsupported_operation")
        }
    }

    private fun repoDefaultBranch(base: String, token: String): String =
        api("GET", base, token).asJsonObject.value("default_branch").ifBlank { "main" }

    private fun readJobLog(base: String, jobId: Long, token: String): String {
        val request = githubRequest("GET", "$base/actions/jobs/$jobId/logs", token, null)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException(response.code)
            return response.body?.byteStream()?.bufferedReader()?.use { reader ->
                val out = CharArray(36_000)
                val count = reader.read(out)
                if (count <= 0) "" else String(out, 0, count)
            }.orEmpty()
        }
    }

    private fun downloadApk(base: String, artifactId: Long, token: String): JsonObject {
        val req = githubRequest("GET", "$base/actions/artifacts/$artifactId/zip", token, null)
        val temporary = File(context.cacheDir, "github_artifacts").apply { mkdirs() }
        val output = File(temporary, "github-$artifactId-${System.nanoTime()}.apk")
        try {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw GitHubApiException(response.code)
                val finalHost = response.request.url.host
                require(response.request.url.isHttps && (
                    finalHost == "github.com" ||
                        finalHost.endsWith(".githubusercontent.com") ||
                        finalHost.endsWith(".blob.core.windows.net")
                    )) { "unsafe_artifact_redirect" }
                val stream = response.body?.byteStream() ?: error("empty_artifact")
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    var selected = false
                    var archiveBytes = 0L
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".apk", true) &&
                            !entry.name.contains("..") && !entry.name.startsWith("/")) {
                            output.outputStream().use { out ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    archiveBytes += read
                                    require(archiveBytes <= MAX_APK_SIZE) { "apk_too_large" }
                                    out.write(buffer, 0, read)
                                }
                            }
                            selected = true
                            break
                        }
                        entry = zip.nextEntry
                    }
                    require(selected && output.isFile && output.length() > 0) { "apk_not_found_in_artifact" }
                }
            }
            return ok(mapOf("artifact" to obj(
                "local_path" to output.absolutePath,
                "name" to output.name,
                "mime_type" to "application/vnd.android.package-archive",
                "size" to output.length()
            ), "message" to "APK скачан из GitHub Actions и подготовлен для файлов этого чата"))
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun api(method: String, path: String, token: String, body: JsonObject? = null): JsonElement {
        val request = githubRequest(method, path, token, body)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException(response.code)
            if (response.code == 204) return obj("ok" to true)
            val content = response.body?.source()?.readByteArray(2_500_000L) ?: ByteArray(0)
            if (content.isEmpty()) return obj("ok" to true)
            return JsonParser.parseString(String(content, Charsets.UTF_8))
        }
    }

    private fun githubRequest(method: String, path: String, token: String, body: JsonObject?): Request {
        require(!path.startsWith("/") && !path.contains("://") &&
            !path.any { it == '\r' || it == '\n' }) { "invalid_github_api_path" }
        val builder = Request.Builder()
            .url("https://api.github.com/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Umnik-Android-GitHub-Agent")
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        return builder.method(method, if (method == "GET") null else payload ?: ByteArray(0).toRequestBody())
            .build()
    }

    private fun JsonElement.compact(): JsonElement {
        val s = toString()
        return if (s.length <= 52_000) this else obj(
            "truncated" to true, "preview" to s.take(50_000),
            "note" to "Используйте более узкий запрос или меньшую страницу"
        )
    }

    private fun ok(payload: Any?): JsonObject = JsonObject().apply {
        addProperty("ok", true)
        add("result", gson.toJsonTree(payload))
    }

    private fun obj(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
        values.forEach { (key, v) -> add(key, gson.toJsonTree(v)) }
    }

    private fun JsonObject.value(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(name: String, fallback: Int): Int =
        runCatching { get(name)?.asInt }.getOrNull() ?: fallback

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        runCatching { get(name)?.asBoolean }.getOrNull() ?: fallback

    private fun page(p: JsonObject): Int = p.int("page", 1).coerceIn(1, 100)
    private fun number(p: JsonObject, key: String = "number"): Long =
        p.value(key).toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE } ?: error("invalid_numeric_id")
    private fun segments(repo: String): String = repo.split('/').joinToString("/") { encode(it) }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun pathUrl(path: String): String = path.split('/').joinToString("/") { encode(it) }
    private fun workingBranch(value: String): String =
        value.takeIf(GitHubAgentScope::isSafeWorkingBranch) ?: error("work_branch_only")
    private fun requireFile(path: String, read: Boolean): String {
        require(GitHubAgentScope.isSafeFilePath(path)) { "invalid_file_path" }
        if (read) require(!GitHubAgentScope.isPrivateFile(path)) { "secret_file_read_blocked" }
        return path
    }

    private class GitHubApiException(val status: Int) : IOException("github_http_$status")

    private companion object {
        const val MAX_APK_SIZE = 50L * 1024L * 1024L
        val repoReadOps = setOf("viewer", "list_repos", "repo_info", "list_tree", "read_file",
            "list_branches", "list_commits", "compare", "list_issues", "read_issue", "list_prs", "read_pr")
        val repoWriteOps = setOf("create_repo", "create_branch", "commit_files",
            "create_issue", "comment_issue", "create_pr")
        val actionReadOps = setOf("list_workflows", "list_runs", "run", "jobs", "job_logs",
            "wait_run", "list_artifacts", "download_apk")
        val actionWriteOps = setOf("dispatch", "rerun_failed")
        val allowedErrors = setOf(
            "github_connection_not_authorized", "task_grant_expired", "unsupported_operation",
            "write_needs_direct_user_instruction", "github_token_not_configured",
            "select_or_create_repository_first", "invalid_repo_name", "github_invalid_new_repo",
            "invalid_ref_sha", "missing_parent_tree", "missing_files", "too_many_files",
            "duplicate_file_path", "source_payload_too_large", "invalid_workflow",
            "invalid_file_path", "secret_file_read_blocked", "invalid_numeric_id",
            "work_branch_only", "deletion_needs_explicit_user_instruction",
            "not_a_file", "binary_or_large_file_not_supported", "missing_commit_refs",
            "invalid_github_api_path", "unsafe_artifact_redirect", "apk_too_large",
            "apk_not_found_in_artifact", "too_many_inputs", "empty_artifact"
        )
    }
}
