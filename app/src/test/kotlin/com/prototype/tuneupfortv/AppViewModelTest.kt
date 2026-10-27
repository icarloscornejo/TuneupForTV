package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.ui.ActionUi
import com.prototype.tuneupfortv.ui.AppLanguage
import com.prototype.tuneupfortv.ui.Screen
import com.prototype.tuneupfortv.ui.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)
    private val settings: MemorySettings = MemorySettings()

    @Before
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** Parsing a device read happens on a real background thread, so the tests wait for what they expect, with a limit. */
    private fun eventually(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $description" }
            Thread.sleep(5)
        }
    }

    private fun AppViewModel.awaitConnected() {
        eventually("connected or failed") { uiState.value.session !is SessionState.Connecting }
    }

    private fun AppViewModel.awaitSettled() {
        eventually("no refresh in progress") { uiState.value.connected?.refreshing == false && uiState.value.action !is ActionUi.Running }
    }

    private fun viewModel(): AppViewModel =
        AppViewModel(
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
        )

    @Test
    fun beforeTheNoticeIsAcceptedNothingConnects() {
        settings.settings = AppSettings(noticeAccepted = false)
        val viewModel = viewModel()
        assertEquals(SessionState.Idle, viewModel.uiState.value.session)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun acceptingTheNoticeSavesItAndConnects() {
        settings.settings = AppSettings(noticeAccepted = false)
        val viewModel = viewModel()
        viewModel.acceptNotice()
        viewModel.awaitConnected()
        assertTrue(settings.settings.noticeAccepted)
        assertTrue(viewModel.uiState.value.session is SessionState.Connected)
    }

    @Test
    fun openingProcessesReadsTheTvAgainAndOpeningPackagesDoesNot() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        val afterConnecting = fake.sent.size
        viewModel.open(Screen.PACKAGES)
        assertEquals(afterConnecting, fake.sent.size)
        viewModel.back()
        viewModel.open(Screen.PROCESSES)
        assertTrue("what is running is read again", fake.sent.size > afterConnecting)
    }

    @Test
    fun packagesAndProcessesKeepTheirOwnView() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.setViewMode(ViewMode.GRID)
        assertEquals(ViewMode.GRID, viewModel.uiState.value.viewMode)
        viewModel.back()
        viewModel.open(Screen.PROCESSES)
        assertEquals("Processes does not follow Packages", ViewMode.LIST, viewModel.uiState.value.viewMode)
        viewModel.setViewMode(ViewMode.LIST)
        viewModel.back()
        viewModel.open(Screen.PACKAGES)
        assertEquals(ViewMode.GRID, viewModel.uiState.value.viewMode)
        assertEquals(ViewMode.GRID.tag, settings.settings.packagesViewMode)
        assertEquals(ViewMode.LIST.tag, settings.settings.processesViewMode)
    }

    @Test
    fun dismissingTheUntestedWarningIsSavedPerModel() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.dismissUntested("MiTV-AFMU0")
        viewModel.dismissUntested("MiTV-AFMU0")
        assertEquals(setOf("MiTV-AFMU0"), settings.settings.dismissedUntestedModels)
        assertEquals(setOf("MiTV-AFMU0"), viewModel.uiState.value.settings.dismissedUntestedModels)
    }

    @Test
    fun anAcceptedNoticeConnectsAtOnceAndLandsOnHome() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        assertTrue("${viewModel.uiState.value.session}", viewModel.uiState.value.session is SessionState.Connected)
        assertEquals(listOf(Screen.HOME), viewModel.uiState.value.backStack)
    }

    @Test
    fun backLeavesSelectingBeforeLeavingTheScreen() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.startSelecting()
        viewModel.onBack()
        assertFalse(viewModel.uiState.value.selecting)
        assertEquals(Screen.PACKAGES, viewModel.uiState.value.screen)
        viewModel.onBack()
        assertEquals(Screen.HOME, viewModel.uiState.value.screen)
    }

    @Test
    fun openingAScreenForgetsThePackageButGoingBackKeepsIt() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.openPackage(USER_APP)
        viewModel.back()
        assertEquals(USER_APP, viewModel.uiState.value.selectedPackage)
        viewModel.back()
        viewModel.open(Screen.PACKAGES)
        assertEquals(null, viewModel.uiState.value.selectedPackage)
    }

    @Test
    fun backGoesUpAndStopsAtHome() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.openPackage(USER_APP)
        assertEquals(Screen.PACKAGE_DETAIL, viewModel.uiState.value.screen)
        viewModel.onBack()
        viewModel.onBack()
        viewModel.onBack()
        assertEquals(listOf(Screen.HOME), viewModel.uiState.value.backStack)
    }

    @Test
    fun anActionAsksThenRunsThenReportsAndTheListsCatchUp() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.askAction(USER_APP, ActionKind.DISABLE)
        assertTrue(viewModel.uiState.value.action is ActionUi.Confirm)
        viewModel.confirmAction()
        viewModel.awaitSettled()
        val action = viewModel.uiState.value.action
        assertTrue("$action", action is ActionUi.Result && action.outcome is ActionOutcome.Finished)
        val connected = viewModel.uiState.value.connected!!
        assertEquals(3, connected.snapshot.packages.single { it.id == USER_APP }.state?.enabled)
        assertEquals(1, connected.journalEntries.size)
    }

    @Test
    fun backDoesNothingWhileAChangeIsBeingMadeAndClosesAnActionScreenOtherwise() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.askAction(USER_APP, ActionKind.DISABLE)
        viewModel.onBack()
        assertEquals(ActionUi.None, viewModel.uiState.value.action)
    }

    @Test
    fun keepingTheCurrentStateClosesTheChangeAndTheScreen() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.askAction(USER_APP, ActionKind.DISABLE)
        viewModel.confirmAction()
        viewModel.awaitSettled()
        val entryId = viewModel.uiState.value.connected!!.journalEntries.single().id
        viewModel.acceptEntry(entryId)
        viewModel.awaitSettled()
        assertEquals(ActionUi.None, viewModel.uiState.value.action)
        assertEquals(com.prototype.tuneupfortv.safety.EntryStatus.ACCEPTED, viewModel.uiState.value.connected!!.journalEntries.single().status)
    }

    @Test
    fun theFilterChangesAreKeptInTheState() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.updateFilter { it.copy(query = "sony", showAll = true) }
        assertEquals("sony", viewModel.uiState.value.filter.query)
        assertTrue(viewModel.uiState.value.filter.showAll)
    }

    @Test
    fun theLanguageIsSavedAndShownInTheState() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.setLanguage(AppLanguage.SPANISH)
        assertEquals(AppLanguage.SPANISH.tag, settings.settings.language)
        assertEquals(AppLanguage.SPANISH, viewModel.uiState.value.language)
        assertFalse(viewModel.uiState.value.settingsSaveFailed)
    }

    @Test
    fun aSettingThatCannotBeSavedIsReportedAndNotShownAsChanged() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        settings.failOnSave = true
        viewModel.setLanguage(AppLanguage.SPANISH)
        assertTrue(viewModel.uiState.value.settingsSaveFailed)
        assertEquals(AppSettings().language, viewModel.uiState.value.settings.language)
    }

    @Test
    fun retryReconnectsAfterAFailure() {
        fake.failure = com.prototype.tuneupfortv.device.DeviceFailure.Unreachable
        val viewModel = viewModel()
        viewModel.awaitConnected()
        assertTrue(viewModel.uiState.value.session is SessionState.Failed)
        fake.failure = null
        viewModel.retry()
        viewModel.awaitConnected()
        assertTrue(viewModel.uiState.value.session is SessionState.Connected)
    }

    @Test
    fun forceStopAllAsksFirstThenStopsTheListedAppsOneByOneAndReports() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PROCESSES)
        viewModel.askCloseAll()
        val connected = viewModel.uiState.value.connected!!
        val expected = com.prototype.tuneupfortv.safety.ForceCloseAll.shown(connected.snapshot, connected.classifications, viewModel.uiState.value.filter)
        assertEquals(ActionUi.ConfirmCloseAll(expected), viewModel.uiState.value.action)
        assertTrue("nothing is sent before the confirmation", fake.mutations.isEmpty())
        viewModel.confirmCloseAll()
        viewModel.awaitSettled()
        val done = viewModel.uiState.value.action as ActionUi.ClosedAll
        assertEquals(expected.size, done.stopped)
        assertEquals(0, done.failed)
        assertEquals(expected.map { "force-stop $it" }.sorted(), fake.mutations.sorted())
    }

    @Test
    fun packagesAndProcessesKeepTheirOwnFilters() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.updateFilter { it.copy(query = "sony", showAll = true) }
        viewModel.back()
        viewModel.open(Screen.PROCESSES)
        assertEquals(com.prototype.tuneupfortv.device.ListFilter(), viewModel.uiState.value.filter)
        viewModel.updateFilter { it.copy(query = "tv") }
        viewModel.back()
        viewModel.open(Screen.PACKAGES)
        assertEquals("sony", viewModel.uiState.value.filter.query)
        assertTrue(viewModel.uiState.value.filter.showAll)
    }

    @Test
    fun forceStopAllOnlyStopsWhatTheSearchLeavesOnTheList() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PROCESSES)
        val connected = viewModel.uiState.value.connected!!
        val one = com.prototype.tuneupfortv.safety.ForceCloseAll.shown(connected.snapshot, connected.classifications, viewModel.uiState.value.filter).first()
        viewModel.updateFilter { it.copy(query = one) }
        viewModel.askCloseAll()
        assertEquals(ActionUi.ConfirmCloseAll(listOf(one)), viewModel.uiState.value.action)
    }

    @Test
    fun uninstallingFromThePageOfAnAppGoesBackToTheListBeforeTheReportIsClosed() {
        val viewModel = viewModel()
        viewModel.awaitConnected()
        viewModel.open(Screen.PACKAGES)
        viewModel.openPackage(USER_APP)
        assertEquals(Screen.PACKAGE_DETAIL, viewModel.uiState.value.screen)
        viewModel.askAction(USER_APP, ActionKind.UNINSTALL)
        viewModel.confirmAction()
        viewModel.awaitSettled()
        assertTrue(viewModel.uiState.value.action is ActionUi.Result)
        // The page is already left: closing the report shows the list, never the page of the app that is gone.
        assertEquals(Screen.PACKAGES, viewModel.uiState.value.screen)
    }
}
