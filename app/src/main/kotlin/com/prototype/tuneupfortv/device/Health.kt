package com.prototype.tuneupfortv.device

/**
 * One look at how the TV is doing. A field is null when it could not be read: the screen then says "unknown",
 * never zero, because "no crashes" and "could not look for crashes" are very different things.
 */
data class HealthReading(
    val takenAtMillis: Long,
    val memoryTotalKb: Long?,
    val memoryAvailableKb: Long?,
    val swapUsedKb: Long?,
    /** Share of the CPU that was busy during the last second, 0 to 100. */
    val cpuBusyPercent: Int?,
    /** Crashes in the last minute. */
    val crashes: Int?,
    /** The first lines of those crashes, to see what they were. */
    val crashSample: List<String>,
)
