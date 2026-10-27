package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.safety.AcceptResult
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.WatchState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the executor does when the disk, the identity or a half-finished restore does not cooperate. */
class ExecutorRobustnessTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val store: MemoryJournalStore = MemoryJournalStore()

    private fun harness(): ExecutorHarness = ExecutorHarness(File(folder.root, "unused"), journalStoreFactory = { store })

    private fun ExecutorHarness.perform(packageId: String, kind: ActionKind): ActionOutcome = runBlocking { executor.perform(packageId, kind) }

    private fun noCommandWasSent(harness: ExecutorHarness) {
        assertTrue("mutations: ${harness.fake.mutations}", harness.fake.mutations.isEmpty())
        assertTrue("a mutating command was sent", harness.fake.sent.none { it.startsWith("if [") })
    }

    @Test
    fun whenTheJournalCannotBeWrittenNothingIsSentToTheDevice() {
        val harness = harness()
        store.savesLeft = 0
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE)
        assertTrue(outcome is ActionOutcome.JournalFailed)
        noCommandWasSent(harness)
        assertTrue(harness.journal.entries.isEmpty())
        assertTrue("the problem must be published", harness.journal.problem.value != null)
    }

    @Test
    fun whenTheMarkBeforeSendingCannotBeWrittenTheCommandIsNotSent() {
        val harness = harness()
        // The PENDING entry is written; the "about to send" mark is not.
        store.savesLeft = 1
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE)
        assertTrue(outcome is ActionOutcome.JournalFailed)
        noCommandWasSent(harness)
        harness.expectState(USER_APP, installed = true, enabled = 0)
    }

    @Test
    fun aDiskThatFailsAfterTheSendDoesNotHideWhatHappened() {
        val harness = harness()
        // PENDING and the "about to send" mark succeed; everything after fails.
        store.savesLeft = 2
        val entry = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        harness.expectState(USER_APP, installed = true, enabled = 3)
        assertEquals("memory must say what really happened", EntryStatus.DONE, harness.journal.entry(entry.id)?.status)
        assertTrue(harness.journal.problem.value != null)
    }

    @Test
    fun aJournalThatCannotBeReadIsAnErrorAndNeverAnEmptyJournal() {
        store.failOnLoad = true
        val error = runCatching { ExecutorHarness(File(folder.root, "unused"), journalStoreFactory = { store }) }.exceptionOrNull()
        assertTrue(error is com.prototype.tuneupfortv.safety.JournalStoreException)
    }

    @Test
    fun aDeviceWithAGenericSerialStaysReadOnly() {
        val harness = harness()
        harness.fake.identity = harness.fake.identity.copy(serial = "0123456789ABCDEF")
        harness.restartApp()
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE)
        assertEquals(ActionOutcome.Rejected(RejectReason.UNRELIABLE_DEVICE), outcome)
        noCommandWasSent(harness)
    }

    @Test
    fun aRestoreWhoseSecondStepFailsNeedsReviewAndIsNotClosedAsFailed() {
        val harness = harness()
        harness.setState("com.sony.dtv.smarthelp", installed = false, enabled = 3)
        val secondStep = Commands.defaultState(PackageName("com.sony.dtv.smarthelp"), DeviceSerial(harness.fake.identity.serial)).line
        harness.fake.exitCodeOverrides[secondStep] = 1
        val entry = (harness.perform("com.sony.dtv.smarthelp", ActionKind.RESTORE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.NEEDS_REVIEW, entry.status)
        assertEquals(1, entry.stepsDone)
        assertEquals(WatchState.INCONCLUSIVE, entry.watch?.state)
        assertTrue("it must block new disables", entry.isBlocking)
    }

    @Test
    fun acceptingRefusesWhenTheDeviceCannotBeRead() {
        val harness = harness()
        val entry = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        harness.fake.failure = com.prototype.tuneupfortv.device.DeviceFailure.Unreachable
        assertEquals(AcceptResult.DEVICE_UNREADABLE, runBlocking { harness.executor.accept(entry.id) })
        assertTrue("the entry must not be closed", harness.journal.entry(entry.id)?.status != EntryStatus.ACCEPTED)
    }

    @Test
    fun acceptingAnUnknownEntryIsReportedNotIgnored() {
        assertEquals(AcceptResult.NOT_FOUND, runBlocking { harness().executor.accept("nope") })
    }

    @Test
    fun acceptingRecordsTheStateTheUserSaw() {
        val harness = harness()
        val entry = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        assertEquals(AcceptResult.ACCEPTED, runBlocking { harness.executor.accept(entry.id) })
        assertEquals(EntryStatus.ACCEPTED, harness.journal.entry(entry.id)?.status)
        assertEquals(3, harness.journal.entry(entry.id)?.acceptedState?.enabled)
    }
}
