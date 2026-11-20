package com.samaveta.android.core

import com.samaveta.android.core.model.Figure
import com.samaveta.android.core.model.FigureSource
import com.samaveta.android.core.model.ModelCatalog
import com.samaveta.android.core.model.ModelCatalogEntry
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelFormat
import com.samaveta.android.core.model.ModelRole
import com.samaveta.android.core.model.Modality
import com.samaveta.android.core.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The catalog is a list of models nobody has downloaded, so the tests here are about the gate rather
 * than about model quality. The recurring theme: a catalog entry must never be able to claim more
 * evidence than exists, and a number in the UI must never be bare.
 */
class ModelCatalogTest {

    @Test
    fun everySuppliedModelIsListedExactlyOnce() {
        assertEquals(27, ModelCatalog.entries.size)
        assertEquals(ModelCatalog.entries.size, ModelCatalog.entries.map { it.modelId.lowercase() }.toSet().size)
    }

    @Test
    fun noEntryClaimsEvidenceItDoesNotHave() {
        ModelCatalog.entries.forEach { entry ->
            assertEquals(entry.modelId, VerificationStatus.CANDIDATE, entry.status)
            assertTrue(entry.statusLine().contains("cannot be downloaded"))
            assertNull("${entry.modelId} must not carry an invented artifact hash", entry.artifactSha256)
            assertFalse(entry.isDownloadable)
        }
        assertTrue(ModelCatalog.nothingDownloadable)
    }

    @Test
    fun theLicenceFamiliesMatchTheDocumentedAudit() {
        // MODEL_LICENSES.md groups the 27 by family; this pins the grouping so a new entry cannot
        // quietly arrive in a family nobody has reviewed.
        fun count(family: String) = ModelCatalog.entries.count { it.licenseFamily == family }
        assertEquals(14, count("Apache"))
        assertEquals(3, count("Llama"))
        assertEquals(2, count("Gemma"))
        assertEquals(4, count("LFM"))
        assertEquals(1, count("Tencent Hunyuan"))
        // Creative Commons covers three entries: parakeet (CC-BY-4.0) and the two NC TTS models.
        assertEquals(3, count("Creative Commons"))
        assertEquals(
            setOf("Apache-2.0", "Llama 3.2 Community License", "Gemma Terms of Use", "LFM Open License",
                "Tencent Hunyuan Community License", "CC-BY-4.0", "CC-BY-NC-SA-4.0"),
            ModelCatalog.entries.map { it.licenseIdentifier }.toSet(),
        )
        assertEquals(
            2,
            ModelCatalog.entries.count { it.licenseFamily == "Creative Commons" && it.licenseNonCommercial },
        )
    }

    @Test
    fun everyEntryNamesARealHttpsSource() {
        ModelCatalog.entries.forEach { entry ->
            assertTrue(entry.upstreamUrl.startsWith("https://huggingface.co/"))
            assertTrue("${entry.modelId} needs its own support URL", entry.docsUrl.startsWith("https://"))
            assertTrue(entry.modelId in entry.upstreamUrl)
        }
    }

    @Test
    fun estimatedFiguresAreLabelledAndDeclaredFiguresAreNot() {
        ModelCatalog.entries.forEach { entry ->
            assertEquals(FigureSource.ESTIMATED, entry.estimatedPeakRamMb.source)
            assertNotNull("${entry.modelId} needs a stated basis for its RAM cost", entry.estimatedPeakRamMb.note)
            entry.estimatedQuantizedSizeMb?.let { size ->
                assertEquals(FigureSource.ESTIMATED, size.source)
                assertTrue(size.value > 0)
            }
            entry.contextWindowTokens?.let { context ->
                // Context lengths came from the product owner's list, so they are declared, not measured.
                assertEquals(FigureSource.DECLARED, context.source)
            }
        }
    }

    @Test
    fun sizeAndPeakRamAgreeWithTheParameterCount() {
        // A 1B model at 0.55 bytes per parameter is ~550 MB of weights and ~742 MB of peak RAM
        // (550 * 1.35 overhead). Checked against a known entry so a broken constant cannot pass.
        val qwen = ModelCatalog.find("Qwen/Qwen3-0.6B")
        assertNotNull(qwen)
        assertEquals(330L, qwen!!.estimatedQuantizedSizeMb!!.value)
        assertEquals((330 * 1.35).toInt(), qwen.estimatedPeakRamMb.value)

        // Peak RAM must always exceed the stored artifact: weights are read into memory plus state.
        ModelCatalog.entries.forEach { entry ->
            entry.estimatedQuantizedSizeMb?.let { size ->
                assertTrue(
                    "${entry.modelId}: peak RAM ${entry.estimatedPeakRamMb.value} should exceed size ${size.value}",
                    entry.estimatedPeakRamMb.value >= size.value,
                )
            }
        }
    }

    @Test
    fun theSizeEstimateIsDecimalMegabytesNotGigabytes() {
        // Regression guard: an earlier form of the arithmetic multiplied by 1e6 and divided by 1e6 in
        // a way that could yield a kilobyte-scale figure for a 1B model. A 1B entry must be hundreds
        // of MB, never single digits and never tens of thousands.
        val llama = ModelCatalog.find("meta-llama/Llama-3.2-1B-Instruct")!!
        val size = llama.estimatedQuantizedSizeMb!!.value
        assertTrue("expected hundreds of MB, got $size", size in 400L..900L)
    }

    @Test
    fun nonCommercialModelsAreFlaggedAndExcludedFromReleaseSafety() {
        val nonCommercial = ModelCatalog.licenceBlocked().map { it.modelId }.sorted()
        assertEquals(
            listOf("OuteAI/Llama-OuteTTS-1.0-1B", "SparkAudio/Spark-TTS-0.5B"),
            nonCommercial,
        )
        nonCommercial.forEach { id ->
            val entry = ModelCatalog.find(id)!!
            assertTrue(entry.licenseNonCommercial)
            assertFalse(id, entry in ModelCatalog.releaseSafe())
        }
        // A device pick must never land on a non-commercial model, even when it is the smallest.
        assertNull(ModelCatalog.bestFor(ModelCapability.TTS, availableRamMb = 4))
        val tts = ModelCatalog.bestFor(ModelCapability.TTS, availableRamMb = 8_192)!!
        assertFalse(tts.licenseNonCommercial)
        assertEquals("hexgrad/Kokoro-82M", tts.modelId)
    }

    @Test
    fun gatedModelsAreRecordedSoAOneTapInstallCannotSilentlyFail() {
        val gated = ModelCatalog.entries.filter { it.gated }.map { it.modelId }.sorted()
        assertEquals(
            listOf(
                "fdtn-ai/antares-1b",
                "google/functiongemma-270m-it",
                "google/gemma-3-1b-it",
                "meta-llama/Llama-3.2-1B-Instruct",
            ),
            gated,
        )
    }

    @Test
    fun discoveryIsByRoleAndCapability() {
        assertTrue(ModelCatalog.byRole(ModelRole.ASR).isNotEmpty())
        assertTrue(ModelCatalog.byRole(ModelRole.TTS).isNotEmpty())
        assertTrue(ModelCatalog.byRole(ModelRole.OCR).isNotEmpty())
        assertEquals(
            listOf("Qwen/Qwen3-ASR-0.6B", "nvidia/parakeet-tdt-0.6b-v3"),
            ModelCatalog.byRole(ModelRole.ASR).map { it.modelId }.sorted(),
        )
        assertTrue(ModelCatalog.byCapability(ModelCapability.OCR).all { Modality.IMAGE in it.modalitiesIn })
        assertNull(ModelCatalog.find("nobody/not-a-model"))
        assertNotNull(ModelCatalog.find("qwen/qwen3-asr-0.6b")) // identifiers are compared case-insensitively
    }

    @Test
    fun aTtsModelIsTheOnlyKindThatClaimsToProduceAudio() {
        ModelCatalog.entries.forEach { entry ->
            if (ModelCapability.TTS in entry.capabilities) {
                assertTrue("${entry.modelId} should emit audio", Modality.AUDIO in entry.modalitiesOut)
            } else {
                assertFalse("${entry.modelId} must not claim audio output", Modality.AUDIO in entry.modalitiesOut)
            }
            assertTrue("${entry.modelId} needs an input modality", entry.modalitiesIn.isNotEmpty())
            assertTrue("${entry.modelId} needs a capability", entry.capabilities.isNotEmpty())
            assertTrue("${entry.modelId} needs a runtime format", entry.preferredFormats.isNotEmpty())
        }
    }

    @Test
    fun abliteratedAndUncensoredEntriesAreRecordedAsSuch() {
        val unaligned = ModelCatalog.entries.filter { !it.safetyAligned }.map { it.modelId }.sorted()
        assertEquals(
            listOf(
                "ElementMerc/tinyllama-1.1b-uncensored",
                "dphn/Dolphin3.0-Llama3.2-1B",
                "huihui-ai/Huihui-Qwen3.5-0.8B-abliterated",
                "nztinversive/llama3.2-1b-Uncensored",
            ),
            unaligned,
        )
        // Every one of them carries a written reason; a flag without a note is not reviewable.
        ModelCatalog.entries.filter { !it.safetyAligned }.forEach { entry ->
            assertNotNull("${entry.modelId} must explain why it is unaligned", entry.notes)
        }
        // Whatever the device, the pick is an aligned, non-commercial-free, ungated model — even
        // when an abliterated or gated candidate is cheaper or would otherwise sort first.
        listOf(256, 512, 1_024, 2_048, 8_192).forEach { ram ->
            listOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION, ModelCapability.VISION_LANGUAGE)
                .forEach { capability ->
                    val fitting = ModelCatalog.byCapability(capability).filter { it.estimatedPeakRamMb.value <= ram }
                    ModelCatalog.bestFor(capability, ram)?.let { pick ->
                        assertTrue("$capability at ${ram}MB picked ${pick.modelId} (unaligned)", pick.safetyAligned)
                        assertFalse(
                            "$capability at ${ram}MB picked ${pick.modelId} (non-commercial)",
                            pick.licenseNonCommercial,
                        )
                        // Gated is a tie-break, not a prohibition: it may only be chosen when nothing
                        // ungated fits at all.
                        if (fitting.any { !it.gated }) {
                            assertFalse("$capability at ${ram}MB picked gated ${pick.modelId}", pick.gated)
                        }
                    }
                }
        }
        // At 256 MB exactly one text candidate fits and it is gated, so the pick is gated — the
        // honest outcome, since refusing outright would hide a model that does fit.
        val tiny = ModelCatalog.bestFor(ModelCapability.TEXT_GENERATION, availableRamMb = 256)
        assertEquals("google/functiongemma-270m-it", tiny?.modelId)
    }

    @Test
    fun aDevicePickPrefersAnUngatedModelOverAGatedOne() {
        // Qwen3-0.6B (600M, Apache) and functiongemma-270m-it (270M, gated) both fit 2 GB; the
        // smaller gated model must not win, because it cannot be installed unattended.
        val pick = ModelCatalog.bestFor(ModelCapability.CLASSIFICATION, availableRamMb = 2_048)
        assertEquals("HuggingFaceTB/SmolLM2-135M-Instruct", pick?.modelId)
        ModelCatalog.entries.filter { it.gated }.forEach { entry ->
            assertTrue("${entry.modelId} is gated and must say so", entry.gated)
        }
        // Determinism: the same call twice returns the same entry.
        assertEquals(pick, ModelCatalog.bestFor(ModelCapability.CLASSIFICATION, availableRamMb = 2_048))
    }

    @Test
    fun bestForRefusesRatherThanRecommendingSomethingThatWillNotFit() {
        // Nothing in the catalog is under 64 MB of peak RAM, so an absurdly small device gets null.
        assertNull(ModelCatalog.bestFor(ModelCapability.ASR, availableRamMb = 32))
        // Plenty of RAM: the smallest ASR candidate wins, and it is the Apache-licensed one.
        val asr = ModelCatalog.bestFor(ModelCapability.ASR, availableRamMb = 8_192)
        assertEquals("Qwen/Qwen3-ASR-0.6B", asr?.modelId)
        // An unsatisfiable capability is null, not an exception and not a substitute model.
        assertTrue(ModelCatalog.byCapability(ModelCapability.TEXT_EMBEDDING).isEmpty())
    }

    @Test
    fun toDescriptorRefusesAnEntryWithoutARealArtifactHash() {
        val entry = ModelCatalog.find("Qwen/Qwen3.5-0.8B")!!
        try {
            ModelCatalog.toDescriptor(entry)
            fail("a candidate entry must not become a registry descriptor")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("SHA-256"))
        }
    }

    @Test
    fun toDescriptorCarriesTheLicenceThroughOnceAnArtifactExists() {
        val realHash = "a".repeat(64)
        val approved = ModelCatalog.find("SparkAudio/Spark-TTS-0.5B")!!.copy(
            status = VerificationStatus.ARTIFACT_VERIFIED,
            artifactSha256 = realHash,
        )
        val descriptor = ModelCatalog.toDescriptor(approved, runtimeId = "onnx-test")
        assertEquals("onnx-test", descriptor.runtimeId)
        assertEquals(realHash, descriptor.sha256)
        assertTrue(descriptor.downloadUrl.startsWith("https://"))
        assertFalse(descriptor.isReleaseSafe) // non-commercial, still recorded
        assertEquals(approved.capabilities, descriptor.capabilities)
        assertTrue(descriptor.storageRequirementMb > 0)
        assertEquals(descriptor.runtimeRamRequirementMb, descriptor.peakRamRequirementMb)

        val apache = ModelCatalog.find("Qwen/Qwen3-0.6B")!!.copy(
            status = VerificationStatus.ARTIFACT_VERIFIED,
            artifactSha256 = realHash,
        )
        val apacheDescriptor = ModelCatalog.toDescriptor(apache)
        assertTrue(apacheDescriptor.isReleaseSafe)
        assertFalse(apacheDescriptor.license.attributionRequired)
        assertNull(apacheDescriptor.license.attributionText)
    }

    @Test
    fun anEntryCannotBeMarkedVerifiedWithoutAHash() {
        try {
            ModelCatalogEntry(
                    modelId = "example/tiny-model",
                displayName = "Tiny",
                role = ModelRole.TEXT,
                parameterCountMillions = 10,
                contextWindowTokens = null,
                licenseIdentifier = "Apache-2.0",
                licenseNonCommercial = false,
                licenseFamily = "Apache",
                gated = false,
                modalitiesIn = setOf(Modality.TEXT),
                modalitiesOut = setOf(Modality.TEXT),
                capabilities = setOf(ModelCapability.TEXT_GENERATION),
                preferredFormats = setOf(ModelFormat.ONNX),
                estimatedQuantizedSizeMb = Figure(5L, FigureSource.ESTIMATED),
                estimatedPeakRamMb = Figure(64, FigureSource.ESTIMATED),
                upstreamUrl = "https://huggingface.co/example/tiny-model",
                docsUrl = "https://huggingface.co/example/tiny-model",
                status = VerificationStatus.BENCHMARKED,
            )
            fail("BENCHMARKED without an artifact hash must be rejected at construction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("SHA-256"))
        }
    }

    @Test
    fun statusLinesAreHonestAboutWhatIsNotVerified() {
        val candidate = ModelCatalog.find("hexgrad/Kokoro-82M")!!
        assertTrue(candidate.statusLine().contains("cannot be downloaded"))
        val rejected = candidate.copy(
            status = VerificationStatus.REJECTED,
            notes = "no GGML runtime for this ABI",
        )
        assertEquals("Not used: no GGML runtime for this ABI", rejected.statusLine())
        val ready = candidate.copy(status = VerificationStatus.APPROVED, artifactSha256 = "b".repeat(64))
        assertEquals("Ready to install", ready.statusLine())
        // And the shipped catalog can only ever render the honest line today.
        assertTrue(ModelCatalog.entries.all { it.statusLine().contains("cannot be downloaded") })
    }

    @Test
    fun everyEntryHasAMobileRuntimePathOrSaysSo() {
        // The catalog is for a phone; an entry with only a GGUF tag is still llama.cpp-mobile-capable,
        // so the assertion is that the format set is one the registry knows, not that it is LiteRT.
        ModelCatalog.entries.forEach { entry ->
            assertTrue("${entry.modelId} has no formats", entry.preferredFormats.isNotEmpty())
        }
        assertTrue(ModelCatalog.find("hexgrad/Kokoro-82M")!!.hasMobileRuntimePath)
    }
}
