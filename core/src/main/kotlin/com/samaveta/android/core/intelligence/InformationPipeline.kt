package com.samaveta.android.core.intelligence

/**
 * Information intelligence: ingestion → normalization → deduplication → entity extraction →
 * relevance → summarization → delivery.
 *
 * The stages here are deterministic where determinism is possible (dedupe, date parsing, entity
 * extraction, freshness, attribution) and leave summarization to a model. This is deliberate: the
 * directive requires that sources, dates and "did we actually read the article?" be facts, not
 * model output. A summarizer may paraphrase; it may not invent provenance.
 */

enum class SourceKind { RSS, NEWS_SITE, OFFICIAL_API, USER_URL, NEWSLETTER, VIDEO, DOCUMENT }

data class SourceCredibility(
    val sourceId: String,
    val displayName: String,
    /** 0.0–1.0 editorial/verifiability score, set by policy not by a model. */
    val score: Double,
    val kind: SourceKind,
    val isPrimarySource: Boolean = false,
) {
    init {
        require(score in 0.0..1.0) { "credibility must be 0..1" }
        require(sourceId.isNotBlank() && displayName.isNotBlank())
    }
}

/**
 * A raw item as fetched. [contentExcerpt] is exactly what was retrieved: a full body, or only a
 * headline/snippet. Nothing downstream may pretend otherwise.
 */
data class RawItem(
    val id: String,
    val url: String,
    val source: SourceCredibility,
    val headline: String,
    val contentExcerpt: String?,
    val publishedAtEpochMs: Long?,
    val fetchedAtEpochMs: Long,
    val language: String = "en",
    /** How much of the article we actually have. */
    val contentCoverage: ContentCoverage,
) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://")) { "item url required" }
        require(headline.isNotBlank()) { "headline required" }
    }
}

enum class ContentCoverage {
    /** Full article body retrieved and parsed. */
    FULL_TEXT,

    /** Only feed-provided summary/description. */
    SUMMARY_ONLY,

    /** Only the headline or a search snippet. */
    HEADLINE_ONLY,
    ;

    val limitationNotice: String?
        get() = when (this) {
            FULL_TEXT -> null
            SUMMARY_ONLY -> "Based on the publisher's summary, not the full article."
            HEADLINE_ONLY -> "Based only on the headline; the article body was not retrieved."
        }
}

/** A normalized, deduplicated story. */
data class Story(
    val id: String,
    val title: String,
    val canonicalUrl: String,
    val primarySource: SourceCredibility,
    val coverage: ContentCoverage,
    val publishedAtEpochMs: Long?,
    /** Event date when the publisher states it; null when unknown. Never guessed. */
    val eventAtEpochMs: Long?,
    val excerpt: String?,
    val topics: Set<String>,
    val entities: List<ExtractedEntity>,
    /** Other sources reporting the same event (duplicate coverage). */
    val alsoReportedBy: List<String> = emptyList(),
    val language: String = "en",
) {
    init {
        require(title.isNotBlank()) { "story title required" }
    }

    /** Provenance block required on every surfaced item. */
    fun provenance(): Provenance = Provenance(
        publisher = primarySource.displayName,
        url = canonicalUrl,
        publishedAtEpochMs = publishedAtEpochMs,
        eventAtEpochMs = eventAtEpochMs,
        coverageLimitation = coverage.limitationNotice,
        alsoReportedBy = alsoReportedBy,
    )
}

data class Provenance(
    val publisher: String,
    val url: String,
    val publishedAtEpochMs: Long?,
    val eventAtEpochMs: Long?,
    val coverageLimitation: String?,
    val alsoReportedBy: List<String>,
)

enum class EntityType { PERSON, ORGANISATION, LOCATION, PRODUCT, EVENT, OTHER }

/**
 * An extracted entity. [confidence] and [method] are mandatory so the UI can distinguish a
 * lexicon hit from a model guess, and the product never presents a guess as fact.
 */
data class ExtractedEntity(
    val text: String,
    val type: EntityType,
    val confidence: Double,
    val method: ExtractionMethod,
) {
    init {
        require(text.isNotBlank()) { "entity text required" }
        require(confidence in 0.0..1.0) { "entity confidence must be 0..1" }
    }
}

enum class ExtractionMethod { LEXICON, CAPITALISATION_HEURISTIC, MODEL }

/**
 * Deterministic entity extraction.
 *
 * Uses a supplied lexicon plus a conservative capitalisation heuristic. It is intentionally modest:
 * it would rather return fewer entities than hallucinate them. Model-based extraction can be layered
 * on top by callers that have a model available, marking [ExtractionMethod.MODEL].
 */
class EntityExtractor(
    private val lexicon: Map<String, EntityType> = DEFAULT_LEXICON,
) {
    fun extract(text: String): List<ExtractedEntity> {
        val found = LinkedHashMap<String, ExtractedEntity>()
        val lower = text.lowercase()

        // 1. Lexicon hits (case-insensitive, word-boundary aware).
        for ((name, type) in lexicon) {
            if (containsWord(lower, name.lowercase())) {
                found[name] = ExtractedEntity(name, type, confidence = 0.9, method = ExtractionMethod.LEXICON)
            }
        }

        // 2. Conservative capitalisation heuristic for multi-word proper nouns not in the lexicon.
        MULTI_WORD_PROPER.findAll(text).forEach { match ->
            val candidate = match.value.trim()
            if (candidate.length in 3..60 && found.keys.none { it.equals(candidate, ignoreCase = true) }) {
                if (!STOPWORD_STARTS.any { candidate.startsWith(it) }) {
                    found.putIfAbsent(
                        candidate,
                        ExtractedEntity(candidate, EntityType.OTHER, 0.45, ExtractionMethod.CAPITALISATION_HEURISTIC),
                    )
                }
            }
        }
        return found.values.sortedByDescending { it.confidence }
    }

    private fun containsWord(haystack: String, needle: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(needle)}(?![\\p{L}\\p{N}])").containsMatchIn(haystack)

    companion object {
        private val MULTI_WORD_PROPER = Regex("\\b(?:[A-Z][a-z0-9]+\\s){1,3}[A-Z][a-z0-9]+\\b")
        private val STOPWORD_STARTS = listOf("The ", "This ", "That ", "A ", "An ", "In ", "On ", "As ", "It ")

        /** A small, curated seed lexicon. Extendable from user interests at runtime. */
        val DEFAULT_LEXICON: Map<String, EntityType> = mapOf(
            "Google" to EntityType.ORGANISATION,
            "Android" to EntityType.PRODUCT,
            "Qualcomm" to EntityType.ORGANISATION,
            "Snapdragon" to EntityType.PRODUCT,
            "Apple" to EntityType.ORGANISATION,
            "OpenAI" to EntityType.ORGANISATION,
            "Hugging Face" to EntityType.ORGANISATION,
            "India" to EntityType.LOCATION,
            "Bengaluru" to EntityType.LOCATION,
            "Kolkata" to EntityType.LOCATION,
            "United States" to EntityType.LOCATION,
            "Gemini" to EntityType.PRODUCT,
            "LiteRT" to EntityType.PRODUCT,
            "PyTorch" to EntityType.PRODUCT,
            "ICML" to EntityType.EVENT,
            "NeurIPS" to EntityType.EVENT,
        )
    }
}

/**
 * Duplicate detection.
 *
 * Two items are the same story when they share a canonical URL, or when their titles are similar
 * enough AND they were published within the same window. Clustering is deterministic.
 */
class Deduplicator(
    private val titleSimilarityThreshold: Double = 0.72,
    private val sameEventWindowMs: Long = 36 * 3_600_000L,
) {
    fun canonicalizeUrl(url: String): String {
        // Strip tracking params, fragments and trailing slashes so the same article dedupes.
        val withoutFragment = url.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val trimmed = base.trimEnd('/')
        val params = withoutFragment.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key in TRACKING_PARAMS || key.startsWith("utm_")
            }
            .sorted()
        return if (params.isEmpty()) trimmed else "$trimmed?${params.joinToString("&")}"
    }

    fun titleSignature(title: String): Set<String> =
        tokenize(title).filter { it.length > 2 && it !in STOPWORDS }.toSet()

    /**
     * Numeric tokens found in a title ("Android 17", "Q3 results", "5 nm"). Titles whose numbers
     * disagree are treated as different events: dedupe must not merge "Android 16 preview" into
     * "Android 17 preview" or ₹100 crore into ₹1,000 crore.
     */
    fun numericSignature(title: String): Set<String> =
        Regex("\\d+(?:[.,]\\d+)*").findAll(title).map { it.value }.toSet()

    /** Jaccard similarity of title signatures, 0..1. */
    fun titleSimilarity(a: String, b: String): Double {
        val sa = titleSignature(a)
        val sb = titleSignature(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val intersection = sa.intersect(sb).size.toDouble()
        return intersection / sa.union(sb).size
    }

    /**
     * Groups [items] into stories, preserving the highest-credibility item as the primary source.
     * Deterministic: input order breaks ties.
     */
    fun deduplicate(items: List<RawItem>): List<List<RawItem>> {
        val clusters = mutableListOf<MutableList<RawItem>>()
        for (item in items) {
            val canonical = canonicalizeUrl(item.url)
            val existing = clusters.firstOrNull { cluster ->
                cluster.any { member ->
                    canonicalizeUrl(member.url) == canonical ||
                        (titleSimilarity(member.headline, item.headline) >= titleSimilarityThreshold &&
                            numericSignature(member.headline) == numericSignature(item.headline) &&
                            sameEvent(member.publishedAtEpochMs, item.publishedAtEpochMs, item.fetchedAtEpochMs))
                }
            }
            if (existing != null) existing += item else clusters += mutableListOf(item)
        }
        return clusters.map { cluster ->
            cluster.sortedWith(
                compareByDescending<RawItem> { it.source.isPrimarySource }
                    .thenByDescending { it.source.score }
                    .thenByDescending { it.contentCoverage.ordinal * -1 },
            )
        }
    }

    private fun sameEvent(a: Long?, b: Long?, fallback: Long): Boolean {
        val left = a ?: fallback
        val right = b ?: fallback
        return kotlin.math.abs(left - right) <= sameEventWindowMs
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    companion object {
        private val TRACKING_PARAMS = setOf("fbclid", "gclid", "ref", "source", "campaign", "cmpid", "ocid")
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "about", "after", "over",
            "its", "his", "her", "their", "your", "our", "has", "have", "had", "was", "were", "are",
            "will", "can", "may", "not", "but", "you", "they", "how", "why", "what", "when", "who",
        )
    }
}

/** A published date as reported by the source, with how it was obtained. */
data class DateEvidence(
    val epochMs: Long?,
    val origin: DateOrigin,
)

enum class DateOrigin {
    /** Publisher's structured metadata (RSS pubDate, JSON-LD datePublished). */
    PUBLISHER_METADATA,

    /** Visible on the page. */
    PAGE_TEXT,

    /** Inferred from the URL pattern; weak. */
    URL_PATTERN,

    UNKNOWN,
}

/**
 * Date handling. Rule: a date is only reported with its [DateOrigin]; when unknown the UI says so
 * rather than showing the fetch time as a publication date (a classic news-app lie).
 */
object Dates {
    fun parseIso8601(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(value.trim()).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parseRfc1123(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Publication date with explicit provenance, never silently defaulted. */
    fun publication(item: RawItem): DateEvidence = DateEvidence(
        epochMs = item.publishedAtEpochMs,
        origin = if (item.publishedAtEpochMs == null) DateOrigin.UNKNOWN else DateOrigin.PUBLISHER_METADATA,
    )

    fun ageHours(publishedAtEpochMs: Long?, nowEpochMs: Long): Double? =
        publishedAtEpochMs?.let { ((nowEpochMs - it).coerceAtLeast(0L)).toDouble() / 3_600_000.0 }

    /** Human phrasing that is honest about uncertainty. */
    fun describeAge(publishedAtEpochMs: Long?, nowEpochMs: Long): String {
        val hours = ageHours(publishedAtEpochMs, nowEpochMs) ?: return "Publication time not stated by the source"
        return when {
            hours < 1 -> "Published in the last hour"
            hours < 24 -> "Published ${hours.toInt()} hour(s) ago"
            hours < 48 -> "Published yesterday"
            else -> "Published ${(hours / 24).toInt()} day(s) ago"
        }
    }
}
