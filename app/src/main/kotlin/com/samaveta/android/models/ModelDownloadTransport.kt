package com.samaveta.android.models

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Production fetch for [ModelDownloader]: the `(String) -> InputStream` seam it takes.
 *
 * [ModelDownloader] owns the policy that matters — HTTPS-only, a `.part` file that is never promoted
 * before its digest matches, deletion on mismatch — and it is unit-tested there with in-memory
 * streams. This file is only the socket, and it deliberately adds nothing to the policy except the
 * one thing a socket must not do on its own: follow a redirect.
 *
 * Why redirects are refused here rather than followed: `HttpURLConnection` follows redirects by
 * default, including a 302 from `https://` to `http://`. That would defeat the HTTPS-only rule one
 * layer below the check that enforces it, so automatic following is disabled and a redirect is
 * surfaced as a failure. A model is code-adjacent data; silently fetching it over plaintext from a
 * host the user never named is not a trade worth making.
 *
 * This transport is not unit-tested — it needs a live socket — which is exactly why it holds no
 * logic beyond connect, read, and refuse.
 */
object ModelDownloadTransport {

    /**
     * Opens [url] and returns its body, or throws [IntegrityException] when the response is not a
     * clean 200 over HTTPS.
     */
    fun open(url: String): InputStream {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (malformed: Exception) {
            throw IntegrityException("model url is not a valid absolute URL: ${malformed.message.orEmpty()}")
        }
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/octet-stream")

        val status = try {
            connection.responseCode
        } catch (failure: Exception) {
            connection.disconnect()
            throw IntegrityException("model download failed: ${failure::class.simpleName}: ${failure.message.orEmpty()}")
        }
        if (status in 300..399) {
            // The Location header is named so a diagnostic can distinguish a moved artifact from a
            // hostile one, but it is never followed.
            val target = connection.getHeaderField("Location").orEmpty().take(120)
            connection.disconnect()
            throw IntegrityException("model download redirected ($status) to '$target'; redirects are refused")
        }
        if (status !in 200..299) {
            connection.disconnect()
            throw IntegrityException("model download returned HTTP $status")
        }
        return connection.inputStream
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
}
