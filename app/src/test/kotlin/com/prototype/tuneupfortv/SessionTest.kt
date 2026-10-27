package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.PERSISTENT
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionProblem
import com.prototype.tuneupfortv.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.prototype.tuneupfortv.session.SharedDeviceState

class SessionTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)
    private val journalStore: MemoryJournalStore = MemoryJournalStore()
    private val classificationStorage: MemoryClassificationStorage = MemoryClassificationStorage()
    private var closed: Int = 0

    private val session: DeviceSession = DeviceSession(
        openGateway = { AdbDeviceGateway(fake, Dispatchers.Unconfined, { fake.advance(it) }, { fake.clockSeconds * 1000 }) },
        journalStore = journalStore,
        classificationStorage = classificationStorage,
        ownPackage = ExecutorHarness.OWN_PACKAGE,
        ioDispatcher = Dispatchers.Unconfined,
        computeDispatcher = Dispatchers.Unconfined,
        closeInBackground = { closed++ ; it.run() },
    )

    private fun connected(): SessionState.Connected = session.state.value as SessionState.Connected

    @Test
    fun connectingReadsTheDeviceAndClassifiesEveryPackage() {
        runBlocking { session.connect() }
        val state = connected()
        assertEquals(state.snapshot.packages.size, state.classifications.size)
        assertEquals(Category.ESSENTIAL, state.classifications.getValue(PERSISTENT).category)
        assertTrue(state.journalEntries.isEmpty())
        assertFalse(state.refreshing)
    }

    @Test
    fun anUnreachableDeviceIsAFailureWithItsReason() {
        fake.failure = DeviceFailure.Unreachable
        runBlocking { session.connect() }
        assertEquals(SessionState.Failed(DeviceFailure.Unreachable, null), session.state.value)
    }

    @Test
    fun aDeviceWaitingForTheUserToAllowDebuggingIsNotAFailure() {
        fake.failure = DeviceFailure.Timeout
        runBlocking { session.connect() }
        assertEquals(SessionState.NeedsAuthorization, session.state.value)
    }

    @Test
    fun aCorruptJournalStopsTheConnectionInsteadOfStartingEmpty() {
        journalStore.failOnLoad = true
        runBlocking { session.connect() }
        assertTrue(session.state.value is SessionState.Failed)
    }

    @Test
    fun aChangeShowsUpInTheJournalOfTheState() {
        runBlocking { session.connect() }
        val outcome = runBlocking { session.perform(USER_APP, ActionKind.DISABLE) }
        assertTrue(outcome is ActionOutcome.Finished)
        assertEquals(1, connected().journalEntries.size)
    }

    @Test
    fun aRefreshThatFailsKeepsWhatWasReadAndSaysSo() {
        runBlocking { session.connect() }
        val before = connected().snapshot
        fake.failure = DeviceFailure.Unreachable
        runBlocking { session.refresh() }
        val after = connected()
        assertEquals(before, after.snapshot)
        assertFalse(after.refreshing)
        assertTrue(SessionProblem.REFRESH_FAILED in after.problems)
        fake.failure = null
        runBlocking { session.refresh() }
        assertFalse(SessionProblem.REFRESH_FAILED in connected().problems)
    }

    @Test
    fun aRefreshPicksUpChangesMadeOutsideTheApp() {
        runBlocking { session.connect() }
        fake.setState(USER_APP, com.prototype.tuneupfortv.device.PackageState(installed = true, enabled = 3, stopped = false))
        runBlocking { session.refresh() }
        assertEquals(3, connected().snapshot.packages.single { it.id == USER_APP }.state?.enabled)
    }

    @Test
    fun aJournalThatCannotBeWrittenIsReportedAndNothingIsChanged() {
        runBlocking { session.connect() }
        journalStore.savesLeft = 0
        val outcome = runBlocking { session.perform(USER_APP, ActionKind.DISABLE) }
        assertTrue(outcome is ActionOutcome.JournalFailed)
        assertTrue(SessionProblem.JOURNAL_NOT_WRITTEN in connected().problems)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun aCategoryChoiceIsAppliedAndRememberedButNeverForAnEssentialPackage() {
        runBlocking { session.connect() }
        runBlocking { session.setOverride(USER_APP, Category.VENDOR_SERVICE) }
        assertEquals(Category.VENDOR_SERVICE, connected().classifications.getValue(USER_APP).category)
        assertEquals(mapOf(USER_APP to Category.VENDOR_SERVICE), classificationStorage.overrides)
        runBlocking { session.setOverride(PERSISTENT, Category.USER_INSTALLED) }
        assertEquals(Category.ESSENTIAL, connected().classifications.getValue(PERSISTENT).category)
        assertFalse(PERSISTENT in classificationStorage.overrides)
    }

    @Test
    fun aChoiceThatCannotBeStoredKeepsTheOldCategoryAndSaysSo() {
        runBlocking { session.connect() }
        val before = connected().classifications.getValue(USER_APP).category
        classificationStorage.failOnSave = true
        runBlocking { session.setOverride(USER_APP, Category.VENDOR_SERVICE) }
        assertEquals(before, connected().classifications.getValue(USER_APP).category)
        assertTrue(SessionProblem.CHOICE_NOT_SAVED in connected().problems)
    }

    @Test
    fun closingReleasesTheConnectionAndForgetsTheState() {
        runBlocking { session.connect() }
        session.close()
        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(1, closed)
        assertNull("nothing can be done once closed", runBlocking { session.perform(USER_APP, ActionKind.DISABLE) })
    }

    @Test
    fun healthIsReadAndTheFirstLookOfTheSessionIsKept() {
        runBlocking { session.connect() }
        fake.cpuBusyPercent = 10
        runBlocking { session.readHealth() }
        fake.cpuBusyPercent = 60
        runBlocking { session.readHealth() }
        val health = connected().health
        assertEquals(10, health.atStart?.cpuBusyPercent)
        assertEquals(60, health.now?.cpuBusyPercent)
        assertFalse(health.reading)
        assertTrue(health.now!!.memoryAvailableKb!! > 0)
        assertEquals(0, health.now!!.crashes)
    }

    @Test
    fun recentCrashesAreCounted() {
        runBlocking { session.connect() }
        fake.crashes += fake.clockSeconds - 5 to "E AndroidRuntime: FATAL EXCEPTION: main"
        runBlocking { session.readHealth() }
        assertEquals(1, connected().health.now?.crashes)
        assertEquals(1, connected().health.now?.crashSample?.size)
    }

    @Test
    fun aCrashLogThatCannotBeReadIsUnknownNeverZero() {
        runBlocking { session.connect() }
        fake.crashLogFails = true
        runBlocking { session.readHealth() }
        assertNull(connected().health.now?.crashes)
        assertTrue("the other measures are still shown", connected().health.now?.memoryAvailableKb != null)
    }

    @Test
    fun aHealthReadThatFailsIsReportedAndKeepsTheLastLook() {
        runBlocking { session.connect() }
        runBlocking { session.readHealth() }
        val before = connected().health.now
        fake.failure = DeviceFailure.Unreachable
        runBlocking { session.readHealth() }
        assertEquals(before, connected().health.now)
        assertTrue(SessionProblem.HEALTH_NOT_READ in connected().problems)
    }

    // region what every session of the process shares

    private fun newSession(shared: SharedDeviceState, closeInBackground: (Runnable) -> Unit = { it.run() }): DeviceSession =
        DeviceSession(
            openGateway = { AdbDeviceGateway(fake, Dispatchers.Unconfined, { fake.advance(it) }, { fake.clockSeconds * 1000 }) },
            journalStore = journalStore,
            classificationStorage = classificationStorage,
            ownPackage = ExecutorHarness.OWN_PACKAGE,
            ioDispatcher = Dispatchers.Unconfined,
            computeDispatcher = Dispatchers.Unconfined,
            closeInBackground = closeInBackground,
            shared = shared,
        )

    @Test
    fun aNewSessionSeesTheEntriesTheOldOneWroteBecauseTheyShareTheJournal() {
        val shared = SharedDeviceState(journalStore)
        val first = newSession(shared)
        runBlocking { first.connect() }
        runBlocking { first.perform(ExecutorHarness.USER_APP, ActionKind.DISABLE) }
        // The old session is replaced while its change is still in the journal: the new one starts from the same list.
        val second = newSession(shared)
        runBlocking { second.connect() }
        val entries = (second.state.value as SessionState.Connected).journalEntries
        assertEquals(listOf(ExecutorHarness.USER_APP), entries.map { it.packageId })
    }

    @Test
    fun aJournalThatCannotBeReadIsNotCachedSoTheRetryReadsItAgain() {
        val shared = SharedDeviceState(journalStore)
        val retrying = newSession(shared)
        journalStore.failOnLoad = true
        runBlocking { retrying.connect() }
        assertTrue(retrying.state.value is SessionState.Failed)
        journalStore.failOnLoad = false
        runBlocking { retrying.connect() }
        assertTrue(retrying.state.value is SessionState.Connected)
    }

    @Test
    fun closingWaitsForAChangeThatIsStillRunningBeforeCuttingTheConnection() {
        val shared = SharedDeviceState(journalStore)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val closing = newSession(shared) { runnable -> Thread { runnable.run(); finished.set(true) }.start() }
        runBlocking { closing.connect() }
        // A change holds the lock of the whole process.
        runBlocking { shared.mutex.lock() }
        closing.close()
        Thread.sleep(150)
        assertFalse("the connection was cut while a change was running", finished.get())
        shared.mutex.unlock()
        val deadline = System.currentTimeMillis() + 5_000
        while (!finished.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(finished.get())
    }

    // endregion
}
