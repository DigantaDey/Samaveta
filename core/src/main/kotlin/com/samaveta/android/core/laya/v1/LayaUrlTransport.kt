package com.samaveta.android.core.laya.v1

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

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

        val uri = URI(path)
        require(uri.scheme == "https" || isLoopback(uri)) {
            "LAYA must be reached over https (loopback is allowed for on-device development): $path"
        }

        // Built inside a lambda on purpose: casting to `HttpURLConnection` in the enclosing function
        // makes the compiler infer `HttpURLConnection` as this expression's type, which produces an
        // "unchecked cast" warning wherever the function is used by a caller that only knows the
        // `LayaHttpTransport` interface. `core` compiles with warnings-as-errors, so that warning
        // would fail the build rather than being ignored.
        val connection = uri.toURL().let { url ->
            (url.openConnection() as HttpURLConnection).apply {
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
        }

        try {
            if (body != null) {
                // Deliberately *not* `setFixedLengthStreamingMode`. Executed, not assumed: on a JDK
                // `HttpURLConnection`, fixed-length streaming mode plus an error response makes
                // `getErrorStream()` return null, which silently discards LAYA's `detail` — the text
                // that names the exact field a `422` rejected. Losing it would turn "max_len exceeds
                // server limit" into "something went wrong". LAYA request bodies are a few kilobytes,
                // so buffering them costs nothing.
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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

    private fun isLoopback(uri: URI): Boolean =
        uri.scheme == "http" && uri.host in LOOPBACK_HOSTS

    internal companion object {
        /** Deliberately narrow: a dotted suffix such as `localhost.evil.example` is not loopback. */
        internal val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
    }
}
