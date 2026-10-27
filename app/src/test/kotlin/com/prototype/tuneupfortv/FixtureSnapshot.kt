package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.adb.Parsers
import java.io.File

/** The real captured fixtures of the Sony BRAVIA, parsed directly (not through the command pipeline). */
object FixtureSnapshot {
    private val directory = File("src/test/resources/fixtures")

    fun read(name: String): String = File(directory, name).readText()

    fun load(readFixture: (String) -> String = ::read): DeviceSnapshot {
        val allIds = Parsers.parsePackageList(readFixture("pm_u.txt"))
        val installedIds = Parsers.parsePackageList(readFixture("pm_plain.txt")).toSet()
        val info = Parsers.parseCapturedPackageInfo(readFixture("pkginfo.txt"))
        val packages = allIds.map { id -> requireNotNull(info[id]) { "Fixture pkginfo.txt has no block for $id" } }.sortedBy { it.id }
        val launchable = Parsers.parseLaunchables(readFixture("launchable_lb.txt")) + Parsers.parseLaunchables(readFixture("launchable_l.txt"))
        return DeviceSnapshot(
            identity = DeviceIdentity("Sony", "BRAVIA_BF1", "FIXTURE0001", 31),
            packages = packages,
            uninstalledForUser0 = allIds.toSet() - installedIds,
            launchable = launchable,
            homePackages = setOf(FakeAdbClient.HOME_PACKAGE),
            inputMethodPackage = FakeAdbClient.KEYBOARD_PACKAGE,
            processes = Parsers.parseProcesses(readFixture("ps.txt")).sortedByDescending { it.rssKb },
            memoryKb = Parsers.parseMeminfo(readFixture("meminfo.txt")),
        )
    }
}
