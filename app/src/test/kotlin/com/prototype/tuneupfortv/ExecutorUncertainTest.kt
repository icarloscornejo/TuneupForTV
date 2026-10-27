package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.Reconciler
import com.prototype.tuneupfortv.safety.WatchState
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Commands whose outcome is not known: lost answers, late landings, the app dying, cancellation, concurrency. */
class ExecutorUncertainTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun harness(): ExecutorHarness = ExecutorHarness(File(folder.root, "journal"))

    private fun ExecutorHarness.perform(packageId: String, kind: ActionKind) = runBlocking { executor.perform(packageId, kind) }
    private fun ExecutorHarness.only(): JournalEntry = journal.entries.single()

    @Test
    fun aLostAnswerKeepsTheEntryPendingAndUncertainInsteadOfFailed() {
        val harness = harness()
        harness.fake.timeoutAfterApply = true
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished
        assertEquals(EntryStatus.PENDING, outcome.entry.status)
        assertTrue(outcome.entry.uncertainSend)
        assertTrue(harness.journal.blocking().isNotEmpty())
        assertTrue(harness.perform(SECOND_USER_APP, ActionKind.DISABLE) is ActionOutcome.Blocked)
    }

    @Test
    fun theDeviceSaysWhatHappenedAfterALostAnswerThatWasApplied() {
        val harness = harness()
        harness.fake.timeoutAfterApply = true
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.reconcile()
        val entry = harness.only()
        assertEquals(EntryStatus.DONE, entry.status)
        // It was applied, but nobody watched: the watch is inconclusive, and that still blocks.
        assertEquals(WatchState.INCONCLUSIVE, entry.watch?.state)
        assertFalse(entry.uncertainSend)
        assertTrue(harness.journal.blocking().isNotEmpty())
    }

    @Test
    fun aLostAnswerThatWasNotAppliedStaysPendingUntilTheSettleWindowPasses() {
        val harness = harness()
        harness.fake.timeoutWithoutApply = true
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.reconcile()
        assertEquals("still might land", EntryStatus.PENDING, harness.only().status)
        harness.nowMillis += Reconciler.SETTLE_WINDOW_MILLIS + 1
        harness.reconcile()
        assertEquals(EntryStatus.FAILED, harness.only().status)
        assertTrue(harness.journal.blocking().isEmpty())
    }

    @Test
    fun aCommandThatLandsAfterTheEntryWasAcceptedReopensItAndTheUserCanFixItWithARestore() {
        val harness = harness()
        harness.fake.landLater = true
        val pending = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        harness.fake.landLater = false
        harness.reconcile()
        assertEquals(EntryStatus.PENDING, harness.only().status) // the device still shows the old state
        runBlocking { harness.executor.accept(pending.id) }
        assertEquals(EntryStatus.ACCEPTED, harness.only().status)

        harness.fake.landPendingMutations() // the disable lands late
        harness.expectState(USER_APP, installed = true, enabled = 3)
        harness.reconcile()
        assertEquals(EntryStatus.NEEDS_REVIEW, harness.only().status)
        assertEquals(WatchState.INCONCLUSIVE, harness.only().watch?.state)
        assertTrue(harness.journal.blocking().isNotEmpty())

        // A restore is never held back by an open entry; keeping the state is what closes it.
        harness.fake.mutations.clear()
        harness.perform(USER_APP, ActionKind.RESTORE)
        assertEquals(listOf("default-state $USER_APP"), harness.fake.mutations)
        harness.expectState(USER_APP, installed = true, enabled = 0)
        runBlocking { harness.executor.accept(pending.id) }
        assertEquals(EntryStatus.ACCEPTED, harness.journal.entry(pending.id)!!.status)
    }

    @Test
    fun anAcceptedEntryDoesNotReopenWhileNothingChangesAfterAcceptingAnAppliedChange() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        val anomaly = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        runBlocking { harness.executor.accept(anomaly.id) }
        repeat(3) { harness.reconcile() }
        harness.restartApp()
        harness.reconcile()
        assertEquals(EntryStatus.ACCEPTED, harness.only().status)
        assertTrue(harness.journal.blocking().isEmpty())
    }

    @Test
    fun anOldAcceptedEntryDoesNotReopenBecauseALaterActionPutThePackageInTheSameState() {
        val harness = harness()
        harness.fake.landLater = true
        val pending = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        harness.fake.landLater = false
        runBlocking { harness.executor.accept(pending.id) }
        harness.nowMillis += 1000
        // A new, real disable of the same package is journaled later.
        harness.fake.pendingClear()
        assertEquals(EntryStatus.DONE, (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry.status)
        harness.reconcile()
        assertEquals(EntryStatus.ACCEPTED, harness.journal.entry(pending.id)?.status)
    }

    @Test
    fun theAppDyingRightAfterTheCommandIsAppliedLeavesAPendingEntryThatTheNextStartResolves() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> throw IllegalStateException("the app was killed") }
        runCatching { harness.perform(USER_APP, ActionKind.DISABLE) }
        harness.fake.onMutation = null
        harness.restartApp()
        assertEquals(EntryStatus.PENDING, harness.only().status)
        assertTrue(harness.only().uncertainSend)
        harness.reconcile()
        assertEquals(EntryStatus.DONE, harness.only().status)
        assertEquals(WatchState.INCONCLUSIVE, harness.only().watch?.state)
    }

    @Test
    fun aCommandThatWasNeverSentBecauseTheAppDiedBeforeIsFailedAtOnce() {
        val harness = harness()
        val entry = JournalEntry(
            id = "never-sent", timestampMillis = 1, packageId = USER_APP, kind = ActionKind.DISABLE,
            previous = harness.fake.state(USER_APP), target = harness.fake.state(USER_APP).copy(enabled = 3),
            steps = listOf(com.prototype.tuneupfortv.safety.Operation.DISABLE_USER), status = EntryStatus.PENDING,
            watch = com.prototype.tuneupfortv.safety.WatchRecord(WatchState.RUNNING, "1", emptyMap()),
        )
        harness.journal.add(entry)
        harness.reconcile()
        assertEquals(EntryStatus.FAILED, harness.only().status)
    }

    @Test
    fun anEntryWhoseWatchWasRunningWhenTheAppDiedBecomesInconclusive() {
        val harness = harness()
        harness.fake.timeoutAfterApply = true
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.reconcile()
        harness.restartApp()
        harness.reconcile()
        assertEquals(WatchState.INCONCLUSIVE, harness.only().watch?.state)
    }

    @Test
    fun aChangeInTheMiddleIsFinishedEvenIfTheCallerIsCancelled() {
        val harness = harness()
        lateinit var job: Job
        harness.fake.onMutation = { _, _ -> job.cancel() } // the user leaves the screen while the command runs
        runBlocking {
            job = launch(start = CoroutineStart.LAZY) { harness.executor.perform(USER_APP, ActionKind.DISABLE) }
            job.start()
            job.join()
        }
        val entry = harness.only()
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(WatchState.OK, entry.watch?.state)
        harness.expectState(USER_APP, installed = true, enabled = 3)
    }

    @Test
    fun twoActionsAtTheSameTimeRunOneAfterTheOtherWithoutOverlappingTheirWatch() {
        val harness = harness()
        runBlocking {
            listOf(USER_APP, SECOND_USER_APP).map { async(Dispatchers.Default) { harness.executor.perform(it, ActionKind.DISABLE) } }.awaitAll()
        }
        assertEquals(2, harness.journal.entries.size)
        // Whichever went second was either blocked by the first one's open entry or ran after its watch ended.
        val mutationLines = harness.fake.sent.withIndex().filter { it.value.startsWith("if [") }.map { it.index }
        if (mutationLines.size == 2) {
            val between = harness.fake.sent.subList(mutationLines[0], mutationLines[1])
            assertTrue("the first watch must finish before the second change", between.any { it.contains("logcat -d -b crash") })
        }
        assertTrue(harness.journal.entries.all { it.status == EntryStatus.DONE })
    }

    @Test
    fun aConnectionThatCannotComeUpAtSendTimeIsAFailureBecauseNothingWasSent() {
        val harness = harness()
        harness.fake.mutationFailure = DeviceFailure.Unreachable
        val entry = (harness.perform(USER_APP, ActionKind.DISABLE) as ActionOutcome.Finished).entry
        assertEquals(EntryStatus.FAILED, entry.status)
        assertFalse(entry.uncertainSend)
        assertTrue(harness.journal.blocking().isEmpty())
        harness.expectState(USER_APP, installed = true, enabled = 0)
    }
}
