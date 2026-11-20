package com.samaveta.android.intelligence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.intelligence.ContentCoverage
import com.samaveta.android.core.intelligence.SourceKind
import com.samaveta.android.core.intelligence.SourceCredibility

/**
 * The coordinator's job is to be honest about partial failure. Most of these tests are about the
 * difference between "there is no news" and "we could not check the news", which is the single most
 * misleading thing a digest can get wrong.
 */
class IntelligenceCoordinatorTest {

    private val now = 1_760_000_000_000L

    private fun source(id: String, name: String = id) = SourceCredibility(
        sourceId = id,
        displayName = name,
        score = 0.6,
        kind = SourceKind.RSS,
    )

    private fun subscription(id: String = "example", url: String = "https://example.com/feed.xml", enabled: Boolean = true) =
        FeedSubscription(source = source(id, "Example $id"), url = url, enabled = enabled)

    /** [pathPrefix] keeps URLs distinct across feeds; identical URLs are (correctly) deduplicated. */
    private fun rss(vararg titles: String, pathPrefix: String = "a") = buildString {
        append("<rss version=\"2.0\"><channel>")
        titles.forEachIndexed { index, title ->
            append(
                "<item><title>$title</title><link>https://example.com/$pathPrefix/$index</link>" +
                    "<guid>$pathPrefix-$index</guid><description>Summary $index</description>" +
                    "<pubDate>Tue, 18 Nov 2025 09:30:00 GMT</pubDate></item>",
            )
        }
        append("</channel></rss>")
    }

    private fun coordinator(vararg responses: FetchOutcome): IntelligenceCoordinator {
        var index = 0
        return IntelligenceCoordinator(
            fetcher = FeedFetcher { _, _ ->
                val outcome = responses.getOrElse(index) { FetchOutcome.Failed("unexpected extra fetch") }
                index++
                outcome
            },
        )
    }

    @Test
    fun readsEverySubscribedSource() = runBlocking {
        val c = coordinator(
            FetchOutcome.Ok(rss("First story", "Second story")),
            FetchOutcome.Ok(rss("Third story", pathPrefix = "b")),
        )
        val (run, _) = c.refresh(listOf(subscription("a"), subscription("b")), emptyMap(), now)
        assertEquals(3, run.digest.cards.size)
        assertEquals(2, run.sources.count { it.status == SourceStatus.READ })
        assertFalse(run.allSourcesFailed)
        assertFalse(run.noSubscriptions)
        assertTrue(run.statusLine().contains("3 new item(s)"))
    }

    @Test
    fun noSubscriptionsIsItsOwnStateNotAnEmptyDigest() = runBlocking {
        val (run, _) = coordinator().refresh(emptyList(), emptyMap(), now)
        assertTrue(run.noSubscriptions)
        assertTrue(run.digest.cards.isEmpty())
        assertTrue(run.statusLine().contains("No sources added yet"))
    }

    @Test
    fun disabledSourcesAreReportedButNotFetched() = runBlocking {
        var fetches = 0
        val c = IntelligenceCoordinator(fetcher = FeedFetcher { _, _ -> fetches++; FetchOutcome.Ok(rss("A story")) })
        val (run, _) = c.refresh(listOf(subscription("a", enabled = false)), emptyMap(), now)
        assertEquals(0, fetches)
        assertEquals(SourceStatus.DISABLED, run.sources.single().status)
        assertTrue("all-disabled is treated as nothing subscribed", run.noSubscriptions)
    }

    @Test
    fun totalFailureIsNeverPresentedAsAQuietDay() = runBlocking {
        val c = coordinator(
            FetchOutcome.Failed("HTTP 503"),
            FetchOutcome.Failed("timeout after 10000ms"),
        )
        val (run, _) = c.refresh(listOf(subscription("a"), subscription("b")), emptyMap(), now)
        assertTrue(run.allSourcesFailed)
        assertEquals(2, run.failedSources.size)
        assertTrue(run.digest.cards.isEmpty())
        assertTrue("must not say the world is quiet", run.statusLine().contains("failed refresh"))
        assertTrue(run.runLimitations().single().contains("could not be reached"))
    }

    @Test
    fun oneFailedSourceDoesNotSuppressTheOthers() = runBlocking {
        val c = coordinator(
            FetchOutcome.Failed("connection reset"),
            FetchOutcome.Ok(rss("Real story")),
        )
        val (run, _) = c.refresh(listOf(subscription("a"), subscription("b")), emptyMap(), now)
        assertFalse(run.allSourcesFailed)
        assertEquals(1, run.digest.cards.size)
        assertTrue(run.statusLine().contains("1 source(s) could not be reached"))
    }

    @Test
    fun aFetcherThatThrowsIsRecordedAsAFailedSourceRatherThanCrashingTheRefresh() = runBlocking {
        val c = IntelligenceCoordinator(fetcher = FeedFetcher { url, _ ->
            if (url.contains("bad")) throw IllegalStateException("DNS failure") else FetchOutcome.Ok(rss("Fine story"))
        })
        val (run, _) = c.refresh(
            listOf(subscription("bad", "https://bad.example/feed"), subscription("good", "https://good.example/feed")),
            emptyMap(),
            now,
        )
        assertEquals(1, run.digest.cards.size)
        val failed = run.failedSources.single()
        assertEquals("bad", failed.sourceId)
        assertTrue(failed.detail!!.contains("DNS failure"))
    }

    @Test
    fun notModifiedCountsAsAHealthySource() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.NotModified).refresh(listOf(subscription()), emptyMap(), now)
        assertFalse(run.allSourcesFailed)
        assertEquals(SourceStatus.NOT_MODIFIED, run.sources.single().status)
        assertTrue(run.digest.cards.isEmpty())
    }

    @Test
    fun anUnreadableFeedIsAFailureNotAnEmptySource() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.Ok("<html>We moved!</html>"))
            .refresh(listOf(subscription()), emptyMap(), now)
        assertEquals(SourceStatus.FAILED, run.sources.single().status)
        assertTrue(run.allSourcesFailed)
        assertTrue(run.sources.single().detail!!.contains("not an RSS or Atom feed"))
    }

    @Test
    fun etagsAreSentAndReturnedForTheNextRefresh() = runBlocking {
        val seenEtags = mutableListOf<String?>()
        var call = 0
        val c = IntelligenceCoordinator(fetcher = FeedFetcher { _, etag ->
            seenEtags += etag
            call++
            FetchOutcome.Ok(rss("Story"), etag = "etag-$call")
        })
        val (_, firstEtags) = c.refresh(listOf(subscription()), emptyMap(), now)
        assertEquals(mapOf("https://example.com/feed.xml" to "etag-1"), firstEtags)

        c.refresh(listOf(subscription()), emptyMap(), now, etags = firstEtags)
        assertEquals(listOf(null, "etag-1"), seenEtags)
    }

    @Test
    fun perEntryRejectionsAreSurfacedButDoNotLoseTheGoodEntries() = runBlocking {
        val mixed = "<rss version=\"2.0\"><channel>" +
            "<item><title>Good</title><link>https://example.com/1</link></item>" +
            "<item><title></title><link>https://example.com/2</link></item>" +
            "</channel></rss>"
        val (run, _) = coordinator(FetchOutcome.Ok(mixed)).refresh(listOf(subscription()), emptyMap(), now)
        assertEquals(1, run.digest.cards.size)
        assertTrue(run.rejectedEntries.single().contains("no title"))
        assertTrue(run.runLimitations().any { it.contains("feed reader issue") })
    }

    @Test
    fun cardsCarryThePublisherNotSamaveta() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.Ok(rss("A story about chips")))
            .refresh(listOf(subscription("techcrunch")), emptyMap(), now)
        val card = run.digest.cards.single()
        assertEquals("Example techcrunch", card.story.primarySource.displayName)
        assertEquals("https://example.com/a/0", card.story.canonicalUrl)
        assertTrue(card.summary.isNotBlank())
    }

    @Test
    fun feedSummariesNeverClaimFullText() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.Ok(rss("Headline only story")))
            .refresh(listOf(subscription()), emptyMap(), now)
        val card = run.digest.cards.single()
        assertEquals(ContentCoverage.SUMMARY_ONLY, card.story.coverage)
        assertTrue("partial coverage must state a limitation", card.limitations.isNotEmpty())
    }

    @Test
    fun withoutASummarizerTheRunSaysSo() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.Ok(rss("Story"))).refresh(listOf(subscription()), emptyMap(), now)
        assertFalse(run.digest.summarizerAvailable)
        assertTrue(run.runLimitations().any { it.contains("No summary model is installed") })
    }

    @Test
    fun mutedTopicsAreExcludedAndExplained() = runBlocking {
        val interests = mapOf<String, com.samaveta.android.core.memory.InterestProfile>(
            "ai" to com.samaveta.android.core.memory.InterestProfile(
                topicKey = "ai",
                displayName = "AI",
                weight = -2.0,
                confidence = 1.0,
                evidenceCount = 5,
                lastSignalEpochMs = now,
                firstSeenEpochMs = now - 1_000,
                muted = true,
            ),
        )
        val (run, _) = coordinator(FetchOutcome.Ok(rss("An AI story"))).refresh(
            listOf(subscription()),
            interests,
            now,
            topicOf = { setOf("ai") },
        )
        assertTrue(run.digest.cards.isEmpty())
        assertTrue(run.digest.skipped.any { it.reason == com.samaveta.android.core.intelligence.SkipReason.MUTED_TOPIC })
        assertTrue(run.statusLine().contains("muted"))
    }

    @Test
    fun feedUrlsMustBeHttpsAtConstruction() {
        try {
            subscription(url = "http://example.com/feed.xml")
            throw AssertionError("plaintext feeds must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("https"))
        }
    }

    @Test
    fun fullTextAvailabilityMapsToHonestCoverage() {
        assertEquals(
            ContentCoverage.FULL_TEXT,
            FullTextAvailability.READ_VIA_BROWSER.coverageOf(excerptPresent = true),
        )
        assertEquals(
            ContentCoverage.SUMMARY_ONLY,
            FullTextAvailability.NOT_FETCHED.coverageOf(excerptPresent = true),
        )
        assertEquals(
            ContentCoverage.HEADLINE_ONLY,
            FullTextAvailability.UNAVAILABLE.coverageOf(excerptPresent = false),
        )
    }

    @Test
    fun generatedTimestampIsTheCallersClockNotTheDeviceClock() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.NotModified).refresh(listOf(subscription()), emptyMap(), now)
        assertEquals(now, run.generatedAtEpochMs)
        assertEquals(now, run.digest.generatedAtEpochMs)
    }

    @Test
    fun aSourceWithNoItemsContributesNoCardsButIsStillRead() = runBlocking {
        val (run, _) = coordinator(FetchOutcome.Ok(rss())).refresh(listOf(subscription()), emptyMap(), now)
        assertEquals(SourceStatus.READ, run.sources.single().status)
        assertEquals(0, run.sources.single().itemCount)
        assertTrue(run.digest.cards.isEmpty())
        assertFalse(run.allSourcesFailed)
    }

    @Test
    fun sourceReportsPreserveSubscriptionOrder() = runBlocking {
        val c = coordinator(FetchOutcome.NotModified, FetchOutcome.Failed("down"), FetchOutcome.NotModified)
        val (run, _) = c.refresh(
            listOf(subscription("a"), subscription("b"), subscription("c")),
            emptyMap(),
            now,
        )
        assertEquals(listOf("a", "b", "c"), run.sources.map { it.sourceId })
    }

    @Test
    fun nullEtagIsPreservedAsNull() = runBlocking {
        val (_, etags) = coordinator(FetchOutcome.Ok(rss("Story"))).refresh(listOf(subscription()), emptyMap(), now)
        assertNull("a server that returns no etag must not produce a fake one", etags["https://example.com/feed.xml"])
    }

    @Test
    fun sourceCredibilityKindIsCarriedThrough() {
        val s = SourceCredibility("id", "Name", 0.9, SourceKind.OFFICIAL_API, isPrimarySource = true)
        val sub = FeedSubscription(s, "https://example.gov/feed")
        assertEquals(SourceKind.OFFICIAL_API, sub.source.kind)
        assertTrue(sub.source.isPrimarySource)
    }
}
