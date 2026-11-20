package com.samaveta.android.core.model

/**
 * The runtime-facing projection of a [ModelDescriptor].
 *
 * Separating this from the registry descriptor keeps the registry free to carry governance metadata
 * (licence, source URL, download location) that a runtime has no business seeing, and gives the
 * runtime manager a small, stable shape to depend on.
 */
data class ModelModule(
    val id: String,
    val displayName: String,
    val version: String,
    val capabilities: Set<ModelCapability>,
    val peakRamRequirementMb: Int,
    val storageRequirementMb: Long,
    val format: ModelFormat,
    val quantization: Quantization,
    val contextWindowTokens: Int?,
    val benchmark: ModelBenchmarkResult?,
) {
    init {
        require(id.isNotBlank()) { "module id required" }
        require(peakRamRequirementMb > 0) { "peak RAM requirement must be positive" }
        require(capabilities.isNotEmpty()) { "module must declare a capability" }
    }

    /** Rough token budget for a prompt, leaving room for the response. */
    fun promptTokenBudget(reserveForOutput: Int = 512): Int =
        contextWindowTokens?.let { (it - reserveForOutput).coerceAtLeast(0) } ?: 0
}
