package com.samaveta.android.core.memory

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Memory and personalization.
 *
 * Three memory categories are modelled explicitly (episodic, semantic, procedural), plus the
 * feedback-driven interest model that ranks Intelligence cards. Everything is local and inspectable:
 * every derived preference must be traceable to the signal that produced it, and every memory can be
 * individually deleted by the user.
 */

enum class MemoryKind { EPISODIC, SEMANTIC, PROCEDURAL }

data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    val createdAtEpochMs: Long,
    val lastAccessedEpochMs: Long,
    /** 0.0–1.0; how strongly this memory should influence behaviour. */
    val confidence: Double,
    val source: MemorySource,
    val tags: Set<String> = emptySet(),
    /** For SEMANTIC memories: which interest entity this is about, if any. */
    val subject: String? = null,
) {
    init {
        require(id.isNotBlank()) { "memory id required" }
        require(text.isNotBlank()) { "memory text required" }
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, got $confidence" }
        require(lastAccessedEpochMs >= 0 && createdAtEpochMs >= 0) { "timestamps must be non-negative" }
    }
}

/** Provenance of a memory. The user must be able to see why SAMAVETA believes something. */
sealed interface MemorySource {
    /** Stated directly by the user (highest trust). */
    data class ExplicitUserStatement(val conversationId: String?) : MemorySource

    /** Inferred from repeated behaviour; must be presented as inference, not fact. */
    data class InferredBehaviour(val signal: String, val occurrences: Int) : MemorySource

    /** Derived from feedback on an Intelligence card. */
    data class FeedbackDerived(val cardId: String) : MemorySource

    data class UserEdited(val editorNote: String?) : MemorySource
}

/**
 * Feedback vocabulary required by the directive. Explicit feedback outranks inferred engagement.
 */
enum class FeedbackSignal(val label: String, val valence: Double) {
    LIKE("More like this", 1.0),
    DISLIKE("Less like this", -1.0),
    NOT_RELEVANT("Not relevant", -0.8),
    REPETITIVE("Already seen this", -0.5),
    ALREADY_KNOWN("I already know this", -0.4),
    TOO_TECHNICAL("Too technical", -0.5),
    TOO_BASIC("Too basic", -0.5),
    POOR_SOURCE("Poor source", -0.9),
    NOT_INTERESTED("Not interested in this topic", -1.2),
    SHOW_MORE("Show more of this", 1.2),
    SHOW_LESS("Show less of this", -1.2),
    ;

    val isPositive: Boolean get() = valence > 0
}

data class FeedbackEvent(
    val id: String,
    val cardId: String,
    val topicKeys: Set<String>,
    val signal: FeedbackSignal,
    val atEpochMs: Long,
    /** True when the user explicitly tapped a control, false when inferred from engagement. */
    val explicit: Boolean = true,
)

/**
 * An interest profile entry for a topic.
 *
 * Weight is kept in an interpreted range and always accompanied by [confidence] and
 * [evidenceCount] so the UI can show "based on 3 signals" rather than a magic number, and so a
 * single tap cannot dominate personalization.
 */
data class InterestProfile(
    val topicKey: String,
    val displayName: String,
    val weight: Double,
    val confidence: Double,
    val evidenceCount: Int,
    val lastSignalEpochMs: Long,
    val firstSeenEpochMs: Long,
    /** Topics the user explicitly muted; these are honoured over any positive weight. */
    val muted: Boolean = false,
) {
    val isActionable: Boolean get() = !muted && confidence >= MIN_CONFIDENCE_TO_ACT

    companion object {
        /** Below this confidence a preference is shown as "learning" and does not reorder results. */
        const val MIN_CONFIDENCE_TO_ACT = 0.35
        const val MIN_INTEREST = -5.0
        const val MAX_INTEREST = 5.0
    }
}

/**
 * Turns feedback into interest weights.
 *
 * Design rules (all unit-tested):
 *  - Explicit feedback carries [EXPLICIT_MULTIPLIER]× the influence of inferred engagement.
 *  - Weight updates are damped as evidence accumulates, so one tap cannot define an interest.
 *  - Recency decay is applied per update and when scoring, so interests fade if the user moves on.
 *  - Muting is absolute and survives positive signals until the user unmutes.
 */
class InterestModel(
    private val explicitMultiplier: Double = 3.0,
    private val baseLearningRate: Double = 0.9,
    private val halfLifeDays: Double = 30.0,
    private val dayMs: Long = 86_400_000L,
) {
    init {
        require(explicitMultiplier >= 1.0) { "explicit feedback must weigh at least as much as inferred" }
        require(halfLifeDays > 0) { "half life must be positive" }
    }

    /** Applies [event] to [existing] (null = new topic) and returns the updated profile. */
    fun apply(existing: InterestProfile?, event: FeedbackEvent, displayName: String = event.topicKeys.first()): InterestProfile {
        var current = existing
        val results = mutableListOf<InterestProfile>()
        // One event can be about several topics; each is updated independently.
        for (topic in event.topicKeys) {
            val base = if (topic == event.topicKeys.first()) current else null
            val updated = applySingle(base, topic, displayName, event)
            if (topic == event.topicKeys.first()) current = updated
            results += updated
        }
        return results.first()
    }

    fun applyToAll(existing: Map<String, InterestProfile>, event: FeedbackEvent): Map<String, InterestProfile> {
        val out = existing.toMutableMap()
        for (topic in event.topicKeys) {
            out[topic] = applySingle(existing[topic], topic, topic, event)
        }
        return out
    }

    private fun applySingle(
        existing: InterestProfile?,
        topicKey: String,
        displayName: String,
        event: FeedbackEvent,
    ): InterestProfile {
        val decayedWeight = existing?.let { decay(it, event.atEpochMs) } ?: 0.0
        val count = (existing?.evidenceCount ?: 0) + 1
        val multiplier = if (event.explicit) explicitMultiplier else 1.0

        // Damped learning rate: 1st signal ≈ base, later signals contribute less.
        val damping = baseLearningRate / sqrt(count.toDouble())
        val delta = event.signal.valence * multiplier * damping

        // A mute is sticky: only an explicit user action may lift it. A later "show more" tap must
        // not silently undo "not interested".
        val mutedBySignal = event.signal == FeedbackSignal.NOT_INTERESTED
        val muted = mutedBySignal || existing?.muted == true

        val newWeight = (decayedWeight + delta).coerceIn(InterestProfile.MIN_INTEREST, InterestProfile.MAX_INTEREST)
        // Confidence rises with independent evidence and with explicit statements.
        val confidence = ((1.0 - Math.pow(0.7, count.toDouble())) * (if (event.explicit) 1.0 else 0.6))
            .coerceIn(0.0, 1.0)

        return InterestProfile(
            topicKey = topicKey,
            displayName = displayName,
            weight = newWeight,
            confidence = confidence,
            evidenceCount = count,
            lastSignalEpochMs = event.atEpochMs,
            firstSeenEpochMs = existing?.firstSeenEpochMs ?: event.atEpochMs,
            muted = muted,
        )
    }

    /**
     * Explicitly lifts a mute. This is the only way a muted topic becomes visible again; it is
     * invoked from the user's preference-management screen, never from feedback inference.
     */
    fun unmute(profile: InterestProfile, nowEpochMs: Long): InterestProfile = profile.copy(
        muted = false,
        lastSignalEpochMs = nowEpochMs,
        // A muted topic has no meaningful positive weight to restore, so restart gently.
        weight = 0.0,
    )

    /** Exponential decay towards zero, half-life [halfLifeDays]. */
    fun decay(profile: InterestProfile, nowEpochMs: Long): Double {
        val elapsed = (nowEpochMs - profile.lastSignalEpochMs).coerceAtLeast(0L)
        val periods = elapsed.toDouble() / (halfLifeDays * dayMs)
        return profile.weight * Math.pow(0.5, periods)
    }

    /** Effective ranking weight at [nowEpochMs], honouring mute and confidence thresholds. */
    fun effectiveWeight(profile: InterestProfile, nowEpochMs: Long): Double = when {
        profile.muted -> -InterestProfile.MAX_INTEREST
        profile.confidence < InterestProfile.MIN_CONFIDENCE_TO_ACT -> 0.0
        else -> decay(profile, nowEpochMs)
    }
}

/** Feedback and interest history with hard caps so storage cannot grow without bound. */
class MemoryStore(
    private val maxItems: Int = 5_000,
    private val maxFeedbackEvents: Int = 20_000,
) {
    private val items = LinkedHashMap<String, MemoryItem>()
    private val feedback = ArrayDeque<FeedbackEvent>()

    fun put(item: MemoryItem) {
        items[item.id] = item
        if (items.size > maxItems) {
            // Evict the least recently accessed, lowest-confidence memory first.
            items.entries
                .sortedWith(compareBy<Map.Entry<String, MemoryItem>> { it.value.lastAccessedEpochMs }
                    .thenBy { it.value.confidence })
                .take(items.size - maxItems)
                .map { it.key }
                .forEach { items.remove(it) }
        }
    }

    fun get(id: String, nowEpochMs: Long? = null): MemoryItem? {
        val item = items[id] ?: return null
        return if (nowEpochMs != null) {
            val touched = item.copy(lastAccessedEpochMs = nowEpochMs)
            items[id] = touched
            touched
        } else {
            item
        }
    }

    fun delete(id: String): Boolean = items.remove(id) != null

    fun byKind(kind: MemoryKind): List<MemoryItem> = items.values.filter { it.kind == kind }

    fun all(): List<MemoryItem> = items.values.toList()

    fun addFeedback(event: FeedbackEvent) {
        feedback.addLast(event)
        while (feedback.size > maxFeedbackEvents) feedback.removeFirst()
    }

    fun feedbackFor(cardId: String): List<FeedbackEvent> = feedback.filter { it.cardId == cardId }

    fun allFeedback(): List<FeedbackEvent> = feedback.toList()

    /** Full export for the user's "export my data" control. */
    fun export(): MemoryExport = MemoryExport(
        items = items.values.toList(),
        feedback = feedback.toList(),
    )

    /** Irreversible personalization reset. */
    fun clear() {
        items.clear()
        feedback.clear()
    }

    fun size(): Int = items.size
}

data class MemoryExport(val items: List<MemoryItem>, val feedback: List<FeedbackEvent>)

/**
 * A candidate Intelligence card, scored against the interest profile.
 *
 * Scoring is deterministic and fully explainable: every component contributes a named term so the
 * UI can answer "why am I seeing this?".
 */
data class ScoringInput(
    val cardId: String,
    val topics: Set<String>,
    val publishedAtEpochMs: Long,
    val sourceCredibility: Double,
    val novelty: Double,
    val entities: Set<String> = emptySet(),
)

data class ScoreBreakdown(
    val total: Double,
    val interestTerm: Double,
    val recencyTerm: Double,
    val credibilityTerm: Double,
    val noveltyTerm: Double,
    val matchedTopics: Set<String>,
    val suppressedByMute: Boolean,
) {
    /** Human-readable "why you are seeing this" line. */
    fun explanation(): String = when {
        suppressedByMute -> "Hidden: you asked not to see this topic."
        matchedTopics.isEmpty() -> "New territory — no strong interest signal yet."
        interestTerm > 0 -> "Matches your interest in ${matchedTopics.joinToString(", ")}."
        else -> "Related to ${matchedTopics.joinToString(", ")}, which you engage with less."
    }
}

class RelevanceRanker(
    private val interestModel: InterestModel = InterestModel(),
    private val recencyHalfLifeHours: Double = 18.0,
    private val hourMs: Long = 3_600_000L,
) {
    fun score(
        input: ScoringInput,
        interests: Map<String, InterestProfile>,
        nowEpochMs: Long,
        sourceTrustFloor: Double = 0.3,
    ): ScoreBreakdown {
        val matched = input.topics.filter { interests.containsKey(it) }.toSet()
        val profiles = matched.mapNotNull { interests[it] }
        val suppressed = profiles.any { it.muted }

        // Interest: average effective weight across matched topics, normalised to 0..1 around zero.
        val interestRaw = if (profiles.isEmpty()) {
            0.0
        } else {
            profiles.map { interestModel.effectiveWeight(it, nowEpochMs) }.average()
        }
        val interestTerm = (interestRaw / InterestProfile.MAX_INTEREST) * 0.5

        val ageHours = ((nowEpochMs - input.publishedAtEpochMs).coerceAtLeast(0L)).toDouble() / hourMs
        val recencyTerm = Math.pow(0.5, ageHours / recencyHalfLifeHours) * 0.25
        val credibilityTerm = input.sourceCredibility.coerceIn(0.0, 1.0) * 0.15
        val noveltyTerm = input.novelty.coerceIn(0.0, 1.0) * 0.10

        var total = interestTerm + recencyTerm + credibilityTerm + noveltyTerm
        if (input.sourceCredibility < sourceTrustFloor) total *= 0.5
        if (suppressed) total = Double.NEGATIVE_INFINITY

        return ScoreBreakdown(
            total = total,
            interestTerm = interestTerm,
            recencyTerm = recencyTerm,
            credibilityTerm = credibilityTerm,
            noveltyTerm = noveltyTerm,
            matchedTopics = matched,
            suppressedByMute = suppressed,
        )
    }

    fun rank(
        inputs: List<ScoringInput>,
        interests: Map<String, InterestProfile>,
        nowEpochMs: Long,
    ): List<Pair<ScoringInput, ScoreBreakdown>> =
        inputs.map { it to score(it, interests, nowEpochMs) }
            .filter { it.second.total != Double.NEGATIVE_INFINITY }
            .sortedByDescending { it.second.total }
}

/** Recency of *interaction*, used for "don't resurface the same thing" behaviour. */
object Recency {
    fun hoursSince(thenEpochMs: Long, nowEpochMs: Long): Double =
        ((nowEpochMs - thenEpochMs).coerceAtLeast(0L)).toDouble() / 3_600_000.0

    /** True when two timestamps are within [toleranceMs] (used for near-duplicate event dates). */
    fun sameDay(aEpochMs: Long, bEpochMs: Long, toleranceMs: Long = 86_400_000L): Boolean =
        abs(aEpochMs - bEpochMs) <= toleranceMs
}

/** Cosine similarity for local embedding search; vectors must be comparable in size. */
object VectorMath {
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vector dimensions differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /** Normalises in place so later dot products are cosine similarities. */
    fun normalizeInPlace(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        if (sum == 0.0) return v
        val inv = (1.0 / sqrt(sum)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    /** Bounded top-k search, so a large local index cannot block the UI thread. */
    fun topK(query: FloatArray, index: List<Pair<String, FloatArray>>, k: Int): List<Pair<String, Double>> =
        index.asSequence()
            .filter { it.second.size == query.size }
            .map { it.first to cosine(query, it.second) }
            .sortedByDescending { it.second }
            .take(k.coerceAtLeast(0))
            .toList()
}

/** Log-scaled engagement used as a weak, non-explicit signal. */
fun inferredEngagementWeight(dwellMs: Long, openCount: Int): Double {
    if (dwellMs <= 0 || openCount <= 0) return 0.0
    val dwellScore = ln(1.0 + dwellMs / 1_000.0) / 10.0
    val openScore = ln(1.0 + openCount.toDouble()) / 3.0
    return (dwellScore * 0.6 + openScore * 0.4).coerceIn(0.0, 1.0)
}
