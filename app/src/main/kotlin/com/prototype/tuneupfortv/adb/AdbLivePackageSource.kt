package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.adb.Parsers
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.LauncherSnapshot
import com.prototype.tuneupfortv.safety.LivePackageSource
import com.prototype.tuneupfortv.safety.LiveReading
import kotlin.time.Duration.Companion.seconds

/** Reads one package, the Home launcher and the keyboard from the device in a single round trip, behind the identity envelope. */
class AdbLivePackageSource(
    private val client: AdbClient,
    private val expectedSerial: String,
) : LivePackageSource {
    override suspend fun read(packageName: PackageName): LiveReading {
        val withLaunchers = packageName.value in LauncherPackages.RELEVANT
        val result = client.shell(Commands.readLivePackage(packageName, withLaunchers), if (withLaunchers) LAUNCHER_READ_DEADLINE else READ_DEADLINE)
        if (result.observedSerial != expectedSerial) {
            throw DeviceException(DeviceFailure.IdentityMismatch(expectedSerial, result.observedSerial))
        }
        val sections = if (result.exitCode == 0) ScriptOutput.parse(result.stdout) else null
        fun output(name: String): String? = sections?.get(name)?.takeIf { it.exitCode == 0 }?.output
        val dump = output(Section.PACKAGE)
        val roles = output(Section.ROLES)
        val homeActivity = output(Section.HOME)
        val keyboard = output(Section.IME)
        // Anything unreadable makes the whole reading unreadable: the Guard then refuses.
        if (dump == null || roles == null || homeActivity == null || keyboard == null) return LiveReading.UNREADABLE
        val record = Parsers.parseRawPackageDump(dump)[packageName.value]
        return LiveReading(
            record = record,
            homePackages = Parsers.parseLaunchables(homeActivity) + Parsers.parseRoleHolders(roles, SnapshotLoader.HOME_ROLE),
            keyboardPackage = Parsers.parseInputMethodPackage(keyboard),
            packageAbsent = record == null,
            launchers = if (withLaunchers) launchers(output(Section.DUMP), output(Section.HOME_CANDIDATES)) else null,
        )
    }

    /** Null when either part is unreadable, or a package the rules look at has a record that cannot be trusted: they then refuse. */
    private fun launchers(dump: String?, candidates: String?): LauncherSnapshot? {
        if (dump == null || candidates == null) return null
        val records = Parsers.parseRawPackageDump(dump)
        fun stateOf(id: String): Result<PackageState?> {
            val record = records[id] ?: return Result.success(null)
            return if (record.isComplete && record.state != null) Result.success(record.state) else Result.failure(IllegalStateException(id))
        }
        val setupwraith = stateOf(LauncherPackages.SETUPWRAITH).getOrElse { return null }
        val light = stateOf(LauncherPackages.LIGHT).getOrElse { return null }
        return LauncherSnapshot(setupwraith, light, Parsers.parseLaunchables(candidates))
    }

    private companion object {
        val READ_DEADLINE = 15.seconds
        val LAUNCHER_READ_DEADLINE = 30.seconds
    }
}
