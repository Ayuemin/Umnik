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

/**
 * Detailed OkHttp lifecycle diagnostics used only when the user explicitly
 * enables diagnostic logging. It intentionally records no headers, query
 * parameters, request bodies or response bodies.
 */
class DiagnosticNetworkEventListener(
    private val context: Context,
    private val source: String
) : EventListener() {
    private val startedAt = SystemClock.elapsedRealtime()

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
        log("responseHeadersStart")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        log("responseHeadersEnd", "http=${response.code}; type=${response.header("Content-Type").orEmpty()}")
    }

    override fun responseBodyStart(call: Call) {
        log("responseBodyStart")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        log("responseBodyEnd", "bytes=$byteCount")
    }

    override fun canceled(call: Call) {
        log("canceled")
    }

    override fun callEnd(call: Call) {
        log("callEnd")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log("callFailed", "${ioe.javaClass.simpleName}: ${ioe.message.orEmpty()}")
    }
}
