package com.samaveta.android.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.intelligence.ContentCoverage
import com.samaveta.android.core.intelligence.SourceCredibility
import com.samaveta.android.core.intelligence.SourceKind

/**
 * Feed parsing runs on hostile input from arbitrary publishers. The tests cover the malformed cases
 * that actually appear in the wild, plus the honesty rules the digest depends on.
 */
class FeedParserTest {

    private val source = SourceCredibility(
        sourceId = "example",
        displayName = "Example News",
        score = 0.7,
        kind = SourceKind.RSS,
    )

    private fun rss(vararg items: String) = """<?xml version="1.0"?>
        <rss version="2.0"><channel>
        <title>Example</title>
        ${items.joinToString("\n")}
        </channel></rss>
    """.trimIndent()

    private fun rssItem(
        title: String = "Android on-device models ship",
        link: String = "https://example.com/a",
        description: String? = "A short summary.",
        pubDate: String? = "Tue, 18 Nov 2025 09:30:00 GMT",
        guid: String = "guid-1",
    ) = buildString {
        append("<item>")
        append("<title>$title</title>")
        append("<link>$link</link>")
        append("<guid>$guid</guid>")
        if (description != null) append("<description>$description</description>")
        if (pubDate != null) append("<pubDate>$pubDate</pubDate>")
        append("</item>")
    }

    @Test
    fun parsesAWellFormedRssFeed() {
        val result = FeedParser.parse(rss(rssItem()), source, fetchedAtEpochMs = 1_000)
        val item = result.items.single()
        assertEquals("Android on-device models ship", item.title)
        assertEquals("https://example.com/a", item.link)
        assertEquals("A short summary.", item.summary)
        assertEquals(ContentCoverage.SUMMARY_ONLY, item.summaryCoverage)
        assertTrue("pubDate must be parsed", item.publishedAtEpochMs != null)
        assertFalse(result.unreadable)
    }

    @Test
    fun parsesAtomFeedsIncludingHrefLinks() {
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Quiet hardware launch</title>
                <link rel="alternate" href="https://example.org/story"/>
                <id>tag:example.org,2025:1</id>
                <updated>2025-11-19T08:00:00Z</updated>
                <summary>Vendor ships a device with no press event.</summary>
              </entry>
            </feed>
        """.trimIndent()
        val result = FeedParser.parse(atom, source, fetchedAtEpochMs = 0)
        val item = result.items.single()
        assertEquals("Quiet hardware launch", item.title)
        assertEquals("https://example.org/story", item.link)
        assertEquals(ContentCoverage.SUMMARY_ONLY, item.summaryCoverage)
        assertTrue(item.publishedAtEpochMs != null)
    }

    @Test
    fun unwrapsCdataAndStripsHtmlFromTheSummary() {
        val item = rssItem(description = "<![CDATA[<p>Hello <b>world</b>&nbsp;&amp; more</p>]]>")
        val parsed = FeedParser.parse(rss(item), source, 0).items.single()
        assertEquals("Hello world & more", parsed.summary)
    }

    @Test
    fun decodesCommonNumericEntitiesWithoutDecodingScript() {
        val description = "&#8217;Tis the season &#38; &#x2014; done"
        val parsed = FeedParser.parse(rss(rssItem(description = description)), source, 0).items.single()
        val summary = parsed.summary ?: error("summary expected")
        assertTrue("entity must decode", summary.contains("season"))
        assertFalse("markup must not survive", summary.contains("<"))
    }

    @Test
    fun scriptTagsDoNotLeakIntoTitles() {
        val item = rssItem(title = "Real title<script>alert(1)</script>")
        val parsed = FeedParser.parse(rss(item), source, 0).items.single()
        assertEquals("Real title", parsed.title)
    }

    @Test
    fun anEntryWithoutATitleIsRejectedButSiblingsSurvive() {
        val result = FeedParser.parse(
            rss(rssItem(guid = "ok-1"), rssItem(title = "", guid = "bad"), rssItem(guid = "ok-2", link = "https://example.com/b")),
            source,
            0,
        )
        assertEquals(2, result.items.size)
        assertTrue(result.rejected.single().contains("no title"))
    }

    @Test
    fun anEntryWithoutALinkIsRejected() {
        val result = FeedParser.parse(rss(rssItem(link = "")), source, 0)
        assertTrue(result.items.isEmpty())
        assertTrue(result.rejected.single().contains("no link"))
    }

    @Test
    fun nonHttpLinksAreRejected() {
        val hostile = rssItem(link = "javascript:alert(1)")
        val result = FeedParser.parse(rss(hostile), source, 0)
        assertTrue(result.items.isEmpty())
        assertTrue(result.rejected.single().contains("not http(s)"))
    }

    @Test
    fun javascriptLinksAreDroppedFromRawItemsToo() {
        val result = FeedParser.parse(rss(rssItem(link = "javascript:alert(1)")), source, 0)
        // Even if a caller bypassed the rejection, the conversion refuses to build an item.
        val raw = FeedParser.toRawItems(
            result.copy(items = listOf(result.items.firstOrNull() ?: FeedParser.ParsedItem(
                id = "x", title = "t", link = "javascript:alert(1)", summary = null,
                publishedAtEpochMs = null, summaryCoverage = ContentCoverage.HEADLINE_ONLY,
            ))),
            source,
            0,
        )
        assertTrue(raw.isEmpty())
    }

    @Test
    fun aDocumentThatIsNotAFeedIsReportedAsUnreadable() {
        val result = FeedParser.parse("<html><body>Not a feed</body></html>", source, 0)
        assertTrue(result.unreadable)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun anEmptyFeedIsNotUnreadableJustEmpty() {
        val result = FeedParser.parse(rss(), source, 0)
        assertFalse(result.unreadable)
        assertTrue(result.items.isEmpty())
        assertTrue(result.rejected.single().contains("no entries"))
    }

    @Test
    fun anOversizedDocumentIsRefusedBeforeParsing() {
        val huge = "x".repeat(FeedParser.MAX_INPUT_CHARS + 1)
        val result = FeedParser.parse(huge, source, 0)
        assertTrue(result.unreadable)
        assertTrue(result.rejected.single().contains("exceeds"))
    }

    @Test
    fun anAbsentDateStaysUnknownRatherThanBecomingNowOrZero() {
        val parsed = FeedParser.parse(rss(rssItem(pubDate = null)), source, fetchedAtEpochMs = 5_000).items.single()
        assertNull("an absent publication date must never be invented", parsed.publishedAtEpochMs)
    }

    @Test
    fun aMalformedDateStaysUnknown() {
        val parsed = FeedParser.parse(rss(rssItem(pubDate = "sometime last week")), source, 0).items.single()
        assertNull(parsed.publishedAtEpochMs)
    }

    @Test
    fun missingSummaryMeansHeadlineOnlyCoverage() {
        val parsed = FeedParser.parse(rss(rssItem(description = null)), source, 0).items.single()
        assertNull(parsed.summary)
        assertEquals(ContentCoverage.HEADLINE_ONLY, parsed.summaryCoverage)
    }

    @Test
    fun feedsAreCappedSoOneHostileDocumentCannotFloodThePipeline() {
        val items = (1..250).map { rssItem(guid = "g-$it", link = "https://example.com/$it") }
        val result = FeedParser.parse(rss(*items.toTypedArray()), source, 0)
        assertEquals(200, result.items.size)
        assertTrue(result.rejected.any { it.contains("only the first 200") })
    }

    @Test
    fun convertedItemsAreProvenanceReady() {
        val raw = FeedParser.toRawItems(FeedParser.parse(rss(rssItem()), source, 0), source, fetchedAtEpochMs = 42)
            .single()
        assertEquals("example", raw.source.sourceId)
        assertEquals(42L, raw.fetchedAtEpochMs)
        assertEquals("example:guid-1", raw.id)
    }

    @Test
    fun noDefaultFeedsAreShipped() {
        assertTrue("SAMAVETA must not subscribe the user to anything by default", ShippedSources.defaults().isEmpty())
    }

    @Test
    fun duplicateGuidAcrossFeedsProducesDistinctIds() {
        val a = FeedParser.toRawItems(FeedParser.parse(rss(rssItem()), source, 0), source, 0).single()
        val other = source.copy(sourceId = "other")
        val b = FeedParser.toRawItems(FeedParser.parse(rss(rssItem()), other, 0), other, 0).single()
        assertTrue(a.id != b.id)
    }
}
