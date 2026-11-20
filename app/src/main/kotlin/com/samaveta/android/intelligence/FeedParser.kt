package com.samaveta.android.intelligence

import com.samaveta.android.core.intelligence.ContentCoverage
import com.samaveta.android.core.intelligence.Dates
import com.samaveta.android.core.intelligence.RawItem
import com.samaveta.android.core.intelligence.SourceCredibility
import com.samaveta.android.core.intelligence.SourceKind

/**
 * Minimal, defensive RSS 2.0 / Atom parser.
 *
 * Why hand-written rather than a library: the input is untrusted third-party XML, and every feed in
 * the wild is slightly broken. A parser we own lets us decide exactly what happens to malformed
 * input, keep the file Android-free so it is unit-tested in CI here, and avoid pulling a full XML
 * stack into the process just to read ten headlines.
 *
 * Deliberately *not* a general XML parser. It understands the shape of feeds and nothing else. It
 * never resolves external entities (no XXE), never fetches anything, and never throws on a single
 * bad item: one broken entry must not cost the user the rest of the feed.
 */
object FeedParser {

    private const val MAX_ITEMS = 200
    private const val MAX_FIELD_CHARS = 20_000

    /** Feeds larger than this are refused outright rather than parsed in a loop. */
    const val MAX_INPUT_CHARS = 4_000_000

    data class ParseResult(
        val items: List<ParsedItem>,
        val rejected: List<String>,
        /** True when the document did not look like a feed at all. */
        val unreadable: Boolean = false,
    )

    /**
     * A feed entry before it becomes a [RawItem]. Dates stay nullable and coverage stays explicit:
     * the pipeline downstream is strict about not inventing either.
     */
    data class ParsedItem(
        val id: String,
        val title: String,
        val link: String,
        val summary: String?,
        val publishedAtEpochMs: Long?,
        val summaryCoverage: ContentCoverage,
    )

    fun parse(xml: String, source: SourceCredibility, fetchedAtEpochMs: Long): ParseResult {
        if (xml.length > MAX_INPUT_CHARS) {
            return ParseResult(emptyList(), listOf("feed exceeds $MAX_INPUT_CHARS characters"), unreadable = true)
        }
        val isAtom = xml.contains("<feed", ignoreCase = true) && xml.contains("<entry", ignoreCase = true)
        val isRss = xml.contains("<rss", ignoreCase = true) || xml.contains("<channel", ignoreCase = true)
        if (!isAtom && !isRss) {
            return ParseResult(emptyList(), listOf("document is not an RSS or Atom feed"), unreadable = true)
        }

        val blocks = if (isAtom) extractBlocks(xml, "entry") else extractBlocks(xml, "item")
        if (blocks.isEmpty()) {
            return ParseResult(emptyList(), listOf("feed contains no entries"), unreadable = false)
        }

        val items = mutableListOf<ParsedItem>()
        val rejected = mutableListOf<String>()
        blocks.take(MAX_ITEMS).forEachIndexed { index, block ->
            val parsed = runCatching { parseEntry(block, source, index) }.getOrNull()
            when {
                parsed == null -> rejected += "entry[$index]: unparseable"
                parsed.title.isBlank() -> rejected += "entry[$index]: no title"
                parsed.link.isBlank() -> rejected += "entry[$index]: no link"
                !isHttpUrl(parsed.link) -> rejected += "entry[$index]: link is not http(s): ${parsed.link.take(80)}"
                else -> items += parsed
            }
        }
        if (blocks.size > MAX_ITEMS) {
            rejected += "feed has ${blocks.size} entries; only the first $MAX_ITEMS were read"
        }
        return ParseResult(items, rejected)
    }

    /** Converts parsed entries to pipeline items, preserving the honest coverage flag. */
    fun toRawItems(
        result: ParseResult,
        source: SourceCredibility,
        fetchedAtEpochMs: Long,
    ): List<RawItem> = result.items.mapNotNull { parsed ->
        val url = parsed.link.trim()
        if (!isHttpUrl(url)) return@mapNotNull null
        RawItem(
            id = "${source.sourceId}:${parsed.id.ifBlank { url }}",
            url = url,
            source = source,
            headline = parsed.title.trim(),
            contentExcerpt = parsed.summary,
            publishedAtEpochMs = parsed.publishedAtEpochMs,
            fetchedAtEpochMs = fetchedAtEpochMs,
            contentCoverage = parsed.summaryCoverage,
        )
    }

    private fun parseEntry(block: String, source: SourceCredibility, index: Int): ParsedItem {
        val title = field(block, "title").orEmpty().let(::stripHtml).trim()
        val link = linkOf(block)
        val summaryHtml = field(block, "description")
            ?: field(block, "summary")
            ?: field(block, "content")
        val summary = summaryHtml?.let(::stripHtml)?.trim()?.takeIf { it.isNotEmpty() }
        val guid = field(block, "guid") ?: field(block, "id") ?: link
        val published = field(block, "pubDate")
            ?.let { Dates.parseRfc1123(it) }
            ?: field(block, "published")?.let { Dates.parseIso8601(it) }
            ?: field(block, "updated")?.let { Dates.parseIso8601(it) }
            ?: field(block, "dc:date")?.let { Dates.parseIso8601(it) }

        return ParsedItem(
            id = guid.orEmpty().take(500).ifBlank { "index-$index" },
            title = title,
            link = link,
            summary = summary,
            publishedAtEpochMs = published,
            // A feed only ever gives us a summary. Claiming FULL_TEXT here would be a lie that the
            // digest then repeats to the user as "we read the article".
            summaryCoverage = if (summary != null) ContentCoverage.SUMMARY_ONLY else ContentCoverage.HEADLINE_ONLY,
        )
    }

    /** Atom uses `<link href="..."/>`; RSS uses `<link>...</link>`. Handle both. */
    private fun linkOf(block: String): String {
        val rss = field(block, "link")
        if (!rss.isNullOrBlank()) return rss.trim()
        val atom = Regex("""<link\b[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)
        return atom?.trim().orEmpty()
    }

    /** Returns the text of the first `<tag>` element, unwrapping CDATA. */
    private fun field(block: String, tag: String): String? {
        val pattern = Regex(
            """<$tag\b[^>]*>(.*?)</$tag>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val raw = pattern.find(block)?.groupValues?.get(1) ?: return null
        return unwrapCdata(raw).take(MAX_FIELD_CHARS)
    }

    private fun unwrapCdata(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("<![CDATA[")) return trimmed
        val end = trimmed.indexOf("]]>")
        return if (end > 9) trimmed.substring(9, end) else trimmed.removePrefix("<![CDATA[")
    }

    private fun extractBlocks(xml: String, tag: String): List<String> {
        val pattern = Regex(
            """<$tag\b[^>]*>(.*?)</$tag>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.findAll(xml).map { it.groupValues[1] }.toList()
    }

    /**
     * Removes markup and decodes the handful of entities feeds actually use. This is display text for
     * a headline, not a security boundary: the result is never interpreted as markup later.
     */
    internal fun stripHtml(value: String): String {
        var text = value
        text = text.replace(Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        text = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<[^>]+>"), "")
        text = text.replace("&nbsp;", " ")
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&#39;", "'")
        text = text.replace("&apos;", "'")
        // Numeric entities, bounded so a hostile feed cannot produce enormous code points.
        text = Regex("""&#(\d{1,6});""").replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace ""
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}

/**
 * The shipped feed list. Empty on purpose: SAMAVETA does not silently subscribe the user to anything,
 * and a URL list is user configuration, not a default.
 */
object ShippedSources {

    /** Feed sources the app will read once the user adds them. */
    fun defaults(): List<SourceCredibility> = emptyList()

    fun credibility(sourceId: String, displayName: String, kind: SourceKind = SourceKind.RSS): SourceCredibility =
        SourceCredibility(
            sourceId = sourceId,
            displayName = displayName,
            score = 0.5,
            kind = kind,
            isPrimarySource = false,
        )
}
