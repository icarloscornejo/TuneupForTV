package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.AccessibilityValue
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.Epoch
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.PackageRecord
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.PermissionName
import com.prototype.tuneupfortv.device.ProcessName
import com.prototype.tuneupfortv.device.ScreensaverValue
import java.io.IOException

// The doors of the domain. Everything the safety logic needs from the outside world is one of these
// interfaces; the ADB and file adapters live elsewhere and the tests plug in the same adapters over a fake TV.

/** Where the journal is kept. The domain only knows that the whole list can be loaded and saved. */
interface JournalStore {
    /** Throws [JournalStoreException] when the stored journal cannot be read: never an empty journal instead. */
    fun load(): List<JournalEntry>

    /** Writes the whole list durably, or throws [JournalStoreException]. */
    fun save(entries: List<JournalEntry>)
}

class JournalStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What the device says about one package right now, and the facts that decide whether it is protected. */
data class LiveReading(
    /** Null when anything needed could not be read: such a package is never touched. */
    val record: PackageRecord?,
    val homePackages: Set<String>,
    val keyboardPackage: String?,
    /** The device answered and has no such package at all (an app removed for good), as opposed to an answer that could not be read. */
    val packageAbsent: Boolean = false,
    /** For the packages of the launcher swap: what the launcher rules need. Null when it was not asked for or could not be read. */
    val launchers: LauncherSnapshot? = null,
) {
    companion object {
        val UNREADABLE: LiveReading = LiveReading(null, emptySet(), null)
    }
}

/** The part of a live read that the launcher rules use. */
data class LauncherSnapshot(
    val setupwraith: PackageState?,
    val lightLauncher: PackageState?,
    val homeCandidates: Set<String>,
)

interface LivePackageSource {
    /** Reads the package, the Home launcher and the keyboard from the device, now. Throws [com.prototype.tuneupfortv.device.DeviceException]. */
    suspend fun read(packageName: PackageName): LiveReading
}

/** What became of one command sent to the device. */
sealed interface SendResult {
    data object Ran : SendResult
    data class Failed(val exitCode: Int) : SendResult

    /** The connection never came up, so the command was certainly not sent. */
    data object NotSent : SendResult

    /** A timeout or a cut connection: it may have run. Only the device can say. */
    data object Uncertain : SendResult

    /** The identity fence stopped it: another device answered, and nothing ran. */
    data object FenceRefused : SendResult
}

interface PackageCommandSender {
    /** Sends one operation, behind the identity fence. */
    suspend fun send(operation: Operation, packageName: PackageName): SendResult
}

/** A process whose pid proves the system is healthy. [required] ones must be running before a risky change. */
data class CriticalProcess(val name: ProcessName, val required: Boolean)

class WatchBaseline(val epoch: Epoch, val pids: Map<String, List<Int>>)

sealed interface BaselineResult {
    data class Ready(val baseline: WatchBaseline) : BaselineResult

    /** The watch cannot work, so the change must not start: a change nobody can verify is a change nobody should make. */
    data class Unavailable(val detail: String) : BaselineResult
}

/** How strict the baseline is: a disable or force-stop needs every required process running first. */
enum class WatchMode { STRICT, LENIENT }

data class WatchOutcome(val state: WatchState, val detail: String?)

/**
 * The post-change watch. Before a change it records the device clock and the pids of the critical processes (full
 * names). After it, for a while, it compares the pids, then reads the crash log since the recorded time. Anything
 * that changed is an anomaly; anything that could not be checked is inconclusive, never "ok". It only reports.
 */
interface HealthWatch {
    suspend fun baseline(critical: List<CriticalProcess>, mode: WatchMode): BaselineResult
    suspend fun observe(baseline: WatchBaseline, seconds: Int): WatchOutcome
}

/**
 * The writes and reads of the launcher swap that are not about one package's state. Every write is behind the identity
 * fence, and every answer is a [SendResult], so a lost answer is never taken for a failure.
 */
interface LauncherPort {
    /** Null when anything the swap needs could not be read. Throws [com.prototype.tuneupfortv.device.DeviceException]. */
    suspend fun read(): LauncherReading?
    suspend fun install(apk: LauncherApk): SendResult
    suspend fun grant(permission: PermissionName): SendResult
    suspend fun compile(): SendResult
    suspend fun setHome(component: ComponentName): SendResult
    suspend fun putScreensaver(value: ScreensaverValue): SendResult
    suspend fun deleteScreensaver(): SendResult
    suspend fun putAccessibility(value: AccessibilityValue): SendResult
}
