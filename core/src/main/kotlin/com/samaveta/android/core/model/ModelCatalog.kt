package com.samaveta.android.core.model

import com.samaveta.android.core.device.Accelerator

/**
 * The shipped model catalog, and the gate between "someone listed a model" and "SAMAVETA will run it".
 *
 * Three rules drive this file, and they exist because every one of them is easy to get wrong:
 *
 *  1. **A catalog entry is not a verified entry.** Every model here is `CANDIDATE`: identified and
 *     assessed, but never downloaded, never benchmarked, and its licence terms read by a human rather
 *     than assumed. `VerificationStatus` records that, and `ModelCatalogEntry.isDownloadable` is false
 *     until an artifact SHA-256 has been recorded from a real distribution.
 *  2. **No invented numbers.** A parameter count or context length is only recorded when the source
 *     states it. Where the supplied list gives a figure, it is carried as `Declared`; where it does
 *     not, the field is null. Artifact sizes and RAM costs are **estimates** and are labelled as such,
 *     because the registry's admission gate depends on them and a guess presented as a measurement
 *     would let a device attempt a model it cannot hold.
 *  3. **Licence gates are real.** `LicenseInfo` already refuses a release-unsafe model. The catalog
 *     adds the discriminator the directive cares about: non-commercial and custom licenses are
 *     recorded as such, so building a distributed release does not silently ship, for example,
 *     CC-BY-NC-SA weights.
 */

/** How much is actually known about a catalog entry. Ordered from least to most evidence. */
enum class VerificationStatus {
    /** Named, with a source, and assessed on paper. Nothing has been downloaded or run. */
    CANDIDATE,

    /** Artifact obtained and its SHA-256 recorded from the real distribution. */
    ARTIFACT_VERIFIED,

    /** Benchmarked on a named device; accuracy, latency and memory measured. */
    BENCHMARKED,

    /** License read and confirmed for the intended distribution; ABI and runtime verified. */
    APPROVED,

    /** Considered and rejected, with the reason retained. */
    REJECTED,
    ;

    val allowsDownload: Boolean get() = this == ARTIFACT_VERIFIED || this == BENCHMARKED || this == APPROVED
}

/**
 * Where a number came from. This exists so that a reviewer can tell a published figure from a
 * measurement and from an estimate, without reading commit history.
 */
enum class FigureSource {
    /** Stated by the model's own documentation or the product owner's brief. */
    DECLARED,

    /** Measured on a named device by this project. */
    MEASURED,

    /**
     * SAMAVETA's own arithmetic from parameter count and quantisation. Good enough to refuse a model
     * on a small device; not good enough to promise performance.
     */
    ESTIMATED,
}

/** A figure with its provenance attached, so no number is ever bare. */
data class Figure<T>(val value: T, val source: FigureSource, val note: String? = null)

/** What a model would be used for in SAMAVETA. Drives the UI grouping and the routing policy. */
enum class ModelRole(val label: String, val description: String) {
    AGENT_REASONING("Agent & tools", "Turns an instruction into a described action"),
    TEXT("Chat & writing", "General text generation and rewriting"),
    MICRO_ROUTER("Routing", "Microscopic classification and routing"),
    OCR("Reading documents", "Text extraction from screenshots, receipts and PDFs"),
    VISION("Understanding images", "Image and screenshot reasoning, object grounding"),
    ASR("Listening", "Speech to text"),
    TTS("Speaking", "Text to speech"),
    VOICE_AGENT("Voice conversation", "End-to-end speech in and out"),
    CODE("Code", "Completion and code security review"),
    EMBEDDING("Search", "Semantic search and deduplication"),
}

/**
 * One catalog entry.
 *
 * @param modelId the exact `org/repo` identifier from the upstream host, so a download is unambiguous.
 * @param licenseNonCommercial true when the licence forbids commercial distribution. Recorded as a
 *   hard flag rather than buried in the identifier string, so a release gate can act on it.
 * @param gated true when the upstream repository requires accepting terms or approval before download.
 *   A gated model cannot be fetched unattended, which matters for a one-tap install.
 */
data class ModelCatalogEntry(
    val modelId: String,
    val displayName: String,
    val role: ModelRole,
    val parameterCountMillions: Int?,
    val contextWindowTokens: Figure<Int>?,
    val licenseIdentifier: String,
    val licenseNonCommercial: Boolean,
    val licenseFamily: String,
    val gated: Boolean,
    val modalitiesIn: Set<Modality>,
    val modalitiesOut: Set<Modality>,
    val capabilities: Set<ModelCapability>,
    val preferredFormats: Set<ModelFormat>,
    val estimatedQuantizedSizeMb: Figure<Long>?,
    val estimatedPeakRamMb: Figure<Int>,
    val upstreamUrl: String,
    val docsUrl: String,
    val status: VerificationStatus,
    /** Populated only from a real distribution. Until then the entry cannot be downloaded. */
    val artifactSha256: String? = null,
    val notes: String? = null,
    /**
     * False for refusal-abliterated or "uncensored" fine-tunes whose alignment has been deliberately
     * removed. SAMAVETA's real boundary is the deterministic permission layer, not the model's
     * willingness to refuse, but a product that sells safety-first behaviour must not silently
     * default to a model that has had its safety behaviour stripped. Recorded per entry so a device
     * pick can prefer an aligned model at equal cost.
     */
    val safetyAligned: Boolean = true,
) {
    init {
        require(modelId.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "catalog modelId must be an upstream org/repo identifier: $modelId"
        }
        require(docsUrl.startsWith("https://")) { "catalog entries need an https source: $modelId" }
        require(!(status.allowsDownload && artifactSha256 == null)) {
            "$modelId is marked ${status.name} but has no artifact SHA-256; record the real hash first"
        }
        if (artifactSha256 != null) {
            require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "artifactSha256 must be lower-case hex" }
        }
    }

    /** The registry key. Derived, stable, and lower-case per `ModelDescriptor`'s rules. */
    val registryId: String
        get() = modelId.lowercase().replace('/', '.').replace(':', '.').replace(' ', '-')

    val isDownloadable: Boolean get() = status.allowsDownload && artifactSha256 != null

    /** True for ONNX/LiteRT paths that do not need a llama.cpp-family runtime. */
    val hasMobileRuntimePath: Boolean
        get() = preferredFormats.any { it in MOBILE_FORMATS }

    /** A short, honest sentence for the Models screen. */
    fun statusLine(): String = when {
        status == VerificationStatus.REJECTED -> "Not used: ${notes ?: "rejected"}"
        isDownloadable -> "Ready to install"
        else -> "Listed, not yet verified — cannot be downloaded"
    }

    private companion object {
        val MOBILE_FORMATS = setOf(ModelFormat.LITERT_LM, ModelFormat.LITERT, ModelFormat.TFLITE, ModelFormat.ONNX)
    }
}

/**
 * The catalog as supplied and assessed on 2026-11-20.
 *
 * Everything below is `CANDIDATE`. That is not modesty, it is accuracy: no artifact has been fetched,
 * no SHA-256 recorded, and no device measurement exists. The list is deliberately kept in one place
 * so the Models screen, the licensing audit and the routing policy all read the same facts.
 */
object ModelCatalog {

    /**
     * Decimal megabytes of weights: a model with `parametersMillions` weights stored at
     * `bytesPerParameter` bytes each occupies `parametersMillions * bytesPerParameter` MB, because a
     * million parameters at one byte per parameter is one megabyte. This is the only arithmetic here,
     * and it is deliberately kept in one place so a wrong constant cannot drift between entries.
     */
    private fun weightsMb(parametersMillions: Int, bytesPerParameter: Double): Double =
        parametersMillions * bytesPerParameter

    /** Rough arithmetic only; every use is labelled `ESTIMATED`. */
    private fun estimatePeakRamMb(parametersMillions: Int?, bytesPerParameter: Double, overhead: Double = 1.35): Int {
        if (parametersMillions == null) return 0
        // Weights + KV cache + runtime overhead. Deliberately pessimistic: over-reserving is a
        // refusal the user can read, under-reserving is an LMKD kill mid-conversation.
        return (weightsMb(parametersMillions, bytesPerParameter) * overhead).toInt().coerceAtLeast(64)
    }

    private fun estimateSizeMb(parametersMillions: Int?, bytesPerParameter: Double): Long? {
        if (parametersMillions == null) return null
        return weightsMb(parametersMillions, bytesPerParameter).toLong().coerceAtLeast(1)
    }

    // ---- license families (identifiers as the upstream publishes them) ----
    private const val APACHE = "Apache-2.0"
    private const val LLAMA = "Llama 3.2 Community License"
    private const val GEMMA = "Gemma Terms of Use"
    private const val LFM = "LFM Open License"
    private const val HUNYUAN = "Tencent Hunyuan Community License"
    private const val CC_BY_4 = "CC-BY-4.0"
    private const val CC_BY_NC_SA = "CC-BY-NC-SA-4.0"

    private fun agentEntry(
        modelId: String,
        name: String,
        paramsM: Int?,
        context: Int?,
        license: String,
        family: String,
        nonCommercial: Boolean = false,
        gated: Boolean = false,
        capabilities: Set<ModelCapability>,
        modalitiesIn: Set<Modality> = setOf(Modality.TEXT),
        notes: String? = null,
        /** Support documentation for the model. Defaults to its own model card, never another project's. */
        docs: String? = null,
        formats: Set<ModelFormat> = setOf(ModelFormat.GGUF, ModelFormat.ONNX, ModelFormat.LITERT_LM),
        role: ModelRole = ModelRole.AGENT_REASONING,
        bytesPerParam: Double = 0.55,
        safetyAligned: Boolean = true,
    ) = ModelCatalogEntry(
        modelId = modelId,
        displayName = name,
        role = role,
        parameterCountMillions = paramsM,
        contextWindowTokens = context?.let { Figure(it, FigureSource.DECLARED) },
        licenseIdentifier = license,
        licenseNonCommercial = nonCommercial,
        licenseFamily = family,
        gated = gated,
        modalitiesIn = modalitiesIn,
        // Derived, not hand-written: every listed model emits text (an ASR or OCR model emits a
        // transcript), and only a TTS model additionally emits audio. Writing this per entry invites
        // an entry that claims audio output but is a text runtime.
        modalitiesOut = if (ModelCapability.TTS in capabilities) {
            setOf(Modality.TEXT, Modality.AUDIO)
        } else {
            setOf(Modality.TEXT)
        },
        capabilities = capabilities,
        preferredFormats = formats,
        estimatedQuantizedSizeMb = estimateSizeMb(paramsM, bytesPerParam)?.let {
            Figure(it, FigureSource.ESTIMATED, "INT4-equivalent; not measured")
        },
        estimatedPeakRamMb = estimatePeakRamMb(paramsM, bytesPerParam).let {
            Figure(it, FigureSource.ESTIMATED, "weights + KV cache + runtime overhead")
        },
        upstreamUrl = "https://huggingface.co/$modelId",
        docsUrl = docs ?: "https://huggingface.co/$modelId",
        status = VerificationStatus.CANDIDATE,
        notes = notes,
        safetyAligned = safetyAligned,
    )

    /**
     * The entries, grouped by what SAMAVETA would use them for.
     *
     * Note on the abliterated/uncensored variants: several are listed because the owner supplied them,
     * but SAMAVETA's own use of them is constrained. A refusal-abliterated model is not a better agent;
     * it is a model with weaker safety behaviour running inside a product that relies on deterministic
     * permission checks. They are recorded with that trade-off written down rather than quietly
     * excluded or quietly preferred.
     */
    val entries: List<ModelCatalogEntry> = listOf(

        // ---------------- Agent reasoning / tool use ----------------
        agentEntry(
            "Qwen/Qwen3.5-0.8B", "Qwen3.5 0.8B", 800, 262_144, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.VISION_LANGUAGE, ModelCapability.CLASSIFICATION),
            modalitiesIn = setOf(Modality.TEXT, Modality.IMAGE),
            notes = "Best sub-1B tool calling per the brief; safety-aligned, so it may refuse security-adjacent prompts.",
        ),
        agentEntry(
            "huihui-ai/Huihui-Qwen3.5-0.8B-abliterated", "Qwen3.5 0.8B (abliterated)", 800, 262_144, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.VISION_LANGUAGE),
            modalitiesIn = setOf(Modality.TEXT, Modality.IMAGE),
            notes = "Refusal-abliterated. Weaker safety behaviour; SAMAVETA's permission layer is the actual " +
                "boundary, so this is recorded as a trade-off, not an upgrade.",
            safetyAligned = false,
        ),
        agentEntry(
            "meta-llama/Llama-3.2-1B-Instruct", "Llama 3.2 1B Instruct", 1_236, 131_072, LLAMA, "Llama",
            gated = true,
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION),
            notes = "Gated download (terms acceptance) and a custom licence; the widest mobile runtime support.",
        ),
        agentEntry(
            "dphn/Dolphin3.0-Llama3.2-1B", "Dolphin 3.0 Llama 3.2 1B", 1_236, 131_072, LLAMA, "Llama",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            notes = "Uncensored fine-tune of a Llama 3.2 base; inherits the Llama licence obligations.",
            safetyAligned = false,
        ),
        agentEntry(
            "nztinversive/llama3.2-1b-Uncensored", "Llama 3.2 1B Uncensored", 1_236, 131_072, LLAMA, "Llama",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            notes = "Uncensored community fine-tune. No verified artifact hash; treat the repo as unvetted.",
            safetyAligned = false,
        ),
        agentEntry(
            "LiquidAI/LFM2-1.2B", "LFM2 1.2B", 1_200, 32_768, LFM, "LFM",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            role = ModelRole.TEXT,
            notes = "Fast on CPU, strong prose. Superseded upstream by LFM2.5.",
        ),
        agentEntry(
            "google/gemma-3-1b-it", "Gemma 3 1B IT", 1_000, 32_768, GEMMA, "Gemma",
            gated = true,
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION),
            role = ModelRole.TEXT,
            notes = "Strong multilingual summarisation; strictest guardrails and weakest tool-call formatting here.",
        ),
        agentEntry(
            "Qwen/Qwen3-0.6B", "Qwen3 0.6B", 600, 32_768, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            notes = "Toggleable thinking mode; 32K context. Good fallback when 0.8B will not fit.",
        ),
        agentEntry(
            "google/functiongemma-270m-it", "FunctionGemma 270M IT", 270, 32_768, GEMMA, "Gemma",
            gated = true,
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION),
            notes = "Purpose-trained for function calling at 270M; not for open-ended reasoning.",
        ),
        agentEntry(
            "LiquidAI/LFM2-700M", "LFM2 700M", 700, 32_768, LFM, "LFM",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            role = ModelRole.TEXT,
        ),
        agentEntry(
            "HuggingFaceTB/SmolLM2-360M-Instruct", "SmolLM2 360M Instruct", 360, 8_192, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION),
            role = ModelRole.MICRO_ROUTER,
            notes = "Trained on function-calling data; hallucinates on complex schemas and is single-step only.",
        ),
        agentEntry(
            "LiquidAI/LFM2-350M", "LFM2 350M", 350, 32_768, LFM, "LFM",
            capabilities = setOf(ModelCapability.TEXT_GENERATION, ModelCapability.CLASSIFICATION),
            role = ModelRole.MICRO_ROUTER,
            notes = "Edge router and classifier; not for multi-turn reasoning.",
        ),
        agentEntry(
            "HuggingFaceTB/SmolLM2-135M-Instruct", "SmolLM2 135M Instruct", 135, 8_192, APACHE, "Apache",
            capabilities = setOf(ModelCapability.CLASSIFICATION),
            role = ModelRole.MICRO_ROUTER,
            notes = "Very fragile instruction following; only usable behind strict JSON grammar constraints.",
        ),
        agentEntry(
            "ElementMerc/tinyllama-1.1b-uncensored", "TinyLlama 1.1B Uncensored", 1_100, 2_048, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            role = ModelRole.TEXT,
            notes = "Legacy Llama-2 architecture; weaker than everything else at this size.",
            safetyAligned = false,
        ),

        // ---------------- Code ----------------
        agentEntry(
            "Maincode/Maincoder-1B", "Maincoder 1B", 1_000, 2_048, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            role = ModelRole.CODE,
            notes = "Fill-in-the-middle completion only; 2K context cannot hold a multi-file task.",
        ),
        agentEntry(
            "fdtn-ai/antares-1b", "Antares 1B", 1_000, 131_072, APACHE, "Apache",
            gated = true,
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            role = ModelRole.CODE,
            notes = "Defensive-security agent loop. SAMAVETA does not ship a vulnerability scanner, so this is " +
                "listed for completeness rather than selected.",
        ),

        // ---------------- OCR ----------------
        agentEntry(
            "lightonai/LightOnOCR-2-1B", "LightOnOCR 2 1B", 1_000, null, APACHE, "Apache",
            capabilities = setOf(ModelCapability.OCR, ModelCapability.VISION_LANGUAGE),
            modalitiesIn = setOf(Modality.IMAGE),
            role = ModelRole.OCR,
            notes = "Full-page OCR to Markdown with bounding boxes; Apache-2.0 makes it the most attractive " +
                "document path here. Page-sized input, so context is not a token-window question.",
        ),
        agentEntry(
            "tencent/HunyuanOCR", "HunyuanOCR 1.5", 1_000, 131_072, HUNYUAN, "Tencent Hunyuan",
            capabilities = setOf(ModelCapability.OCR, ModelCapability.VISION_LANGUAGE),
            modalitiesIn = setOf(Modality.IMAGE),
            role = ModelRole.OCR,
            notes = "Custom community licence; 4K images means a large KV cache. Licence review required.",
        ),
        agentEntry(
            "openbmb/MiniCPM-OCR-1.0", "MiniCPM-OCR 1.0", 1_300, 40_960, APACHE, "Apache",
            capabilities = setOf(ModelCapability.OCR, ModelCapability.VISION_LANGUAGE),
            modalitiesIn = setOf(Modality.IMAGE),
            role = ModelRole.OCR,
            notes = "Best ~1B layout parsing for tables and formulae, but best results need a companion layout model.",
        ),

        // ---------------- Vision ----------------
        agentEntry(
            "HuggingFaceTB/SmolVLM2-500M-Video-Instruct", "SmolVLM2 500M Video", 500, 8_192, APACHE, "Apache",
            capabilities = setOf(ModelCapability.VISION_LANGUAGE),
            modalitiesIn = setOf(Modality.IMAGE, Modality.VIDEO),
            role = ModelRole.VISION,
            notes = "Smallest vision path here; weak on dense small text, so not the screenshot reader.",
        ),
        agentEntry(
            "vikhyatk/moondream2", "Moondream2", 1_860, 2_048, APACHE, "Apache",
            capabilities = setOf(ModelCapability.VISION_LANGUAGE, ModelCapability.OBJECT_DETECTION),
            modalitiesIn = setOf(Modality.IMAGE),
            role = ModelRole.VISION,
            notes = "Native pointing and bounding boxes; short 2K context. Relevant to UI grounding.",
        ),

        // ---------------- Voice ----------------
        agentEntry(
            "LiquidAI/LFM2.5-Audio-1.5B", "LFM2.5 Audio 1.5B", 1_500, 32_768, LFM, "LFM",
            capabilities = setOf(ModelCapability.ASR, ModelCapability.TTS, ModelCapability.TEXT_GENERATION),
            modalitiesIn = setOf(Modality.AUDIO),
            role = ModelRole.VOICE_AGENT,
            notes = "Single-model speech in/out; removes the STT→LLM→TTS cascade. Above 1B and a custom licence.",
        ),
        agentEntry(
            "Qwen/Qwen3-ASR-0.6B", "Qwen3 ASR 0.6B", 600, null, APACHE, "Apache",
            capabilities = setOf(ModelCapability.ASR, ModelCapability.LANGUAGE_ID),
            modalitiesIn = setOf(Modality.AUDIO),
            role = ModelRole.ASR,
            notes = "52 languages/dialects with streaming. Preferred ASR candidate: Apache-2.0 and small.",
        ),
        agentEntry(
            "nvidia/parakeet-tdt-0.6b-v3", "Parakeet TDT 0.6B v3", 600, null, CC_BY_4, "Creative Commons",
            capabilities = setOf(ModelCapability.ASR),
            modalitiesIn = setOf(Modality.AUDIO),
            role = ModelRole.ASR,
            notes = "Very fast European multilingual ASR with timestamps; CC-BY-4.0 needs attribution, which " +
                "SAMAVETA can satisfy. Weaker Indic coverage than Qwen3-ASR, which matters for this product.",
        ),
        agentEntry(
            "hexgrad/Kokoro-82M", "Kokoro 82M", 82, null, APACHE, "Apache",
            capabilities = setOf(ModelCapability.TTS),
            modalitiesIn = setOf(Modality.TEXT),
            role = ModelRole.TTS,
            notes = "82M TTS, >10x real-time on CPU. No voice cloning. Best TTS candidate by size and licence.",
        ),
        agentEntry(
            "SparkAudio/Spark-TTS-0.5B", "Spark TTS 0.5B", 500, null, CC_BY_NC_SA, "Creative Commons",
            nonCommercial = true,
            capabilities = setOf(ModelCapability.TTS),
            modalitiesIn = setOf(Modality.TEXT),
            role = ModelRole.TTS,
            notes = "Non-commercial (CC-BY-NC-SA-4.0). Cannot ship in a distributed release; a personal build only.",
        ),
        agentEntry(
            "OuteAI/Llama-OuteTTS-1.0-1B", "OuteTTS 1.0 1B", 1_000, 8_192, CC_BY_NC_SA, "Creative Commons",
            nonCommercial = true,
            capabilities = setOf(ModelCapability.TTS),
            modalitiesIn = setOf(Modality.TEXT),
            role = ModelRole.TTS,
            notes = "Non-commercial and slow on CPU. Voice cloning via GGUF; licensing blocks distribution.",
        ),
    )

    fun byRole(role: ModelRole): List<ModelCatalogEntry> = entries.filter { it.role == role }

    fun byCapability(capability: ModelCapability): List<ModelCatalogEntry> =
        entries.filter { capability in it.capabilities }

    fun find(modelId: String): ModelCatalogEntry? =
        entries.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }

    /** Entries safe to redistribute in a commercial build, per the recorded licence flags. */
    fun releaseSafe(): List<ModelCatalogEntry> = entries.filter { !it.licenseNonCommercial }

    /** Entries that would need a licence review before a distributed release includes them. */
    fun licenceBlocked(): List<ModelCatalogEntry> = entries.filter { it.licenseNonCommercial }

    /** True when nothing in the catalog can actually be installed yet. */
    val nothingDownloadable: Boolean get() = entries.none { it.isDownloadable }

    /**
     * Picks the best candidate for a capability on this device, using only recorded facts and
     * estimated RAM.
     *
     * Returns null rather than a guess when nothing fits. A caller that receives null must say the
     * feature is unavailable; it must not fall back to a model that does not fit.
     */
    fun bestFor(capability: ModelCapability, availableRamMb: Int): ModelCatalogEntry? =
        byCapability(capability)
            .filter { it.estimatedPeakRamMb.value <= availableRamMb }
            .filter { !it.licenseNonCommercial }
            // The order is a product decision, written down rather than left to list order:
            //   1. aligned before abliterated — safety-first means the default is the aligned model;
            //   2. ungated before gated — gated weights cannot be fetched unattended, so preferring
            //      them would turn a one-tap install into a half-finished one;
            //   3. cheapest first — a model that fits with room left over survives thermal pressure;
            //   4. Apache-2.0 before bespoke licences, at equal cost — fewest obligations for a
            //      distributed build;
            //   5. identifier, so the same catalog and the same device always give the same answer.
            .sortedWith(
                compareByDescending<ModelCatalogEntry> { it.safetyAligned }
                    .thenBy { it.gated }
                    .thenBy { it.estimatedPeakRamMb.value }
                    .thenBy { if (it.licenseFamily == "Apache") 0 else 1 }
                    .thenBy { it.modelId.lowercase() },
            )
            .firstOrNull()

    /**
     * Converts an entry into a registry descriptor.
     *
     * Refuses when the entry is not downloadable: constructing a `ModelDescriptor` requires a real
     * SHA-256 and download URL, and inventing either would defeat the integrity gate that exists to
     * stop a corrupted or substituted artifact from being loaded.
     */
    fun toDescriptor(entry: ModelCatalogEntry, runtimeId: String = "unassigned"): ModelDescriptor {
        val sha = entry.artifactSha256
            ?: throw IllegalStateException(
                "'${entry.modelId}' has no recorded artifact SHA-256; it cannot become a registry entry yet.",
            )
        val estimatedSize = entry.estimatedQuantizedSizeMb?.value
            ?: throw IllegalStateException("'${entry.modelId}' has no size estimate to reserve storage against.")
        return ModelDescriptor(
            id = entry.registryId,
            displayName = entry.displayName,
            version = "1.0.0",
            parameterCountMillions = entry.parameterCountMillions,
            architecture = entry.licenseFamily,
            inputModalities = entry.modalitiesIn,
            outputModalities = entry.modalitiesOut,
            capabilities = entry.capabilities,
            format = entry.preferredFormats.first(),
            quantization = Quantization.INT4,
            storageRequirementMb = estimatedSize,
            runtimeRamRequirementMb = entry.estimatedPeakRamMb.value,
            peakRamRequirementMb = entry.estimatedPeakRamMb.value,
            runtimeId = runtimeId,
            requiredAccelerators = emptySet<Accelerator>(),
            optionalAccelerators = emptySet<Accelerator>(),
            supportedLanguages = emptySet(),
            license = LicenseInfo(
                identifier = entry.licenseIdentifier,
                commercialUsePermitted = !entry.licenseNonCommercial,
                redistributionPermitted = !entry.licenseNonCommercial,
                modificationPermitted = true,
                attributionRequired = entry.licenseFamily != "Apache",
                attributionText = if (entry.licenseFamily != "Apache") {
                    "${entry.displayName} (${entry.licenseIdentifier}), see ${entry.upstreamUrl}"
                } else {
                    null
                },
                sourceUrl = entry.upstreamUrl,
            ),
            downloadUrl = entry.upstreamUrl,
            sha256 = sha,
            contextWindowTokens = entry.contextWindowTokens?.value,
            benchmark = null,
            distributionSizeBytes = estimatedSize * 1024L * 1024L,
        )
    }
}
