package com.samaveta.android.intelligence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are the reason the transport is a seam: the decisions worth pinning are SAMAVETA's
 * policy choices (redirects, content types, status handling), not the platform's HTTP stack.
 */
class HttpFeedFetcherTest {

    private val policy = HttpFetchPolicy(timeoutMs = 5_000, maxBytes = 100_000)

    private fun fetcher(response: HttpFetchResponse, recorded: MutableList<String> = mutableListOf()) =
        HttpFeedFetcher(
            transport = HttpTransport { url, etag, _, _ ->
                recorded += listOfNotNull(url, etag).joinToString("|")
                response
            },
            policy = policy,
        )

    @Test
    fun aHealthyFeedIsReturnedWithItsEtag() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "<rss/>", etag = "\"abc\"", contentType = "application/rss+xml"))
            .fetch("https://example.com/feed", null)
        assertEquals(FetchOutcome.Ok("<rss/>", "\"abc\""), outcome)
    }

    @Test
    fun aPlaintextFeedIsRefusedBeforeAnyRequest() = runBlocking {
        val seen = mutableListOf<String>()
        val outcome = fetcher(HttpFetchResponse(200, "<rss/>"), seen).fetch("http://example.com/feed", null)
        assertEquals(FetchOutcome.Failed("feed url must be https"), outcome)
        assertTrue("no request must be made for a plaintext URL", seen.isEmpty())
    }

    @Test
    fun theEtagIsForwardedForConditionalRequests() = runBlocking {
        val seen = mutableListOf<String>()
        fetcher(HttpFetchResponse(200, "<rss/>"), seen).fetch("https://example.com/feed", "\"v1\"")
        assertEquals(listOf("https://example.com/feed|\"v1\""), seen)
    }

    @Test
    fun notModifiedIsNotAnError() = runBlocking {
        val outcome: FetchOutcome = fetcher(HttpFetchResponse(304, "")).fetch("https://e.com/f", "\"v1\"")
        assertEquals(FetchOutcome.NotModified, outcome)
    }

    @Test
    fun redirectsAreRefusedRatherThanFollowed() = runBlocking {
        val outcome = fetcher(
            HttpFetchResponse(301, "", location = "https://elsewhere.example/feed"),
        ).fetch("https://example.com/feed", null) as FetchOutcome.Failed
        assertTrue(outcome.reason.contains("redirected"))
        assertTrue(outcome.reason.contains("add the new address yourself"))
    }

    @Test
    fun aRedirectWithoutALocationIsReported() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(302, "")).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(outcome.reason.contains("without a location"))
    }

    @Test
    fun forbiddenAndGoneHaveDistinctMessages() = runBlocking {
        val forbidden = fetcher(HttpFetchResponse(403, "")).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(forbidden.reason.contains("refused access"))
        val gone = fetcher(HttpFetchResponse(410, "")).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(gone.reason.contains("no longer exists"))
    }

    @Test
    fun serverErrorsNameTheStatus() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(503, "")).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertEquals("feed returned HTTP 503", outcome.reason)
    }

    @Test
    fun anHtmlErrorPageServedAs200IsRefused() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "<html>oops</html>", contentType = "text/html"))
            .fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(outcome.reason.contains("unsupported content type 'text/html'"))
    }

    @Test
    fun contentTypeWithCharsetIsAccepted() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "<rss/>", contentType = "application/rss+xml; charset=utf-8"))
            .fetch("https://e.com/f", null)
        assertTrue(outcome is FetchOutcome.Ok)
    }

    @Test
    fun aMissingContentTypeIsToleratedBecauseFeedsLieAboutIt() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "<rss/>", contentType = null)).fetch("https://e.com/f", null)
        assertTrue(outcome is FetchOutcome.Ok)
    }

    @Test
    fun anOversizedBodyIsRefused() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "x".repeat(100_001), contentType = "application/xml"))
            .fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(outcome.reason.contains("larger than"))
    }

    @Test
    fun anEmptyBodyIsAFailureNotAnEmptyFeed() = runBlocking {
        val outcome = fetcher(HttpFetchResponse(200, "   ", contentType = "application/rss+xml"))
            .fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(outcome.reason.contains("empty body"))
    }

    @Test
    fun networkFailuresAreTranslatedIntoPlainLanguage() = runBlocking {
        val timeout = HttpFeedFetcher(
            transport = HttpTransport { _, _, _, _ -> throw java.net.SocketTimeoutException("read timed out") },
            policy = policy,
        ).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(timeout.reason.contains("timed out after 5000ms"))

        val offline = HttpFeedFetcher(
            transport = HttpTransport { _, _, _, _ -> throw java.net.UnknownHostException("e.com") },
            policy = policy,
        ).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(offline.reason.contains("could not be resolved"))

        val tls = HttpFeedFetcher(
            transport = HttpTransport { _, _, _, _ -> throw javax.net.ssl.SSLHandshakeException("bad cert") },
            policy = policy,
        ).fetch("https://e.com/f", null) as FetchOutcome.Failed
        assertTrue(tls.reason.contains("TLS failed"))
    }

    @Test
    fun thePolicyBoundsAreValidatedAtConstruction() {
        try {
            HttpFetchPolicy(timeoutMs = 10)
            throw AssertionError("a 10ms feed timeout must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("timeout"))
        }
        try {
            HttpFetchPolicy(maxBytes = 10)
            throw AssertionError("a 10-byte feed limit must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size limit"))
        }
    }
}
