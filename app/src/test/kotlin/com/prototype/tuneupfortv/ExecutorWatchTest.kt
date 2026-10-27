package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.DISABLED_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.JournalEntry
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

/** The post-change watch and the barrier it raises. */
class ExecutorWatchTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun harness(): ExecutorHarness = ExecutorHarness(File(folder.root, "journal"))

    private fun ExecutorHarness.perform(packageId: String, kind: ActionKind): ActionOutcome = runBlocking { executor.perform(packageId, kind) }

    private fun ExecutorHarness.entryOf(outcome: ActionOutcome): JournalEntry = (outcome as ActionOutcome.Finished).entry

    @Test
    fun aPidThatChangesIsAnAnomalyAndTheUserIsAskedNeverChangedAutomatically() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(WatchState.ANOMALY, entry.watch?.state)
        assertTrue(entry.watch?.detail.orEmpty(), entry.watch?.detail.orEmpty().contains("sonysystemservice"))
        // Nobody undid it behind the user's back.
        assertEquals(3, harness.fake.state(USER_APP).enabled)
        assertEquals(listOf("disable-user $USER_APP"), harness.fake.mutations)
    }

    @Test
    fun anOpenAnomalyBlocksNewDisablesButNeverRepairsOrCompensations() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        harness.perform(USER_APP, ActionKind.DISABLE)
        harness.fake.onMutation = null

        assertTrue(harness.perform(SECOND_USER_APP, ActionKind.DISABLE) is ActionOutcome.Blocked)
        // A repair goes through even while blocked.
        assertEquals(EntryStatus.DONE, harness.entryOf(harness.perform(DISABLED_USER_APP, ActionKind.RESTORE)).status)
    }

    @Test
    fun acceptingTheCurrentStateLiftsTheBarrierWithoutSendingAnything() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        val anomaly = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        harness.fake.onMutation = null
        val mutationsBefore = harness.fake.mutations.size
        runBlocking { harness.executor.accept(anomaly.id) }
        assertEquals(EntryStatus.ACCEPTED, harness.journal.entry(anomaly.id)?.status)
        assertEquals(mutationsBefore, harness.fake.mutations.size)
        assertTrue(harness.journal.blocking().isEmpty())
        assertEquals(EntryStatus.DONE, harness.entryOf(harness.perform(SECOND_USER_APP, ActionKind.DISABLE)).status)
    }

    @Test
    fun onlyTheAirplayServerProcessChangingIsDetectedWithoutAnyCrashLine() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.airplayapp:airplayserver"] = listOf(9999) }
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(WatchState.ANOMALY, entry.watch?.state)
        assertTrue(entry.watch?.detail.orEmpty().contains("airplayserver"))
    }

    @Test
    fun aNewCrashAfterTheChangeIsAnAnomalyButAnOldOneIsIgnored() {
        val harness = harness()
        harness.fake.crashes += (harness.fake.clockSeconds - 500) to "E AndroidRuntime: FATAL EXCEPTION: old one"
        harness.fake.onMutation = { _, _ -> harness.fake.crashes += (harness.fake.clockSeconds + 1) to "E AndroidRuntime: FATAL EXCEPTION: main" }
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(WatchState.ANOMALY, entry.watch?.state)
        assertTrue(entry.watch?.detail.orEmpty().contains("new crash"))
        assertFalse(entry.watch?.detail.orEmpty().contains("old one"))
    }

    @Test
    fun aCriticalProcessThatStopsRunningIsAnAnomaly() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids.remove("com.sony.dtv.airplayapp") }
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(WatchState.ANOMALY, entry.watch?.state)
        assertTrue(entry.watch?.detail.orEmpty().contains("stopped running"))
    }

    @Test
    fun aCriticalProcessThatIsNotRunningBeforeADisableBlocksItAndNothingIsSent() {
        val harness = harness()
        harness.fake.pids.remove("com.sony.dtv.airplayapp")
        val outcome = harness.perform(USER_APP, ActionKind.DISABLE)
        assertTrue("$outcome", outcome is ActionOutcome.WatchUnavailable)
        assertTrue(harness.fake.mutations.isEmpty())
        assertTrue(harness.journal.entries.isEmpty())
    }

    @Test
    fun aRestoreWithAnAbsentProcessProceedsAndItAppearingAfterwardsIsNotAnAnomaly() {
        val harness = harness()
        harness.fake.pids.remove("com.sony.dtv.airplayapp")
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.airplayapp"] = listOf(777) }
        val entry = harness.entryOf(harness.perform(DISABLED_USER_APP, ActionKind.RESTORE))
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(WatchState.OK, entry.watch?.state)
    }

    @Test
    fun aHomeLauncherThatIsNotRunningIsNotWatchedAndDoesNotBlock() {
        val harness = harness()
        harness.fake.pids.remove(com.prototype.tuneupfortv.adb.FakeAdbClient.HOME_PACKAGE)
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(WatchState.OK, entry.watch?.state)
        assertFalse(entry.watch!!.baselinePids.containsKey(com.prototype.tuneupfortv.adb.FakeAdbClient.HOME_PACKAGE))
    }

    @Test
    fun aLogcatThatComplainsIsInconclusiveNeverOk() {
        val harness = harness()
        harness.fake.crashLogFails = true
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(EntryStatus.DONE, entry.status)
        assertEquals(WatchState.INCONCLUSIVE, entry.watch?.state)
        // An inconclusive watch stops the next disable just like an anomaly.
        assertTrue(harness.perform(SECOND_USER_APP, ActionKind.DISABLE) is ActionOutcome.Blocked)
    }

    @Test
    fun aPidofThatFailsForRealIsInconclusiveButMissingProcessIsNot() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pidofExitOverrides["airplayptp"] = 2 }
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertEquals(WatchState.INCONCLUSIVE, entry.watch?.state)
    }

    @Test
    fun anAnomalyWinsOverAnInconclusiveResult() {
        val harness = harness()
        harness.fake.crashLogFails = true
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        assertEquals(WatchState.ANOMALY, harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE)).watch?.state)
    }

    @Test
    fun theWatchLastsAsLongAsConfiguredAndReadsTheCrashLogFromTheDeviceClock() {
        val harness = harness().also { it.watchSeconds = 5 }
        val before = harness.fake.clockSeconds
        val entry = harness.entryOf(harness.perform(USER_APP, ActionKind.DISABLE))
        assertTrue(harness.fake.clockSeconds - before >= 5)
        val crashRead = harness.fake.sent.single { it.contains("logcat -d -b crash") }
        assertTrue(crashRead, crashRead.contains("-T ${entry.watch?.baselineEpoch}.000"))
    }

    @Test
    fun theWatchRunsAfterARestoreToo() {
        val harness = harness()
        harness.fake.onMutation = { _, _ -> harness.fake.pids["com.sony.dtv.sonysystemservice"] = listOf(5252) }
        assertEquals(WatchState.ANOMALY, harness.entryOf(harness.perform(DISABLED_USER_APP, ActionKind.RESTORE)).watch?.state)
    }
}

class WatchWindowTest {
    @Test
    fun theWindowIsWallClockTimeEvenWhenEveryReadTakesAWhile() {
        // Regression: the window used to be "N polls", so reads that took a second each made 10 seconds last 17.
        val fake = com.prototype.tuneupfortv.adb.FakeAdbClient(FixtureSnapshot::read)
        fake.secondsPerCall = 1
        val clock = { fake.clockSeconds * 1000 }
        val watch = com.prototype.tuneupfortv.adb.AdbHealthWatch(fake, fake.identity.serial, pause = { fake.advance(it) }, clock = clock)
        val baseline = com.prototype.tuneupfortv.safety.WatchBaseline(
            com.prototype.tuneupfortv.device.Epoch("1"),
            mapOf("com.sony.dtv.sonysystemservice" to listOf(1329), "airplayptp" to listOf(613)),
        )
        val start = fake.clockSeconds
        val outcome = runBlocking { watch.observe(baseline, seconds = 6) }
        val elapsed = fake.clockSeconds - start
        assertEquals(WatchState.OK, outcome.state)
        // 6 seconds of window plus at most one poll and the final crash read, not 6 polls of 2 seconds each.
        assertTrue("the watch took $elapsed s for a 6 s window", elapsed <= 6 + 2 + 1)
        val polls = fake.sent.count { it.contains("for n in") }
        assertTrue("$polls polls", polls in 2..4)
    }

    @Test
    fun allProcessesAreReadInASingleRoundTripPerPoll() {
        val fake = com.prototype.tuneupfortv.adb.FakeAdbClient(FixtureSnapshot::read)
        val watch = com.prototype.tuneupfortv.adb.AdbHealthWatch(fake, fake.identity.serial, pause = { fake.advance(it) }, clock = { fake.clockSeconds * 1000 })
        val names = listOf("com.sony.dtv.sonysystemservice", "com.sony.dtv.airplayapp", "airplayptp")
        val baseline = com.prototype.tuneupfortv.safety.WatchBaseline(com.prototype.tuneupfortv.device.Epoch("1"), names.associateWith { fake.pids.getValue(it) })
        runBlocking { watch.observe(baseline, seconds = 3) }
        assertEquals(3, fake.sent.count { it.contains("for n in") })
        assertTrue(fake.sent.none { it.contains("pidof com.") })
    }
}
