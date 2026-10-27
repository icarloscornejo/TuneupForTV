package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.DISABLED_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.PERSISTENT
import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.adb.Section
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.WatchState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The guard and the happy paths: what is refused, with zero commands sent, and what goes through. */
class ExecutorSafetyTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun harness(): ExecutorHarness = ExecutorHarness(File(folder.root, "journal"))

    private fun ExecutorHarness.perform(packageId: String, kind: ActionKind): ActionOutcome = runBlocking { executor.perform(packageId, kind) }

    private fun ExecutorHarness.assertNothingWasSent() {
        assertTrue("mutations: ${fake.mutations}", fake.mutations.isEmpty())
        assertFalse("a mutating command was sent", fake.sent.any { it.startsWith("if [") })
        assertTrue("the journal must stay empty", journal.entries.isEmpty())
    }

    @Test
    fun aPersistentPackageIsRejectedBeforeAnyCommandIsSent() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.ESSENTIAL), harness.perform(PERSISTENT, ActionKind.DISABLE))
        assertEquals(ActionOutcome.Rejected(RejectReason.ESSENTIAL), harness.perform(PERSISTENT, ActionKind.FORCE_STOP))
        harness.assertNothingWasSent()
    }

    @Test
    fun thisAppIsRejectedForEveryAction() {
        // A fixture package plays the role of this app.
        val harness = ExecutorHarness(File(folder.root, "journal"), ownPackage = USER_APP)
        ActionKind.entries.forEach { assertEquals(ActionOutcome.Rejected(RejectReason.THIS_APP), harness.perform(USER_APP, it)) }
        harness.assertNothingWasSent()
    }

    @Test
    fun anEmptyTruncatedOrFailedLiveReadRejectsEverythingWithoutSendingAnything() {
        val unreadable = listOf(
            "" to 0,
            "Packages:\n  Package [$USER_APP] (1):\n" to 0, // cut off: no flags, no state
            "Packages:\n" to 0,
            "anything" to 1, // the command itself failed
        )
        unreadable.forEach { (output, exit) ->
            val harness = ExecutorHarness(File(folder.newFolder(), "journal"))
            harness.fake.sectionOutputOverrides[Section.PACKAGE] = output
            harness.fake.sectionExitOverrides[Section.PACKAGE] = exit
            ActionKind.entries.forEach { kind ->
                assertEquals("$kind with '$output'", ActionOutcome.Rejected(RejectReason.UNREADABLE), harness.perform(USER_APP, kind))
            }
            harness.assertNothingWasSent()
        }
    }

    @Test
    fun aFailedReadOfTheHomeLauncherOrTheKeyboardAlsoRejects() {
        listOf(Section.ROLES, Section.HOME, Section.IME).forEach { section ->
            val harness = ExecutorHarness(File(folder.newFolder(), "journal"))
            harness.fake.sectionExitOverrides[section] = 1
            assertEquals(ActionOutcome.Rejected(RejectReason.UNREADABLE), harness.perform(USER_APP, ActionKind.DISABLE))
            harness.assertNothingWasSent()
        }
    }

    @Test
    fun theHomeLauncherAndTheKeyboardAreProtectedFromTheLiveRead() {
        val harness = harness()
        harness.fake.home = USER_APP // the user installed a launcher and made it Home
        assertEquals(ActionOutcome.Rejected(RejectReason.ESSENTIAL), harness.perform(USER_APP, ActionKind.DISABLE))
        harness.assertNothingWasSent()
    }

    @Test
    fun disablingAPackageInStateTwoIsRejected() {
        val harness = harness()
        harness.expectState("com.sony.dtv.my5launcher", installed = true, enabled = 2)
        assertEquals(ActionOutcome.Rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), harness.perform("com.sony.dtv.my5launcher", ActionKind.DISABLE))
        harness.assertNothingWasSent()
    }

    @Test
    fun anUninstalledPackageCannotBeDisabledOrForceStopped() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.NOT_INSTALLED), harness.perform("com.sony.dtv.smarthelp", ActionKind.DISABLE))
        assertEquals(ActionOutcome.Rejected(RejectReason.NOT_INSTALLED), harness.perform("com.sony.dtv.smarthelp", ActionKind.FORCE_STOP))
        harness.assertNothingWasSent()
    }

    @Test
    fun anInvalidPackageIdIsRejectedWithoutTouchingTheDevice() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.UNREADABLE), harness.perform("com.example; rm -rf /", ActionKind.DISABLE))
        assertTrue(harness.fake.sent.isEmpty())
    }

    @Test
    fun aDeadDeviceIsADeviceErrorNotAJournalEntry() {
        val harness = harness()
        harness.fake.failure = DeviceFailure.Unreachable
        assertEquals(ActionOutcome.DeviceError(DeviceFailure.Unreachable), harness.perform(USER_APP, ActionKind.DISABLE))
        assertTrue(harness.journal.entries.isEmpty())
    }

    @Test
    fun anotherDeviceAnsweringTheLiveReadIsRefused() {
        val harness = harness()
        harness.fake.observedSerialOverride = "SOMEONE-ELSE"
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE)
        assertTrue("$outcome", outcome is ActionOutcome.DeviceError && outcome.failure is DeviceFailure.IdentityMismatch)
        harness.assertNothingWasSent()
    }

    @Test
    fun theDeviceSideFenceRefusesACommandBuiltForAnotherSerial() {
        val harness = harness()
        val result = runBlocking { harness.fake.shell(Commands.disableUser(PackageName(USER_APP), com.prototype.tuneupfortv.device.DeviceSerial("OTHER-BOX"))) }
        assertEquals(Commands.IDENTITY_MISMATCH_EXIT, result.exitCode)
        assertTrue(harness.fake.mutations.isEmpty())
        harness.expectState(USER_APP, installed = true, enabled = 0)
    }

    // region the paths that go through

    @Test
    fun disablingANormalAppWorksAndIsJournaledWithItsPreviousState() {
        val harness = harness()
        harness.expectState(USER_APP, installed = true, enabled = 0)
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished
        assertEquals(EntryStatus.DONE, outcome.entry.status)
        assertEquals(WatchState.OK, outcome.entry.watch?.state)
        assertEquals(0, outcome.entry.previous.enabled)
        assertEquals(3, outcome.entry.target?.enabled)
        assertEquals(listOf("disable-user $USER_APP"), harness.fake.mutations)
        assertEquals(3, harness.fake.state(USER_APP).enabled)
        assertTrue(harness.journal.blocking().isEmpty())
    }

    @Test
    fun uninstallingANormalAppIsJournaledAndTheCommandIsFinal() {
        val harness = harness()
        val entry = (harness.perform(USER_APP, ActionKind.UNINSTALL) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(true, entry.previous.installed)
        assertEquals(false, entry.target?.installed)
        assertEquals(listOf("uninstall $USER_APP"), harness.fake.mutations)
        assertFalse(harness.fake.state(USER_APP).installed)
        val sent = harness.fake.sent.single { it.startsWith("if [") }
        assertTrue(sent, sent.contains("pm uninstall --user 0 $USER_APP") && !sent.contains("pm uninstall -k"))
    }

    @Test
    fun anAppRemovedForGoodIsDoneNotInDoubt() {
        val harness = harness()
        harness.fake.removeOnUninstall = true
        val entry = (harness.perform(USER_APP, ActionKind.UNINSTALL) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, entry.status)
        assertTrue(harness.journal.blocking().none { it.status == EntryStatus.NEEDS_REVIEW })
    }

    @Test
    fun anUncertainUninstallThatRemovedTheAppForGoodIsSettledByTheNextRead() {
        val harness = harness()
        harness.fake.removeOnUninstall = true
        harness.fake.timeoutAfterApply = true
        val outcome = harness.perform(USER_APP, ActionKind.UNINSTALL) as ActionOutcome.Finished
        assertEquals(EntryStatus.PENDING, outcome.entry.status)
        harness.fake.timeoutAfterApply = false
        harness.reconcile()
        assertEquals(EntryStatus.DONE, harness.journal.entries.single().status)
    }

    @Test
    fun aProtectedPackageCannotBeUninstalledAndNothingIsSent() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.ESSENTIAL), harness.perform(PERSISTENT, ActionKind.UNINSTALL))
        harness.assertNothingWasSent()
    }

    @Test
    fun anUninstalledAppCanBeRestored() {
        val harness = harness()
        harness.perform(USER_APP, ActionKind.UNINSTALL)
        val restored = (harness.perform(USER_APP, ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, restored.status)
        assertTrue(harness.fake.state(USER_APP).installed)
        assertEquals(listOf("uninstall $USER_APP", "install-existing $USER_APP"), harness.fake.mutations)
    }

    @Test
    fun anUninstallIsNotWatchedAndNoOpenEntryHoldsItBack() {
        val harness = harness()
        // A disable that shook the system leaves an open entry: it holds back every other disable.
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.fake.onMutation = null
        assertTrue(harness.journal.blocking().isNotEmpty())
        assertTrue(harness.perform(SECOND_USER_APP, ActionKind.DISABLE) is ActionOutcome.Blocked)

        val entry = (harness.perform(SECOND_USER_APP, ActionKind.UNINSTALL) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(null, entry.watch)
        assertFalse("an unwatched uninstall leaves nothing to decide", entry.isBlocking)
    }

    @Test
    fun everyMutationCarriesTheIdentityFenceAndUserZero() {
        val harness = harness()
        harness.perform(USER_APP, ActionKind.DISABLE)
        val mutation = harness.fake.sent.single { it.startsWith("if [") }
        assertTrue(mutation, mutation.contains("--user 0 $USER_APP"))
        assertTrue(mutation, mutation.contains("= \"FAKE0001\""))
        assertTrue(mutation, mutation.endsWith("else exit 97; fi"))
    }

    @Test
    fun theJournalEntryIsOnDiskBeforeTheCommandIsSent() {
        val harness = harness()
        var seenPendingOnDisk = false
        harness.fake.onMutation = { packageId, _ ->
            // The entry written before sending, read back from the file by a second journal object.
            val onDisk = com.prototype.tuneupfortv.safety.Journal(com.prototype.tuneupfortv.store.JsonJournalStore(File(folder.root, "journal"))).entries
            seenPendingOnDisk = onDisk.any { it.packageId == packageId && it.status == EntryStatus.PENDING && it.uncertainSend }
        }
        harness.perform(USER_APP, ActionKind.DISABLE)
        assertTrue(seenPendingOnDisk)
    }

    @Test
    fun forceStopJustStopsTheAppWithNoJournalNoBarrierAndNoWatch() {
        val harness = harness()
        harness.fake.pids.remove("com.sony.dtv.airplayapp") // a watch would have refused: a force stop does not need one
        assertEquals(ActionOutcome.ForceStopped, harness.perform(USER_APP, ActionKind.FORCE_STOP))
        assertEquals(listOf("force-stop $USER_APP"), harness.fake.mutations)
        assertTrue(harness.journal.entries.isEmpty())
        assertTrue(harness.fake.sent.none { it.contains("logcat") || it.contains("for n in") })
    }

    @Test
    fun forceStopIsNotHeldBackByAnOpenEntry() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.fake.onMutation = null
        assertEquals(ActionOutcome.ForceStopped, harness.perform(SECOND_USER_APP, ActionKind.FORCE_STOP))
    }

    @Test
    fun aForceStopThatFailsIsReported() {
        val harness = harness()
        harness.fake.exitCodeOverrides[Commands.forceStop(PackageName(USER_APP), DeviceSerial(harness.fake.identity.serial)).line] = 1
        assertEquals(ActionOutcome.ForceStopFailed(1), harness.perform(USER_APP, ActionKind.FORCE_STOP))
    }

    @Test
    fun restoringADisabledPackageGoesBackToTheDefaultState() {
        val harness = harness()
        harness.expectState(DISABLED_USER_APP, installed = true, enabled = 3)
        val entry = (harness.perform(DISABLED_USER_APP, ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(listOf("default-state $DISABLED_USER_APP"), harness.fake.mutations)
        assertEquals(0, harness.fake.state(DISABLED_USER_APP).enabled)
    }

    @Test
    fun restoringSomethingAlreadyEnabledIsRejected() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.NOTHING_TO_RESTORE), harness.perform(USER_APP, ActionKind.RESTORE))
        harness.assertNothingWasSent()
    }

    @Test
    fun restoringFromStateTwoIsRejectedAndNothingIsSent() {
        val harness = harness()
        assertEquals(ActionOutcome.Rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), harness.perform("com.sony.dtv.my5launcher", ActionKind.RESTORE))
        harness.assertNothingWasSent()
    }

    @Test
    fun restoringAnUninstalledPackageInstallsItAndNeverUninstallsAnything() {
        val harness = harness()
        harness.expectState("com.sony.dtv.smarthelp", installed = false, enabled = 0)
        val entry = (harness.perform("com.sony.dtv.smarthelp", ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(listOf("install-existing com.sony.dtv.smarthelp"), harness.fake.mutations)
        assertTrue(harness.fake.state("com.sony.dtv.smarthelp").installed)
    }

    @Test
    fun anUninstalledPackageStillDisabledByTheUserTakesTwoJournaledSteps() {
        val harness = harness()
        harness.setState("com.sony.dtv.smarthelp", installed = false, enabled = 3)
        val entry = (harness.perform("com.sony.dtv.smarthelp", ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(listOf("install-existing com.sony.dtv.smarthelp", "default-state com.sony.dtv.smarthelp"), harness.fake.mutations)
        assertEquals(2, entry.stepsDone)
        assertEquals(EntryStatus.DONE, entry.status)
    }

    @Test
    fun theSoundsLauncherReinstallsButStaysDisabledAndSendsOnlyInstallExisting() {
        val harness = harness()
        harness.expectState("com.sony.dtv.soundslauncher", installed = false, enabled = 2)
        val entry = (harness.perform("com.sony.dtv.soundslauncher", ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(listOf("install-existing com.sony.dtv.soundslauncher"), harness.fake.mutations)
        harness.expectState("com.sony.dtv.soundslauncher", installed = true, enabled = 2)
        assertEquals(EntryStatus.DONE, entry.status)
    }

    @Test
    fun aCommandThatExitsNonZeroIsAFailedEntryNotADoneOne() {
        val harness = harness()
        val line = Commands.disableUser(PackageName(USER_APP), com.prototype.tuneupfortv.device.DeviceSerial("FAKE0001")).line
        harness.fake.exitCodeOverrides[line] = 1
        val entry = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.FAILED, entry.status)
        assertFalse(entry.uncertainSend)
        assertTrue(harness.journal.blocking().isEmpty())
        harness.expectState(USER_APP, installed = true, enabled = 0)
    }

    @Test
    fun theDeviceReadAfterEveryChangeStaysConsistentAndShowsTheNewState() {
        // After each action the app reads the whole device again: that read must not be rejected, and must show the change.
        val harness = harness()
        harness.perform(USER_APP, ActionKind.DISABLE)
        assertEquals(3, harness.snapshot().packages.first { it.id == USER_APP }.state?.enabled)
        harness.perform(USER_APP, ActionKind.RESTORE)
        assertEquals(0, harness.snapshot().packages.first { it.id == USER_APP }.state?.enabled)
        harness.perform("com.sony.dtv.smarthelp", ActionKind.RESTORE)
        val snapshot = harness.snapshot()
        assertFalse("com.sony.dtv.smarthelp" in snapshot.uninstalledForUser0)
        assertEquals(true, snapshot.packages.first { it.id == "com.sony.dtv.smarthelp" }.state?.installed)
        harness.snapshot()
    }

    @Test
    fun theOnlyOperationThatRemovesAPackageIsTheUserZeroUninstall() {
        // Disable, never delete, except for the one removal the user asks for by name.
        val removing = com.prototype.tuneupfortv.safety.Operation.entries.filter { it.name.contains("UNINSTALL") }
        assertEquals(listOf(com.prototype.tuneupfortv.safety.Operation.UNINSTALL_USER), removing)
    }
}
