package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.DISABLED_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.SECOND_USER_APP
import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.DebloatEntry
import com.prototype.tuneupfortv.device.DebloatGroup
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.ui.DebloatWizard
import com.prototype.tuneupfortv.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Debloat leads the first time as an assistant (choose, confirm, run, result, launcher) and then shows its tabs. */
@OptIn(ExperimentalCoroutinesApi::class)
class DebloatWizardTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)
    private val settings: MemorySettings = MemorySettings(AppSettings(noticeAccepted = true))

    private fun debloatList(vararg entries: DebloatEntry): DebloatList = DebloatList("sony-test-list", "Test list", listOf(DebloatGroup("Group", entries.toList())))

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

    private fun viewModel(list: DebloatList): AppViewModel {
        val viewModel = AppViewModel(
            settingsStorage = settings,
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
            loadDebloatLists = { listOf(list) },
        )
        eventually("connected") { viewModel.uiState.value.session !is SessionState.Connecting }
        eventually("lists read") { viewModel.uiState.value.debloat.lists != null }
        return viewModel
    }

    private val cleanable = debloatList(DebloatEntry(USER_APP, null, true), DebloatEntry(SECOND_USER_APP, null, true))

    private fun AppViewModel.settled() = eventually("the run is over") { uiState.value.debloat.running.not() && uiState.value.connected?.refreshing == false }

    @Test
    fun theFirstTimeTheAssistantLeadsAndStartsOnTheChoice() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        assertEquals(DebloatWizard.PICK, viewModel.uiState.value.debloat.wizard)
        assertFalse(settings.settings.debloatWizardDone)
    }

    @Test
    fun backInTheAssistantGoesOneStepBackAndOnlyLeavesFromTheTabs() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.askDebloat()
        assertTrue(viewModel.uiState.value.debloat.confirming)
        viewModel.onBack()
        assertFalse(viewModel.uiState.value.debloat.confirming)
        assertEquals(DebloatWizard.PICK, viewModel.uiState.value.debloat.wizard)
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)

        viewModel.nextDebloat()
        viewModel.onBack()
        assertEquals(DebloatWizard.PICK, viewModel.uiState.value.debloat.wizard)
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)

        viewModel.onBack()
        assertNull(viewModel.uiState.value.debloat.wizard)
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)

        viewModel.onBack()
        assertEquals(Screen.HOME, viewModel.uiState.value.screen)
    }

    @Test
    fun backFromTheLauncherAfterARunShowsTheReportAndBackAgainClosesIt() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        viewModel.nextDebloat()
        viewModel.onBack()
        assertEquals(DebloatWizard.PICK, viewModel.uiState.value.debloat.wizard)
        assertTrue(viewModel.uiState.value.debloat.toRestore.isNotEmpty())
        viewModel.onBack()
        assertNull(viewModel.uiState.value.debloat.wizard)
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)
    }

    @Test
    fun finishingTheAssistantShowsTheTabsRemembersItAndClosesTheReport() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.askDebloat()
        viewModel.startDebloat()
        viewModel.settled()
        assertTrue(viewModel.uiState.value.debloat.toRestore.isNotEmpty())
        viewModel.nextDebloat()
        assertEquals(DebloatWizard.LAUNCHER, viewModel.uiState.value.debloat.wizard)
        viewModel.finishDebloat()
        val ui = viewModel.uiState.value.debloat
        assertNull(ui.wizard)
        assertTrue("the report is closed with the assistant", ui.statuses.isEmpty() && ui.toRestore.isEmpty() && ui.stopOutcome == null)
        assertTrue(settings.settings.debloatWizardDone)
    }

    @Test
    fun onceFinishedTheDebloatOpensOnItsTabsNextTime() {
        settings.settings = AppSettings(noticeAccepted = true, debloatWizardDone = true)
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        assertNull(viewModel.uiState.value.debloat.wizard)
    }

    @Test
    fun theAssistantCanBeStartedAgainForAnAppThatWasForgotten() {
        settings.settings = AppSettings(noticeAccepted = true, debloatWizardDone = true)
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.restartDebloatWizard()
        val ui = viewModel.uiState.value.debloat
        assertEquals(DebloatWizard.PICK, ui.wizard)
        assertEquals(setOf(USER_APP, SECOND_USER_APP), ui.chosen)
        assertTrue(settings.settings.debloatWizardDone)
    }

    @Test
    fun skippingTheCleaningGoesStraightToTheLauncherAndSendsNothing() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.nextDebloat()
        assertEquals(DebloatWizard.LAUNCHER, viewModel.uiState.value.debloat.wizard)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun aTvThatIsAlreadyCleanStartsTheAssistantAtTheLauncher() {
        val viewModel = viewModel(debloatList(DebloatEntry(DISABLED_USER_APP, null, true)))
        viewModel.openDebloat()
        assertTrue(viewModel.uiState.value.debloat.chosen.isEmpty())
        assertEquals(DebloatWizard.LAUNCHER, viewModel.uiState.value.debloat.wizard)
    }

    @Test
    fun theConfirmationIsAStepOfItsOwnAndGoingBackSendsNothing() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.askDebloat()
        assertTrue(viewModel.uiState.value.debloat.confirming)
        viewModel.cancelDebloat()
        assertFalse(viewModel.uiState.value.debloat.confirming)
        assertEquals(DebloatWizard.PICK, viewModel.uiState.value.debloat.wizard)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun leavingMidwayAndComingBackResumesWhereItWas() {
        val viewModel = viewModel(cleanable)
        viewModel.openDebloat()
        viewModel.nextDebloat()
        viewModel.back()
        viewModel.openDebloat()
        assertEquals(DebloatWizard.LAUNCHER, viewModel.uiState.value.debloat.wizard)
    }
}
