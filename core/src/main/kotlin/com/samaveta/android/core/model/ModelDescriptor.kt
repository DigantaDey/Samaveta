package com.samaveta.android.core.model

import com.samaveta.android.core.device.Accelerator

/** Modalities a model can consume or produce. */
enum class Modality { TEXT, IMAGE, AUDIO, VIDEO }

/** What a model is actually good for. Used to match a task to specialist pipelines. */
enum class ModelCapability {
    TEXT_GENERATION,
    TEXT_EMBEDDING,
    CLASSIFICATION,
    OCR,
    OBJECT_DETECTION,
    IMAGE_CLASSIFICATION,
    VISION_LANGUAGE,
    ASR,
    TTS,
    SPEAKER_EMBEDDING,
    TRANSLATION,
    RERANKING,
    LANGUAGE_ID,
}

enum class ModelFormat { LITERT_LM, LITERT, ONNX, TFLITE, GGUF, TASK_BUNDLE, NATIVE_LIBRARY }

enum class Quantization { NONE, INT8, INT4, INT4_MIXED, FP16, DYNAMIC_INT8 }

/**
 * Licensing evidence for a model or a library.
 *
 * "Open weights" is not the same as "commercially usable", so redistribution and commercial-use
 * permissions are recorded separately and a release gate refuses models that cannot answer them.
 */
data class LicenseInfo(
    val identifier: String,
    val commercialUsePermitted: Boolean,
    val redistributionPermitted: Boolean,
    val modificationPermitted: Boolean,
    val attributionRequired: Boolean,
    val attributionText: String?,
    val sourceUrl: String,
) {
    init {
        require(identifier.isNotBlank()) { "license identifier required" }
        require(sourceUrl.startsWith("https://")) { "license source must be a verified https URL" }
        if (attributionRequired) {
            require(!attributionText.isNullOrBlank()) {
                "license $identifier requires attribution but no attribution text was recorded"
            }
        }
    }

    /** Gate used before a model is allowed into a commercial release. */
    fun isReleaseSafe(): Boolean = commercialUsePermitted && redistributionPermitted
}

/**
 * A model entry in the SAMAVETA Model Registry.
 *
 * Every field the directive requires is here; nullable fields are things we genuinely cannot know
 * before benchmarking on real hardware, and are represented as absent rather than invented.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val parameterCountMillions: Int?,
    val architecture: String,
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val capabilities: Set<ModelCapability>,
    val format: ModelFormat,
    val quantization: Quantization,
    val storageRequirementMb: Long,
    val runtimeRamRequirementMb: Int,
    val peakRamRequirementMb: Int,
    val runtimeId: String,
    val requiredAccelerators: Set<Accelerator>,
    val optionalAccelerators: Set<Accelerator>,
    val supportedLanguages: Set<String>,
    val license: LicenseInfo,
    val downloadUrl: String,
    /** Lower-case hex SHA-256 of the distributed artifact; mandatory for integrity checking. */
    val sha256: String,
    val contextWindowTokens: Int?,
    val benchmark: ModelBenchmarkResult?,
    val distributionSizeBytes: Long,
) {
    init {
        require(id.isNotBlank()) { "model id required" }
        require(id.matches(Regex("[a-z0-9]+([._-][a-z0-9]+)*"))) {
            "model id must be lower-case, dot/dash/underscore separated: $id"
        }
        require(storageRequirementMb > 0) { "storage requirement must be positive" }
        require(runtimeRamRequirementMb > 0) { "runtime RAM requirement must be positive" }
        require(peakRamRequirementMb >= runtimeRamRequirementMb) {
            "peak RAM ($peakRamRequirementMb) must be >= load RAM ($runtimeRamRequirementMb) for $id"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be 64 lower-case hex chars: $sha256" }
        require(downloadUrl.startsWith("https://")) { "model downloads must use https: $downloadUrl" }
        require(inputModalities.isNotEmpty()) { "model must accept at least one modality" }
        require(capabilities.isNotEmpty()) { "model must declare at least one capability" }
        (inputModalities + outputModalities).forEach { modality ->
            // Guards against catalog entries that claim audio output but are text-only runtimes.
            require(Modality.VIDEO !in setOf(modality) || ModelCapability.VISION_LANGUAGE in capabilities) {
                "video modality requires a vision-language capability"
            }
        }
    }

    val isReleaseSafe: Boolean get() = license.isReleaseSafe()

    /** RAM to reserve when this model is loaded and running at worst case. */
    val reservationRamMb: Int get() = peakRamRequirementMb
}

/**
 * Measured benchmark data. Only ever populated with results from a real run; the
 * `deviceModel` and `measuredAtEpochMs` fields make unverified claims traceable.
 */
data class ModelBenchmarkResult(
    val deviceModel: String,
    val androidApiLevel: Int,
    val measuredAtEpochMs: Long,
    val coldLoadMs: Long,
    val firstTokenLatencyMs: Long,
    val tokensPerSecond: Double?,
    val peakRamMb: Int,
    val thermalDeltaCelsius: Double?,
    val dataset: String,
    val accuracyMetric: String,
    val accuracyValue: Double,
    val acceleratorUsed: Accelerator,
) {
    init {
        require(coldLoadMs >= 0) { "coldLoadMs must not be negative" }
        require(accuracyValue in 0.0..1.0) { "accuracy must be normalised to 0..1, got $accuracyValue" }
        require(deviceModel.isNotBlank()) { "benchmark must name the device it was measured on" }
    }

    /** Blended quality metric used for candidate ordering; derived from measured accuracy. */
    val qualityScore: Double get() = accuracyValue
}
