package com.samaveta.android.core.device

/**
 * A point-in-time snapshot of the physical device.
 *
 * SAMAVETA must behave differently on an 8 GB reference phone and on a 4 GB entry-level device, so
 * every model decision consumes this snapshot rather than assuming a fixed hardware baseline.
 * Collected by the Android layer (`DeviceProfileProvider`) and passed down as plain data, which
 * keeps every rule in this module unit-testable on a plain JVM.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidApiLevel: Int,
    val socModel: String,
    val supportedAbis: Set<String>,
    val totalRamMb: Int,
    val availableRamMb: Int,
    val availableStorageMb: Long,
    val isLowRamDevice: Boolean,
    val coreCount: Int,
) {
    init {
        require(totalRamMb > 0) { "totalRamMb must be positive" }
        require(androidApiLevel >= 21) { "unsupported android api level: $androidApiLevel" }
    }

    /** Coarse memory class used to gate model selection. Deliberately conservative. */
    fun memoryTier(): MemoryTier = MemoryTier.forTotalRam(totalRamMb)

    /** True when the device can plausibly run a multi-billion-parameter quantized LLM. */
    fun canRunLargeLocalModel(): Boolean =
        !isLowRamDevice && totalRamMb >= MemoryTier.COMFORTABLE.minTotalRamMb
}

enum class MemoryTier(val minTotalRamMb: Int) {
    /** Below the 8 GB product baseline: single small models only, aggressive unloading. */
    CONSTRAINED(0),

    /** 4–6 GB. Small LLMs or specialist models, one at a time. */
    LIMITED(4_096),

    /** 6–8 GB. The documented minimum reference hardware. */
    STANDARD(6_144),

    /** 8 GB or more: the product's minimum target hardware. */
    COMFORTABLE(8_192),

    /** 12 GB or more: can hold a model plus a specialist pipeline concurrently. */
    GENEROUS(12_288),
    ;

    companion object {
        fun forTotalRam(totalRamMb: Int): MemoryTier =
            entries.lastOrNull { totalRamMb >= it.minTotalRamMb } ?: CONSTRAINED
    }
}

/**
 * Hardware accelerators that a model runtime may target.
 *
 * NPU support is never assumed: on Android it depends on the SoC, the driver, the runtime build and
 * the OEM. [AcceleratorAvailability.UNKNOWN] is a first-class state so the product can say "not
 * verified on this device" instead of claiming acceleration it has no evidence for.
 */
enum class Accelerator { CPU, GPU, NPU }

enum class AcceleratorAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class AcceleratorSupport(
    val accelerator: Accelerator,
    val availability: AcceleratorAvailability,
    /** Why we believe this — e.g. "NNAPI driver probe failed", "no device evidence yet". */
    val evidence: String,
) {
    init {
        require(evidence.isNotBlank()) { "accelerator support must carry evidence" }
    }
}

data class DeviceCapabilities(
    val support: List<AcceleratorSupport>,
    val backendVersion: String,
) {
    init {
        require(support.isNotEmpty()) { "at least CPU support must be reported" }
    }

    fun availabilityOf(accelerator: Accelerator): AcceleratorAvailability =
        support.firstOrNull { it.accelerator == accelerator }?.availability
            ?: AcceleratorAvailability.UNKNOWN

    /**
     * Only an explicitly AVAILABLE accelerator satisfies a hard requirement. UNKNOWN never counts:
     * a model that needs the NPU must not be scheduled on the hope that one exists.
     */
    fun canSatisfy(required: Set<Accelerator>): Boolean =
        required.isEmpty() || required.any { availabilityOf(it) == AcceleratorAvailability.AVAILABLE }

    fun availableAccelerators(): Set<Accelerator> =
        support.filter { it.availability == AcceleratorAvailability.AVAILABLE }
            .map { it.accelerator }
            .toSet()
}
