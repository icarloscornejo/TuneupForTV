package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.adb.FakeDump
import com.prototype.tuneupfortv.adb.Section
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.LoadedDevice
import com.prototype.tuneupfortv.adb.Parsers
import com.prototype.tuneupfortv.device.SnapshotException
import com.prototype.tuneupfortv.adb.SnapshotLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotLoaderTest {
    private fun fake(): FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)

    private fun connect(client: FakeAdbClient): LoadedDevice = runBlocking { SnapshotLoader(client).connect() }

    private fun snapshotOf(client: FakeAdbClient): DeviceSnapshot = connect(client).snapshot

    @Test
    fun loadsTheWholeFixtureThroughTheRealCommandPipeline() {
        val device = connect(fake())
        val snapshot = device.snapshot
        assertEquals(193, snapshot.packages.size)
        assertEquals(7, snapshot.uninstalledForUser0.size)
        assertTrue(FakeAdbClient.HOME_PACKAGE in snapshot.homePackages)
        assertEquals(FakeAdbClient.KEYBOARD_PACKAGE, snapshot.inputMethodPackage)
        assertTrue(snapshot.processes.isNotEmpty())
        assertEquals("BRAVIA_BF1", device.identity.model)
        assertEquals("BRAVIA_BF1", snapshot.identity.model)
        assertTrue("com.netflix.ninja" in snapshot.launchable)
    }

    @Test
    fun everythingIsReadInASingleRoundTrip() {
        val client = fake()
        connect(client)
        assertEquals(1, client.sent.size)
    }

    @Test
    fun theRawDumpGivesTheSamePackagesAsTheCapturedBlocks() {
        val viaCommands = snapshotOf(fake())
        val direct = FixtureSnapshot.load()
        assertEquals(direct.packages, viaCommands.packages)
        assertEquals(direct.uninstalledForUser0, viaCommands.uninstalledForUser0)
        assertEquals(direct.launchable, viaCommands.launchable)
    }

    @Test
    fun theFakeDumpRoundTripsEveryRecord() {
        val captured = Parsers.parseCapturedPackageInfo(FixtureSnapshot.read("pkginfo.txt"))
        val parsed = Parsers.parseRawPackageDump(FakeDump.render(captured.values))
        assertEquals(captured.keys, parsed.keys)
        captured.forEach { (id, record) -> assertEquals("record of $id", record, parsed.getValue(id)) }
    }

    @Test
    fun onlyReadCommandsAreEverSent() {
        val client = fake()
        connect(client)
        val mutating = Regex("""\b(disable|enable|force-stop|install|uninstall|default-state|clear|reboot)\b""")
        client.sent.forEach { assertFalse("read-only read sent: ${it.take(120)}", mutating.containsMatchIn(it)) }
    }

    @Test
    fun aCutDumpIsRejectedInsteadOfShownAsAShorterList() {
        val client = fake().apply { dumpMode = FakeAdbClient.DumpMode.TRUNCATED }
        assertThrows(SnapshotException::class.java) { connect(client) }
    }

    @Test
    fun anEmptyDumpIsRejected() {
        val client = fake().apply { dumpMode = FakeAdbClient.DumpMode.EMPTY }
        assertThrows(SnapshotException::class.java) { connect(client) }
    }

    @Test
    fun aWholeAnswerThatIsCutShortIsRejected() {
        val client = fake()
        val full = runBlocking { client.shell(Commands.readDevice()).stdout }
        client.outputOverrides[Commands.readDevice().line] = full.take(full.length / 3)
        assertThrows(SnapshotException::class.java) { connect(client) }
        client.outputOverrides[Commands.readDevice().line] = full.substringBefore("@@BEGIN|meminfo")
        assertThrows(SnapshotException::class.java) { connect(client) }
    }

    @Test
    fun everyCommandThatExitsNonZeroStopsTheLoadWhateverTheOthersSaid() {
        val sections = listOf(
            Section.SERIAL, Section.MODEL, Section.MANUFACTURER, Section.SDK, Section.PACKAGES_ALL, Section.PACKAGES_INSTALLED, Section.DUMP,
            Section.LAUNCHERS_LEANBACK, Section.LAUNCHERS, Section.HOME, Section.ROLES, Section.IME, Section.PROCESSES, Section.MEMINFO,
        )
        sections.forEach { name ->
            val client = fake().apply { sectionExitOverrides[name] = 1 }
            assertThrows("$name failing must stop the load", SnapshotException::class.java) { connect(client) }
        }
    }

    @Test
    fun anotherDeviceAnsweringARereadIsRejected() {
        val client = fake()
        val identity = connect(client).identity
        client.observedSerialOverride = "SOMEONE-ELSE"
        val error = assertThrows(DeviceException::class.java) { runBlocking { SnapshotLoader(client).reload(identity) } }
        assertEquals(DeviceFailure.IdentityMismatch("FAKE0001", "SOMEONE-ELSE"), error.failure)
    }

    @Test
    fun aRereadGivesTheCurrentState() {
        val client = fake()
        val identity = connect(client).identity
        client.setState("org.localsend.localsend_app", com.prototype.tuneupfortv.device.PackageState(installed = true, enabled = 3, stopped = false))
        val again = runBlocking { SnapshotLoader(client).reload(identity) }
        assertEquals(3, again.packages.first { it.id == "org.localsend.localsend_app" }.state?.enabled)
    }

    @Test
    fun aDeadDeviceFailsWithItsOwnReason() {
        val client = fake().apply { failure = DeviceFailure.Unreachable }
        assertEquals(DeviceFailure.Unreachable, assertThrows(DeviceException::class.java) { connect(client) }.failure)
    }

    @Test
    fun aTimeoutOnTheFirstCallMeansTheDeviceIsWaitingForAuthorization() {
        val client = fake().apply { failure = DeviceFailure.Timeout }
        assertEquals(DeviceFailure.AwaitingAuthorization, assertThrows(DeviceException::class.java) { connect(client) }.failure)
    }

    @Test
    fun aPackageTheListAndTheDumpDisagreeAboutIsReadAsNotInstalledAndTheTvStillLoads() {
        val installed = FixtureSnapshot.read("pm_plain.txt").lines().filter { it.isNotBlank() }
        // The dump says installed, the list does not (a package left half removed)...
        val missingFromList = installed.first().removePrefix("package:")
        val withoutOne = fake().apply { sectionOutputOverrides[Section.PACKAGES_INSTALLED] = installed.drop(1).joinToString("\n") }
        val first = snapshotOf(withoutOne)
        assertEquals(false, first.packages.single { it.id == missingFromList }.state?.installed)
        assertTrue(missingFromList in first.uninstalledForUser0)
        // ...or the list says installed and the dump says uninstalled. Either way nothing can be done to it.
        val uninstalled = FixtureSnapshot.read("pm_u.txt").lines().first { it.isNotBlank() && it !in installed }.removePrefix("package:")
        val withExtra = fake().apply { sectionOutputOverrides[Section.PACKAGES_INSTALLED] = (installed + "package:$uninstalled").joinToString("\n") }
        val second = snapshotOf(withExtra)
        assertEquals(false, second.packages.single { it.id == uninstalled }.state?.installed)
        assertTrue(uninstalled in second.uninstalledForUser0)
    }

    @Test
    fun manyPackagesTheListAndTheDumpDisagreeAboutMeanTheReadCannotBeTrusted() {
        val installed = FixtureSnapshot.read("pm_plain.txt").lines().filter { it.isNotBlank() }
        val client = fake().apply { sectionOutputOverrides[Section.PACKAGES_INSTALLED] = installed.drop(20).joinToString("\n") }
        assertThrows(SnapshotException::class.java) { connect(client) }
    }

    @Test
    fun theInstalledListMustBeASubsetOfAllPackages() {
        val client = fake().apply { sectionOutputOverrides[Section.PACKAGES_ALL] = FixtureSnapshot.read("pm_u.txt").lines().drop(5).joinToString("\n") }
        assertThrows(SnapshotException::class.java) { connect(client) }
    }
}
