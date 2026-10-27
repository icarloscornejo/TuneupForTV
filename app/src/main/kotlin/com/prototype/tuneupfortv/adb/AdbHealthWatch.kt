package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.Epoch
import com.prototype.tuneupfortv.device.ProcessName
import com.prototype.tuneupfortv.safety.BaselineResult
import com.prototype.tuneupfortv.safety.CriticalProcess
import com.prototype.tuneupfortv.safety.HealthWatch
import com.prototype.tuneupfortv.safety.WatchBaseline
import com.prototype.tuneupfortv.safety.WatchMode
import com.prototype.tuneupfortv.safety.WatchOutcome
import com.prototype.tuneupfortv.safety.WatchState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The post-change watch. Before a change it records the device clock and the pids of the critical
 * processes (full names). After it, for a while, it compares the pids, then reads the crash log
 * since the recorded time. Anything that changed is an anomaly; anything that could not be checked is
 * inconclusive, never "ok". It only reports.
 */
class AdbHealthWatch(
    private val client: AdbClient,
    private val serial: String,
    /** Injected so tests do not wait in real time. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The clock the watch window is measured with. Monotonic: a change of the TV's date during the watch cannot shorten or stretch the window. */
    private val clock: () -> Long = ::monotonicMillis,
) : HealthWatch {
    private sealed interface PidRead {
        data class Pids(val pids: List<Int>) : PidRead
        data object Absent : PidRead
        data class Failed(val detail: String) : PidRead
    }

    override suspend fun baseline(critical: List<CriticalProcess>, mode: WatchMode): BaselineResult {
        val names = critical.map { it.name }
        val result = try {
            client.shell(Commands.readWatchBaseline(names), READ_DEADLINE)
        } catch (error: DeviceException) {
            return BaselineResult.Unavailable("could not read the clock and the critical processes: ${error.failure}")
        }
        val sections = if (result.observedSerial == serial && result.exitCode == 0) ScriptOutput.parse(result.stdout) else null
        val epochSection = sections?.get(Section.EPOCH)
        val pidSection = sections?.get(Section.PIDS)
        if (epochSection == null || pidSection == null || epochSection.exitCode != 0 || pidSection.exitCode != 0) {
            return BaselineResult.Unavailable("the clock and the critical processes could not be read")
        }
        val epoch = Epoch.orNull(epochSection.output.trim()) ?: return BaselineResult.Unavailable("the device clock is unreadable")
        val reads = interpretPids(pidSection.output, names)
        val pids = LinkedHashMap<String, List<Int>>()
        for (process in critical) {
            when (val read = reads.getValue(process.name.value)) {
                is PidRead.Failed -> return BaselineResult.Unavailable(read.detail)
                is PidRead.Pids -> pids[process.name.value] = read.pids
                PidRead.Absent -> when {
                    // A required process that is not running means the system is already unhealthy, or the profile does not fit.
                    process.required && mode == WatchMode.STRICT -> return BaselineResult.Unavailable("${process.name} is not running")
                    process.required -> pids[process.name.value] = emptyList()
                    // An optional one (the Home launcher) that is not running is simply not watched.
                }
            }
        }
        return BaselineResult.Ready(WatchBaseline(epoch, pids))
    }

    override suspend fun observe(baseline: WatchBaseline, seconds: Int): WatchOutcome {
        val anomalies = LinkedHashSet<String>()
        val failures = LinkedHashSet<String>()
        val names = baseline.pids.keys.mapNotNull { ProcessName.orNull(it) }
        val windowMillis = seconds * MILLIS_PER_SECOND
        val start = clock()
        while (names.isNotEmpty()) {
            val remaining = windowMillis - (clock() - start)
            if (remaining <= 0) break
            pause(minOf(POLL_INTERVAL_MILLIS, remaining))
            val reads = try {
                readPids(names)
            } catch (error: DeviceException) {
                failures += "could not read the processes: ${error.failure}"
                continue
            }
            for ((name, before) in baseline.pids) {
                when (val read = reads.getValue(name)) {
                    is PidRead.Failed -> failures += read.detail
                    is PidRead.Pids -> if (before.isNotEmpty() && read.pids.toSet() != before.toSet()) anomalies += "$name changed pid: $before -> ${read.pids}"
                    PidRead.Absent -> if (before.isNotEmpty()) anomalies += "$name stopped running (was $before)"
                }
            }
        }
        // With nothing to compare the window is still waited out, so the crash log has time to show a problem.
        if (names.isEmpty()) pause(windowMillis)
        readCrashes(baseline.epoch, anomalies, failures)
        return when {
            anomalies.isNotEmpty() -> WatchOutcome(WatchState.ANOMALY, (anomalies + failures).take(MAX_DETAIL_LINES).joinToString("; "))
            failures.isNotEmpty() -> WatchOutcome(WatchState.INCONCLUSIVE, failures.take(MAX_DETAIL_LINES).joinToString("; "))
            else -> WatchOutcome(WatchState.OK, null)
        }
    }

    private suspend fun readCrashes(since: Epoch, anomalies: MutableSet<String>, failures: MutableSet<String>) {
        val result = try {
            client.shell(Commands.crashLog(since), READ_DEADLINE)
        } catch (error: DeviceException) {
            failures += "could not read the crash log: ${error.failure}"
            return
        }
        when {
            result.observedSerial != serial -> failures += "the crash log came from another device"
            // `logcat` prints its complaint (for example a bad time) on stderr and may still exit 0.
            result.exitCode != 0 || result.stderr.isNotBlank() -> failures += "the crash log could not be read (exit ${result.exitCode})"
            else -> {
                val crashLines = result.stdout.lines().filter { it.isNotBlank() && !it.startsWith(LOGCAT_HEADER) }
                if (crashLines.isNotEmpty()) anomalies += "new crash: ${crashLines.first().take(MAX_CRASH_CHARS)}"
            }
        }
    }

    /** One read for every process. A process that cannot be read gets [PidRead.Failed]; a missing line counts as a failure too. */
    private suspend fun readPids(names: List<ProcessName>): Map<String, PidRead> {
        if (names.isEmpty()) return emptyMap()
        val result = client.shell(Commands.pidofAll(names), READ_DEADLINE)
        if (result.observedSerial != serial || result.exitCode != 0 || result.stderr.isNotBlank()) {
            return names.associate { it.value to PidRead.Failed("pidof ${it.value} could not be read") }
        }
        return interpretPids(result.stdout, names)
    }

    /** Turns the `@@name|exit|pids` lines into a read per process. */
    private fun interpretPids(output: String, names: List<ProcessName>): Map<String, PidRead> {
        val byName = output.lines().filter { it.startsWith("@@") }.mapNotNull { line ->
            val parts = line.removePrefix("@@").split('|', limit = 3)
            if (parts.size < 3) null else parts[0] to parseRead(parts[0], parts[1].toIntOrNull(), parts[2].trim())
        }.toMap()
        return names.associate { it.value to (byName[it.value] ?: PidRead.Failed("pidof ${it.value} gave no answer")) }
    }

    private fun parseRead(name: String, exit: Int?, output: String): PidRead =
        when {
            exit == 0 -> output.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.let { pids -> if (pids.isEmpty()) PidRead.Failed("pidof $name returned no pids") else PidRead.Pids(pids) }
            exit == 1 && output.isEmpty() -> PidRead.Absent
            else -> PidRead.Failed("pidof $name failed (exit $exit)")
        }

    private companion object {
        val READ_DEADLINE = 10.seconds
        const val LOGCAT_HEADER: String = "---------"
        const val MILLIS_PER_SECOND: Long = 1000L
        const val POLL_INTERVAL_MILLIS: Long = 1000L
        const val MAX_DETAIL_LINES: Int = 3
        const val MAX_CRASH_CHARS: Int = 160
    }
}

/** Milliseconds from a clock that only moves forward. Only differences between two readings mean anything. */
fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI: Long = 1_000_000L
