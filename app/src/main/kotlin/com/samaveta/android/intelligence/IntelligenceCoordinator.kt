package com.samaveta.android.intelligence

import com.samaveta.android.core.intelligence.ContentCoverage
import com.samaveta.android.core.intelligence.Digest
import com.samaveta.android.core.intelligence.DigestBuilder
import com.samaveta.android.core.memory.InterestProfile
import com.samaveta.android.core.intelligence.SourceCredibility
import com.samaveta.android.core.intelligence.SourceKind

/**
 * Runs one intelligence refresh: fetch every subscribed source, parse it, and build a digest.
 *
 * The interesting part is not the happy path, it is what the user is told when things go wrong:
 *
 *  - A feed that could not be reached is **reported as a failed source**, not quietly dropped.
 *  - If *every* source failed, the result says so. Presenting an empty digest in that case would
 *    tell the user "nothing happened in the world", which is a different and false claim.
 *  - With no subscriptions configured, the empty state asks the user to add a source rather than
 *    implying SAMAVETA is watching something.
 *  - Failures are per-source: one dead feed never suppresses the other feeds' stories.
 */
data class FeedSubscription(
    val source: SourceCredibility,
    /** Feed URL. Must be https: a plaintext feed can be rewritten in transit by anyone. */
    val url: String,
    val enabled: Boolean = true,
) {
    init {
        require(url.startsWith("https://")) { "feed urls must be https, got '$url'" }
        require(source.sourceId.isNotBlank()) { "source id required" }
    }
}

/** What the fetcher is allowed to return. Deliberately small: no headers, no redirects to follow. */
sealed interface FetchOutcome {
    data class Ok(val body: String, val etag: String? = null) : FetchOutcome

    /** Server said nothing changed. Not an error. */
    data object NotModified : FetchOutcome

    data class Failed(val reason: String) : FetchOutcome
}

fun interface FeedFetcher {
    suspend fun fetch(url: String, etag: String?): FetchOutcome
}

enum class SourceStatus { READ, NOT_MODIFIED, FAILED, DISABLED }

data class SourceReport(
    val sourceId: String,
    val displayName: String,
    val status: SourceStatus,
    val itemCount: Int = 0,
    val detail: String? = null,
)

data class IntelligenceRun(
    val digest: Digest,
    val sources: List<SourceReport>,
    val rejectedEntries: List<String>,
    val generatedAtEpochMs: Long,
    /** True when at least one source was subscribed but none could be read. */
    val allSourcesFailed: Boolean,
    val noSubscriptions: Boolean,
) {
    val failedSources: List<SourceReport> get() = sources.filter { it.status == SourceStatus.FAILED }

    /**
     * The line the UI shows above the cards. It must never imply a complete picture when the run was
     * partial, and must never imply the world is quiet when nothing could be read.
     */
    fun statusLine(): String = when {
        noSubscriptions ->
            "No sources added yet. Add a feed in Settings to start your digest."
        allSourcesFailed ->
            "Could not reach your sources. Nothing was checked, so this is not a quiet day — it is a failed refresh."
        digest.cards.isEmpty() && failedSources.isNotEmpty() ->
            "${failedSources.size} source(s) could not be reached. Nothing new from the rest."
        digest.cards.isEmpty() -> digest.emptyStateMessage()
        failedSources.isNotEmpty() ->
            "${digest.cards.size} new item(s). ${failedSources.size} source(s) could not be reached."
        else -> "${digest.cards.size} new item(s) from ${sources.count { it.status == SourceStatus.READ }} source(s)."
    }

    /** Limitations that belong on the digest as a whole, over and above per-card ones. */
    fun runLimitations(): List<String> = buildList {
        if (failedSources.isNotEmpty()) {
            add("Some sources could not be reached: ${failedSources.joinToString(", ") { it.displayName }}.")
        }
        if (rejectedEntries.isNotEmpty()) {
            add("${rejectedEntries.size} feed reader issue(s) were skipped; see diagnostics.")
        }
        if (!digest.summarizerAvailable && digest.cards.isNotEmpty()) {
            add("No summary model is installed, so cards show the publisher's own text.")
        }
    }
}

class IntelligenceCoordinator(
    private val fetcher: FeedFetcher,
    private val builder: DigestBuilder = DigestBuilder(),
    private val parser: (String, SourceCredibility, Long) -> FeedParser.ParseResult = { body, source, now ->
        FeedParser.parse(body, source, now)
    },
) {

    /**
     * @param etags last-known ETag per feed URL, so steady-state refreshes are cheap.
     * @return the run plus the new ETags to persist. Returning them separately keeps this function
     *   free of storage concerns while still allowing conditional requests.
     */
    suspend fun refresh(
        subscriptions: List<FeedSubscription>,
        interests: Map<String, InterestProfile>,
        nowEpochMs: Long,
        etags: Map<String, String?> = emptyMap(),
        topicOf: (com.samaveta.android.core.intelligence.RawItem) -> Set<String> = { emptySet() },
        maxCards: Int = 8,
    ): Pair<IntelligenceRun, Map<String, String?>> {
        val active = subscriptions.filter { it.enabled }
        if (active.isEmpty()) {
            val run = IntelligenceRun(
                digest = emptyDigest(nowEpochMs, summarizerAvailable = false),
                sources = subscriptions.map {
                    SourceReport(it.source.sourceId, it.source.displayName, SourceStatus.DISABLED)
                },
                rejectedEntries = emptyList(),
                generatedAtEpochMs = nowEpochMs,
                allSourcesFailed = false,
                noSubscriptions = true,
            )
            return run to etags
        }

        val items = mutableListOf<com.samaveta.android.core.intelligence.RawItem>()
        val reports = mutableListOf<SourceReport>()
        val rejected = mutableListOf<String>()
        val newEtags = etags.toMutableMap()

        for (subscription in active) {
            val outcome = try {
                fetcher.fetch(subscription.url, etags[subscription.url])
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                FetchOutcome.Failed("${failure::class.simpleName}: ${failure.message.orEmpty()}")
            }

            when (outcome) {
                is FetchOutcome.Failed -> reports += SourceReport(
                    subscription.source.sourceId,
                    subscription.source.displayName,
                    SourceStatus.FAILED,
                    detail = outcome.reason,
                )

                FetchOutcome.NotModified -> reports += SourceReport(
                    subscription.source.sourceId,
                    subscription.source.displayName,
                    SourceStatus.NOT_MODIFIED,
                )

                is FetchOutcome.Ok -> {
                    val parsed = parser(outcome.body, subscription.source, nowEpochMs)
                    outcome.etag?.let { newEtags[subscription.url] = it }
                    if (parsed.unreadable) {
                        // A feed that parses as nothing is a broken subscription, not an empty world.
                        reports += SourceReport(
                            subscription.source.sourceId,
                            subscription.source.displayName,
                            SourceStatus.FAILED,
                            detail = parsed.rejected.firstOrNull() ?: "feed could not be read",
                        )
                        continue
                    }
                    val raw = FeedParser.toRawItems(parsed, subscription.source, nowEpochMs)
                    items += raw
                    rejected += parsed.rejected.map { "${subscription.source.sourceId}: $it" }
                    reports += SourceReport(
                        subscription.source.sourceId,
                        subscription.source.displayName,
                        SourceStatus.READ,
                        itemCount = raw.size,
                    )
                }
            }
        }

        val digest = builder.build(
            items = items,
            interests = interests,
            nowEpochMs = nowEpochMs,
            maxCards = maxCards,
            topicOf = topicOf,
        )
        val readCount = reports.count { it.status == SourceStatus.READ || it.status == SourceStatus.NOT_MODIFIED }
        return IntelligenceRun(
            digest = digest,
            sources = reports,
            rejectedEntries = rejected,
            generatedAtEpochMs = nowEpochMs,
            allSourcesFailed = readCount == 0,
            noSubscriptions = false,
        ) to newEtags
    }

    private fun emptyDigest(nowEpochMs: Long, summarizerAvailable: Boolean) = Digest(
        cards = emptyList(),
        skipped = emptyList(),
        generatedAtEpochMs = nowEpochMs,
        summarizerAvailable = summarizerAvailable,
    )
}

/** A subscription with no feed yet: used by the empty state, and by tests that need a source. */
fun subscriptionFor(url: String, sourceId: String, displayName: String, enabled: Boolean = true) =
    FeedSubscription(
        source = SourceCredibility(
            sourceId = sourceId,
            displayName = displayName,
            score = 0.5,
            kind = SourceKind.RSS,
        ),
        url = url,
        enabled = enabled,
    )

/** Where a fetched article body would come from. Feeds only carry summaries; this is the read-more path. */
enum class FullTextAvailability {
    /** The feed gave us only a summary or headline. */
    NOT_FETCHED,

    /** The article was read through the browser and the reader mode produced usable text. */
    READ_VIA_BROWSER,

    /** Fetching was attempted and refused or failed; the reason is surfaced to the user. */
    UNAVAILABLE,
    ;

    fun coverageOf(excerptPresent: Boolean): ContentCoverage = when (this) {
        READ_VIA_BROWSER -> ContentCoverage.FULL_TEXT
        UNAVAILABLE, NOT_FETCHED -> if (excerptPresent) ContentCoverage.SUMMARY_ONLY else ContentCoverage.HEADLINE_ONLY
    }
}
