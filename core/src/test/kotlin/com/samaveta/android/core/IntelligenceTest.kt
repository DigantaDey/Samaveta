package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.intelligence.ContentCoverage
import com.samaveta.android.core.intelligence.Dates
import com.samaveta.android.core.intelligence.Deduplicator
import com.samaveta.android.core.intelligence.DigestBuilder
import com.samaveta.android.core.intelligence.EntityExtractor
import com.samaveta.android.core.intelligence.EntityType
import com.samaveta.android.core.intelligence.ExtractionMethod
import com.samaveta.android.core.intelligence.NoSummarizer
import com.samaveta.android.core.intelligence.RawItem
import com.samaveta.android.core.intelligence.SkipReason
import com.samaveta.android.core.intelligence.SourceCredibility
import com.samaveta.android.core.intelligence.SourceKind
import com.samaveta.android.core.intelligence.Summarizer
import com.samaveta.android.core.memory.FeedbackEvent
import com.samaveta.android.core.memory.FeedbackSignal
import com.samaveta.android.core.memory.InterestModel
import kotlinx.coroutines.runBlocking

class IntelligenceTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private val credible = SourceCredibility("src-a", "Example Wire", 0.9, SourceKind.NEWS_SITE)
    private val sketchy = SourceCredibility("src-z", "Unknown Blog", 0.2, SourceKind.USER_URL)

    private fun item(
        id: String = "i1",
        url: String = "https://example.com/a",
        headline: String = "Android gains on-device AI tooling",
        source: SourceCredibility = credible,
        excerpt: String? = "Body text about on-device AI.",
        coverage: ContentCoverage = ContentCoverage.FULL_TEXT,
        publishedAt: Long? = now - hour,
    ) = RawItem(
        id = id, url = url, source = source, headline = headline,
        contentExcerpt = excerpt, publishedAtEpochMs = publishedAt,
        fetchedAtEpochMs = now, contentCoverage = coverage,
    )

    // ---- dedupe ------------------------------------------------------------------------------

    @Test
    fun stripsTrackingParametersFromUrls() {
        val d = Deduplicator()
        val a = d.canonicalizeUrl("https://example.com/story?utm_source=x&utm_medium=y&id=7#top")
        val b = d.canonicalizeUrl("https://example.com/story/?id=7")
        assertEquals(b, a)
    }

    @Test
    fun sameStoryFromDifferentUrlsIsDeduplicated() {
        val d = Deduplicator()
        val clusters = d.deduplicate(
            listOf(
                item(id = "1", url = "https://a.com/x?id=1", headline = "Qualcomm unveils new mobile NPU for phones"),
                item(id = "2", url = "https://b.com/y", headline = "Qualcomm unveils new mobile NPU for phones"),
            ),
        )
        assertEquals("same event from two outlets must collapse to one story", 1, clusters.size)
        assertEquals(2, clusters[0].size)
    }

    @Test
    fun titlesWithDifferentNumbersAreNotTreatedAsTheSameEvent() {
        val d = Deduplicator()
        val clusters = d.deduplicate(
            listOf(
                item(id = "16", url = "https://a.com/1", headline = "Android 16 developer preview released"),
                item(id = "17", url = "https://a.com/2", headline = "Android 17 developer preview released"),
            ),
        )
        assertEquals("differing version numbers must not be merged", 2, clusters.size)
    }

    @Test
    fun storyIdsAreStableAndTraceable() = runBlocking {
        val a = DigestBuilder().build(listOf(item()), emptyMap(), now)
        val b = DigestBuilder().build(listOf(item()), emptyMap(), now)
        assertEquals(a.cards.first().story.id, b.cards.first().story.id)
        assertTrue(a.cards.first().story.id.contains("https://example.com/a"))
    }

    @Test
    fun distinctStoriesAreNotMerged() {
        val d = Deduplicator()
        val clusters = d.deduplicate(
            listOf(
                item(id = "1", url = "https://a.com/1", headline = "Android 17 developer preview released"),
                item(id = "2", url = "https://a.com/2", headline = "RBI holds interest rates steady for the quarter"),
            ),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun dedupePrefersPrimarySourceAndHigherCredibility() {
        val d = Deduplicator()
        val primary = credible.copy(sourceId = "src-primary", isPrimarySource = true)
        val clusters = d.deduplicate(
            listOf(
                item(id = "low", url = "https://z.com/1", headline = "Big chip announcement lands today", source = sketchy),
                item(id = "primary", url = "https://a.com/1", headline = "Big chip announcement lands today", source = primary),
            ),
        )
        assertEquals("primary", clusters[0].first().id)
    }

    @Test
    fun titleSimilarityIsBounded() {
        val d = Deduplicator()
        assertEquals(1.0, d.titleSimilarity("Android AI news", "Android AI news"), 0.0001)
        assertEquals(0.0, d.titleSimilarity("", "x"), 0.0001)
        assertTrue(d.titleSimilarity("Android AI news", "Cricket scores today") < 0.2)
    }

    // ---- entities ----------------------------------------------------------------------------

    @Test
    fun extractsLexiconEntitiesWithHighConfidence() {
        val entities = EntityExtractor().extract("Google and Qualcomm discussed Android with LiteRT in India.")
        val google = entities.first { it.text == "Google" }
        assertEquals(EntityType.ORGANISATION, google.type)
        assertEquals(0.9, google.confidence, 0.0001)
        assertEquals(ExtractionMethod.LEXICON, google.method)
        assertTrue(entities.any { it.text == "India" && it.type == EntityType.LOCATION })
    }

    @Test
    fun marksHeuristicEntitiesAsLowConfidenceAndOther() {
        val entities = EntityExtractor().extract("The release from Brightwave Systems was unexpected.")
        val heuristic = entities.first { it.text == "Brightwave Systems" }
        assertEquals(EntityType.OTHER, heuristic.type)
        assertEquals(ExtractionMethod.CAPITALISATION_HEURISTIC, heuristic.method)
        assertTrue("heuristic matches must not look certain", heuristic.confidence < 0.5)
    }

    @Test
    fun doesNotMisidentifyWordsInsideOtherWords() {
        val entities = EntityExtractor().extract("Indiana is not India, and Androids are not Android devices.")
        assertTrue(entities.any { it.text == "India" })
        assertFalse(entities.any { it.text == "Indiana" })
    }

    // ---- dates -------------------------------------------------------------------------------

    @Test
    fun parsesIsoAndRfcDates() {
        assertNotNull(Dates.parseIso8601("2026-11-20T09:30:00Z"))
        assertNotNull(Dates.parseIso8601("2026-11-20T09:30:00+05:30"))
        assertNull(Dates.parseIso8601("not a date"))
        assertNull(Dates.parseIso8601(null))
        assertNotNull(Dates.parseRfc1123("Fri, 20 Nov 2026 09:30:00 GMT"))
    }

    @Test
    fun missingPublicationDateIsReportedAsUnknownNotGuessed() {
        val unknown = item(publishedAt = null)
        assertEquals(
            com.samaveta.android.core.intelligence.DateOrigin.UNKNOWN,
            Dates.publication(unknown).origin,
        )
        assertNull(Dates.publication(unknown).epochMs)
        assertTrue(Dates.describeAge(null, now).contains("not stated"))
    }

    @Test
    fun describesAgeHonestly() {
        assertTrue(Dates.describeAge(now - 30 * 60_000L, now).contains("last hour"))
        assertTrue(Dates.describeAge(now - 5 * hour, now).contains("5 hour"))
        assertTrue(Dates.describeAge(now - 30 * hour, now).contains("day"))
    }

    // ---- digest ------------------------------------------------------------------------------

    @Test
    fun headlineOnlyItemsSaySo() = runBlocking {
        val digest = DigestBuilder().build(
            listOf(item(coverage = ContentCoverage.HEADLINE_ONLY, excerpt = null)),
            emptyMap(), now,
        )
        val card = digest.cards.single()
        assertTrue(card.limitations.any { it.contains("headline") })
        assertFalse(digest.summarizerAvailable)
    }

    @Test
    fun everyCardCarriesProvenance() = runBlocking {
        val digest = DigestBuilder().build(listOf(item()), emptyMap(), now)
        val card = digest.cards.single()
        assertEquals("Example Wire", card.provenance.publisher)
        assertTrue(card.provenance.url.startsWith("https://example.com"))
        assertNotNull(card.provenance.publishedAtEpochMs)
    }

    @Test
    fun withoutSummarizerThePublisherExcerptIsUsedAndLabelled() = runBlocking {
        val digest = DigestBuilder(NoSummarizer).build(
            listOf(item(excerpt = "Publisher supplied description.")),
            emptyMap(), now,
        )
        assertEquals("Publisher supplied description.", digest.cards.single().summary)
        assertFalse(digest.summarizerAvailable)
    }

    @Test
    fun failingSummarizerDoesNotBreakTheDigest() = runBlocking {
        val broken = object : Summarizer {
            override suspend fun summarize(story: com.samaveta.android.core.intelligence.Story): String? =
                throw IllegalStateException("model crashed")
        }
        val digest = DigestBuilder(broken).build(listOf(item(excerpt = "Fallback text.")), emptyMap(), now)
        assertEquals("Fallback text.", digest.cards.single().summary)
    }

    @Test
    fun summarizerOutputIsUsedWhenAvailable() = runBlocking {
        val fake = object : Summarizer {
            override suspend fun summarize(story: com.samaveta.android.core.intelligence.Story): String =
                "Model summary."
        }
        val digest = DigestBuilder(fake).build(listOf(item()), emptyMap(), now)
        assertEquals("Model summary.", digest.cards.single().summary)
        assertTrue(digest.summarizerAvailable)
    }

    @Test
    fun duplicateCoverageIsCountedAndDisclosed() = runBlocking {
        val digest = DigestBuilder().build(
            listOf(
                item(id = "1", url = "https://a.com/x", headline = "Qualcomm unveils new mobile NPU for phones"),
                item(id = "2", url = "https://b.com/y", headline = "Qualcomm unveils new mobile NPU for phones"),
            ),
            emptyMap(), now,
        )
        val card = digest.cards.single()
        assertEquals(2, card.sourceCount)
        assertEquals(listOf("Example Wire"), card.provenance.alsoReportedBy)
    }

    @Test
    fun mutedTopicsAreExcludedWithAnExplanation() = runBlocking {
        val model = InterestModel()
        var interests = model.applyToAll(emptyMap(), FeedbackEvent("e1", "c", setOf("crypto"), FeedbackSignal.NOT_INTERESTED, now))
        interests = model.applyToAll(interests, FeedbackEvent("e2", "c", setOf("crypto"), FeedbackSignal.DISLIKE, now + 1))
        val digest = DigestBuilder(ranker = com.samaveta.android.core.memory.RelevanceRanker(model)).build(
            listOf(item(headline = "Crypto exchange collapses again")),
            interests, now,
            topicOf = { setOf("crypto") },
        )
        assertTrue(digest.isEmpty)
        assertTrue(digest.skipped.any { it.reason == SkipReason.MUTED_TOPIC })
        assertTrue(digest.emptyStateMessage().contains("muted"))
    }

    @Test
    fun digestsAreCappedAndExplainWhatWasDropped() = runBlocking {
        // Headlines must be genuinely distinct: near-identical titles are (correctly) deduplicated.
        val subjects = listOf("chipsets", "modems", "cameras", "displays", "batteries", "speakers")
        val items = (1..12).map { i ->
            val subject = subjects[(i - 1) % subjects.size]
            item(
                id = "i$i",
                url = "https://a.com/$i",
                headline = "Qualcomm details its $subject roadmap for $i phones",
            )
        }
        val digest = DigestBuilder().build(items, emptyMap(), now, maxCards = 5)
        assertEquals(5, digest.cards.size)
        assertTrue(digest.skipped.any { it.reason == SkipReason.LOWER_RELEVANCE })
    }

    @Test
    fun lowCredibilitySourceIsFlaggedAsLimitation() = runBlocking {
        val digest = DigestBuilder().build(
            listOf(item(source = sketchy)),
            emptyMap(), now,
        )
        assertTrue(digest.cards.single().limitations.any { it.contains("credibility") })
    }

    @Test
    fun cardsAreOrderedByScore() = runBlocking {
        val digest = DigestBuilder().build(
            listOf(
                item(id = "old", url = "https://a.com/old", headline = "Older AI story about Android", publishedAt = now - 100 * hour),
                item(id = "new", url = "https://a.com/new", headline = "Fresh AI story about Android", publishedAt = now - hour),
            ),
            emptyMap(), now,
        )
        assertTrue(digest.cards.first().story.canonicalUrl.endsWith("/new"))
        assertTrue(digest.cards[0].score >= digest.cards[1].score)
    }
}
