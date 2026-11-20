package com.samaveta.android.intelligence

/**
 * HTTP(S) fetching for feeds, expressed as a policy over a narrow transport seam.
 *
 * The seam exists because the rules worth testing are not "does HttpURLConnection work" — that is
 * the platform's job — but "what does SAMAVETA do with a 500, a redirect to another host, a
 * 200 MB response, or a non-feed content type". Those are our decisions, they are security-relevant,
 * and they are exercised by tests here without touching a network.
 *
 * Rules enforced below:
 *  - https only; a plaintext feed can be rewritten by anyone on the path.
 *  - No cross-host redirects. A feed URL that bounces to another domain is treated as a failure the
 *    user can see, not silently followed.
 *  - Bounded response size and bounded time.
 *  - No cookies, no credentials, no referrer. A feed is public content; sending identity with it is
 *    a privacy leak that buys nothing.
 *  - Non-2xx status is a failure with the status named, so diagnostics can distinguish a dead feed
 *    from a blocked one.
 */

/** A response as the transport saw it. Kept dumb so the policy is the only thing with opinions. */
data class HttpFetchResponse(
    val statusCode: Int,
    val body: String,
    val etag: String? = null,
    val contentType: String? = null,
    val location: String? = null,
)

/** The seam. Production uses [UrlConnectionTransport]; tests use a fake. */
fun interface HttpTransport {
    /** Performs one GET. Implementations must not follow redirects themselves. */
    fun get(url: String, etag: String?, timeoutMs: Int, maxBytes: Int): HttpFetchResponse
}

data class HttpFetchPolicy(
    val timeoutMs: Int = 10_000,
    val maxBytes: Int = 2_000_000,
    /** Content types accepted as feeds. Anything else is refused rather than parsed hopefully. */
    val acceptedContentTypes: List<String> = listOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/xml",
        "text/xml",
        "application/rdf+xml",
    ),
) {
    init {
        require(timeoutMs in 1_000..60_000) { "feed timeout must be between 1s and 60s" }
        require(maxBytes in 16_384..8_000_000) { "feed size limit out of range" }
    }
}

class HttpFeedFetcher(
    private val transport: HttpTransport,
    private val policy: HttpFetchPolicy = HttpFetchPolicy(),
) : FeedFetcher {

    override suspend fun fetch(url: String, etag: String?): FetchOutcome {
        if (!url.startsWith("https://")) {
            return FetchOutcome.Failed("feed url must be https")
        }

        val response = try {
            transport.get(url, etag, policy.timeoutMs, policy.maxBytes)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: java.io.IOException) {
            return FetchOutcome.Failed(describe(failure))
        } catch (failure: Exception) {
            return FetchOutcome.Failed("${failure::class.simpleName}: ${failure.message.orEmpty()}")
        }

        // 304 is checked before redirects: it is a successful "nothing changed" and must never be
        // caught by the 3xx range. (This was a real bug: 304 was reported as a broken redirect.)
        if (response.statusCode == 304) return FetchOutcome.NotModified

        // Redirects: the transport must not follow them, and we refuse to follow them ourselves.
        if (response.statusCode in 300..399) {
            val target = response.location
            return FetchOutcome.Failed(
                if (target.isNullOrBlank()) {
                    "feed redirected (${response.statusCode}) without a location"
                } else {
                    "feed redirected (${response.statusCode}) to ${target.take(120)}; add the new address yourself"
                },
            )
        }
        if (response.statusCode == 401 || response.statusCode == 403) {
            return FetchOutcome.Failed("feed refused access (${response.statusCode})")
        }
        if (response.statusCode == 404 || response.statusCode == 410) {
            return FetchOutcome.Failed("feed no longer exists (${response.statusCode})")
        }
        if (response.statusCode !in 200..299) {
            return FetchOutcome.Failed("feed returned HTTP ${response.statusCode}")
        }
        if (response.body.length > policy.maxBytes) {
            return FetchOutcome.Failed("feed is larger than ${policy.maxBytes} bytes")
        }
        if (response.body.isBlank()) {
            return FetchOutcome.Failed("feed returned an empty body")
        }

        val contentType = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != null && contentType.isNotEmpty() && contentType !in policy.acceptedContentTypes) {
            // HTML error pages served with 200 are the common case here.
            return FetchOutcome.Failed("feed returned unsupported content type '$contentType'")
        }

        return FetchOutcome.Ok(response.body, response.etag)
    }

    private fun describe(failure: java.io.IOException): String = when (failure) {
        is java.net.SocketTimeoutException -> "feed timed out after ${policy.timeoutMs}ms"
        is java.net.UnknownHostException -> "feed host could not be resolved (offline?)"
        is javax.net.ssl.SSLException -> "feed TLS failed: ${failure.message.orEmpty()}"
        else -> "feed request failed: ${failure::class.simpleName}: ${failure.message.orEmpty()}"
    }
}

/**
 * Production transport over `HttpURLConnection`.
 *
 * Kept deliberately small and boring: no cookie store, no caches, no automatic redirects, no
 * compression negotiation beyond the platform default, and a hard cap on bytes read.
 */
class UrlConnectionTransport : HttpTransport {

    override fun get(url: String, etag: String?, timeoutMs: Int, maxBytes: Int): HttpFetchResponse {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8")
            // A stable, honest User-Agent. No version fingerprinting beyond the product name.
            setRequestProperty("User-Agent", "Samaveta/1.0 (feed reader)")
            etag?.let { setRequestProperty("If-None-Match", it) }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readBounded(it, maxBytes) }.orEmpty()
            HttpFetchResponse(
                statusCode = status,
                body = body,
                etag = connection.getHeaderField("ETag"),
                contentType = connection.contentType,
                location = connection.getHeaderField("Location"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [maxBytes], then stops: a hostile server cannot stream the device out of memory. */
    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8_192)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
