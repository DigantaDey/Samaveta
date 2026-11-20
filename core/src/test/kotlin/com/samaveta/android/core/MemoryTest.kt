package com.samaveta.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.samaveta.android.core.memory.FeedbackEvent
import com.samaveta.android.core.memory.FeedbackSignal
import com.samaveta.android.core.memory.InterestModel
import com.samaveta.android.core.memory.InterestProfile
import com.samaveta.android.core.memory.MemoryItem
import com.samaveta.android.core.memory.MemoryKind
import com.samaveta.android.core.memory.MemorySource
import com.samaveta.android.core.memory.MemoryStore
import com.samaveta.android.core.memory.RelevanceRanker
import com.samaveta.android.core.memory.ScoringInput
import com.samaveta.android.core.memory.VectorMath
import com.samaveta.android.core.memory.inferredEngagementWeight

class MemoryTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun event(
        topics: Set<String>,
        signal: FeedbackSignal,
        at: Long = now,
        explicit: Boolean = true,
        cardId: String = "card-1",
    ) = FeedbackEvent("e-${topics.hashCode()}-$signal", cardId, topics, signal, at, explicit)

    private fun item(id: String, kind: MemoryKind = MemoryKind.SEMANTIC, confidence: Double = 0.8) = MemoryItem(
        id = id,
        kind = kind,
        text = "User is interested in on-device AI",
        createdAtEpochMs = now - day,
        lastAccessedEpochMs = now - day,
        confidence = confidence,
        source = MemorySource.ExplicitUserStatement("conv-1"),
    )

    // ---- feedback weighting ------------------------------------------------------------------

    @Test
    fun repeatedDislikesProduceNegativeInterest() {
        val model = InterestModel()
        var profiles = emptyMap<String, InterestProfile>()
        repeat(5) { i ->
            profiles = model.applyToAll(profiles, event(setOf("crypto"), FeedbackSignal.DISLIKE, at = now + i * 1_000L))
        }
        assertTrue("weight should go negative", profiles.getValue("crypto").weight < 0)
        assertEquals(5, profiles.getValue("crypto").evidenceCount)
    }

    @Test
    fun explicitFeedbackOutweighsInferredEngagement() {
        val model = InterestModel()
        val explicitProfile = model.applyToAll(
            emptyMap(),
            event(setOf("on-device-ai"), FeedbackSignal.LIKE, explicit = true),
        ).getValue("on-device-ai")
        val inferredProfile = model.applyToAll(
            emptyMap(),
            event(setOf("on-device-ai"), FeedbackSignal.LIKE, explicit = false),
        ).getValue("on-device-ai")
        assertTrue(
            "explicit signal must dominate",
            explicitProfile.weight > inferredProfile.weight,
        )
        assertTrue(explicitProfile.confidence > inferredProfile.confidence)
    }

    @Test
    fun singleTapCannotDominatePersonalization() {
        val model = InterestModel()
        val oneTap = model.applyToAll(emptyMap(), event(setOf("t"), FeedbackSignal.LIKE)).getValue("t")
        assertTrue("confidence must stay low after one signal", oneTap.confidence < InterestProfile.MIN_CONFIDENCE_TO_ACT)
        assertEquals(0.0, model.effectiveWeight(oneTap, now), 0.0001)
    }

    @Test
    fun dampingKeepsWeightsBounded() {
        val model = InterestModel()
        var profiles = emptyMap<String, InterestProfile>()
        repeat(200) { i ->
            profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.SHOW_MORE, at = now + i))
        }
        val w = profiles.getValue("ai").weight
        assertTrue("weight must be clamped", w <= InterestProfile.MAX_INTEREST)
        assertTrue(w > 0)
    }

    @Test
    fun interestsDecayOverTime() {
        val model = InterestModel(halfLifeDays = 30.0)
        var profiles = emptyMap<String, InterestProfile>()
        repeat(6) { i -> profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.LIKE, at = now + i)) }
        val profile = profiles.getValue("ai")
        val w0 = model.decay(profile, profile.lastSignalEpochMs)
        val w30 = model.decay(profile, profile.lastSignalEpochMs + 30 * day)
        val w90 = model.decay(profile, profile.lastSignalEpochMs + 90 * day)
        assertEquals("half life halves the weight", w0 / 2, w30, 0.05)
        assertTrue(w90 < w30)
        assertTrue(w90 > 0)
    }

    @Test
    fun notInterestedMutesTopicAbsolutely() {
        val model = InterestModel()
        var profiles = emptyMap<String, InterestProfile>()
        repeat(5) { i -> profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.LIKE, at = now + i)) }
        profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.NOT_INTERESTED, at = now + 100))
        assertTrue(profiles.getValue("ai").muted)
        assertFalse(profiles.getValue("ai").isActionable)
        assertEquals(-InterestProfile.MAX_INTEREST, model.effectiveWeight(profiles.getValue("ai"), now + 200), 0.001)
    }

    @Test
    fun mutedTopicStaysMutedAgainstLaterPositiveSignal() {
        val model = InterestModel()
        var profiles = model.applyToAll(emptyMap(), event(setOf("ai"), FeedbackSignal.NOT_INTERESTED))
        profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.SHOW_MORE, at = now + 10))
        assertTrue("an explicit mute must not be silently overridden", profiles.getValue("ai").muted)
    }

    @Test
    fun muteCanOnlyBeLiftedExplicitlyByTheUser() {
        val model = InterestModel()
        var profiles = model.applyToAll(emptyMap(), event(setOf("ai"), FeedbackSignal.NOT_INTERESTED))
        assertTrue(profiles.getValue("ai").muted)
        // A later positive signal must not lift the mute.
        profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.SHOW_MORE, at = now + 5))
        assertTrue(profiles.getValue("ai").muted)
        // Only the explicit unmute path restores the topic.
        val restored = model.unmute(profiles.getValue("ai"), now + 10)
        assertFalse(restored.muted)
        assertEquals(0.0, restored.weight, 0.0001)
    }

    @Test
    fun newTopicStartsFromZeroAndTracksFirstSeen() {
        val model = InterestModel()
        val profile = model.applyToAll(emptyMap(), event(setOf("robotics"), FeedbackSignal.LIKE)).getValue("robotics")
        assertEquals(1, profile.evidenceCount)
        assertEquals(now, profile.firstSeenEpochMs)
        assertFalse(profile.muted)
    }

    @Test
    fun multiTopicEventUpdatesEveryTopic() {
        val model = InterestModel()
        val profiles = model.applyToAll(emptyMap(), event(setOf("ai", "android", "chips"), FeedbackSignal.LIKE))
        assertEquals(setOf("ai", "android", "chips"), profiles.keys)
        profiles.values.forEach { assertTrue(it.weight > 0) }
    }

    // ---- ranking -----------------------------------------------------------------------------

    @Test
    fun rankingPrefersMatchedInterests() {
        val model = InterestModel()
        var profiles = emptyMap<String, InterestProfile>()
        repeat(6) { i -> profiles = model.applyToAll(profiles, event(setOf("ai"), FeedbackSignal.SHOW_MORE, at = now + i)) }

        val ranker = RelevanceRanker(model)
        val inputs = listOf(
            ScoringInput("c1", setOf("sports"), now - 3_600_000L, 0.9, 0.8),
            ScoringInput("c2", setOf("ai"), now - 3_600_000L, 0.9, 0.8),
        )
        val ranked = ranker.rank(inputs, profiles, now + 100)
        assertEquals("c2", ranked.first().first.cardId)
    }

    @Test
    fun mutedTopicsAreRemovedFromRanking() {
        val model = InterestModel()
        var profiles = model.applyToAll(emptyMap(), event(setOf("crypto"), FeedbackSignal.NOT_INTERESTED))
        profiles = model.applyToAll(profiles, event(setOf("crypto"), FeedbackSignal.DISLIKE))
        val ranker = RelevanceRanker(model)
        val ranked = ranker.rank(
            listOf(ScoringInput("c1", setOf("crypto"), now, 0.95, 1.0)),
            profiles,
            now,
        )
        assertTrue("muted topics must not appear at all", ranked.isEmpty())
    }

    @Test
    fun lowCredibilitySourceIsPenalisedNotDropped() {
        val model = InterestModel()
        val ranker = RelevanceRanker(model)
        val bad = ranker.score(ScoringInput("c1", setOf("ai"), now, 0.1, 0.5), emptyMap(), now)
        val good = ranker.score(ScoringInput("c2", setOf("ai"), now, 0.95, 0.5), emptyMap(), now)
        assertTrue(bad.total < good.total)
        assertTrue(bad.total > 0.0 - 1.0)
    }

    @Test
    fun olderNewsRanksLowerThanFreshNews() {
        val ranker = RelevanceRanker(InterestModel())
        val fresh = ranker.score(ScoringInput("c1", setOf("ai"), now - 1_800_000L, 0.9, 0.5), emptyMap(), now)
        val stale = ranker.score(ScoringInput("c2", setOf("ai"), now - 5 * day, 0.9, 0.5), emptyMap(), now)
        assertTrue(fresh.total > stale.total)
    }

    @Test
    fun scoreExplanationIsHumanReadable() {
        val ranker = RelevanceRanker(InterestModel())
        val breakdown = ranker.score(ScoringInput("c1", setOf("ai"), now, 0.9, 0.5), emptyMap(), now)
        assertTrue(breakdown.explanation().isNotBlank())
        assertTrue(breakdown.explanation().contains("New territory"))
    }

    // ---- store -------------------------------------------------------------------------------

    @Test
    fun storeAccessUpdatesLastAccessed() {
        val store = MemoryStore()
        store.put(item("m1"))
        val fetched = store.get("m1", nowEpochMs = now)
        assertNotNull(fetched)
        assertEquals(now, fetched!!.lastAccessedEpochMs)
    }

    @Test
    fun storeEvictsLeastRecentlyAccessedWhenFull() {
        val store = MemoryStore(maxItems = 3)
        store.put(item("m1").copy(lastAccessedEpochMs = 1))
        store.put(item("m2").copy(lastAccessedEpochMs = 2))
        store.put(item("m3").copy(lastAccessedEpochMs = 3))
        store.put(item("m4").copy(lastAccessedEpochMs = 4))
        assertEquals(3, store.size())
        assertNull("oldest must be evicted", store.get("m1"))
        assertNotNull(store.get("m4"))
    }

    @Test
    fun storeDeleteAndClearAreImmediate() {
        val store = MemoryStore()
        store.put(item("m1"))
        store.put(item("m2", kind = MemoryKind.EPISODIC))
        assertTrue(store.delete("m1"))
        assertEquals(1, store.size())
        store.addFeedback(event(setOf("ai"), FeedbackSignal.LIKE))
        store.clear()
        assertEquals(0, store.size())
        assertTrue(store.allFeedback().isEmpty())
    }

    @Test
    fun storeExportContainsEverything() {
        val store = MemoryStore()
        store.put(item("m1"))
        store.put(item("m2", kind = MemoryKind.PROCEDURAL))
        store.addFeedback(event(setOf("ai"), FeedbackSignal.LIKE))
        val export = store.export()
        assertEquals(2, export.items.size)
        assertEquals(1, export.feedback.size)
        assertEquals(1, store.byKind(MemoryKind.SEMANTIC).size)
        assertEquals(1, store.byKind(MemoryKind.PROCEDURAL).size)
    }

    @Test
    fun feedbackIsCapped() {
        val store = MemoryStore(maxFeedbackEvents = 5)
        repeat(10) { i -> store.addFeedback(event(setOf("t"), FeedbackSignal.LIKE, at = now + i, cardId = "c$i")) }
        assertEquals(5, store.allFeedback().size)
    }

    // ---- vector math -------------------------------------------------------------------------

    @Test
    fun cosineSimilarityIsCorrect() {
        assertEquals(1.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 0.0001)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001)
        assertEquals(-1.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 0.0001)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0.0001)
    }

    @Test
    fun cosineRejectsMismatchedDimensions() {
        try {
            VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f))
            org.junit.Assert.fail("dimension mismatch must fail loudly")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("dimensions"))
        }
    }

    @Test
    fun normalizeProducesUnitVectors() {
        val v = VectorMath.normalizeInPlace(floatArrayOf(3f, 4f))
        assertEquals(0.6, v[0].toDouble(), 0.0001)
        assertEquals(0.8, v[1].toDouble(), 0.0001)
    }

    @Test
    fun topKReturnsBestMatchesOnly() {
        val index = listOf(
            "a" to floatArrayOf(1f, 0f),
            "b" to floatArrayOf(0.9f, 0.1f),
            "c" to floatArrayOf(0f, 1f),
        )
        val top = VectorMath.topK(floatArrayOf(1f, 0f), index, 2)
        assertEquals(2, top.size)
        assertEquals("a", top[0].first)
        assertEquals("b", top[1].first)
    }

    @Test
    fun inferredEngagementIsNeutralForNoSignal() {
        assertEquals(0.0, inferredEngagementWeight(0, 0), 0.0001)
        val weak = inferredEngagementWeight(5_000, 1)
        val strong = inferredEngagementWeight(600_000, 12)
        assertTrue(strong > weak)
        assertTrue(strong <= 1.0)
    }
}
