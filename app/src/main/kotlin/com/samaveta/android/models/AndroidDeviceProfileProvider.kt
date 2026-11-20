package com.samaveta.android.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.samaveta.android.core.device.AcceleratorAvailability
import com.samaveta.android.core.device.DeviceProfile
import java.io.File

/**
 * Reads the *actual* hardware state, once per snapshot. Values are never hard-coded and never
 * assumed from the device name: an OEM can ship an 8 GB device with a 6 GB memory class, and the
 * product must behave according to what Android reports.
 */
class AndroidDeviceProfileProvider(private val context: Context) {

    fun snapshot(): DeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

        val totalRamMb = (memoryInfo.totalMem / MEGABYTE).toInt()
        val availableRamMb = (memoryInfo.availMem / MEGABYTE).toInt()

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            // minSdk is 28, so Build.VERSION.SDK_INT is always usable.
            androidApiLevel = Build.VERSION.SDK_INT,
            socModel = readSocModel(),
            supportedAbis = Build.SUPPORTED_ABIS?.toSet() ?: emptySet(),
            totalRamMb = totalRamMb.coerceAtLeast(1),
            availableRamMb = availableRamMb.coerceAtLeast(0),
            availableStorageMb = availableStorageMb(),
            isLowRamDevice = activityManager.isLowRamDevice,
            coreCount = Runtime.getRuntime().availableProcessors(),
        )
    }

    /**
     * Best-effort SoC identification. Android has no public API for this; the documented,
     * non-privileged fallbacks are Build.SOC_MODEL (API 31+) and the hardware/board fields.
     * Returns a string that explicitly says when the value is a fallback.
     */
    private fun readSocModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socModel = Build.SOC_MODEL
            if (!socModel.isNullOrBlank() && socModel != Build.UNKNOWN) return socModel
        }
        val hardware = Build.HARDWARE.orEmpty()
        return if (hardware.isNotBlank()) "$hardware (hardware field)" else "unknown"
    }

    /**
     * Free space on the volume that holds downloaded models.
     *
     * Models are stored in the app's own files directory (not external storage), so the space that
     * matters is the space available to `filesDir`.
     */
    private fun availableStorageMb(): Long = statFreeMb(context.filesDir)

    private fun statFreeMb(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        (stat.availableBytes / MEGABYTE)
    } catch (_: Exception) {
        0L
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
    }
}

/**
 * Probes runtime backends that actually ship in this build.
 *
 * The honest answer today is that no GPU/NPU delegate library is bundled, so the probe returns
 * UNKNOWN with evidence rather than guessing. When a delegate is added, this class reports it and
 * the Model Registry immediately starts allowing accelerates models.
 */
class AndroidRuntimeProbe(private val context: Context) {

    data class Availability(val availability: AcceleratorAvailability, val evidence: String)

    fun gpuAvailability(): Availability {
        // A GPU delegate is usable only when the native library is actually packaged in the APK.
        val hasDelegate = try {
            System.loadLibrary("tensorflowlite_gpu_delegate")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        return if (hasDelegate) {
            Availability(AcceleratorAvailability.AVAILABLE, "GPU delegate library loaded from the APK")
        } else {
            Availability(
                AcceleratorAvailability.UNKNOWN,
                "No GPU delegate is bundled in this build; GPU execution is not claimed.",
            )
        }
    }

    fun backendVersion(): String =
        "android-api-${Build.VERSION.SDK_INT}"
}
