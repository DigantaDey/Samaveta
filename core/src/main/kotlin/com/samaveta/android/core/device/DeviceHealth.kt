package com.samaveta.android.core.device

/** Thermal status, mapped 1:1 from Android's `PowerManager` thermal API by the Android layer. */
enum class ThermalLevel {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    SHUTDOWN,
    ;

    /** Severity order, safest to hottest. */
    val severity: Int get() = ordinal
}

data class ThermalState(val level: ThermalLevel) {
    companion object {
        val NOMINAL = ThermalState(ThermalLevel.NOMINAL)

        /** Used when the platform cannot report thermal status (API < 29, or OEM gaps). */
        val UNKNOWN = NOMINAL
    }
}

enum class MemoryPressure { NORMAL, MODERATE, HIGH, CRITICAL }

data class BatteryState(
    val percent: Int,
    val isCharging: Boolean,
    val powerSaveMode: Boolean,
) {
    init {
        require(percent in 0..100) { "battery percent out of range: $percent" }
    }
}

/**
 * Priority of a piece of work. Background work yields first when the device is constrained; an
 * interactive request the user is actively waiting on is the last thing to be refused.
 */
enum class WorkPriority {
    /** User is watching the result of this right now. */
    FOREGROUND_INTERACTIVE,

    /** Useful soon, but nobody is blocked on it (digest generation, re-embedding). */
    BACKGROUND,

    /** Housekeeping: prefetch, benchmarks, model updates. */
    MAINTENANCE,
}

/**
 * Device health policy: decides whether work of a given priority may proceed at all.
 *
 * This is intentionally deterministic and lives outside the LLM and outside LAYA: a model may
 * *propose* work, but the device gets the final say on whether it may run right now.
 */
object DeviceHealthPolicy {

    fun permits(
        priority: WorkPriority,
        thermal: ThermalState,
        memoryPressure: MemoryPressure,
        battery: BatteryState,
    ): HealthVerdict {
        val thermalReason = thermalBlock(priority, thermal.level)
        if (thermalReason != null) return HealthVerdict.Blocked(thermalReason)

        val memoryReason = memoryBlock(priority, memoryPressure)
        if (memoryReason != null) return HealthVerdict.Blocked(memoryReason)

        val batteryReason = batteryBlock(priority, battery)
        if (batteryReason != null) return HealthVerdict.Blocked(batteryReason)

        return HealthVerdict.Permitted
    }

    private fun thermalBlock(priority: WorkPriority, level: ThermalLevel): HealthBlockReason? {
        val hot = level.severity >= ThermalLevel.SEVERE.severity
        return when {
            level == ThermalLevel.SHUTDOWN ->
                HealthBlockReason.THERMAL_SHUTDOWN

            level.severity >= ThermalLevel.CRITICAL.severity ->
                HealthBlockReason.THERMAL_CRITICAL

            // Severe throttling still allows the interactive request: refusing it would look like a
            // hang to the user. Everything else defers.
            hot && priority != WorkPriority.FOREGROUND_INTERACTIVE ->
                HealthBlockReason.THERMAL_THROTTLED

            level == ThermalLevel.MODERATE && priority == WorkPriority.MAINTENANCE ->
                HealthBlockReason.THERMAL_THROTTLED

            else -> null
        }
    }

    private fun memoryBlock(priority: WorkPriority, pressure: MemoryPressure): HealthBlockReason? = when {
        pressure == MemoryPressure.CRITICAL -> HealthBlockReason.MEMORY_CRITICAL
        pressure == MemoryPressure.HIGH && priority != WorkPriority.FOREGROUND_INTERACTIVE ->
            HealthBlockReason.MEMORY_PRESSURE
        else -> null
    }

    private fun batteryBlock(priority: WorkPriority, battery: BatteryState): HealthBlockReason? {
        if (battery.isCharging) return null
        val threshold = when (priority) {
            WorkPriority.FOREGROUND_INTERACTIVE -> 3
            WorkPriority.BACKGROUND -> 15
            WorkPriority.MAINTENANCE -> 25
        }
        return when {
            battery.percent < 3 -> HealthBlockReason.BATTERY_CRITICAL
            battery.percent < threshold -> HealthBlockReason.BATTERY_LOW
            battery.powerSaveMode && priority == WorkPriority.MAINTENANCE ->
                HealthBlockReason.POWER_SAVE_MODE
            else -> null
        }
    }
}

sealed interface HealthVerdict {
    data object Permitted : HealthVerdict

    data class Blocked(val reason: HealthBlockReason) : HealthVerdict {
        val shouldRetryLater: Boolean
            get() = reason != HealthBlockReason.THERMAL_SHUTDOWN
    }
}

enum class HealthBlockReason(val userMessage: String) {
    THERMAL_THROTTLED("The device is warm; this task was postponed to protect performance."),
    THERMAL_CRITICAL("The device is too hot to run inference right now."),
    THERMAL_SHUTDOWN("The device reports an emergency thermal shutdown state."),
    MEMORY_PRESSURE("Memory is tight; background AI work is paused."),
    MEMORY_CRITICAL("The device is critically low on memory."),
    BATTERY_LOW("Battery is too low for this background task."),
    BATTERY_CRITICAL("Battery is critically low."),
    POWER_SAVE_MODE("Battery saver is on; maintenance work is paused."),
}
