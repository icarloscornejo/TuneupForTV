package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.Operation
import com.prototype.tuneupfortv.safety.Reconciler
import com.prototype.tuneupfortv.store.JsonJournalStore
import com.prototype.tuneupfortv.safety.JournalStoreException
import com.prototype.tuneupfortv.safety.WatchRecord
import com.prototype.tuneupfortv.safety.WatchState
import com.prototype.tuneupfortv.store.JsonStoreException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalLogicTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun state(enabled: Int, installed: Boolean = true) = PackageState(installed, enabled, stopped = false)

    private fun entry(
        id: String = "e1",
        packageId: String = "com.a.b",
        time: Long = 1_000,
        kind: ActionKind = ActionKind.DISABLE,
        previous: PackageState = state(0),
        target: PackageState? = state(3),
        status: EntryStatus = EntryStatus.DONE,
        watch: WatchState? = WatchState.OK,
        uncertain: Boolean = false,
        settleUntil: Long? = null,
        accepted: PackageState? = null,
    ) = JournalEntry(
        id, time, packageId, kind, previous, target, listOf(Operation.DISABLE_USER), 0, status,
        watch?.let { WatchRecord(it, "100", mapOf("p" to listOf(1))) }, uncertain, settleUntil, accepted,
    )

    // region journal on disk

    @Test
    fun entriesSurviveARestart() {
        val journal = Journal(JsonJournalStore(folder.root))
        journal.add(entry())
        journal.update("e1") { it.copy(status = EntryStatus.UNDONE) }
        assertEquals(EntryStatus.UNDONE, Journal(JsonJournalStore(folder.root)).entry("e1")?.status)
    }

    @Test
    fun aCorruptJournalIsAnErrorNeverAnEmptyOne() {
        File(folder.root, "journal.json").writeText("{ not json")
        assertThrows(JournalStoreException::class.java) { Journal(JsonJournalStore(folder.root)) }
    }

    @Test
    fun onlyOpenEntriesBlock() {
        val blocking = listOf(
            entry(status = EntryStatus.PENDING),
            entry(status = EntryStatus.NEEDS_REVIEW),
            entry(status = EntryStatus.UNVERIFIED),
            entry(status = EntryStatus.DONE, watch = WatchState.ANOMALY),
            entry(status = EntryStatus.DONE, watch = WatchState.INCONCLUSIVE),
            entry(status = EntryStatus.DONE, watch = WatchState.RUNNING),
        )
        val free = listOf(
            entry(status = EntryStatus.DONE, watch = WatchState.OK),
            entry(status = EntryStatus.FAILED),
            entry(status = EntryStatus.UNDONE, watch = WatchState.ANOMALY),
            entry(status = EntryStatus.ACCEPTED, watch = WatchState.ANOMALY),
        )
        blocking.forEach { assertTrue("$it", it.isBlocking) }
        free.forEach { assertFalse("$it", it.isBlocking) }
    }

    // endregion

    // region reconciliation

    private val now = 5_000_000L

    private fun reconcile(entry: JournalEntry, live: PackageState, later: Boolean = false) = Reconciler.reconcile(entry, live, now, later)

    @Test
    fun aPendingEntryWhoseTargetIsReachedIsDoneButNeverTrustedWithoutAWatch() {
        val result = reconcile(entry(status = EntryStatus.PENDING, watch = WatchState.RUNNING, uncertain = true), state(3))
        assertEquals(EntryStatus.DONE, result.status)
        assertEquals(WatchState.INCONCLUSIVE, result.watch?.state)
        assertFalse(result.uncertainSend)
    }

    @Test
    fun aPendingEntryThatWasNeverSentFailsAtOnceButASentOneWaitsForTheWindow() {
        assertEquals(EntryStatus.FAILED, reconcile(entry(status = EntryStatus.PENDING, uncertain = false), state(0)).status)
        val waiting = entry(status = EntryStatus.PENDING, uncertain = true, settleUntil = now + 1)
        assertEquals(EntryStatus.PENDING, reconcile(waiting, state(0)).status)
        assertEquals(EntryStatus.FAILED, reconcile(waiting.copy(settleUntilMillis = now - 1), state(0)).status)
    }

    @Test
    fun aPendingEntryWhoseStateMatchesNeitherNeedsReview() {
        val result = reconcile(entry(status = EntryStatus.PENDING, uncertain = true), state(4))
        assertEquals(EntryStatus.NEEDS_REVIEW, result.status)
        assertEquals(WatchState.INCONCLUSIVE, result.watch?.state)
    }

    @Test
    fun aPendingForceStopBecomesUnverifiedWithAnOpenWatch() {
        val result = reconcile(entry(kind = ActionKind.FORCE_STOP, target = null, status = EntryStatus.PENDING, uncertain = true), state(0))
        assertEquals(EntryStatus.UNVERIFIED, result.status)
        assertEquals(WatchState.INCONCLUSIVE, result.watch?.state)
        assertTrue(result.isBlocking)
    }

    @Test
    fun aWatchThatWasRunningWhenTheAppDiedIsInconclusive() {
        assertEquals(WatchState.INCONCLUSIVE, reconcile(entry(watch = WatchState.RUNNING), state(3)).watch?.state)
    }

    @Test
    fun aLateLandingReopensOnlyWhenAllItsConditionsHold() {
        val closed = entry(status = EntryStatus.ACCEPTED, uncertain = true, settleUntil = now + 1000, accepted = state(0))
        assertEquals(EntryStatus.NEEDS_REVIEW, reconcile(closed, state(3)).status)
        // Each condition on its own keeps it closed:
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed, state(0)).status) // not at its target
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed.copy(acceptedState = state(3)), state(3)).status) // the user accepted exactly this state
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed, state(3), later = true).status) // a later action explains it
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed.copy(uncertainSend = false), state(3)).status) // not an uncertain send
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed.copy(settleUntilMillis = now - 1), state(3)).status) // the window is over
        assertFalse(reconcile(closed.copy(settleUntilMillis = now - 1), state(3)).uncertainSend)
    }

    @Test
    fun aForceStopIsNeverReopenedByState() {
        val closed = entry(kind = ActionKind.FORCE_STOP, target = null, status = EntryStatus.ACCEPTED, uncertain = true, settleUntil = now + 1000)
        assertEquals(EntryStatus.ACCEPTED, reconcile(closed, state(0)).status)
    }

    // endregion
}
