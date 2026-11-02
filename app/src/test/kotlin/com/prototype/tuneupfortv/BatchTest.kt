package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.PERSISTENT
import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.BatchPlanner
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.ui.ActionUi
import com.prototype.tuneupfortv.ui.BatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)

    @Before
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(): AppViewModel {
        val viewModel = AppViewModel(
            settingsStorage = MemorySettings(AppSettings(noticeAccepted = true)),
            session = DeviceSession(
                openGateway = { AdbDeviceGateway(fake, Dispatchers.Unconfined, { fake.advance(it) }, { fake.clockSeconds * 1000 }) },
                journalStore = MemoryJournalStore(),
                classificationStorage = MemoryClassificationStorage(),
                ownPackage = ExecutorHarness.OWN_PACKAGE,
                ioDispatcher = Dispatchers.Unconfined,
                computeDispatcher = Dispatchers.Unconfined,
                closeInBackground = { it.run() },
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )
        eventually { viewModel.uiState.value.session !is SessionState.Connecting }
        return viewModel
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(5)
        }
    }

    private fun AppViewModel.settled() = eventually { uiState.value.connected?.refreshing == false && uiState.value.action.let { it !is ActionUi.RunningBatch && it != ActionUi.Running } }

    @Test
    fun theBatchPlanLeavesProtectedAndUnchangeablePackagesAlone() {
        val snapshot = ExecutorHarness(java.io.File("unused")).snapshot()
        val classifications = Classifier.classify(snapshot, ClassificationContext(DeviceProfiles.SONY, ExecutorHarness.OWN_PACKAGE))
        val plan = BatchPlanner.plan(ActionKind.DISABLE, setOf(USER_APP, PERSISTENT, ExecutorHarness.DISABLED_USER_APP), snapshot.packages, classifications, emptySet())
        assertEquals(listOf(USER_APP), plan.runnable)
        assertEquals(RejectReason.ESSENTIAL, plan.skipped[PERSISTENT])
        assertEquals(RejectReason.ALREADY_DISABLED, plan.skipped[ExecutorHarness.DISABLED_USER_APP])
    }

    @Test
    fun aForceStopBatchSkipsWhatIsNotRunning() {
        val snapshot = ExecutorHarness(java.io.File("unused")).snapshot()
        val classifications = Classifier.classify(snapshot, ClassificationContext(DeviceProfiles.SONY, ExecutorHarness.OWN_PACKAGE))
        val plan = BatchPlanner.plan(ActionKind.FORCE_STOP, setOf(USER_APP), snapshot.packages, classifications, running = emptySet())
        assertTrue(plan.runnable.isEmpty())
        assertEquals(RejectReason.NOT_RUNNING, plan.skipped[USER_APP])
    }

    @Test
    fun selectingAndConfirmingDisablesEachPackageAndReports() {
        val viewModel = viewModel()
        viewModel.startSelecting()
        viewModel.toggleSelected(USER_APP)
        viewModel.toggleSelected(SECOND_USER_APP)
        viewModel.toggleSelected(PERSISTENT)
        viewModel.askBatch(ActionKind.DISABLE)
        assertTrue("nothing is sent before the confirmation", fake.mutations.isEmpty())
        viewModel.confirmBatch()
        viewModel.settled()
        val done = (viewModel.uiState.value.action as ActionUi.BatchDone).result
        assertEquals(2, done.succeeded)
        assertEquals(1, done.skipped) // the protected one
        assertNull(done.stoppedAt)
        assertEquals(listOf("disable-user $USER_APP", "disable-user $SECOND_USER_APP").sorted(), fake.mutations.sorted())
        assertTrue("the selection is cleared", viewModel.uiState.value.selection.isEmpty() && !viewModel.uiState.value.selecting)
    }

    @Test
    fun aBatchStopsAtTheFirstChangeThatIsNotCleanAndLeavesTheRestUntouched() {
        val viewModel = viewModel()
        viewModel.startSelecting()
        viewModel.selectAll(listOf(USER_APP, SECOND_USER_APP))
        viewModel.askBatch(ActionKind.DISABLE)
        // The first disable shakes a critical process: the batch must not go on to the second.
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.confirmBatch()
        viewModel.settled()
        val done = (viewModel.uiState.value.action as ActionUi.BatchDone).result
        assertEquals(0, done.succeeded)
        assertEquals(1, done.untried)
        assertEquals(USER_APP, done.stoppedAt)
        assertEquals(1, fake.mutations.size)
        val entry = viewModel.uiState.value.connected!!.journalEntries.single()
        assertTrue(entry.isBlocking && entry.status == EntryStatus.DONE)
    }

    private fun batchResult(viewModel: AppViewModel): BatchResult = (viewModel.uiState.value.action as ActionUi.BatchDone).result

    private fun AppViewModel.reportReady() =
        eventually { uiState.value.action is ActionUi.BatchDone && uiState.value.connected?.refreshing == false }

    /** Both user apps selected, and the first disable shakes a critical process, so the batch stops there. */
    private fun stoppedBatch(): AppViewModel {
        val viewModel = viewModel()
        viewModel.startSelecting()
        viewModel.selectAll(listOf(USER_APP, SECOND_USER_APP))
        viewModel.askBatch(ActionKind.DISABLE)
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.confirmBatch()
        viewModel.reportReady()
        fake.onMutation = null
        assertEquals(USER_APP, batchResult(viewModel).stoppedAt)
        return viewModel
    }

    @Test
    fun aPackageTheTvRefusesIsSkippedOverWithItsErrorAndTheBatchGoesOn() {
        val viewModel = viewModel()
        val line = Commands.disableUser(PackageName(USER_APP), DeviceSerial(fake.identity.serial)).line
        fake.exitCodeOverrides[line] = 255
        fake.stderrOverrides[line] = "SecurityException: Shell cannot change component state"
        viewModel.startSelecting()
        viewModel.selectAll(listOf(USER_APP, SECOND_USER_APP))
        viewModel.askBatch(ActionKind.DISABLE)
        viewModel.confirmBatch()
        viewModel.reportReady()
        val done = batchResult(viewModel)
        assertEquals(1, done.succeeded)
        assertNull("a refusal does not stop the batch", done.stoppedAt)
        assertTrue(USER_APP in done.failed)
        assertEquals("only what reached the TV can be undone", listOf(SECOND_USER_APP), done.changed)
        assertTrue(done.failedToUndo.isEmpty())
        val entry = viewModel.uiState.value.connected!!.journalEntries.first { it.packageId == USER_APP }
        assertEquals(EntryStatus.FAILED, entry.status)
        assertTrue(entry.failureDetail!!.contains("SecurityException: Shell cannot change component state"))
    }

    @Test
    fun anUninstallBatchThatWorkedIsNotCalledFailed() {
        val viewModel = viewModel()
        viewModel.startSelecting()
        viewModel.selectAll(listOf(USER_APP, SECOND_USER_APP))
        viewModel.askBatch(ActionKind.UNINSTALL)
        viewModel.confirmBatch()
        viewModel.reportReady()
        val done = batchResult(viewModel)
        assertEquals(2, done.succeeded)
        assertTrue(done.failed.isEmpty())
        assertEquals(0, done.untried)
    }

    @Test
    fun leavingTheStoppedChangeAsIsKeepsTheReportAndContinueRunsTheRestOnce() {
        val viewModel = stoppedBatch()
        assertEquals(listOf(SECOND_USER_APP), batchResult(viewModel).untriedIds)
        val entryId = (batchResult(viewModel).stoppedBecause as ActionOutcome.Finished).entry.id
        viewModel.acceptEntry(entryId)
        eventually { viewModel.uiState.value.action is ActionUi.BatchDone && viewModel.uiState.value.connected?.openEntries.orEmpty().isEmpty() }

        // A repeated press of the remote must not start a second run.
        viewModel.continueBatch()
        viewModel.continueBatch()
        viewModel.reportReady()
        val done = batchResult(viewModel)
        assertEquals(1, done.succeeded)
        assertEquals(0, done.untried)
        assertEquals(listOf(USER_APP, SECOND_USER_APP), done.changed)
        assertEquals(1, fake.mutations.count { it == "disable-user $SECOND_USER_APP" })
    }

    @Test
    fun undoingTheFailedClosesTheStopSoTheRestCanContinueAndUndoAllCoversTheRest() {
        val viewModel = stoppedBatch()
        viewModel.restoreBatch(onlyFailed = true)
        viewModel.reportReady()
        val afterFirstUndo = batchResult(viewModel)
        assertNull("the change that stopped the batch is settled once it is back", afterFirstUndo.stoppedAt)
        assertEquals(1, afterFirstUndo.undone)
        assertTrue(afterFirstUndo.changed.isEmpty())
        assertTrue(viewModel.uiState.value.connected!!.openEntries.isEmpty())
        assertEquals("default-state $USER_APP", fake.mutations.last())

        viewModel.continueBatch()
        viewModel.reportReady()
        assertEquals(listOf(SECOND_USER_APP), batchResult(viewModel).changed)

        viewModel.restoreBatch(onlyFailed = false)
        viewModel.reportReady()
        val afterUndoAll = batchResult(viewModel)
        assertTrue(afterUndoAll.changed.isEmpty())
        assertEquals(2, afterUndoAll.undone)
        assertEquals("default-state $SECOND_USER_APP", fake.mutations.last())
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() {
        val viewModel = viewModel()
        viewModel.startSelecting()
        viewModel.toggleSelected(USER_APP)
        viewModel.askBatch(ActionKind.DISABLE)
        viewModel.dismissAction()
        assertTrue(fake.mutations.isEmpty())
        assertEquals(setOf(USER_APP), viewModel.uiState.value.selection)
    }
}
