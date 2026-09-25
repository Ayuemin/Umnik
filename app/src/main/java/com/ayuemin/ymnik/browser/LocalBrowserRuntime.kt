package com.ayuemin.ymnik.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.network.LocalWebFetchPolicy
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class LocalBrowserLifecycle {
    WORKING,
    WAITING_USER,
    BLOCKED,
    READY_TO_FINISH,
    DONE
}

data class LocalBrowserActivity(
    val chatId: String,
    val sessionId: String,
    val host: String,
    val status: String,
    val lifecycle: LocalBrowserLifecycle,
    val url: String,
    val attentionKind: String? = null,
    val attentionMessage: String? = null,
    val controlOwner: String = "MODEL"
)

private data class BrowserSession(
    val sessionId: String,
    val chatId: String,
    val profileId: String = "default",
    @Volatile var lifecycle: LocalBrowserLifecycle = LocalBrowserLifecycle.WORKING,
    @Volatile var currentUrl: String = "",
    @Volatile var stepNumber: Int = 0,
    @Volatile var nextElementRef: Int = 1,
    @Volatile var lastSnapshotId: String? = null,
    @Volatile var lastUrl: String = "",
    @Volatile var lastTitle: String = "",
    @Volatile var lastContentHash: Int = 0,
    @Volatile var lastContent: String = "",
    @Volatile var lastViewportContent: String = "",
    @Volatile var lastElementFingerprints: Map<Int, String> = emptyMap(),
    @Volatile var navigationStartedCount: Long = 0L,
    @Volatile var navigationFinishedCount: Long = 0L,
    @Volatile var lastNavigationStartedUrl: String = "",
    @Volatile var lastNavigationFinishedUrl: String = "",
    @Volatile var mainFrameErrorCount: Long = 0L,
    @Volatile var lastMainFrameError: String = "",
    @Volatile var lastBlockedFollowTarget: String = "",
    @Volatile var lastBlockedFollowFingerprint: String = "",
    @Volatile var lastBlockedFollowCandidates: String = "",
    @Volatile var userGateKind: String? = null,
    @Volatile var userGateMessage: String? = null,
    @Volatile var pendingUserDecision: CompletableDeferred<String>? = null
)

object LocalBrowserRuntime {
    private const val COMPACT_TEXT_LIMIT = 10_000
    private const val COMPACT_ELEMENT_LIMIT = 72
    private const val FULL_TEXT_LIMIT = 24_000
    private const val FULL_ELEMENT_LIMIT = 120
    private const val DELTA_TEXT_LIMIT = 4_000
    private const val LINK_INDEX_LIMIT = 32
    private const val COMMAND_TIMEOUT_MS = 65_000L
    private const val USER_INTERACTION_TIMEOUT_MS = 10 * 60_000L
    private const val DOWNLOAD_MAX_BYTES = 50L * 1024L * 1024L
    private const val DOWNLOAD_MAX_REDIRECTS = 5
    private const val NAVIGATION_START_WAIT_MS = 3_000L
    private const val NAVIGATION_READY_WAIT_MS = 30_000L
    private const val NAVIGATION_NETWORK_GRACE_MS = 15_000L
    private const val NAVIGATION_POLL_MS = 125L
    private const val SNAPSHOT_SYNC_ATTEMPTS = 24
    private const val FOLLOW_RENDER_RETRIES = 12
    private const val FOLLOW_SCORE_MARGIN = 20

    private data class NavigationWaitResult(
        val started: Boolean,
        val completed: Boolean,
        val currentUrl: String,
        val reason: String? = null,
        val networkGraceUsed: Boolean = false
    )

    private val gson = Gson()
    private val downloadHttp = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val commandMutex = Mutex()
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val publicHostCache = ConcurrentHashMap<String, Boolean>()

    private val attachLock = Any()
    @Volatile private var attachedWebView: WebView? = null
    private var attachSignal = CompletableDeferred<Unit>()
    @Volatile private var activeChatId: String? = null

    private val mutableActivity = MutableStateFlow<LocalBrowserActivity?>(null)
    val activity: StateFlow<LocalBrowserActivity?> = mutableActivity.asStateFlow()
    private val mutableUserControlVisible = MutableStateFlow(false)
    val userControlVisible: StateFlow<Boolean> = mutableUserControlVisible.asStateFlow()

    fun showUserControl(chatId: String) {
        val session = sessions[chatId] ?: return
        if (session.lifecycle == LocalBrowserLifecycle.WAITING_USER) {
            mutableUserControlVisible.value = true
        }
    }

    fun hideUserControl() {
        mutableUserControlVisible.value = false
    }

    fun confirmPendingUserAction(chatId: String) {
        resolveUserGate(chatId, "confirm")
    }

    fun cancelPendingUserAction(chatId: String) {
        resolveUserGate(chatId, "cancel")
    }

    fun finishUserControl(chatId: String) {
        resolveUserGate(chatId, "done")
    }

    private fun resolveUserGate(chatId: String, decision: String) {
        val session = sessions[chatId] ?: return
        val gate = session.pendingUserDecision ?: return
        if (!gate.isCompleted) gate.complete(decision)
        mutableUserControlVisible.value = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    markBlocked("unsupported_scheme", uri.toString())
                    return true
                }
                return runCatching {
                    LocalWebFetchPolicy.validateLiteralHost(uri.host.orEmpty())
                    false
                }.getOrElse {
                    markBlocked("blocked_address", uri.toString())
                    true
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return if (request.isForMainFrame) {
                        markBlocked("unsupported_scheme", uri.toString())
                        blockedResponse("Umnik blocked an unsupported browser address.")
                    } else {
                        null
                    }
                }
                return runCatching {
                    requirePublicHost(uri.host.orEmpty())
                    null
                }.getOrElse {
                    if (request.isForMainFrame) markBlocked("blocked_address", uri.toString())
                    blockedResponse("Umnik blocked a local or unsafe browser address.")
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                val chatId = activeChatId ?: return
                val session = sessions[chatId] ?: return
                session.navigationStartedCount += 1L
                session.lastNavigationStartedUrl = url.orEmpty()
                url?.let { session.currentUrl = it }
                publish(session, "загружает страницу", url.orEmpty())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val chatId = activeChatId ?: return
                val session = sessions[chatId] ?: return
                session.navigationFinishedCount += 1L
                session.lastNavigationFinishedUrl = url.orEmpty()
                url?.let { session.currentUrl = it }
                publish(session, "читает страницу", url.orEmpty())
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val chatId = activeChatId ?: return
                sessions[chatId]?.let { session ->
                    session.mainFrameErrorCount += 1L
                    session.lastMainFrameError =
                        error.errorCode.toString() + ":" + error.description.toString().take(160)
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    publish(session, "ошибка загрузки", request.url.toString())
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                handler.cancel()
                markBlocked("ssl_error", error.url.orEmpty())
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                handler.cancel()
                markBlocked("http_auth_required", "https://$host")
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            markBlocked("download_requires_artifact_pipeline", url.orEmpty())
        }

        synchronized(attachLock) {
            attachedWebView = webView
            if (!attachSignal.isCompleted) attachSignal.complete(Unit)
        }
    }

    fun detach(webView: WebView) {
        synchronized(attachLock) {
            if (attachedWebView !== webView) return
            attachedWebView = null
            if (attachSignal.isCompleted) attachSignal = CompletableDeferred()
        }
        if (mutableActivity.value != null) mutableActivity.value = null
    }

    suspend fun open(chatId: String, rawUrl: String): String = commandMutex.withLock {
        val safeUrl = withContext(Dispatchers.IO) {
            val parsed = LocalWebFetchPolicy.parseUrl(rawUrl)
            requirePublicHost(parsed.host)
            parsed.toString()
        }
        val session = session(chatId)
        session.lifecycle = LocalBrowserLifecycle.WORKING
        runCommand(session, safeUrl, "открывает страницу", "open", "target=" + safeUrl.take(220)) {
            val webView = webView()
            load(webView, safeUrl)
            snapshot(session, webView)
        }
    }

    suspend fun read(chatId: String, full: Boolean = false): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            if (full) "читает страницу подробно" else "читает страницу",
            "read",
            "full=" + full
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            snapshot(session, webView, forceBaseline = true, expanded = full)
        }
    }

    suspend fun follow(chatId: String, rawUrl: String?, target: String): String = commandMutex.withLock {
        val cleanTarget = target.trim()
        require(cleanTarget.isNotBlank()) { "Не указана ссылка, по которой нужно перейти" }
        val safeUrl = rawUrl?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
            withContext(Dispatchers.IO) {
                val parsed = LocalWebFetchPolicy.parseUrl(value)
                requirePublicHost(parsed.host)
                parsed.toString()
            }
        }
        val session = if (safeUrl != null) session(chatId) else requireSession(chatId)
        session.lifecycle = LocalBrowserLifecycle.WORKING
        runCommand(
            session,
            safeUrl ?: session.currentUrl,
            "переходит по ссылке",
            "follow",
            "target=" + cleanTarget.take(120) + if (safeUrl != null) "; open=true" else "; open=false"
        ) {
            val webView = webView()
            if (safeUrl != null) load(webView, safeUrl) else ensureCurrent(session, webView)

            val normalizedTarget = normalizeFollowTarget(cleanTarget)
            val fingerprint = pageFingerprint(webView)
            if (
                session.lastBlockedFollowTarget == normalizedTarget &&
                session.lastBlockedFollowFingerprint == fingerprint
            ) {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("source", "local_browser")
                        addProperty("session_id", session.sessionId)
                        addProperty("state", "BLOCKED")
                        addProperty("reason", "repeated_unchanged_target")
                        addProperty("recoverable", true)
                        val stored = runCatching {
                            gson.fromJson(session.lastBlockedFollowCandidates, JsonArray::class.java)
                        }.getOrNull() ?: JsonArray()
                        add("candidates", stored)
                        addProperty(
                            "message",
                            "Этот же follow уже был неоднозначным на неизменившейся странице. Не повторяй его: выбери candidate.ref и используй local_browser_click либо уточни target."
                        )
                    }
                )
            }

            val before = currentUrl(webView)
            val startedBefore = session.navigationStartedCount
            val finishedBefore = session.navigationFinishedCount
            val errorsBefore = session.mainFrameErrorCount
            var result = decodedJson(evaluate(webView, followScript(cleanTarget, session.nextElementRef)))
            var renderRetries = 0
            while (
                result.get("ok")?.asBoolean != true &&
                result.get("reason")?.asString == "target_not_found" &&
                renderRetries < FOLLOW_RENDER_RETRIES
            ) {
                renderRetries++
                delay(250)
                result = decodedJson(evaluate(webView, followScript(cleanTarget, session.nextElementRef)))
            }
            result.get("next_ref")?.let { next ->
                runCatching { next.asInt }.getOrNull()?.let { session.nextElementRef = maxOf(session.nextElementRef, it) }
            }
            if (renderRetries > 0) {
                DiagnosticLog.record(
                    webView.context.applicationContext,
                    "LOCAL_BROWSER",
                    "follow_settle session=" + session.sessionId.take(8) +
                        " attempts=" + renderRetries +
                        " target=" + cleanTarget.take(120)
                )
            }
            if (result.get("ok")?.asBoolean != true) {
                val candidates = result.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
                session.lastBlockedFollowTarget = normalizedTarget
                session.lastBlockedFollowFingerprint = fingerprint
                session.lastBlockedFollowCandidates = gson.toJson(candidates)
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("source", "local_browser")
                        addProperty("session_id", session.sessionId)
                        addProperty("state", "BLOCKED")
                        addProperty("reason", result.get("reason")?.asString ?: "target_not_unique")
                        addProperty("recoverable", true)
                        add("candidates", candidates)
                        addProperty(
                            "message",
                            "Локальный переход не выбран однозначно. Кандидаты уже содержат ref, name и href: выбери нужный ref и используй local_browser_click. Full read нужен только если этих кандидатов недостаточно."
                        )
                    }
                )
            }

            session.lastBlockedFollowTarget = ""
            session.lastBlockedFollowFingerprint = ""
            session.lastBlockedFollowCandidates = ""

            val expectedHref = result.get("href")?.asString
            val navigation = settleAfterAction(
                session = session,
                webView = webView,
                beforeUrl = before,
                expectedUrl = expectedHref,
                startedBefore = startedBefore,
                finishedBefore = finishedBefore,
                errorsBefore = errorsBefore
            )
            if (!navigation.completed) {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand navigationFailureJson(session, navigation, expectedHref)
            }
            session.lifecycle = LocalBrowserLifecycle.WORKING
            snapshot(session, webView)
        }
    }

    suspend fun click(chatId: String, ref: Int): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            "переходит по странице",
            "click",
            "ref=" + ref,
            timeoutMs = USER_INTERACTION_TIMEOUT_MS + COMMAND_TIMEOUT_MS
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val before = currentUrl(webView)
            val startedBefore = session.navigationStartedCount
            val finishedBefore = session.navigationFinishedCount
            val errorsBefore = session.mainFrameErrorCount
            val raw = evaluate(webView, clickScript(ref))
            var result = decodedJson(raw)
            if (result.get("ok")?.asBoolean != true) {
                val reason = result.get("reason")?.asString ?: "click_blocked"
                if (result.get("confirmation_required")?.asBoolean == true) {
                    val label = result.get("name")?.asString.orEmpty().ifBlank { "действие на странице" }
                    val decision = awaitUserGate(
                        session,
                        kind = "CONFIRM_ACTION",
                        message = "Подтвердите: «" + label.take(120) + "». Действие может изменить данные или отправить форму."
                    )
                    if (decision != "confirm") {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand gson.toJson(
                            mapOf(
                                "ok" to false,
                                "source" to "local_browser",
                                "session_id" to session.sessionId,
                                "state" to "BLOCKED",
                                "reason" to "user_cancelled",
                                "recoverable" to false
                            )
                        )
                    }
                    session.lifecycle = LocalBrowserLifecycle.WORKING
                    result = decodedJson(evaluate(webView, confirmedClickScript(ref)))
                    if (result.get("ok")?.asBoolean != true) {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand gson.toJson(
                            mapOf(
                                "ok" to false,
                                "source" to "local_browser",
                                "session_id" to session.sessionId,
                                "state" to "BLOCKED",
                                "reason" to (result.get("reason")?.asString ?: "confirmed_click_failed"),
                                "recoverable" to true
                            )
                        )
                    }
                    val navigation = settleOptionalNavigation(
                        session = session,
                        webView = webView,
                        beforeUrl = before,
                        startedBefore = startedBefore,
                        finishedBefore = finishedBefore,
                        errorsBefore = errorsBefore
                    )
                    if (navigation != null && !navigation.completed) {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand navigationFailureJson(session, navigation, null)
                    }
                    delay(250)
                    return@runCommand snapshot(session, webView)
                }
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    mapOf(
                        "ok" to false,
                        "source" to "local_browser",
                        "session_id" to session.sessionId,
                        "state" to "BLOCKED",
                        "reason" to reason,
                        "recoverable" to true,
                        "suggested_recovery" to "READ_PAGE_AGAIN"
                    )
                )
            }

            if (result.get("kind")?.asString == "navigation") {
                val expectedHref = result.get("href")?.asString
                val navigation = settleAfterAction(
                    session = session,
                    webView = webView,
                    beforeUrl = before,
                    expectedUrl = expectedHref,
                    startedBefore = startedBefore,
                    finishedBefore = finishedBefore,
                    errorsBefore = errorsBefore
                )
                if (!navigation.completed) {
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    return@runCommand navigationFailureJson(session, navigation, expectedHref)
                }
            } else {
                delay(250)
            }

            session.lifecycle = LocalBrowserLifecycle.WORKING
            snapshot(session, webView)
        }
    }

    suspend fun type(chatId: String, ref: Int, text: String): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            "вводит текст",
            "type",
            "ref=" + ref + "; chars=" + text.length,
            timeoutMs = USER_INTERACTION_TIMEOUT_MS + COMMAND_TIMEOUT_MS
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val raw = evaluate(webView, typeScript(ref, text))
            val result = decodedJson(raw)
            if (result.get("ok")?.asBoolean != true) {
                val reason = result.get("reason")?.asString ?: "type_blocked"
                if (result.get("user_takeover")?.asBoolean == true) {
                    val decision = awaitUserGate(
                        session,
                        kind = "TAKEOVER",
                        message = "Нужен ручной ввод на странице. Введите пароль, код или пройдите проверку сами — секретные данные не передаются модели."
                    )
                    if (decision != "done") {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand gson.toJson(
                            mapOf(
                                "ok" to false,
                                "source" to "local_browser",
                                "session_id" to session.sessionId,
                                "state" to "BLOCKED",
                                "reason" to "user_cancelled",
                                "recoverable" to false
                            )
                        )
                    }
                    session.lifecycle = LocalBrowserLifecycle.WORKING
                    delay(250)
                    return@runCommand snapshot(session, webView, forceBaseline = true)
                }
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    mapOf(
                        "ok" to false,
                        "source" to "local_browser",
                        "session_id" to session.sessionId,
                        "state" to "BLOCKED",
                        "reason" to reason,
                        "recoverable" to true
                    )
                )
            }
            delay(250)
            snapshot(session, webView)
        }
    }

    suspend fun takeover(chatId: String, reasonRaw: String): String = commandMutex.withLock {
        val session = requireSession(chatId)
        val reason = reasonRaw.trim().take(180).ifBlank { "Нужно ручное действие на странице" }
        runCommand(
            session,
            session.currentUrl,
            "ждёт ручного действия",
            "takeover",
            "reason=" + reason,
            timeoutMs = USER_INTERACTION_TIMEOUT_MS + COMMAND_TIMEOUT_MS
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val decision = awaitUserGate(
                session,
                kind = "TAKEOVER",
                message = reason + ". Выполните действие на странице сами и нажмите «Вернуть модели»."
            )
            if (decision != "done") {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    mapOf(
                        "ok" to false,
                        "source" to "local_browser",
                        "session_id" to session.sessionId,
                        "state" to "BLOCKED",
                        "reason" to "user_cancelled",
                        "recoverable" to false
                    )
                )
            }
            session.lifecycle = LocalBrowserLifecycle.WORKING
            snapshot(session, webView, forceBaseline = true)
        }
    }

    suspend fun download(chatId: String, ref: Int): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(session, session.currentUrl, "скачивает файл", "download", "ref=" + ref) {
            val webView = webView()
            ensureCurrent(session, webView)
            val target = decodedJson(evaluate(webView, downloadTargetScript(ref)))
            if (target.get("ok")?.asBoolean != true) {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand gson.toJson(
                    mapOf(
                        "ok" to false,
                        "source" to "local_browser",
                        "session_id" to session.sessionId,
                        "state" to "BLOCKED",
                        "reason" to (target.get("reason")?.asString ?: "download_target_invalid"),
                        "recoverable" to true
                    )
                )
            }
            val href = target.get("href")?.asString.orEmpty()
            val suggestedName = target.get("name")?.asString.orEmpty()
            val artifact = downloadPublicArtifact(
                webView = webView,
                session = session,
                rawUrl = href,
                suggestedName = suggestedName
            )
            session.lifecycle = LocalBrowserLifecycle.WORKING
            gson.toJson(
                JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("source", "local_browser")
                    addProperty("session_id", session.sessionId)
                    addProperty("state", "WORKING")
                    addProperty("url", session.currentUrl)
                    add("artifact", artifact)
                    addProperty(
                        "message",
                        "Публичный файл скачан во временное хранилище Umnik и должен быть сохранён как ресурс чата."
                    )
                }
            )
        }
    }

    suspend fun scroll(chatId: String, directionRaw: String): String = commandMutex.withLock {
        val direction = directionRaw.trim().lowercase()
        require(direction in setOf("down", "up", "top", "bottom")) { "Неизвестное направление прокрутки" }
        val session = requireSession(chatId)
        runCommand(session, session.currentUrl, "прокручивает страницу", "scroll", "direction=" + direction) {
            val webView = webView()
            ensureCurrent(session, webView)
            val script = when (direction) {
                "down" -> "window.scrollBy(0, Math.max(window.innerHeight * 0.78, 420)); true;"
                "up" -> "window.scrollBy(0, -Math.max(window.innerHeight * 0.78, 420)); true;"
                "top" -> "window.scrollTo(0, 0); true;"
                else -> "window.scrollTo(0, document.documentElement.scrollHeight); true;"
            }
            evaluate(webView, script)
            delay(180)
            snapshot(session, webView)
        }
    }

    suspend fun back(chatId: String): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(session, session.currentUrl, "возвращается назад", "back") {
            val webView = webView()
            ensureCurrent(session, webView)
            val canGoBack = withContext(Dispatchers.Main.immediate) { webView.canGoBack() }
            if (!canGoBack) {
                return@runCommand gson.toJson(
                    mapOf(
                        "ok" to false,
                        "source" to "local_browser",
                        "session_id" to session.sessionId,
                        "state" to "BLOCKED",
                        "reason" to "no_back_history",
                        "recoverable" to true
                    )
                )
            }
            val before = currentUrl(webView)
            val startedBefore = session.navigationStartedCount
            val finishedBefore = session.navigationFinishedCount
            val errorsBefore = session.mainFrameErrorCount
            withContext(Dispatchers.Main.immediate) { webView.goBack() }
            val navigation = settleAfterAction(
                session = session,
                webView = webView,
                beforeUrl = before,
                expectedUrl = null,
                startedBefore = startedBefore,
                finishedBefore = finishedBefore,
                errorsBefore = errorsBefore
            )
            if (!navigation.completed) {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                return@runCommand navigationFailureJson(session, navigation, null)
            }
            snapshot(session, webView)
        }
    }

    suspend fun wait(chatId: String, seconds: Int): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(session, session.currentUrl, "ждёт обновления страницы", "wait", "seconds=" + seconds.coerceIn(1, 5)) {
            val webView = webView()
            ensureCurrent(session, webView)
            delay(seconds.coerceIn(1, 5) * 1_000L)
            snapshot(session, webView)
        }
    }

    suspend fun done(chatId: String): String = commandMutex.withLock {
        val session = requireSession(chatId)
        session.lifecycle = LocalBrowserLifecycle.READY_TO_FINISH
        mutableActivity.value = null
        activeChatId = null
        logAction(
            session,
            "done",
            "end",
            "state=" + session.lifecycle.name + "; url=" + session.currentUrl.take(220)
        )
        DiagnosticLog.record(
            webView().context.applicationContext,
            "LOCAL_BROWSER",
            "ready session=" + session.sessionId.take(8) +
                " chat=" + chatId.take(8) +
                " steps=" + session.stepNumber
        )
        gson.toJson(
            mapOf(
                "ok" to true,
                "source" to "local_browser",
                "session_id" to session.sessionId,
                "state" to "READY_TO_FINISH",
                "url" to session.currentUrl,
                "steps" to session.stepNumber
            )
        )
    }

    private fun session(chatId: String): BrowserSession =
        sessions.computeIfAbsent(chatId) {
            BrowserSession(sessionId = UUID.randomUUID().toString(), chatId = chatId)
        }

    private fun requireSession(chatId: String): BrowserSession =
        sessions[chatId]?.takeIf { it.currentUrl.isNotBlank() }
            ?: error("Browser-сессия этого чата ещё не открыта")

    private suspend fun <T> runCommand(
        session: BrowserSession,
        url: String,
        status: String,
        action: String,
        detail: String = "",
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        block: suspend () -> T
    ): T {
        activeChatId = session.chatId
        publish(session, status, url)
        logAction(
            session,
            action,
            "start",
            buildString {
                append("url=").append(url.take(220))
                if (detail.isNotBlank()) append("; ").append(detail)
            }
        )
        return try {
            val result = withTimeout(timeoutMs) { block() }
            logAction(
                session,
                action,
                "end",
                "state=" + session.lifecycle.name + "; url=" + session.currentUrl.take(220)
            )
            result
        } catch (timeout: TimeoutCancellationException) {
            session.lifecycle = LocalBrowserLifecycle.BLOCKED
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                attachedWebView?.stopLoading()
            }
            logAction(
                session,
                action,
                "error",
                "state=BLOCKED; reason=command_timeout; timeoutMs=" + timeoutMs +
                    "; url=" + session.currentUrl.take(220)
            )
            throw IllegalStateException(
                "Local Browser не завершил действие за " + (timeoutMs / 1_000) +
                    " секунд. Действие остановлено; можно попробовать другой способ.",
                timeout
            )
        } catch (error: Throwable) {
            logAction(
                session,
                action,
                "error",
                "state=" + session.lifecycle.name + "; error=" +
                    (error.message ?: error::class.java.simpleName).take(220)
            )
            throw error
        } finally {
            if (activeChatId == session.chatId) activeChatId = null
            if (mutableActivity.value?.sessionId == session.sessionId) mutableActivity.value = null
        }
    }

    private fun logAction(
        session: BrowserSession,
        action: String,
        phase: String,
        detail: String = ""
    ) {
        attachedWebView?.context?.applicationContext?.let { context ->
            DiagnosticLog.record(
                context,
                "LOCAL_BROWSER_ACTION",
                buildString {
                    append("session=").append(session.sessionId.take(8))
                    append("; chat=").append(session.chatId.take(8))
                    append("; action=").append(action)
                    append("; phase=").append(phase)
                    append("; step=").append(session.stepNumber)
                    if (detail.isNotBlank()) append("; ").append(detail)
                }
            )
        }
    }

    private fun publish(session: BrowserSession, status: String, url: String) {
        val resolved = url.ifBlank { session.currentUrl }
        val host = runCatching { Uri.parse(resolved).host.orEmpty() }.getOrDefault("")
            .ifBlank { "страница" }
        mutableActivity.value = LocalBrowserActivity(
            chatId = session.chatId,
            sessionId = session.sessionId,
            host = host,
            status = status,
            lifecycle = session.lifecycle,
            url = resolved,
            attentionKind = session.userGateKind,
            attentionMessage = session.userGateMessage,
            controlOwner = if (session.lifecycle == LocalBrowserLifecycle.WAITING_USER) "USER" else "MODEL"
        )
    }

    private suspend fun awaitUserGate(
        session: BrowserSession,
        kind: String,
        message: String
    ): String {
        val gate = CompletableDeferred<String>()
        session.userGateKind = kind
        session.userGateMessage = message
        session.pendingUserDecision = gate
        session.lifecycle = LocalBrowserLifecycle.WAITING_USER
        publish(session, "ждёт пользователя", session.currentUrl)
        playUserAttention()
        return try {
            withTimeout(USER_INTERACTION_TIMEOUT_MS) { gate.await() }
        } finally {
            session.pendingUserDecision = null
            session.userGateKind = null
            session.userGateMessage = null
            mutableUserControlVisible.value = false
        }
    }

    private fun playUserAttention() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).let { tone ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                Thread {
                    try {
                        Thread.sleep(260)
                    } finally {
                        tone.release()
                    }
                }.start()
            }
        }
    }

    private fun markBlocked(reason: String, url: String) {
        val chatId = activeChatId ?: return
        val session = sessions[chatId] ?: return
        session.lifecycle = LocalBrowserLifecycle.BLOCKED
        publish(session, "действие заблокировано", url)
        attachedWebView?.context?.applicationContext?.let { context ->
            DiagnosticLog.record(
                context,
                "LOCAL_BROWSER",
                "blocked session=" + session.sessionId.take(8) +
                    " reason=" + reason +
                    " url=" + url.take(240)
            )
        }
    }

    private fun requirePublicHost(hostRaw: String) {
        val host = hostRaw.trim().lowercase()
        require(host.isNotBlank()) { "В URL нет адреса сайта" }
        val allowed = publicHostCache[host] ?: runCatching {
            LocalWebFetchPolicy.validateLiteralHost(host)
            LocalWebFetchPolicy.resolvePublic(host)
            true
        }.getOrDefault(false).also { publicHostCache[host] = it }
        require(allowed) { "Локальные и служебные сетевые адреса запрещены" }
    }

    private suspend fun webView(): WebView {
        attachedWebView?.let { return it }
        val signal = synchronized(attachLock) { attachSignal }
        withTimeout(5_000L) { signal.await() }
        return attachedWebView ?: error("Browser WebView не подключён к интерфейсу")
    }

    private suspend fun ensureCurrent(session: BrowserSession, webView: WebView) {
        val actual = currentUrl(webView)
        if (actual.isBlank() || !sameDocumentUrl(actual, session.currentUrl)) {
            load(webView, session.currentUrl)
        }
    }

    private fun sameDocumentUrl(left: String, right: String): Boolean =
        left.substringBefore('#') == right.substringBefore('#')

    private suspend fun load(webView: WebView, url: String) {
        val before = currentUrl(webView)
        val session = activeChatId?.let(sessions::get)
        val startedBefore = session?.navigationStartedCount ?: 0L
        val finishedBefore = session?.navigationFinishedCount ?: 0L
        val errorsBefore = session?.mainFrameErrorCount ?: 0L

        triggerNavigation(webView, url)

        if (session != null) {
            val navigation = waitForNavigation(
                session = session,
                webView = webView,
                beforeUrl = before,
                expectedUrl = url,
                startedBefore = startedBefore,
                finishedBefore = finishedBefore,
                errorsBefore = errorsBefore
            )
            if (!navigation.completed) {
                session.lifecycle = LocalBrowserLifecycle.BLOCKED
                error(
                    when (navigation.reason) {
                        "network_unavailable" -> "Сеть временно недоступна во время открытия страницы"
                        "network_error" -> "WebView сообщил об ошибке сети: " + session.lastMainFrameError
                        "navigation_not_started" -> "Переход на страницу не начался"
                        else -> "Страница не успела загрузиться: " + (navigation.reason ?: "navigation_timeout")
                    }
                )
            }
        } else {
            waitForReady(webView)
        }
        delay(250)
    }

    private suspend fun triggerNavigation(webView: WebView, url: String) {
        val before = currentUrl(webView)
        withContext(Dispatchers.Main.immediate) {
            webView.stopLoading()
            if (before.isNotBlank() && before != "about:blank" && sameDocumentUrl(before, url)) {
                webView.reload()
            } else {
                webView.loadUrl(url)
            }
        }
    }

    private suspend fun settleAfterAction(
        session: BrowserSession,
        webView: WebView,
        beforeUrl: String,
        expectedUrl: String?,
        startedBefore: Long,
        finishedBefore: Long,
        errorsBefore: Long
    ): NavigationWaitResult = waitForNavigation(
        session = session,
        webView = webView,
        beforeUrl = beforeUrl,
        expectedUrl = expectedUrl,
        startedBefore = startedBefore,
        finishedBefore = finishedBefore,
        errorsBefore = errorsBefore
    )

    private suspend fun settleOptionalNavigation(
        session: BrowserSession,
        webView: WebView,
        beforeUrl: String,
        startedBefore: Long,
        finishedBefore: Long,
        errorsBefore: Long
    ): NavigationWaitResult? {
        val deadline = SystemClock.elapsedRealtime() + 1_500L
        while (SystemClock.elapsedRealtime() < deadline) {
            val actual = currentUrl(webView)
            val callbackStarted = session.navigationStartedCount > startedBefore
            val moved = actual.substringBefore('#').let {
                it.isNotBlank() && it != "about:blank" && it != beforeUrl.substringBefore('#')
            }
            if (callbackStarted || moved) {
                val startedUrl = session.lastNavigationStartedUrl
                val expected = startedUrl.takeIf {
                    it.isNotBlank() && sameDocumentUrl(it, beforeUrl)
                }
                return waitForNavigation(
                    session = session,
                    webView = webView,
                    beforeUrl = beforeUrl,
                    expectedUrl = expected,
                    startedBefore = startedBefore,
                    finishedBefore = finishedBefore,
                    errorsBefore = errorsBefore
                )
            }
            delay(125)
        }
        return null
    }

    private suspend fun waitForNavigation(
        session: BrowserSession,
        webView: WebView,
        beforeUrl: String,
        expectedUrl: String?,
        startedBefore: Long,
        finishedBefore: Long,
        errorsBefore: Long,
        allowNetworkRetry: Boolean = true
    ): NavigationWaitResult {
        val beforeDocument = beforeUrl.substringBefore('#')
        val expectedDocument = expectedUrl.orEmpty().substringBefore('#')
        val sameTargetReload = expectedDocument.isNotBlank() && expectedDocument == beforeDocument
        val startDeadline = SystemClock.elapsedRealtime() + NAVIGATION_START_WAIT_MS
        var current = currentUrl(webView)
        var networkGraceUsed = false

        while (SystemClock.elapsedRealtime() < startDeadline) {
            if (session.mainFrameErrorCount > errorsBefore) {
                val retry = recoverNavigationAfterNetworkLoss(
                    session = session,
                    webView = webView,
                    expectedUrl = expectedUrl,
                    allowNetworkRetry = allowNetworkRetry
                )
                if (retry != null) return retry.copy(networkGraceUsed = true)
                return NavigationWaitResult(
                    started = session.navigationStartedCount > startedBefore,
                    completed = false,
                    currentUrl = current,
                    reason = if (hasUsableNetwork(webView)) "network_error" else "network_unavailable"
                )
            }

            if (!hasUsableNetwork(webView)) {
                val retry = recoverNavigationAfterNetworkLoss(
                    session = session,
                    webView = webView,
                    expectedUrl = expectedUrl,
                    allowNetworkRetry = allowNetworkRetry
                )
                if (retry != null) return retry.copy(networkGraceUsed = true)
                if (!hasUsableNetwork(webView)) {
                    return NavigationWaitResult(
                        started = false,
                        completed = false,
                        currentUrl = currentUrl(webView),
                        reason = "network_unavailable",
                        networkGraceUsed = true
                    )
                }
                networkGraceUsed = true
            }

            current = currentUrl(webView)
            val currentDocument = current.substringBefore('#')
            val callbackStarted = session.navigationStartedCount > startedBefore
            val startedDocument = session.lastNavigationStartedUrl.substringBefore('#')
            val callbackStartedForThisNavigation = callbackStarted &&
                startedDocument.isNotBlank() &&
                startedDocument != "about:blank" &&
                (sameTargetReload || startedDocument != beforeDocument)
            val urlMoved = currentDocument.isNotBlank() &&
                currentDocument != "about:blank" &&
                currentDocument != beforeDocument
            val reachedExpected = expectedDocument.isNotBlank() &&
                expectedDocument != beforeDocument &&
                currentDocument == expectedDocument

            if (callbackStartedForThisNavigation || urlMoved || reachedExpected) break
            delay(NAVIGATION_POLL_MS)
        }

        current = currentUrl(webView)
        val currentDocumentAfterStart = current.substringBefore('#')
        val callbackStartedAfterWait = session.navigationStartedCount > startedBefore
        val startedDocumentAfterWait = session.lastNavigationStartedUrl.substringBefore('#')
        val callbackStartedForThisNavigation = callbackStartedAfterWait &&
            startedDocumentAfterWait.isNotBlank() &&
            startedDocumentAfterWait != "about:blank" &&
            (sameTargetReload || startedDocumentAfterWait != beforeDocument)
        val urlMovedAfterWait = currentDocumentAfterStart.isNotBlank() &&
            currentDocumentAfterStart != "about:blank" &&
            currentDocumentAfterStart != beforeDocument
        val started = callbackStartedForThisNavigation || urlMovedAfterWait

        if (!started) {
            if (!hasUsableNetwork(webView)) {
                val retry = recoverNavigationAfterNetworkLoss(
                    session = session,
                    webView = webView,
                    expectedUrl = expectedUrl,
                    allowNetworkRetry = allowNetworkRetry
                )
                if (retry != null) return retry.copy(networkGraceUsed = true)
            }
            DiagnosticLog.record(
                webView.context.applicationContext,
                "LOCAL_BROWSER_NAV",
                "session=" + session.sessionId.take(8) +
                    "; result=navigation_not_started" +
                    "; before=" + beforeUrl.take(180) +
                    "; expected=" + expectedUrl.orEmpty().take(180) +
                    "; networkGrace=" + networkGraceUsed
            )
            return NavigationWaitResult(
                started = false,
                completed = false,
                currentUrl = current,
                reason = "navigation_not_started",
                networkGraceUsed = networkGraceUsed
            )
        }

        var deadline = SystemClock.elapsedRealtime() + NAVIGATION_READY_WAIT_MS
        var stableReady = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            if (session.mainFrameErrorCount > errorsBefore) {
                val retry = recoverNavigationAfterNetworkLoss(
                    session = session,
                    webView = webView,
                    expectedUrl = expectedUrl,
                    allowNetworkRetry = allowNetworkRetry
                )
                if (retry != null) return retry.copy(networkGraceUsed = true)
                return NavigationWaitResult(
                    started = true,
                    completed = false,
                    currentUrl = currentUrl(webView),
                    reason = if (hasUsableNetwork(webView)) "network_error" else "network_unavailable",
                    networkGraceUsed = networkGraceUsed
                )
            }

            if (!hasUsableNetwork(webView) && !networkGraceUsed) {
                networkGraceUsed = true
                deadline += NAVIGATION_NETWORK_GRACE_MS
                DiagnosticLog.record(
                    webView.context.applicationContext,
                    "LOCAL_BROWSER_NAV",
                    "session=" + session.sessionId.take(8) +
                        "; network_grace_ms=" + NAVIGATION_NETWORK_GRACE_MS
                )
            }

            current = currentUrl(webView)
            val currentDocument = current.substringBefore('#')
            val callbackFinished = session.navigationFinishedCount > finishedBefore
            val finishedDocument = session.lastNavigationFinishedUrl.substringBefore('#')
            val finishedForThisNavigation = callbackFinished &&
                finishedDocument.isNotBlank() &&
                finishedDocument != "about:blank" &&
                if (sameTargetReload) {
                    finishedDocument == beforeDocument
                } else {
                    finishedDocument != beforeDocument
                }

            val urlMoved = currentDocument.isNotBlank() &&
                currentDocument != "about:blank" &&
                currentDocument != beforeDocument
            val state = runCatching {
                evaluatePrimitive(webView, "document.readyState")
            }.getOrDefault("")
            val ready = state == "interactive" || state == "complete"

            val completionEvidence = if (sameTargetReload) {
                finishedForThisNavigation
            } else {
                urlMoved
            }
            stableReady = if (ready && completionEvidence) stableReady + 1 else 0

            if (
                (sameTargetReload && ready && finishedForThisNavigation) ||
                (!sameTargetReload && stableReady >= 2 && urlMoved)
            ) {
                DiagnosticLog.record(
                    webView.context.applicationContext,
                    "LOCAL_BROWSER_NAV",
                    "session=" + session.sessionId.take(8) +
                        "; result=complete" +
                        "; before=" + beforeDocument.take(160) +
                        "; expected=" + expectedDocument.take(160) +
                        "; url=" + current.take(180) +
                        "; networkGrace=" + networkGraceUsed
                )
                return NavigationWaitResult(
                    started = true,
                    completed = true,
                    currentUrl = current,
                    networkGraceUsed = networkGraceUsed
                )
            }
            delay(250)
        }

        val networkAvailable = hasUsableNetwork(webView)
        val reason = if (networkAvailable) "navigation_timeout" else "network_unavailable"
        DiagnosticLog.record(
            webView.context.applicationContext,
            "LOCAL_BROWSER_NAV",
            "session=" + session.sessionId.take(8) +
                "; result=" + reason +
                "; url=" + currentUrl(webView).take(180) +
                "; networkGrace=" + networkGraceUsed
        )
        return NavigationWaitResult(
            started = true,
            completed = false,
            currentUrl = currentUrl(webView),
            reason = reason,
            networkGraceUsed = networkGraceUsed
        )
    }

    private suspend fun recoverNavigationAfterNetworkLoss(
        session: BrowserSession,
        webView: WebView,
        expectedUrl: String?,
        allowNetworkRetry: Boolean
    ): NavigationWaitResult? {
        val retryUrl = expectedUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        if (!allowNetworkRetry) return null

        val networkWasUnavailable = !hasUsableNetwork(webView)
        if (!networkWasUnavailable && session.lastMainFrameError.isBlank()) return null

        DiagnosticLog.record(
            webView.context.applicationContext,
            "LOCAL_BROWSER_NAV",
            "session=" + session.sessionId.take(8) +
                "; network_wait_ms=" + NAVIGATION_NETWORK_GRACE_MS +
                "; expected=" + retryUrl.take(180)
        )
        val recovered = waitForUsableNetwork(webView, NAVIGATION_NETWORK_GRACE_MS)
        if (!recovered) return null

        DiagnosticLog.record(
            webView.context.applicationContext,
            "LOCAL_BROWSER_NAV",
            "session=" + session.sessionId.take(8) +
                "; network_recovered=true; retry=" + retryUrl.take(180)
        )

        val retryBefore = currentUrl(webView)
        val retryStartedBefore = session.navigationStartedCount
        val retryFinishedBefore = session.navigationFinishedCount
        val retryErrorsBefore = session.mainFrameErrorCount
        triggerNavigation(webView, retryUrl)
        return waitForNavigation(
            session = session,
            webView = webView,
            beforeUrl = retryBefore,
            expectedUrl = retryUrl,
            startedBefore = retryStartedBefore,
            finishedBefore = retryFinishedBefore,
            errorsBefore = retryErrorsBefore,
            allowNetworkRetry = false
        )
    }

    private suspend fun waitForUsableNetwork(webView: WebView, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasUsableNetwork(webView)) return true
            delay(500)
        }
        return hasUsableNetwork(webView)
    }

    private fun hasUsableNetwork(webView: WebView): Boolean {
        val manager = webView.context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun navigationFailureJson(
        session: BrowserSession,
        navigation: NavigationWaitResult,
        expectedUrl: String?
    ): String = gson.toJson(
        JsonObject().apply {
            addProperty("ok", false)
            addProperty("source", "local_browser")
            addProperty("session_id", session.sessionId)
            addProperty("state", "BLOCKED")
            addProperty("reason", navigation.reason ?: "navigation_failed")
            addProperty("recoverable", true)
            addProperty("navigation_started", navigation.started)
            addProperty("current_url", navigation.currentUrl)
            expectedUrl?.takeIf { it.isNotBlank() }?.let { addProperty("expected_url", it) }
            addProperty("network_grace_used", navigation.networkGraceUsed)
            addProperty(
                "message",
                when (navigation.reason) {
                    "network_unavailable" -> "Сеть пропала во время перехода. Umnik дал дополнительное время, но загрузка не завершилась."
                    "network_error" -> "WebView сообщил об ошибке сети во время перехода."
                    "navigation_not_started" -> "Нажатие произошло, но переход на новую страницу не начался."
                    else -> "Переход начался, но страница не завершила загрузку в отведённое время."
                }
            )
        }
    )

    private fun normalizeFollowTarget(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private suspend fun pageFingerprint(webView: WebView): String {
        val value = evaluatePrimitive(
            webView,
            "(location.href + '\\n' + document.title + '\\n' + String(document.body?.innerText || '').slice(0, 6000) + '\\n' + document.querySelectorAll('a[href]').length)"
        )
        return value.hashCode().toString()
    }


    private suspend fun waitForReady(webView: WebView) {
        var stableReady = 0
        repeat(48) {
            delay(250)
            val state = runCatching { evaluatePrimitive(webView, "document.readyState") }.getOrDefault("")
            val ready = state == "interactive" || state == "complete"
            if (ready) stableReady++ else stableReady = 0
            if (stableReady >= 2) return
        }
    }

    private suspend fun currentUrl(webView: WebView): String =
        withContext(Dispatchers.Main.immediate) { webView.url.orEmpty() }

    private suspend fun snapshot(
        session: BrowserSession,
        webView: WebView,
        forceBaseline: Boolean = false,
        expanded: Boolean = false
    ): String {
        val startRef = session.nextElementRef.coerceAtLeast(1)
        val textLimit = if (expanded) FULL_TEXT_LIMIT else COMPACT_TEXT_LIMIT
        val elementLimit = if (expanded) FULL_ELEMENT_LIMIT else COMPACT_ELEMENT_LIMIT
        var page = decodedJson(evaluate(webView, snapshotScript(startRef, textLimit, elementLimit)))
        var settleAttempts = 0

        fun snapshotLooksEmpty(value: JsonObject): Boolean {
            val pageUrl = value.get("url")?.asString.orEmpty()
            val pageContent = value.get("content")?.asString.orEmpty()
            val pageElements = value.getAsJsonArray("elements") ?: JsonArray()
            val isWebPage = pageUrl.startsWith("http://") || pageUrl.startsWith("https://")
            return isWebPage && pageContent.isBlank() && pageElements.size() == 0
        }

        fun snapshotMatchesWebView(value: JsonObject, actualUrl: String): Boolean {
            val pageUrl = value.get("url")?.asString.orEmpty()
            if (pageUrl.isBlank() || actualUrl.isBlank() || actualUrl == "about:blank") return true
            if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return true
            return sameDocumentUrl(pageUrl, actualUrl)
        }

        var actualUrl = currentUrl(webView)
        while (
            (snapshotLooksEmpty(page) || !snapshotMatchesWebView(page, actualUrl)) &&
            settleAttempts < SNAPSHOT_SYNC_ATTEMPTS
        ) {
            settleAttempts++
            delay(200)
            actualUrl = currentUrl(webView)
            page = decodedJson(evaluate(webView, snapshotScript(startRef, textLimit, elementLimit)))
        }

        actualUrl = currentUrl(webView)
        if (!snapshotMatchesWebView(page, actualUrl)) {
            val pageUrl = page.get("url")?.asString.orEmpty()
            DiagnosticLog.record(
                webView.context.applicationContext,
                "LOCAL_BROWSER",
                "snapshot_document_mismatch session=" + session.sessionId.take(8) +
                    " webView=" + actualUrl.take(220) +
                    " document=" + pageUrl.take(220)
            )
            error("Browser ещё переключает документ: snapshot не соответствует текущему URL")
        }

        if (settleAttempts > 0) {
            DiagnosticLog.record(
                webView.context.applicationContext,
                "LOCAL_BROWSER",
                "snapshot_settle session=" + session.sessionId.take(8) +
                    " attempts=" + settleAttempts +
                    " webView=" + actualUrl.take(220) +
                    " document=" + page.get("url")?.asString.orEmpty().take(220)
            )
        }

        val url = page.get("url")?.asString.orEmpty().ifBlank { actualUrl }
        val title = page.get("title")?.asString.orEmpty()
        val content = page.get("content")?.asString.orEmpty()
        val viewportContent = page.get("viewport_content")?.asString.orEmpty()
        val elements = page.getAsJsonArray("elements") ?: JsonArray()
        val linkIndex = page.getAsJsonArray("link_index") ?: JsonArray()
        val currentElements = elementObjects(elements)
        val currentFingerprints = currentElements.mapValues { (_, value) -> gson.toJson(value) }
        val nextRef = page.get("next_ref")?.asInt ?: startRef
        session.nextElementRef = maxOf(session.nextElementRef, nextRef)
        session.currentUrl = url
        session.stepNumber += 1
        session.lifecycle = LocalBrowserLifecycle.WORKING

        val snapshotId = UUID.randomUUID().toString()
        val contentHash = content.hashCode()
        val initial = session.lastSnapshotId == null
        val documentChanged = !initial && session.lastUrl.substringBefore('#') != url.substringBefore('#')
        val baseline = forceBaseline || initial || documentChanged
        val contentChanged = !initial && session.lastContentHash != contentHash
        val viewportChanged = !initial && session.lastViewportContent != viewportContent

        val delta = JsonObject().apply {
            addProperty("initial", initial)
            addProperty("urlChanged", documentChanged)
            addProperty("titleChanged", !initial && session.lastTitle != title)
            addProperty("contentChanged", contentChanged)
            addProperty("viewportChanged", viewportChanged)
            addProperty("navigation", if (documentChanged) "NEW_DOCUMENT" else "SAME_PAGE")
        }

        val result = JsonObject().apply {
            addProperty("ok", true)
            addProperty("source", "local_browser")
            addProperty("trust", "untrusted_web_content")
            addProperty("snapshotId", snapshotId)
            addProperty("sessionId", session.sessionId)
            addProperty("pageId", url.substringBefore('#'))
            addProperty("stepNumber", session.stepNumber)
            addProperty("profileId", session.profileId)
            addProperty("url", url)
            addProperty("title", title)
            addProperty("state", page.get("state")?.asString ?: "unknown")
            addProperty(
                "representation",
                when {
                    expanded -> "DOM_FULL"
                    baseline -> "DOM_COMPACT"
                    else -> "DOM_DELTA"
                }
            )
            add("viewport", page.get("viewport") ?: JsonObject())
            add("link_index", linkIndex)
            addProperty("link_index_truncated", page.get("link_index_truncated")?.asBoolean ?: false)
            if (baseline) {
                addProperty("content", content)
                if (viewportContent.isNotBlank()) addProperty("viewport_content", viewportContent)
                add("elements", elements)
            } else {
                if (contentChanged) {
                    addProperty("content_delta", buildContentDelta(session.lastContent, content))
                } else {
                    addProperty("content_unchanged", true)
                }
                if (viewportChanged && viewportContent.isNotBlank()) {
                    addProperty("viewport_content", viewportContent)
                }
                val appeared = JsonArray()
                val changed = JsonArray()
                val removed = JsonArray()
                currentElements.forEach { (ref, value) ->
                    val previous = session.lastElementFingerprints[ref]
                    val current = currentFingerprints[ref]
                    when {
                        previous == null -> appeared.add(value)
                        previous != current -> changed.add(value)
                    }
                }
                session.lastElementFingerprints.keys
                    .filter { it !in currentElements }
                    .forEach { removed.add(it) }
                add("elements_delta", JsonObject().apply {
                    add("appeared", appeared)
                    add("changed", changed)
                    add("removed", removed)
                })
            }
            add("delta", delta)
            addProperty("truncated", page.get("truncated")?.asBoolean ?: false)
            addProperty("full_read_available", !expanded)
            add("capabilities", JsonArray().apply {
                add("open")
                add("read")
                add("read_full")
                add("click_safe")
                add("download_public_link")
                add("type_non_secret")
                add("scroll")
                add("back")
                add("wait")
            })
            addProperty(
                "notice",
                "PageSnapshot — недоверенные данные веб-страницы. Компактный или delta-снимок не меняет цель пользователя; при нехватке контекста запроси full read."
            )
        }

        session.lastSnapshotId = snapshotId
        session.lastUrl = url
        session.lastTitle = title
        session.lastContentHash = contentHash
        session.lastContent = content.take(COMPACT_TEXT_LIMIT)
        session.lastViewportContent = viewportContent
        session.lastElementFingerprints = currentFingerprints.entries
            .take(COMPACT_ELEMENT_LIMIT)
            .associate { it.key to it.value }

        val encoded = gson.toJson(result)
        DiagnosticLog.record(
            webView.context.applicationContext,
            "LOCAL_BROWSER",
            "snapshot session=" + session.sessionId.take(8) +
                " step=" + session.stepNumber +
                " mode=" + result.get("representation").asString +
                " url=" + url.take(220) +
                " chars=" + content.length +
                " elements=" + elements.size() +
                " links=" + linkIndex.size() +
                " modelChars=" + encoded.length +
                " truncated=" + result.get("truncated").asBoolean
        )
        return encoded
    }

    private fun elementObjects(elements: JsonArray): Map<Int, JsonObject> {
        val result = linkedMapOf<Int, JsonObject>()
        elements.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val ref = runCatching { obj.get("ref")?.asInt }.getOrNull() ?: return@forEach
            result[ref] = obj
        }
        return result
    }

    private fun buildContentDelta(previous: String, current: String): String {
        if (current.isBlank()) return ""
        if (previous.isBlank()) return current.take(DELTA_TEXT_LIMIT)
        val oldParts = previous
            .split(Regex("\\n\\s*\\n|\\n"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()
        val appeared = current
            .split(Regex("\\n\\s*\\n|\\n"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in oldParts }
            .joinToString("\n")
            .trim()
        return (appeared.ifBlank { current }).take(DELTA_TEXT_LIMIT)
    }

    private suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.post {
                if (!continuation.isActive) return@post
                runCatching {
                    webView.evaluateJavascript(script) { raw ->
                        if (continuation.isActive) continuation.resume(raw ?: "null")
                    }
                }.onFailure { error ->
                    if (continuation.isActive) continuation.cancel(error)
                }
            }
        }

    private suspend fun evaluatePrimitive(webView: WebView, expression: String): String {
        val raw = evaluate(webView, expression)
        return runCatching { gson.fromJson(raw, String::class.java).orEmpty() }
            .getOrDefault(raw.trim('"'))
    }

    private fun decodedJson(raw: String): JsonObject {
        val jsonText = runCatching { gson.fromJson(raw, String::class.java) }.getOrNull()
            ?: raw.takeIf { it.trimStart().startsWith("{") }
            ?: error("Browser вернул некорректный snapshot")
        return gson.fromJson(jsonText, JsonObject::class.java)
            ?: error("Browser вернул пустой snapshot")
    }

    private fun snapshotScript(startRef: Int, textLimit: Int, elementLimit: Int): String = """
        (() => {
          const reg = window.__umnikRegistry || (window.__umnikRegistry = {
            next: $startRef,
            weak: new WeakMap(),
            refs: new Map()
          });
          reg.next = Math.max(reg.next || $startRef, $startRef);
          const refFor = (el) => {
            let ref = reg.weak.get(el);
            if (!ref) {
              ref = reg.next++;
              reg.weak.set(el, ref);
              reg.refs.set(ref, el);
            }
            return ref;
          };
          const clean = (v, n = 180) => String(v || '').replace(/\s+/g, ' ').trim().slice(0, n);
          const all = Array.from(document.querySelectorAll(
            'a[href],button,input,textarea,select,summary,[role="button"],[role="link"],[role="tab"]'
          )).filter(el => {
            const style = getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden';
          });
          const inViewport = (el) => {
            const r = el.getBoundingClientRect();
            return r.bottom >= 0 && r.top <= window.innerHeight && r.right >= 0 && r.left <= window.innerWidth;
          };
          const prioritized = all
            .map((el, index) => ({el, index, viewport: inViewport(el)}))
            .sort((a, b) => (a.viewport === b.viewport) ? (a.index - b.index) : (a.viewport ? -1 : 1))
            .map(item => item.el);
          const elements = prioritized.slice(0, $elementLimit).map(el => {
            const tag = (el.tagName || '').toLowerCase();
            const type = clean(el.getAttribute('type'), 40).toLowerCase();
            const role = clean(el.getAttribute('role') || tag, 40);
            const secret = type === 'password';
            const name = clean(
              el.getAttribute('aria-label') ||
              el.innerText ||
              el.getAttribute('placeholder') ||
              (!secret ? el.value : '') ||
              el.getAttribute('title')
            );
            return {
              ref: refFor(el),
              tag,
              role,
              type,
              name,
              href: tag === 'a' ? clean(el.href, 600) : '',
              expanded: el.getAttribute('aria-expanded'),
              disabled: !!el.disabled
            };
          });
          const linkCandidates = all
            .filter(el => (el.tagName || '').toLowerCase() === 'a')
            .filter(el => /^https?:\/\//i.test(String(el.href || '')))
            .map((el, index) => {
              const name = clean(
                el.getAttribute('aria-label') || el.innerText || el.getAttribute('title') || '',
                120
              );
              const navLike = !!el.closest('nav,header,[role="navigation"]');
              return {
                el,
                index,
                name,
                href: clean(el.href, 360),
                download: el.hasAttribute('download'),
                priority: (navLike ? 1000 : 0) + (inViewport(el) ? 200 : 0) - index
              };
            })
            .filter(item => item.name && item.href)
            .sort((a, b) => b.priority - a.priority);
          const linkIndex = [];
          const seenLinkHrefs = new Set();
          for (const item of linkCandidates) {
            const key = item.href.replace(/#.*$/, '');
            if (seenLinkHrefs.has(key)) continue;
            seenLinkHrefs.add(key);
            linkIndex.push({ref:refFor(item.el), name:item.name, href:item.href, download:item.download});
            if (linkIndex.length >= $LINK_INDEX_LIMIT) break;
          }
          const bodyText = String(document.body?.innerText || '')
            .replace(/\r/g, '')
            .replace(/\n{3,}/g, '\n\n')
            .trim();
          const viewportText = Array.from(document.querySelectorAll('h1,h2,h3,h4,p,li,label,a,button'))
            .filter(inViewport)
            .map(el => clean(el.innerText || el.getAttribute('aria-label') || '', 500))
            .filter(Boolean)
            .filter((value, index, array) => array.indexOf(value) === index)
            .join('\n')
            .slice(0, 3500);
          return JSON.stringify({
            url: location.href,
            title: document.title || '',
            state: document.readyState || 'unknown',
            viewport: {
              scrollY: Math.round(window.scrollY || 0),
              height: Math.round(window.innerHeight || 0),
              documentHeight: Math.round(document.documentElement?.scrollHeight || 0)
            },
            content: bodyText.slice(0, $textLimit),
            viewport_content: viewportText,
            elements,
            link_index: linkIndex,
            link_index_truncated: linkCandidates.length > linkIndex.length,
            next_ref: reg.next,
            truncated: bodyText.length > $textLimit || all.length > $elementLimit
          });
        })()
    """.trimIndent()

    private fun followScript(target: String, startRef: Int): String {
        val encodedTarget = gson.toJson(target)
        return """
            (() => {
              const target = $encodedTarget;
              const reg = window.__umnikRegistry || (window.__umnikRegistry = {
                next: $startRef,
                weak: new WeakMap(),
                refs: new Map()
              });
              reg.next = Math.max(reg.next || $startRef, $startRef);
              const refFor = (el) => {
                let ref = reg.weak.get(el);
                if (!ref) {
                  ref = reg.next++;
                  reg.weak.set(el, ref);
                  reg.refs.set(ref, el);
                }
                return ref;
              };
              const norm = (value) => String(value || '')
                .toLowerCase()
                .replace(/[^a-z0-9а-яё]+/gi, ' ')
                .trim();
              const singular = (value) => {
                const token = String(value || '');
                return token.length > 3 && token.endsWith('s') ? token.slice(0, -1) : token;
              };
              const tokens = (value) => norm(value).split(' ').filter(Boolean).map(singular);
              const wanted = norm(target);
              const wantedTokens = tokens(target);
              if (!wanted) {
                return JSON.stringify({
                  ok:false,
                  reason:'empty_target',
                  candidates:[],
                  next_ref:reg.next
                });
              }
              const visible = (el) => {
                const style = getComputedStyle(el);
                const rect = el.getBoundingClientRect();
                return style.display !== 'none' &&
                  style.visibility !== 'hidden' &&
                  rect.width > 0 &&
                  rect.height > 0;
              };
              const candidates = Array.from(document.querySelectorAll('a[href]'))
                .filter(el =>
                  visible(el) &&
                  !el.hasAttribute('download') &&
                  /^https?:\/\//i.test(String(el.href || ''))
                )
                .map(el => {
                  const name = String(
                    el.getAttribute('aria-label') ||
                    el.innerText ||
                    el.getAttribute('title') ||
                    ''
                  ).replace(/\s+/g, ' ').trim().slice(0, 180);
                  return {
                    el,
                    ref:refFor(el),
                    name,
                    key:norm(name),
                    href:String(el.href || '')
                  };
                })
                .filter(item => item.key);

              const exact = candidates.filter(item => item.key === wanted);
              const partial = candidates.filter(item =>
                item.key.includes(wanted) || wanted.includes(item.key)
              );
              const overlap = candidates.filter(item => {
                const itemTokens = tokens(item.key);
                return wantedTokens.some(token => itemTokens.includes(token));
              });
              const matches = exact.length > 0 ? exact : (partial.length > 0 ? partial : overlap);

              const unique = [];
              const seenHref = new Set();
              for (const item of matches) {
                const key = String(item.href || '').replace(/#.*$/, '');
                if (!seenHref.has(key)) {
                  seenHref.add(key);
                  unique.push(item);
                }
              }

              const scoreCandidate = (item) => {
                let score = item.key === wanted ? 100 : 60;
                let url;
                try {
                  url = new URL(item.href, location.href);
                } catch (_) {
                  return score;
                }

                const pathTokens = url.pathname
                  .split('/')
                  .filter(Boolean)
                  .map(part => norm(decodeURIComponent(part)))
                  .flatMap(part => part.split(' ').filter(Boolean))
                  .map(singular);

                const matchedPositions = [];
                for (const wantedToken of wantedTokens) {
                  const pos = pathTokens.indexOf(wantedToken);
                  if (pos >= 0) {
                    score += 25;
                    matchedPositions.push(pos);
                  } else {
                    score -= 20;
                  }
                }

                if (
                  wantedTokens.length > 0 &&
                  pathTokens.length >= wantedTokens.length
                ) {
                  const suffix = pathTokens.slice(pathTokens.length - wantedTokens.length);
                  if (suffix.join(' ') === wantedTokens.join(' ')) score += 35;
                }

                if (matchedPositions.length > 0) {
                  const lastMatched = Math.max(...matchedPositions);
                  const trailing = pathTokens.slice(lastMatched + 1)
                    .filter(token => !wantedTokens.includes(token));
                  score -= trailing.length * 25;
                }

                const nameTokens = tokens(item.key);
                const extraNameTokens = nameTokens.filter(token => !wantedTokens.includes(token));
                if (item.key !== wanted) score -= Math.min(extraNameTokens.length * 8, 24);

                return score;
              };

              const ranked = unique
                .map(item => ({...item, score:scoreCandidate(item)}))
                .sort((a, b) => b.score - a.score);

              const publicCandidates = ranked.slice(0, 8).map(item => ({
                ref:item.ref,
                name:item.name,
                href:item.href,
                score:item.score
              }));

              if (ranked.length === 0) {
                return JSON.stringify({
                  ok:false,
                  reason:'target_not_found',
                  candidates:[],
                  next_ref:reg.next
                });
              }

              const winner = ranked[0];
              const runnerUp = ranked[1];
              const decisive = !runnerUp || (winner.score - runnerUp.score) >= $FOLLOW_SCORE_MARGIN;
              if (!decisive) {
                return JSON.stringify({
                  ok:false,
                  reason:'target_ambiguous',
                  candidates:publicCandidates,
                  next_ref:reg.next
                });
              }

              winner.el.scrollIntoView({block:'center', inline:'nearest'});
              winner.el.click();
              return JSON.stringify({
                ok:true,
                href:winner.href,
                name:winner.name,
                ref:winner.ref,
                score:winner.score,
                next_ref:reg.next
              });
            })()
        """.trimIndent()
    }

    private fun clickScript(ref: Int): String = """
        (() => {
          const reg = window.__umnikRegistry;
          const el = reg?.refs?.get($ref);
          if (!el || !el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref'});
          if (el.disabled) return JSON.stringify({ok:false, reason:'disabled'});
          const tag = (el.tagName || '').toLowerCase();
          const role = (el.getAttribute('role') || '').toLowerCase();
          if (tag === 'a') {
            const href = String(el.href || '');
            if (!/^https?:\/\//i.test(href)) return JSON.stringify({ok:false, reason:'unsafe_link_scheme'});
            if (el.hasAttribute('download')) return JSON.stringify({ok:false, reason:'download_requires_artifact_pipeline'});
            el.click();
            return JSON.stringify({ok:true, kind:'navigation', href});
          }
          const safeUi =
            tag === 'summary' ||
            role === 'tab' ||
            el.hasAttribute('aria-expanded') ||
            el.hasAttribute('aria-controls');
          if (safeUi) {
            el.click();
            return JSON.stringify({ok:true, kind:'safe_ui'});
          }
          const name = String(
            el.getAttribute('aria-label') ||
            el.innerText ||
            el.getAttribute('title') ||
            el.getAttribute('name') ||
            'действие'
          ).replace(/\s+/g, ' ').trim().slice(0, 140);
          return JSON.stringify({
            ok:false,
            reason:'consequential_or_unknown_action',
            confirmation_required:true,
            name
          });
        })()
    """.trimIndent()

    private fun confirmedClickScript(ref: Int): String = """
        (() => {
          const reg = window.__umnikRegistry;
          const el = reg?.refs?.get($ref);
          if (!el || !el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref'});
          if (el.disabled) return JSON.stringify({ok:false, reason:'disabled'});
          const tag = (el.tagName || '').toLowerCase();
          const type = String(el.getAttribute('type') || '').toLowerCase();
          if (tag === 'input' && ['file','password','hidden'].includes(type)) {
            return JSON.stringify({ok:false, reason:'sensitive_or_unsupported_control'});
          }
          el.scrollIntoView({block:'center', inline:'nearest'});
          el.click();
          return JSON.stringify({ok:true});
        })()
    """.trimIndent()

    private fun typeScript(ref: Int, text: String): String {
        val encoded = gson.toJson(text)
        return """
            (() => {
              const reg = window.__umnikRegistry;
              const el = reg?.refs?.get($ref);
              if (!el || !el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref'});
              if (el.disabled || el.readOnly) return JSON.stringify({ok:false, reason:'not_editable'});
              const tag = (el.tagName || '').toLowerCase();
              const type = String(el.getAttribute('type') || 'text').toLowerCase();
              const autoComplete = String(el.getAttribute('autocomplete') || '').toLowerCase();
              const fieldMeta = [
                el.getAttribute('name'),
                el.getAttribute('id'),
                el.getAttribute('aria-label'),
                el.getAttribute('placeholder')
              ].join(' ').toLowerCase();
              if (type === 'password' || autoComplete === 'one-time-code' || /(^|\W)(otp|2fa|mfa)(\W|$)|verification.?code|one.?time/.test(fieldMeta)) {
                return JSON.stringify({ok:false, reason:'sensitive_field_requires_user', user_takeover:true});
              }
              if (type === 'file') {
                return JSON.stringify({ok:false, reason:'file_input_requires_user'});
              }
              if (type === 'hidden') {
                return JSON.stringify({ok:false, reason:'hidden_field_blocked'});
              }
              const editable = tag === 'textarea' || tag === 'input' || el.isContentEditable;
              if (!editable) return JSON.stringify({ok:false, reason:'not_text_field'});
              const value = $encoded;
              el.focus();
              if (el.isContentEditable) {
                el.textContent = value;
              } else {
                el.value = value;
              }
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()
    }

    private fun downloadTargetScript(ref: Int): String = """
        (() => {
          const reg = window.__umnikRegistry;
          const el = reg?.refs?.get($ref);
          if (!el || !el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref'});
          const tag = (el.tagName || '').toLowerCase();
          if (tag !== 'a') return JSON.stringify({ok:false, reason:'download_requires_link'});
          const href = String(el.href || '');
          if (!/^https?:\/\//i.test(href)) return JSON.stringify({ok:false, reason:'unsafe_link_scheme'});
          const name = String(
            el.getAttribute('download') ||
            el.getAttribute('aria-label') ||
            el.innerText ||
            el.getAttribute('title') ||
            ''
          ).replace(/\s+/g, ' ').trim().slice(0, 180);
          return JSON.stringify({ok:true, href, name});
        })()
    """.trimIndent()

    private suspend fun downloadPublicArtifact(
        webView: WebView,
        session: BrowserSession,
        rawUrl: String,
        suggestedName: String
    ): JsonObject = withContext(Dispatchers.IO) {
        var current = URI(rawUrl)
        var redirects = 0
        while (true) {
            val scheme = current.scheme?.lowercase().orEmpty()
            require(scheme == "http" || scheme == "https") { "Разрешены только публичные HTTP(S)-загрузки" }
            requirePublicHost(current.host.orEmpty())

            val response = downloadHttp.newCall(
                Request.Builder()
                    .url(current.toString())
                    .get()
                    .header("User-Agent", "Umnik-LocalBrowser/1.0")
                    .build()
            ).execute()

            response.use { res ->
                if (res.code in 300..399) {
                    require(redirects < DOWNLOAD_MAX_REDIRECTS) { "Слишком много перенаправлений при скачивании" }
                    val location = res.header("Location").orEmpty()
                    require(location.isNotBlank()) { "Сервер вернул перенаправление без адреса" }
                    current = current.resolve(location)
                    redirects++
                    continue
                }
                require(res.isSuccessful) { "Не удалось скачать файл: HTTP " + res.code }
                val body = res.body ?: error("Сервер вернул пустой файл")
                val declared = body.contentLength()
                require(declared < 0L || declared <= DOWNLOAD_MAX_BYTES) { "Файл больше 50 МБ" }

                val disposition = res.header("Content-Disposition").orEmpty()
                val headerName = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE)
                    .find(disposition)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(Uri::decode)
                    .orEmpty()
                val pathName = current.path.substringAfterLast('/').takeIf { it.isNotBlank() }.orEmpty()
                val name = safeDownloadName(
                    headerName.ifBlank { suggestedName }.ifBlank { pathName }.ifBlank { "download.bin" }
                )

                val dir = File(webView.context.applicationContext.cacheDir, "browser_downloads/" + session.sessionId)
                    .apply { mkdirs() }
                val target = File(dir, UUID.randomUUID().toString().take(8) + "_" + name)
                try {
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= DOWNLOAD_MAX_BYTES) { "Файл больше 50 МБ" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(target.isFile) { "Не удалось сохранить скачанный файл" }
                    val mime = body.contentType()?.toString().orEmpty().ifBlank { "application/octet-stream" }
                    DiagnosticLog.record(
                        webView.context.applicationContext,
                        "LOCAL_BROWSER_DOWNLOAD",
                        "session=" + session.sessionId.take(8) +
                            "; bytes=" + target.length() +
                            "; url=" + current.toString().take(220) +
                            "; name=" + name.take(120)
                    )
                    return@withContext JsonObject().apply {
                        addProperty("name", name)
                        addProperty("mime_type", mime)
                        addProperty("size", target.length())
                        addProperty("source_url", current.toString())
                        addProperty("local_path", target.absolutePath)
                    }
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
        }
        error("Скачивание не завершено")
    }

    private fun safeDownloadName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
            .take(120)
            .ifBlank { "download.bin" }

    private fun blockedResponse(message: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        )
}
