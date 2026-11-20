package com.samaveta.android.core.model

import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.AcceleratorAvailability
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.device.MemoryTier

/** Installed state of a model on this device. */
sealed interface InstallationState {
    data object NotInstalled : InstallationState
    data class Installed(val installedAtEpochMs: Long, val verified: Boolean) : InstallationState
    data class Downloading(val bytesDownloaded: Long, val bytesTotal: Long) : InstallationState
    data class Corrupt(val reason: String) : InstallationState
    data class Failed(val reason: String) : InstallationState
}

data class RegistryEntry(
    val descriptor: ModelDescriptor,
    val installation: InstallationState,
) {
    val isUsable: Boolean
        get() = (installation as? InstallationState.Installed)?.verified == true
}

/**
 * The SAMAVETA Model Registry.
 *
 * Holds the catalog of known models and what is installed locally. It answers two questions that
 * the rest of the product (and LAYA, over its contract) depends on:
 *   1. which models exist and what they cost, and
 *   2. whether a given model can actually run *right now* on *this* device.
 */
class ModelRegistry(entries: List<RegistryEntry> = emptyList()) {

    private val byId = LinkedHashMap<String, RegistryEntry>()

    init {
        entries.forEach { add(it) }
    }

    fun add(entry: RegistryEntry) {
        require(!byId.containsKey(entry.descriptor.id)) {
            "duplicate model id in registry: ${entry.descriptor.id}"
        }
        byId[entry.descriptor.id] = entry
    }

    fun updateInstallation(modelId: String, state: InstallationState) {
        val existing = byId[modelId] ?: throw NoSuchElementException("unknown model: $modelId")
        byId[modelId] = existing.copy(installation = state)
    }

    fun get(modelId: String): RegistryEntry? = byId[modelId]

    fun all(): List<RegistryEntry> = byId.values.toList()

    fun installed(): List<RegistryEntry> = byId.values.filter { it.isUsable }

    fun withCapability(capability: ModelCapability): List<RegistryEntry> =
        byId.values.filter { capability in it.descriptor.capabilities }

    /** Models that pass the commercial release gate; used by the licensing audit. */
    fun releaseSafe(): List<RegistryEntry> = byId.values.filter { it.descriptor.isReleaseSafe }

    fun totalInstalledStorageMb(): Long =
        installed().sumOf { it.descriptor.storageRequirementMb }

    /**
     * Evaluates a concrete model against a device snapshot. This is the deterministic gate that
     * turns "LAYA proposed model X" into "model X may / may not execute here".
     */
    fun evaluate(
        modelId: String,
        device: DeviceProfile,
        capabilities: DeviceCapabilities,
        availableRamMb: Int = device.availableRamMb,
        availableStorageMb: Long = device.availableStorageMb,
    ): AdmissionDecision {
        val entry = byId[modelId]
            ?: return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.NOT_REGISTERED,
                "Model '$modelId' is not in the SAMAVETA registry.",
            )
        val d = entry.descriptor

        when (val install = entry.installation) {
            is InstallationState.NotInstalled -> return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.NOT_INSTALLED,
                "'${d.displayName}' is not installed. Installation requires ${d.storageRequirementMb} MB.",
            )

            is InstallationState.Corrupt -> return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.INTEGRITY_FAILURE,
                "'${d.displayName}' failed integrity verification: ${install.reason}",
            )

            is InstallationState.Failed -> return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.INSTALLATION_FAILED,
                "'${d.displayName}' is in a failed state: ${install.reason}",
            )

            is InstallationState.Downloading -> return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.DOWNLOAD_INCOMPLETE,
                "'${d.displayName}' is still downloading (${install.bytesDownloaded}/${install.bytesTotal} bytes).",
            )

            is InstallationState.Installed -> if (!install.verified) {
                return AdmissionDecision.Rejected(
                    modelId,
                    AdmissionRejection.INTEGRITY_FAILURE,
                    "'${d.displayName}' is installed but its SHA-256 was never verified.",
                )
            }
        }

        if (d.requiredAccelerators.isNotEmpty() && !capabilities.canSatisfy(d.requiredAccelerators)) {
            val missing = d.requiredAccelerators.filter {
                capabilities.availabilityOf(it) != AcceleratorAvailability.AVAILABLE
            }
            return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.ACCELERATOR_UNAVAILABLE,
                "'${d.displayName}' requires ${missing.joinToString()} acceleration, which is not verified on this device.",
            )
        }

        if (d.requiredAccelerators.any { it == Accelerator.NPU } &&
            !device.supportedAbis.contains("arm64-v8a")
        ) {
            return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.INCOMPATIBLE_ABI,
                "'${d.displayName}' targets an NPU backend that requires 64-bit ARM.",
            )
        }

        if (availableRamMb < d.peakRamRequirementMb) {
            return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.INSUFFICIENT_RAM,
                "Needs ${d.peakRamRequirementMb} MB RAM; $availableRamMb MB available.",
            )
        }

        if (availableStorageMb < d.storageRequirementMb) {
            return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.INSUFFICIENT_STORAGE,
                "Needs ${d.storageRequirementMb} MB storage; $availableStorageMb MB available.",
            )
        }

        // A low-RAM device must not attempt a large model even if the numbers momentarily line up:
        // LMKD kills the app as memory fragments, and the user sees a crash, not an error.
        if (device.isLowRamDevice && device.memoryTier() == MemoryTier.CONSTRAINED &&
            d.parameterCountMillions != null && d.parameterCountMillions > 1_500
        ) {
            return AdmissionDecision.Rejected(
                modelId,
                AdmissionRejection.DEVICE_CLASS_LIMIT,
                "'${d.displayName}' exceeds this device's class; ADB memory-class is too low.",
            )
        }

        val accelerators = buildSet {
            add(Accelerator.CPU)
            d.optionalAccelerators.forEach { if (capabilities.availabilityOf(it) == AcceleratorAvailability.AVAILABLE) add(it) }
            d.requiredAccelerators.forEach { add(it) }
        }
        return AdmissionDecision.Admitted(modelId, accelerators, d)
    }

    /**
     * Chooses the best admissible model for a capability set, preferring quality then headroom.
     * This is a *fallback candidate list* SAMAVETA offers to LAYA — it is not a decision engine.
     */
    fun candidatesFor(
        required: Set<ModelCapability>,
        device: DeviceProfile,
        capabilities: DeviceCapabilities,
    ): List<AdmissionDecision.Admitted> =
        installed()
            .filter { entry -> required.all { it in entry.descriptor.capabilities } }
            .mapNotNull { entry ->
                evaluate(entry.descriptor.id, device, capabilities) as? AdmissionDecision.Admitted
            }
            .sortedWith(
                compareByDescending<AdmissionDecision.Admitted> {
                    it.model.benchmark?.accuracyValue ?: 0.0
                }.thenBy { it.model.peakRamRequirementMb },
            )
}

sealed interface AdmissionDecision {
    val modelId: String

    data class Admitted(
        override val modelId: String,
        val accelerators: Set<Accelerator>,
        val model: ModelDescriptor,
    ) : AdmissionDecision

    data class Rejected(
        override val modelId: String,
        val reason: AdmissionRejection,
        val explanation: String,
    ) : AdmissionDecision
}

enum class AdmissionRejection(val retryableWithoutUserAction: Boolean) {
    NOT_REGISTERED(false),
    NOT_INSTALLED(false),
    DOWNLOAD_INCOMPLETE(true),
    INSTALLATION_FAILED(true),
    INTEGRITY_FAILURE(true),
    ACCELERATOR_UNAVAILABLE(false),
    INCOMPATIBLE_ABI(false),
    INSUFFICIENT_RAM(true),
    INSUFFICIENT_STORAGE(false),
    DEVICE_CLASS_LIMIT(false),
    UNSUPPORTED_MODALITY(false),
    CONTEXT_EXCEEDED(false),
}
