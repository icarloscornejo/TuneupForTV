package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.adb.AdbHealthWatch
import com.prototype.tuneupfortv.adb.AdbLauncherPort
import com.prototype.tuneupfortv.adb.AdbLivePackageSource
import com.prototype.tuneupfortv.adb.AdbPackageCommandSender
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.adb.FakeAdbClient.Companion.LIGHT_PACKAGE
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.Operation
import com.prototype.tuneupfortv.safety.PendingKind
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.ScopeProblem
import com.prototype.tuneupfortv.safety.StopCause
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapRefusal
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.SwapStepStatus
import com.prototype.tuneupfortv.store.JsonJournalStore
import com.prototype.tuneupfortv.store.LauncherSwapStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The whole swap and its undo, on a fake TV that changes the way a real one does. */
class LauncherSwapTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val stock = FakeAdbClient.HOME_PACKAGE
    private val setup = LauncherPackages.SETUPWRAITH
    private val dream = LauncherPackages.DREAMX
    private val bundled = LauncherApk(ByteArray(64), "1.0.1", 10001L)

    private var tvCount = 0

    private inner class Tv(identity: DeviceIdentity = DeviceIdentity("Sony", "BRAVIA_BF1", "FAKE0001", 31)) {
        val fake = FakeAdbClient(FixtureSnapshot::read, identity)
        val directory = File(folder.root, "state-${tvCount++}").also { it.mkdirs() }
        var now = 1_000_000L
        val journal = Journal(JsonJournalStore(directory))
        val store = LauncherSwapStore(directory)
        val statuses = linkedMapOf<SwapStep, SwapStepStatus>()
        val executor: ActionExecutor = ActionExecutor(
            liveSource = AdbLivePackageSource(fake, fake.identity.serial),
            sender = AdbPackageCommandSender(fake, DeviceSerial(fake.identity.serial)),
            watch = AdbHealthWatch(fake, fake.identity.serial, pause = { fake.advance(it) }, clock = { fake.clockSeconds * 1000 }),
            journal = journal,
            identity = fake.identity,
            profile = DeviceProfiles.SONY,
            ownPackage = ExecutorHarness.OWN_PACKAGE,
            watchSeconds = 2,
            dispatcher = Dispatchers.Unconfined,
            nowMillis = { now },
            swapStore = store,
            launcher = AdbLauncherPort(fake, DeviceSerial(identity.serial)),
        )
        val runner = requireNotNull(executor.launcherSwap)

        fun swap(): SwapOutcome = runBlocking { runner.run(bundled) { step, status -> statuses[step] = status } }
        fun undo(): SwapOutcome = runBlocking { runner.undo { step, status -> statuses[step] = status } }
        fun preview(): SwapPreview = runBlocking { runner.preview(bundled.versionCode) }
        fun perform(packageId: String, kind: ActionKind): ActionOutcome = runBlocking { executor.perform(packageId, kind) }
        fun state(packageId: String): PackageState = fake.state(packageId)
        fun assertNothingSent() {
            assertTrue("sent: ${fake.mutations}", fake.mutations.isEmpty())
            assertFalse("a mutating command was sent", fake.sent.any { it.startsWith("if [") })
            assertTrue("a file was pushed: ${fake.pushes}", fake.pushes.isEmpty())
        }
    }

    private fun done(outcome: SwapOutcome): SwapOutcome.Done = outcome as SwapOutcome.Done
    private fun stopped(outcome: SwapOutcome): SwapOutcome.Stopped = outcome as SwapOutcome.Stopped
    private fun refused(outcome: SwapOutcome): SwapRefusal = (outcome as SwapOutcome.Refused).refusal

    @Test
    fun aFullSwapInstallsTurnsOffInTheSafeOrderAndEndsWithTheNewLauncherAsHome() {
        val tv = Tv()
        val outcome = done(tv.swap())
        assertTrue(outcome.unconfirmed.isEmpty())
        assertFalse(outcome.wifiReminder)
        assertTrue(tv.state(LIGHT_PACKAGE).installed)
        assertEquals(LIGHT_PACKAGE, tv.fake.home)
        assertEquals("${LauncherPackages.LIGHT}/.ClockDream", tv.fake.screensaver)
        listOf(setup, stock, dream).forEach { assertEquals(it, 3, tv.state(it).enabled) }
        // The setup app goes off before the stock launcher, and Home is set while the stock launcher is already off.
        val order = tv.fake.mutations
        assertTrue(order.toString(), order.indexOf("disable-user $setup") < order.indexOf("disable-user $stock"))
        assertTrue(order.toString(), order.indexOf("disable-user $stock") < order.indexOf("set-home ${LauncherPackages.HOME_COMPONENT}"))
        assertTrue(order.toString(), order.indexOf("install $LIGHT_PACKAGE") < order.indexOf("disable-user $setup"))
        assertEquals(listOf("/data/local/tmp/tuneup-launcher.apk" to 64), tv.fake.pushes)
        assertEquals(3, order.count { it.startsWith("grant ") })
        // The undo is on record, and every step says it is done.
        assertNotNull(tv.store.load())
        assertTrue(tv.statuses.values.all { it == SwapStepStatus.DONE })
        // Nothing was left open in the journal.
        assertTrue(tv.journal.blocking().isEmpty())
    }

    @Test
    fun theUndoPutsEverythingBackTheWayItWasAndForgetsTheRecord() {
        val tv = Tv()
        tv.swap()
        val outcome = done(tv.undo())
        assertFalse(outcome.wifiReminder)
        listOf(setup, stock, dream).forEach { assertEquals(it, 0, tv.state(it).enabled) }
        assertEquals(stock, tv.fake.home)
        assertNull(tv.fake.screensaver)
        assertNull(tv.store.load())
        // The new launcher stays installed and enabled.
        assertEquals(0, tv.state(LIGHT_PACKAGE).enabled)
        val order = tv.fake.mutations
        assertTrue(order.toString(), order.lastIndexOf("default-state $stock") < order.lastIndexOf("default-state $setup"))
    }

    @Test
    fun anExplicitlyEnabledPackageComesBackExplicitlyEnabled() {
        val tv = Tv()
        tv.fake.setState(dream, PackageState(installed = true, enabled = 1, stopped = false))
        tv.swap()
        tv.undo()
        assertEquals(1, tv.state(dream).enabled)
        assertTrue(tv.fake.mutations.contains("enable $dream"))
    }

    @Test
    fun ifHomeDoesNotMoveTheStockLauncherAndTheSetupAppComeBackAndNothingElseIsTurnedOff() {
        val tv = Tv()
        tv.fake.setHomeIgnored = true
        val outcome = stopped(tv.swap())
        assertEquals(SwapStep.HOME, outcome.step)
        assertEquals(StopCause.NOT_CONFIRMED, outcome.cause)
        assertTrue(outcome.rolledBack)
        listOf(setup, stock).forEach { assertEquals(it, 0, tv.state(it).enabled) }
        // The screensaver of the stock launcher was never touched.
        assertEquals(0, tv.state(dream).enabled)
        assertEquals(stock, tv.fake.home)
    }

    @Test
    fun aRollbackThatCannotPutTheStockLauncherBackNeverTurnsTheSetupAppOn() {
        val tv = Tv()
        tv.fake.setHomeIgnored = true
        // The stock launcher cannot be restored: the shell refuses to touch it.
        tv.fake.onMutation = { packageId, operation ->
            if (packageId == stock && operation == "disable-user") tv.fake.setState(stock, PackageState(installed = true, enabled = 2, stopped = false))
        }
        val outcome = stopped(tv.swap())
        assertTrue(outcome.rollbackIncomplete)
        assertEquals(3, tv.state(setup).enabled)
        assertFalse(tv.fake.mutations.contains("default-state $setup"))
    }

    @Test
    fun aDeviceWithoutAReliableSerialIsLeftAlone() {
        val tv = Tv(DeviceIdentity("Sony", "BRAVIA_BF1", "0123456789ABCDEF", 31))
        assertEquals(SwapRefusal.UnreliableDevice, refused(tv.swap()))
        tv.assertNothingSent()
        assertNull(tv.store.load())
    }

    @Test
    fun anOpenEntryHoldsTheSwapBackBeforeAnythingIsWritten() {
        val tv = Tv()
        tv.journal.add(
            JournalEntry("open", 1L, ExecutorHarness.USER_APP, ActionKind.DISABLE, PackageState(true, 0, false), PackageState(true, 3, false), listOf(Operation.DISABLE_USER), status = EntryStatus.NEEDS_REVIEW),
        )
        assertEquals(SwapRefusal.OpenEntries, refused(tv.swap()))
        tv.assertNothingSent()
        assertNull(tv.store.load())
    }

    @Test
    fun aRecordThatCannotBeReadStopsTheSwapTheUpdateAndTheUndoAndIsNeverOverwritten() {
        val tv = Tv()
        val file = File(tv.directory, "launcher-swap.json").also { it.writeText("{ not json") }
        assertEquals(SwapRefusal.RecordUnreadable, refused(tv.swap()))
        assertEquals(SwapRefusal.RecordUnreadable, refused(tv.undo()))
        tv.assertNothingSent()
        assertEquals("{ not json", file.readText())
        // The Guard keeps its rule too: a stock launcher is not uninstalled while the record is in doubt.
        tv.fake.addPackage("com.google.android.tvlauncher", PackageState(true, 0, false))
        assertEquals(ActionOutcome.Rejected(RejectReason.LAUNCHER_SWAP_ACTIVE), tv.perform("com.google.android.tvlauncher", ActionKind.UNINSTALL))
    }

    @Test
    fun aPersistentStockLauncherIsFoundBeforeTheSetupAppIsTurnedOff() {
        val tv = Tv()
        tv.fake.markPersistent(stock)
        val refusal = refused(tv.swap()) as SwapRefusal.Guarded
        assertEquals(stock, refusal.packageId)
        assertEquals(RejectReason.ESSENTIAL, refusal.reason)
        tv.assertNothingSent()
        assertEquals(0, tv.state(setup).enabled)
    }

    @Test
    fun anUnknownHomeCandidateRefusesTheSwapWithTheReason() {
        val tv = Tv()
        tv.fake.addPackage("com.duokan.factorytest", PackageState(true, 0, false))
        tv.fake.homeCapable += "com.duokan.factorytest"
        val refusal = refused(tv.swap()) as SwapRefusal.Scope
        assertEquals(ScopeProblem.UNKNOWN_HOME_CANDIDATE, refusal.problem)
        tv.assertNothingSent()
    }

    @Test
    fun aTvWhoseFirmwareUninstallsAddedLaunchersIsRefusedBeforeAnythingChanges() {
        listOf(PackageState(true, 0, false), PackageState(true, 3, false)).forEach { state ->
            val tv = Tv()
            tv.fake.addPackage("com.nes.coreservice", state)
            val refusal = refused(tv.swap()) as SwapRefusal.Scope
            assertEquals(ScopeProblem.HOME_REMOVER, refusal.problem)
            assertEquals("com.nes.coreservice", refusal.detail)
            tv.assertNothingSent()
        }
    }

    @Test
    fun theNokia8010CarriesThatServiceButTheSwapStillRuns() {
        val tv = Tv()
        tv.fake.addPackage("com.nes.coreservice", PackageState(true, 0, false))
        tv.fake.sectionOutputOverrides["product_name"] = "SEI804NK"
        assertEquals(LIGHT_PACKAGE, tv.swap().let { tv.fake.home })
    }

    @Test
    fun theSetupAppCannotBeTurnedOnWhileNoStockLauncherCanTakeHome() {
        val tv = Tv()
        tv.swap()
        // Stock launcher off, the setup app off: turning the setup app on alone is the crash loop.
        assertEquals(ActionOutcome.Rejected(RejectReason.WOULD_CRASH_LOOP), tv.perform(setup, ActionKind.RESTORE))
        assertEquals(3, tv.state(setup).enabled)
        // Once the stock launcher is back, it is allowed.
        assertTrue((tv.perform(stock, ActionKind.RESTORE) as ActionOutcome.Finished).isClean)
        assertTrue((tv.perform(setup, ActionKind.RESTORE) as ActionOutcome.Finished).isClean)
    }

    @Test
    fun theSetupAppCannotBeTurnedOnWhileATurnOffOfTheStockLauncherMayStillLand() {
        val tv = Tv()
        tv.fake.setState(setup, PackageState(installed = true, enabled = 3, stopped = false))
        tv.journal.add(
            JournalEntry("late", 1L, stock, ActionKind.DISABLE, PackageState(true, 0, false), PackageState(true, 3, false), listOf(Operation.DISABLE_USER), status = EntryStatus.PENDING, uncertainSend = true),
        )
        assertEquals(ActionOutcome.Rejected(RejectReason.WOULD_CRASH_LOOP), tv.perform(setup, ActionKind.RESTORE))
        assertEquals(3, tv.state(setup).enabled)
    }

    @Test
    fun aStockLauncherIsNotUninstalledWhileTheSwapIsOnRecord() {
        val tv = Tv()
        tv.fake.addPackage("com.google.android.tvlauncher", PackageState(true, 0, false))
        // Nothing on record: the usual rules decide.
        tv.swap()
        assertEquals(ActionOutcome.Rejected(RejectReason.LAUNCHER_SWAP_ACTIVE), tv.perform("com.google.android.tvlauncher", ActionKind.UNINSTALL))
        assertEquals(true, tv.state("com.google.android.tvlauncher").installed)
    }

    @Test
    fun noPathOfTheSwapOrTheUndoSendsAnUninstall() {
        val tv = Tv()
        tv.swap()
        tv.undo()
        assertTrue(tv.fake.sent.none { it.contains("pm uninstall") })
        tv.fake.installExit = 1
        val second = Tv()
        second.fake.installExit = 1
        val outcome = stopped(second.swap())
        assertEquals(SwapStep.INSTALL, outcome.step)
        assertEquals(StopCause.FAILED, outcome.cause)
        assertTrue(second.fake.sent.none { it.contains("uninstall") })
        // The stock launcher and the setup app were never touched.
        listOf(setup, stock, dream).forEach { assertEquals(it, 0, second.state(it).enabled) }
    }

    @Test
    fun anUpdateOfALauncherThatIsAlreadyHomeSetsAndChecksHomeAgainAndLeavesNoRecord() {
        val tv = Tv()
        tv.fake.addPackage(LIGHT_PACKAGE, PackageState(true, 0, false), setOf("HAS_CODE"))
        tv.fake.homeCapable += LIGHT_PACKAGE
        tv.fake.home = LIGHT_PACKAGE
        tv.fake.homeComponentOverride = LauncherPackages.HOME_COMPONENT
        tv.fake.homeCapable.remove(stock)
        tv.fake.installedLauncherVersion = 10001L
        // The installed copy is older; installing makes Android forget the choice of Home.
        tv.fake.installDropsHome = true
        val preview = (tv.preview() as SwapPreview.Ready).plan
        assertTrue(preview.updateOnly)
        done(tv.swap())
        assertEquals(LIGHT_PACKAGE, tv.fake.home)
        assertNull(tv.store.load())
        assertTrue(tv.fake.mutations.contains("set-home ${LauncherPackages.HOME_COMPONENT}"))
        // Nothing of the stock side was touched by an update.
        listOf(setup, stock, dream).forEach { assertEquals(it, 0, tv.state(it).enabled) }
    }

    @Test
    fun theAccessibilityHelpersAndOnlyThoseComeBackAfterAReinstall() {
        val tv = Tv()
        tv.fake.accessibility = "com.other/.Svc:${LIGHT_PACKAGE}/.HomeKeyService"
        tv.fake.installDropsAccessibility = true
        done(tv.swap())
        assertEquals("com.other/.Svc:${LIGHT_PACKAGE}/.HomeKeyService", tv.fake.accessibility)
        // When there were none, the setting is not touched at all.
        val plain = Tv().also { it.fake.accessibility = "com.other/.Svc" }
        done(plain.swap())
        assertEquals("com.other/.Svc", plain.fake.accessibility)
        assertTrue(plain.fake.mutations.none { it.startsWith("accessibility") })
    }

    @Test
    fun aLostAnswerToTheInstallStopsTheRunAndNothingElseIsTouchedUntilItSettles() {
        val tv = Tv()
        tv.fake.landLater = true
        val outcome = stopped(tv.swap())
        assertEquals(SwapStep.INSTALL, outcome.step)
        assertEquals(StopCause.UNCERTAIN, outcome.cause)
        assertEquals(PendingKind.INSTALL, tv.store.load()?.pending?.kind)
        listOf(setup, stock, dream).forEach { assertEquals(it, 0, tv.state(it).enabled) }
        // It has not landed and has not had time to: the next try waits.
        tv.fake.landLater = false
        assertEquals(SwapRefusal.WriteStillPending, refused(tv.swap()))
        // The undo is not offered either while a write is in doubt.
        assertEquals(SwapRefusal.WriteStillPending, refused(tv.undo()))
        // Once the window has passed it could no longer land: the swap goes on.
        tv.now += 11 * 60 * 1000L
        done(tv.swap())
        assertEquals(LIGHT_PACKAGE, tv.fake.home)
    }

    @Test
    fun anOptionalPermissionThatIsRefusedIsNotedAndTheSwapGoesOn() {
        val tv = Tv()
        val grant = com.prototype.tuneupfortv.adb.Commands.grant(
            com.prototype.tuneupfortv.device.PackageName(LIGHT_PACKAGE),
            com.prototype.tuneupfortv.device.PermissionName("android.permission.READ_TV_LISTINGS"),
            DeviceSerial(tv.fake.identity.serial),
        )
        tv.fake.exitCodeOverrides[grant.line] = 255
        val outcome = done(tv.swap())
        assertEquals(setOf(SwapStep.PERMISSIONS), outcome.unconfirmed)
        assertEquals(LIGHT_PACKAGE, tv.fake.home)
    }

    @Test
    fun theWifiReminderAppearsOnlyWhenTheSavedNetworkMayHaveBelongedToTheSetupApp() {
        val owned = Tv().also { it.fake.wifiOwners = "cname=com.google.android.tungsten.setupwraith\n" }
        assertTrue((owned.preview() as SwapPreview.Ready).plan.wifiWarning)
        assertTrue(done(owned.swap()).wifiReminder)
        val other = Tv()
        assertFalse((other.preview() as SwapPreview.Ready).plan.wifiWarning)
        assertFalse(done(other.swap()).wifiReminder)
        // An unreadable answer counts as possibly its own: the warning is shown rather than skipped.
        val unreadable = Tv().also { it.fake.sectionExitOverrides["wifi_owners"] = 1 }
        assertTrue((unreadable.preview() as SwapPreview.Ready).plan.wifiWarning)
    }

    @Test
    fun aLostConnectionBeforeAnythingIsSentIsARefusalNotAStop() {
        val tv = Tv()
        tv.fake.failure = DeviceFailure.Unreachable
        assertTrue(refused(tv.swap()) is SwapRefusal.Device)
        assertNull(tv.store.load())
    }

    @Test
    fun theSwapHoldsTheLockOfChangesAndAPlainActionWaitsForItsTurn() {
        // One lock covers both: after the swap an ordinary action runs normally, and the journal has every step the swap took.
        val tv = Tv()
        done(tv.swap())
        assertTrue(tv.journal.entries.map { it.packageId }.containsAll(listOf(setup, stock, dream)))
        assertTrue((tv.perform(ExecutorHarness.USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).isClean)
    }
}
