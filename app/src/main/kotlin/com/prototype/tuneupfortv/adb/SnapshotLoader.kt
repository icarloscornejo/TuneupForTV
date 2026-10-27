package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.LoadedDevice
import com.prototype.tuneupfortv.device.SnapshotException
import com.prototype.tuneupfortv.device.PackageRecord
import com.prototype.tuneupfortv.device.ProcessRecord
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a whole device in a single round trip. The commands run one after the other on the device,
 * each between markers with its own exit code, so a failure in the middle can never hide behind a
 * later command that succeeded, and a cut answer is detected instead of shown as a shorter list.
 */
class SnapshotLoader(private val client: AdbClient) {
    /**
     * The first read of a device: opens the connection, learns who it is, and reads everything.
     * The connection may wait for the user to accept "Allow USB debugging?": if it times out, TCP
     * did connect (a dead address fails earlier), so it is reported as [DeviceFailure.AwaitingAuthorization].
     */
    suspend fun connect(firstCallDeadline: Duration = FIRST_CALL_DEADLINE): LoadedDevice {
        val result = try {
            client.shell(Commands.readDevice(), firstCallDeadline)
        } catch (error: DeviceException) {
            if (error.failure == DeviceFailure.Timeout) throw DeviceException(DeviceFailure.AwaitingAuthorization, error)
            throw error
        }
        return parse(result, expected = null)
    }

    /** Reads the same device again. Another device answering is refused. */
    suspend fun reload(expected: DeviceIdentity): DeviceSnapshot = parse(client.shell(Commands.readDevice()), expected).snapshot

    // Parsing a 700 KB dump is real work on a slow TV: it never runs on the main thread.
    private suspend fun parse(result: ShellResult, expected: DeviceIdentity?): LoadedDevice =
        withContext(Dispatchers.Default) {
            val observed = result.observedSerial ?: throw SnapshotException("the answer carries no serial")
            if (expected != null && observed != expected.serial) throw DeviceException(DeviceFailure.IdentityMismatch(expected.serial, observed))
            if (result.exitCode != 0) throw SnapshotException("the read failed with exit ${result.exitCode}")
            val sections = ScriptOutput.parse(result.stdout) ?: throw SnapshotException("the answer was cut short")

            fun output(name: String, requireOutput: Boolean = false): String {
                val section: ScriptSection = sections[name] ?: throw SnapshotException("$name is missing")
                if (section.exitCode != 0) throw SnapshotException("$name failed with exit ${section.exitCode}")
                if (requireOutput && section.output.isBlank()) throw SnapshotException("$name came back empty")
                return section.output
            }

            val serial = output(Section.SERIAL).trim()
            if (serial != observed) throw SnapshotException("the serial changed while reading")
            val model = output(Section.MODEL).trim()
            val manufacturer = output(Section.MANUFACTURER).trim()
            val sdk = output(Section.SDK).trim().toIntOrNull() ?: throw SnapshotException("the SDK level is not a number")
            if (model.isEmpty() || manufacturer.isEmpty()) throw SnapshotException("the device did not report model or manufacturer")
            val identity = DeviceIdentity(manufacturer, model, serial, sdk)
            // Only for showing: a TV that does not answer these is still a TV the app can manage.
            fun optionalProperty(name: String): String = sections[name]?.takeIf { it.exitCode == 0 }?.output?.trim().orEmpty()

            val allIds = packageIds(output(Section.PACKAGES_ALL, requireOutput = true), Section.PACKAGES_ALL)
            val installedIds = packageIds(output(Section.PACKAGES_INSTALLED, requireOutput = true), Section.PACKAGES_INSTALLED).toSet()
            if (!allIds.toSet().containsAll(installedIds)) throw SnapshotException("installed packages are not a subset of all packages")

            val records = Parsers.parseRawPackageDump(output(Section.DUMP, requireOutput = true))
            val disagreeing = mutableSetOf<String>()
            val packages = allIds.map { id ->
                val record = records[id] ?: throw SnapshotException("the package dump has no record for $id")
                val state = record.state
                if (!record.isComplete || state == null) throw SnapshotException("the package dump record for $id is incomplete")
                if (state.installed == (id in installedIds)) {
                    record
                } else {
                    // A package left half removed (some TVs keep one after an uninstall) is listed one way and dumped
                    // the other. It is read as not installed, so nothing can be done to it, and the TV still loads.
                    disagreeing += id
                    record.copy(state = state.copy(installed = false))
                }
            }.sortedBy { it.id }
            // A few oddities are normal; many mean the two reads do not describe the same TV, and nothing can be trusted.
            if (disagreeing.size > maxOf(MIN_TOLERATED_DISAGREEMENTS, allIds.size / TOLERATED_DISAGREEMENT_DIVISOR)) {
                throw SnapshotException("the package list and the dump disagree about ${disagreeing.size} packages, for example ${disagreeing.first()}")
            }
            val effectiveInstalledIds = installedIds - disagreeing

            val launchable = Parsers.parseLaunchables(output(Section.LAUNCHERS_LEANBACK)) + Parsers.parseLaunchables(output(Section.LAUNCHERS))
            val homePackages = Parsers.parseLaunchables(output(Section.HOME)) +
                Parsers.parseRoleHolders(output(Section.ROLES, requireOutput = true), HOME_ROLE)
            val processes = Parsers.parseProcesses(output(Section.PROCESSES, requireOutput = true))
            if (processes.isEmpty()) throw SnapshotException("the process list is empty")
            val memory = Parsers.parseMeminfo(output(Section.MEMINFO, requireOutput = true))
            if ("MemTotal" !in memory) throw SnapshotException("meminfo has no MemTotal")

            LoadedDevice(
                identity,
                DeviceSnapshot(
                    identity = identity,
                    packages = packages,
                    uninstalledForUser0 = allIds.toSet() - effectiveInstalledIds,
                    launchable = launchable,
                    homePackages = homePackages,
                    inputMethodPackage = Parsers.parseInputMethodPackage(output(Section.IME)),
                    processes = processes.sortedByDescending { it.rssKb },
                    memoryKb = memory,
                    productName = optionalProperty(Section.PRODUCT_NAME),
                    buildId = optionalProperty(Section.BUILD_ID),
                ),
            )
        }

    private fun packageIds(text: String, name: String): List<String> {
        val ids = Parsers.parsePackageList(text)
        if (ids.toSet().size != ids.size) throw SnapshotException("$name has duplicates")
        return ids
    }

    companion object {
        const val HOME_ROLE: String = "android.app.role.HOME"
        private const val MIN_TOLERATED_DISAGREEMENTS: Int = 3
        private const val TOLERATED_DISAGREEMENT_DIVISOR: Int = 50

        /** Long enough for a person to read the authorization prompt and press OK. */
        val FIRST_CALL_DEADLINE: Duration = 60.seconds
    }
}
