package com.samaveta.android.core.laya.v1

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The production [LayaHttpTransport], over `HttpURLConnection`.
 *
 * It lives in `core` rather than in the Android module on purpose: it uses only `java.net`, so it is
 * the one piece of the LAYA path that can be executed here against a **real socket** rather than
 * reviewed. `LayaTransportTest` starts a loopback HTTP server and drives this class through it, which
 * is why the transport's opinions below are test-backed instead of asserted.
 *
 * The rules it enforces, each of which is a decision rather than a default:
 *
 *  - **No redirects.** `HttpURLConnection` follows them silently by default, and a redirect is the
 *    cheapest way to move a client from the server the user configured onto one they did not. A 3xx
 *    is returned to the caller as-is and becomes a visible failure.
 *  - **No caches, no cookies, no referrer.** A LAYA call carries an utterance; it must not drag
 *    platform state along with it.
 *  - **A bounded response.** A hostile or broken server cannot stream the device out of memory.
 *  - **A timeout on both connect and read**, so a hung server does not hold a coroutine forever.
 *  - **Plaintext only over loopback**, mirroring [LayaV1Config]. A rewritten answer is an injection,
 *    and the config's rule is re-checked here because this class is public and constructible on its
 *    own.
 */
class LayaUrlTransport(
    /** Bearer token for a `LAYA_API_KEY`-protected deployment. Null means "send no header". */
    private val apiKey: String? = null,
    /** Hard cap on a response body. LAYA predictions are small; anything near this is a fault. */
    private val maxResponseBytes: Int = 1_000_000,
    private val userAgent: String = "Samaveta/1.0 (LAYA client)",
) : LayaHttpTransport {

    init {
        require(maxResponseBytes in 1_024..8_000_000) { "maxResponseBytes out of range" }
        require(apiKey == null || apiKey.isNotBlank()) { "an api key must not be blank; omit it instead" }
    }

    override fun call(path: String, method: String, body: String?, timeoutMs: Long): LayaHttpResponse {
        require(method == "GET" || method == "POST") { "unsupported method $method" }
        require(method != "GET" || body == null) { "a GET must not carry a body" }

        val url = URL(path)
        require(url.protocol == "https" || isLoopback(url)) {
            "LAYA must be reached over https (loopback is allowed for on-device development): $path"
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // Error bodies matter: LAYA's `422` names the exact field that was wrong, and losing it
            // would turn a precise, fixable rejection into "something went wrong".
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readBounded(it) }.orEmpty()
            return LayaHttpResponse(statusCode = status, body = text)
        } catch (timeout: SocketTimeoutException) {
            // Surfaced as a timeout rather than a generic failure: the caller must not retry it,
            // because the server may have run the inference anyway.
            throw LayaV1Failure.Timeout(timeoutMs)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): String {
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxResponseBytes) {
                throw IOException("LAYA response exceeded $maxResponseBytes bytes and was discarded")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun isLoopback(url: URL): Boolean =
        url.protocol == "http" && url.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")

    internal companion object {
        /** Exposed for tests that assert the loopback exception is narrow. */
        internal val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }
}
