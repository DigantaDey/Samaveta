package com.samaveta.android.core.intelligence

import com.samaveta.android.core.memory.InterestProfile
import com.samaveta.android.core.memory.RelevanceRanker
import com.samaveta.android.core.memory.ScoringInput

/**
 * Digest assembly.
 *
 * A [IntelligenceCard] is the only object the UI may render as information. It structurally
 * requires provenance, and its [summary] is tagged with how much of the article was actually
 * available, so the product cannot claim to have analysed content it never fetched.
 */
data class IntelligenceCard(
    val story: Story,
    val summary: String,
    val whyItMatters: String?,
    val score: Double,
    val scoreExplanation: String,
    val topics: Set<String>,
    /** Explicit statement of limitations on this card. Never empty for partial coverage. */
    val limitations: List<String>,
    val sourceCount: Int,
) {
    init {
        require(summary.isNotBlank()) { "card must carry a summary" }
        val coverage = story.coverage
        if (coverage != ContentCoverage.FULL_TEXT) {
            require(limitations.isNotEmpty()) {
                "partial coverage (${coverage.name}) must state its limitation"
            }
        }
    }

    val provenance: Provenance get() = story.provenance()
}

/**
 * Summarizer boundary. The implementation is a model; this interface lets the pipeline be tested
 * without one and makes it explicit when summarization is unavailable.
 */
interface Summarizer {
    /**
     * Returns a summary of [story], or null when it cannot summarise (no model installed, offline
     * model missing, etc.). Returning null is required behaviour — the caller then presents the
     * publisher's own excerpt and says a summary was not generated.
     */
    suspend fun summarize(story: Story): String?
}

/** Never pretends to summarise. Used until a summarization model is installed. */
object NoSummarizer : Summarizer {
    override suspend fun summarize(story: Story): String? = null
}

class DigestBuilder(
    private val summarizer: Summarizer = NoSummarizer,
    private val ranker: RelevanceRanker = RelevanceRanker(),
    private val entityExtractor: EntityExtractor = EntityExtractor(),
    private val deduplicator: Deduplicator = Deduplicator(),
) {
    /**
     * Builds cards for [items].
     *
     * @param topicOf maps an item to its topic keys, normally from the user's configured interests.
     * @param noveltyOf 0..1 estimate of how new this is to the user (caller-supplied).
     */
    suspend fun build(
        items: List<RawItem>,
        interests: Map<String, InterestProfile>,
        nowEpochMs: Long,
        maxCards: Int = 8,
        topicOf: (RawItem) -> Set<String> = { emptySet() },
        noveltyOf: (RawItem) -> Double = { 0.5 },
    ): Digest {
        val clusters = deduplicator.deduplicate(items)
        val cards = mutableListOf<IntelligenceCard>()
        val skipped = mutableListOf<SkippedItem>()

        for (cluster in clusters) {
            val primary = cluster.first()
            val story = toStory(primary, cluster, topicOf, nowEpochMs)
            val scoring = ranker.score(
                ScoringInput(
                    cardId = story.id,
                    topics = story.topics,
                    publishedAtEpochMs = story.publishedAtEpochMs ?: primary.fetchedAtEpochMs,
                    sourceCredibility = story.primarySource.score,
                    novelty = noveltyOf(primary),
                ),
                interests,
                nowEpochMs,
            )
            if (scoring.suppressedByMute) {
                skipped += SkippedItem(story.id, SkipReason.MUTED_TOPIC, "You asked not to see ${story.topics.joinToString(", ")}.")
                continue
            }

            val limitations = buildList {
                story.coverage.limitationNotice?.let { add(it) }
                if (story.publishedAtEpochMs == null) add("The source did not state a publication date.")
                if (story.eventAtEpochMs != null && story.publishedAtEpochMs != null &&
                    story.eventAtEpochMs > story.publishedAtEpochMs
                ) {
                    add("The event date is later than the publication date; treat timing with caution.")
                }
                if (story.primarySource.score < 0.5) add("Source credibility is unestablished.")
                if (cluster.size > 1) add("${cluster.size} sources are reporting this event.")
            }

            val generated = try {
                summarizer.summarize(story)
            } catch (_: Exception) {
                null
            }

            val summary = when {
                generated != null && generated.isNotBlank() -> generated
                !story.excerpt.isNullOrBlank() -> story.excerpt
                else -> story.title
            }

            cards += IntelligenceCard(
                story = story,
                summary = summary,
                whyItMatters = null,
                score = scoring.total,
                scoreExplanation = scoring.explanation(),
                topics = story.topics,
                limitations = limitations,
                sourceCount = cluster.size,
            )
        }

        val ranked = cards.sortedByDescending { it.score }
        val kept = ranked.take(maxCards)
        ranked.drop(maxCards).forEach {
            skipped += SkippedItem(it.story.id, SkipReason.LOWER_RELEVANCE, "Ranked below the top $maxCards.")
        }

        return Digest(
            cards = kept,
            skipped = skipped,
            generatedAtEpochMs = nowEpochMs,
            summarizerAvailable = summarizer !== NoSummarizer,
        )
    }

    private fun toStory(
        primary: RawItem,
        cluster: List<RawItem>,
        topicOf: (RawItem) -> Set<String>,
        nowEpochMs: Long,
    ): Story {
        val topics = cluster.flatMap { topicOf(it) }.toSet()
        val textForEntities = listOfNotNull(primary.headline, primary.contentExcerpt).joinToString(". ")
        return Story(
            // Derived from the canonical URL: stable across runs, traceable back to the source,
            // and collision-free where a 32-bit hash would not be.
            id = "story:" + deduplicator.canonicalizeUrl(primary.url),
            title = primary.headline,
            canonicalUrl = deduplicator.canonicalizeUrl(primary.url),
            primarySource = primary.source,
            coverage = primary.contentCoverage,
            publishedAtEpochMs = primary.publishedAtEpochMs,
            eventAtEpochMs = null,
            excerpt = primary.contentExcerpt,
            topics = topics,
            entities = entityExtractor.extract(textForEntities),
            alsoReportedBy = cluster.drop(1).map { it.source.displayName }.distinct(),
            language = primary.language,
        )
    }
}

data class Digest(
    val cards: List<IntelligenceCard>,
    val skipped: List<SkippedItem>,
    val generatedAtEpochMs: Long,
    /** When false the UI must present publisher excerpts and say no summary was generated. */
    val summarizerAvailable: Boolean,
) {
    init {
        require(cards.size <= 100) { "an unreasonable number of cards was assembled: ${cards.size}" }
    }

    val isEmpty: Boolean get() = cards.isEmpty()

    /** Honest empty-state text. */
    fun emptyStateMessage(): String = when {
        skipped.any { it.reason == SkipReason.MUTED_TOPIC } ->
            "Nothing new to show. Some topics are hidden because you muted them."
        else -> "Nothing new right now. SAMAVETA will keep watching your topics."
    }
}

data class SkippedItem(val storyId: String, val reason: SkipReason, val explanation: String)

enum class SkipReason { MUTED_TOPIC, DUPLICATE, LOWER_RELEVANCE, STALE }
