package com.ayuemin.ymnik.diagnostics

import android.content.Context
import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detailed OkHttp lifecycle diagnostics. Local Shell model calls also use this
 * lifecycle boundary for the response-start watchdog; the complementary 90 s
 * read-idle timeout is applied by DiagnosticHttpInterceptor.
 */
class DiagnosticNetworkEventListener(
    private val context: Context,
    private val source: String
) : EventListener() {
    private val startedAt = SystemClock.elapsedRealtime()
    private var responseStartWatchdog: ScheduledFuture<*>? = null

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun log(stage: String, details: String = "") {
        if (!DiagnosticLog.isEnabled(context)) return
        DiagnosticLog.record(
            context,
            "NET PHASE",
            buildString {
                append(source)
                append(" | ")
                append(stage)
                append(" | +")
                append(elapsedMs())
                append(" ms")
                if (details.isNotBlank()) {
                    append(" | ")
                    append(details)
                }
            }
        )
    }

    private fun safeRequest(request: Request): String {
        val url = request.url
        return "${request.method} ${url.scheme}://${url.host}${url.encodedPath}"
    }

    override fun callStart(call: Call) {
        log("callStart", safeRequest(call.request()))
        if (source == LOCAL_SHELL_SOURCE) {
            responseStartWatchdog = WATCHDOG_EXECUTOR.schedule({
                if (!call.isCanceled()) {
                    log("watchdogResponseStart", "timeoutMs=$SHELL_RESPONSE_START_TIMEOUT_MS; action=cancel")
                    call.cancel()
                }
            }, SHELL_RESPONSE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun dnsStart(call: Call, domainName: String) {
        log("dnsStart", "host=$domainName")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val addresses = inetAddressList.take(3).joinToString(",") { it.hostAddress.orEmpty() }
        log("dnsEnd", "host=$domainName; addresses=$addresses; count=${inetAddressList.size}")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        log("connectStart", "address=${inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostString}:${inetSocketAddress.port}; proxy=${proxy.type()}")
    }

    override fun secureConnectStart(call: Call) {
        log("tlsStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        log("tlsEnd", "tls=${handshake?.tlsVersion?.javaName.orEmpty()}; cipher=${handshake?.cipherSuite?.javaName.orEmpty()}")
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        log("connectEnd", "protocol=${protocol?.toString().orEmpty()}")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        log("requestHeadersEnd", safeRequest(request))
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        log("requestBodyEnd", "bytes=$byteCount")
    }

    override fun responseHeadersStart(call: Call) {
        responseStartWatchdog?.cancel(false)
        responseStartWatchdog = null
        val elapsed = elapsedMs()
        if (source == LOCAL_SHELL_SOURCE) {
            val streak = if (elapsed >= SHELL_SLOW_START_MS) {
                SLOW_START_STREAKS.computeIfAbsent(source) { AtomicInteger(0) }.incrementAndGet()
            } else {
                SLOW_START_STREAKS.computeIfAbsent(source) { AtomicInteger(0) }.also { it.set(0) }.get()
            }
            if (elapsed >= SHELL_SLOW_START_MS) {
                log("slowResponseStart", "elapsedMs=$elapsed; streak=$streak")
                if (streak >= SHELL_SLOW_START_WARN_STREAK) {
                    DiagnosticLog.record(
                        context,
                        "LOCAL_SHELL_NETWORK_HEALTH",
                        "slow_start_streak=$streak; lastHeadersMs=$elapsed; recommendation=pause_before_new_model_call"
                    )
                }
            }
        }
        log("responseHeadersStart")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        log("responseHeadersEnd", "http=${response.code}; type=${response.header("Content-Type").orEmpty()}")
    }

    override fun responseBodyStart(call: Call) {
        log("responseBodyStart", "idleTimeout=90s")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        log("responseBodyEnd", "bytes=$byteCount")
    }

    override fun canceled(call: Call) {
        cancelWatchdogs()
        log("canceled")
    }

    override fun callEnd(call: Call) {
        cancelWatchdogs()
        log("callEnd")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        cancelWatchdogs()
        log("callFailed", "${ioe.javaClass.simpleName}: ${ioe.message.orEmpty()}")
    }

    private fun cancelWatchdogs() {
        responseStartWatchdog?.cancel(false)
        responseStartWatchdog = null
    }

    companion object {
        private const val LOCAL_SHELL_SOURCE = "Local Shell Model"
        private const val SHELL_RESPONSE_START_TIMEOUT_MS = 60_000L
        private const val SHELL_SLOW_START_MS = 10_000L
        private const val SHELL_SLOW_START_WARN_STREAK = 3
        private val SLOW_START_STREAKS = ConcurrentHashMap<String, AtomicInteger>()
        private val WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "umnik-shell-watchdog").apply { isDaemon = true }
        }
    }
}
