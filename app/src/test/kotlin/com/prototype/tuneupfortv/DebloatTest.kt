package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.PERSISTENT
import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.DebloatEntry
import com.prototype.tuneupfortv.device.DebloatGroup
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.ui.Screen
import com.prototype.tuneupfortv.ui.StepStatus
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
class DebloatTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)

    private val testList: DebloatList = DebloatList(
        fileName = "sony-test-list",
        title = "Test list",
        groups = listOf(
            DebloatGroup(
                "Group",
                listOf(
                    DebloatEntry(USER_APP, null, suggested = true),
                    DebloatEntry(SECOND_USER_APP, null, suggested = true),
                    DebloatEntry(PERSISTENT, null, suggested = false),
                    DebloatEntry(ExecutorHarness.DISABLED_USER_APP, null, suggested = true),
                ),
            ),
        ),
    )

    @Before
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $description" }
            Thread.sleep(5)
        }
    }

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
            loadDebloatLists = { listOf(testList) },
        )
        eventually("connected") { viewModel.uiState.value.session !is SessionState.Connecting }
        eventually("lists read") { viewModel.uiState.value.debloat.lists != null }
        viewModel.openDebloat()
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)
        assertEquals(testList.fileName, viewModel.uiState.value.debloat.openList)
        return viewModel
    }

    private fun AppViewModel.settled() =
        eventually("the run is over") { uiState.value.debloat.running.not() && uiState.value.connected?.refreshing == false }

    @Test
    fun onlyWhatAppliesStartsTickedAndAProtectedPackageIsSkippedWhenForced() {
        val viewModel = viewModel()
        assertEquals(setOf(USER_APP, SECOND_USER_APP), viewModel.uiState.value.debloat.chosen)
        viewModel.toggleDebloatEntry(PERSISTENT)
        viewModel.askDebloat()
        assertTrue(viewModel.uiState.value.debloat.confirming)
        assertTrue("nothing is sent before the confirmation", fake.mutations.isEmpty())
        viewModel.startDebloat()
        viewModel.settled()
        val ui = viewModel.uiState.value.debloat
        assertEquals(StepStatus.Done, ui.statuses[USER_APP])
        assertEquals(StepStatus.Done, ui.statuses[SECOND_USER_APP])
        assertEquals(StepStatus.Skipped(RejectReason.ESSENTIAL), ui.statuses[PERSISTENT])
        assertEquals(listOf(USER_APP, SECOND_USER_APP), ui.toRestore)
        assertEquals(listOf("disable-user $USER_APP", "disable-user $SECOND_USER_APP").sorted(), fake.mutations.sorted())
    }

    @Test
    fun aRunThatShakesTheSystemStopsLeavesTheRestUntriedAndRestoresTheCulpritFirst() {
        val viewModel = viewModel()
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        val stopped = viewModel.uiState.value.debloat
        assertTrue(stopped.statuses[USER_APP] is StepStatus.Failed)
        assertEquals(StepStatus.Untried, stopped.statuses[SECOND_USER_APP])
        assertEquals("the package that stopped the run can be restored", listOf(USER_APP), stopped.toRestore)
        assertTrue(stopped.stopOutcome is ActionOutcome.Finished)

        fake.onMutation = null
        viewModel.restoreDebloat()
        viewModel.settled()
        val restored = viewModel.uiState.value.debloat
        assertTrue("after undoing everything the screen starts over", restored.statuses.isEmpty() && restored.stopOutcome == null)
        assertTrue(restored.toRestore.isEmpty())
        assertTrue(restored.chosen.containsAll(setOf(USER_APP, SECOND_USER_APP)))
        assertEquals("default-state $USER_APP", fake.mutations.last())
    }

    @Test
    fun nothingStartsWhileAChangeWaitsForADecision() {
        val viewModel = viewModel()
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        fake.onMutation = null
        val sent = fake.mutations.size
        viewModel.askDebloat()
        assertTrue(!viewModel.uiState.value.debloat.confirming)
        viewModel.startDebloat()
        assertEquals(sent, fake.mutations.size)
    }

    @Test
    fun keepingTheStoppedChangeAndDismissingTheReportBothClearIt() {
        val viewModel = viewModel()
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        val entryId = (viewModel.uiState.value.debloat.stopOutcome as ActionOutcome.Finished).entry.id

        viewModel.acceptEntry(entryId)
        eventually("the report is cleared") { viewModel.uiState.value.debloat.stopOutcome == null }
        // Keeping the change does not forget it: it can still be restored.
        assertEquals(listOf(USER_APP), viewModel.uiState.value.debloat.toRestore)

        viewModel.clearDebloatStop()
        assertNull(viewModel.uiState.value.debloat.stopOutcome)
    }

    @Test
    fun aDisableThatCanStillLandStaysOnTheRestoreList() {
        val viewModel = viewModel()
        fake.landLater = true
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        fake.landLater = false
        assertEquals(USER_APP, viewModel.uiState.value.debloat.toRestore.first())

        // The TV still reads it as enabled, so there is nothing to undo yet, but the disable can arrive.
        viewModel.restoreDebloat()
        viewModel.settled()
        assertEquals(StepStatus.MayLand, viewModel.uiState.value.debloat.statuses[USER_APP])
        assertTrue(USER_APP in viewModel.uiState.value.debloat.toRestore)

        fake.landPendingMutations()
        viewModel.refresh()
        eventually("refreshed") { viewModel.uiState.value.connected?.refreshing == false }
        viewModel.restoreDebloat()
        viewModel.settled()
        assertTrue(viewModel.uiState.value.debloat.statuses.isEmpty() && viewModel.uiState.value.debloat.toRestore.isEmpty())
        assertEquals("default-state $USER_APP", fake.mutations.last())
    }

    @Test
    fun aSecondRunKeepsWhatTheFirstOneStillHasToRestore() {
        val viewModel = viewModel()
        fake.onMutation = { _, _ -> fake.pids["com.sony.dtv.sonysystemservice"] = listOf(4242) }
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        fake.onMutation = null
        viewModel.acceptEntry((viewModel.uiState.value.debloat.stopOutcome as ActionOutcome.Finished).entry.id)
        eventually("kept") { viewModel.uiState.value.connected?.openEntries.orEmpty().isEmpty() }

        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        val ui = viewModel.uiState.value.debloat
        assertTrue(USER_APP in ui.toRestore && SECOND_USER_APP in ui.toRestore)
    }

    @Test
    fun anAlreadyDisabledPackageCanBeSwitchedBackOnFromTheList() {
        val viewModel = viewModel()
        val disabled = ExecutorHarness.DISABLED_USER_APP
        // It is not in the "to disable" ticks: it is already off.
        assertTrue(disabled !in viewModel.uiState.value.debloat.chosen)
        viewModel.toggleDebloatEnable(disabled)
        viewModel.enableDebloat()
        viewModel.settled()
        assertEquals("default-state $disabled", fake.mutations.single())
        val ui = viewModel.uiState.value.debloat
        assertTrue("the screen shows the real state again", ui.statuses.isEmpty() && ui.chosenEnable.isEmpty() && !ui.running)
        assertEquals("nothing was disabled by this", emptyList<String>(), ui.toRestore)
    }
}
