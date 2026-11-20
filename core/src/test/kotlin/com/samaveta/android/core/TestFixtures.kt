package com.samaveta.android.core

import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.AcceleratorAvailability
import com.samaveta.android.core.device.AcceleratorSupport
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.model.InstallationState
import com.samaveta.android.core.model.LicenseInfo
import com.samaveta.android.core.model.ModelBenchmarkResult
import com.samaveta.android.core.model.ModelCapability
import com.samaveta.android.core.model.ModelDescriptor
import com.samaveta.android.core.model.ModelFormat
import com.samaveta.android.core.model.Modality
import com.samaveta.android.core.model.Quantization
import com.samaveta.android.core.model.RegistryEntry

/**
 * Shared, deliberately realistic test fixtures.
 *
 * The device numbers mirror the product's documented reference hardware (8 GB / Snapdragon 6-series
 * class) and a low-memory device, so policies are exercised against the hardware they must support.
 * Model entries are *test doubles*, not claims about real models.
 */
object TestFixtures {

    fun referenceDevice() = DeviceProfile(
        manufacturer = "TestOEM",
        model = "Reference 8GB",
        androidApiLevel = 34,
        socModel = "sm-6series",
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a"),
        totalRamMb = 8_192,
        availableRamMb = 3_200,
        availableStorageMb = 90_000,
        isLowRamDevice = false,
        coreCount = 8,
    )

    fun lowMemoryDevice() = DeviceProfile(
        manufacturer = "TestOEM",
        model = "Entry 3GB",
        androidApiLevel = 31,
        socModel = "entry",
        supportedAbis = setOf("arm64-v8a"),
        totalRamMb = 3_072,
        availableRamMb = 900,
        availableStorageMb = 12_000,
        isLowRamDevice = true,
        coreCount = 4,
    )

    fun capabilitiesCpuOnly() = DeviceCapabilities(
        support = listOf(
            AcceleratorSupport(Accelerator.CPU, AcceleratorAvailability.AVAILABLE, "always available"),
            AcceleratorSupport(Accelerator.GPU, AcceleratorAvailability.UNKNOWN, "not probed on this device"),
            AcceleratorSupport(Accelerator.NPU, AcceleratorAvailability.UNKNOWN, "no device evidence"),
        ),
        backendVersion = "test",
    )

    fun capabilitiesWithGpu() = DeviceCapabilities(
        support = listOf(
            AcceleratorSupport(Accelerator.CPU, AcceleratorAvailability.AVAILABLE, "always available"),
            AcceleratorSupport(Accelerator.GPU, AcceleratorAvailability.AVAILABLE, "probe succeeded"),
            AcceleratorSupport(Accelerator.NPU, AcceleratorAvailability.UNAVAILABLE, "NNAPI driver rejected"),
        ),
        backendVersion = "test",
    )

    val apacheLicense = LicenseInfo(
        identifier = "Apache-2.0",
        commercialUsePermitted = true,
        redistributionPermitted = true,
        modificationPermitted = true,
        attributionRequired = true,
        attributionText = "Apache License 2.0",
        sourceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    )

    val nonCommercialLicense = LicenseInfo(
        identifier = "CC-BY-NC-4.0",
        commercialUsePermitted = false,
        redistributionPermitted = true,
        modificationPermitted = true,
        attributionRequired = true,
        attributionText = "CC BY-NC 4.0",
        sourceUrl = "https://creativecommons.org/licenses/by-nc/4.0/",
    )

    fun smallTextModel(
        id: String = "test-llama-1b",
        peakRamMb: Int = 1_600,
        license: LicenseInfo = apacheLicense,
        benchmark: ModelBenchmarkResult? = null,
    ) = ModelDescriptor(
        id = id,
        displayName = "Test 1B Instruct",
        version = "1.0.0",
        parameterCountMillions = 1_240,
        architecture = "decoder-only transformer",
        inputModalities = setOf(Modality.TEXT),
        outputModalities = setOf(Modality.TEXT),
        capabilities = setOf(ModelCapability.TEXT_GENERATION),
        format = ModelFormat.LITERT_LM,
        quantization = Quantization.INT4,
        storageRequirementMb = 800,
        runtimeRamRequirementMb = 1_200,
        peakRamRequirementMb = peakRamMb,
        runtimeId = "litert-lm",
        requiredAccelerators = emptySet(),
        optionalAccelerators = setOf(Accelerator.GPU),
        supportedLanguages = setOf("en", "hi"),
        license = license,
        downloadUrl = "https://models.example.com/test-llama-1b.task",
        sha256 = "a".repeat(64),
        contextWindowTokens = 4_096,
        benchmark = benchmark,
        distributionSizeBytes = 838_860_800,
    )

    fun ocrModel(id: String = "test-ocr") = smallTextModel(id = id, peakRamMb = 300).copy(
        displayName = "Test OCR",
        capabilities = setOf(ModelCapability.OCR),
        inputModalities = setOf(Modality.IMAGE),
        outputModalities = setOf(Modality.TEXT),
        parameterCountMillions = 12,
        storageRequirementMb = 40,
        runtimeRamRequirementMb = 120,
        peakRamRequirementMb = 260,
        contextWindowTokens = null,
    )

    fun npuOnlyModel(id: String = "test-npu-vlm") = smallTextModel(id = id, peakRamMb = 2_400).copy(
        capabilities = setOf(ModelCapability.VISION_LANGUAGE),
        inputModalities = setOf(Modality.IMAGE, Modality.TEXT),
        requiredAccelerators = setOf(Accelerator.NPU),
    )

    fun installedEntry(model: ModelDescriptor) =
        RegistryEntry(model, InstallationState.Installed(installedAtEpochMs = 1_700_000_000_000L, verified = true))

    fun unverifiedEntry(model: ModelDescriptor) =
        RegistryEntry(model, InstallationState.Installed(installedAtEpochMs = 1_700_000_000_000L, verified = false))

    fun notInstalledEntry(model: ModelDescriptor) = RegistryEntry(model, InstallationState.NotInstalled)

    fun benchmark(accuracy: Double = 0.72, device: String = "Reference 8GB") = ModelBenchmarkResult(
        deviceModel = device,
        androidApiLevel = 34,
        measuredAtEpochMs = 1_700_000_000_000L,
        coldLoadMs = 1_800,
        firstTokenLatencyMs = 420,
        tokensPerSecond = 12.4,
        peakRamMb = 1_700,
        thermalDeltaCelsius = 3.1,
        dataset = "test-suite-v1",
        accuracyMetric = "exact-match",
        accuracyValue = accuracy,
        acceleratorUsed = Accelerator.CPU,
    )
}
