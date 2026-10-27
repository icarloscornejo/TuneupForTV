package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.safety.InMemorySwapRecordStore
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapRefusal
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.SwapStepStatus
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)
    private val records: InMemorySwapRecordStore = InMemorySwapRecordStore()
    private var release: LauncherRelease? = LauncherRelease("1.0.1", 10001L, "https://example.test/l.apk", "https://example.test/l.apk.sha256", "0".repeat(64))
    private var apk: LauncherApk? = LauncherApk(ByteArray(8), "1.0.1", 10001L)
    private var lookups: Int = 0
    private var downloads: Int = 0

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

    private fun viewModel(): AppViewModel =
        AppViewModel(
            settingsStorage = MemorySettings(),
            session = DeviceSession(
                openGateway = { AdbDeviceGateway(fake, Dispatchers.Unconfined, { fake.advance(it) }, { fake.clockSeconds * 1000 }) },
                journalStore = MemoryJournalStore(),
                classificationStorage = MemoryClassificationStorage(),
                ownPackage = ExecutorHarness.OWN_PACKAGE,
                ioDispatcher = Dispatchers.Unconfined,
                computeDispatcher = Dispatchers.Unconfined,
                closeInBackground = { it.run() },
                swapStore = records,
            ),
            ioDispatcher = Dispatchers.Unconfined,
            fetchLauncherRelease = { lookups++; release },
            downloadLauncher = { downloads++; apk },
        ).also { viewModel -> eventually("connected") { viewModel.uiState.value.session is SessionState.Connected } }

    /** Opens the Debloat and moves the assistant on to its last step, the launcher, which is how that step is reached. */
    private fun AppViewModel.openLauncherTab() {
        openDebloat()
        nextDebloat()
    }

    @Test
    fun nothingIsLookedUpOnTheNetworkUntilTheLauncherStepIsOpened() {
        val viewModel = viewModel()
        assertEquals(0, lookups)
        assertFalse(viewModel.uiState.value.launcher.loaded)
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        assertEquals(1, lookups)
        assertEquals("1.0.1", viewModel.uiState.value.launcher.availableVersion)
    }

    @Test
    fun noReleaseMeansNotAvailableAndNothingCanStart() {
        release = null
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        val ui = viewModel.uiState.value.launcher
        assertNull(ui.availableVersion)
        assertNull(ui.preview)
        viewModel.askLauncher()
        viewModel.startLauncher()
        assertFalse(viewModel.uiState.value.launcher.confirming)
        assertFalse(viewModel.uiState.value.launcher.running)
        assertTrue(fake.mutations.isEmpty())
        assertEquals(0, downloads)
    }

    @Test
    fun tryingAgainFindsTheReleaseOnceItIsPublished() {
        release = null
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        assertNull(viewModel.uiState.value.launcher.availableVersion)
        release = LauncherRelease("1.0.1", 10001L, "https://example.test/l.apk", "https://example.test/l.apk.sha256", "0".repeat(64))
        viewModel.retryLauncher()
        eventually("looked again") { viewModel.uiState.value.launcher.loaded && viewModel.uiState.value.launcher.availableVersion != null }
        assertTrue(viewModel.uiState.value.launcher.preview is SwapPreview.Ready)
    }

    @Test
    fun aFailedDownloadRefusesTheRunAndSendsNothingToTheTv() {
        apk = null
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        viewModel.askLauncher()
        viewModel.startLauncher()
        eventually("the run ends") { viewModel.uiState.value.launcher.outcome != null && !viewModel.uiState.value.launcher.running }
        val outcome = viewModel.uiState.value.launcher.outcome as SwapOutcome.Refused
        assertEquals(SwapRefusal.DownloadFailed, outcome.refusal)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun openingTheScreenShowsWhatTheSwapWouldDoWithoutChangingAnything() {
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        // The launcher is the last, optional step of the debloat assistant, not a screen of its own.
        assertEquals(Screen.DEBLOAT, viewModel.uiState.value.screen)
        assertEquals(DebloatWizard.LAUNCHER, viewModel.uiState.value.debloat.wizard)
        val preview = viewModel.uiState.value.launcher.preview as SwapPreview.Ready
        assertTrue(SwapStep.HOME in preview.plan.steps)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun askingThenConfirmingRunsTheSwapAndTheScreenKeepsTheReport() {
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        viewModel.askLauncher()
        assertTrue(viewModel.uiState.value.launcher.confirming)
        viewModel.cancelLauncher()
        assertFalse(viewModel.uiState.value.launcher.confirming)
        assertTrue(fake.mutations.isEmpty())
        viewModel.askLauncher()
        viewModel.startLauncher()
        eventually("the run ends") { viewModel.uiState.value.launcher.outcome != null && !viewModel.uiState.value.launcher.running }
        val ui = viewModel.uiState.value.launcher
        assertTrue(ui.outcome is SwapOutcome.Done)
        assertEquals(LauncherPackages.LIGHT, fake.home)
        assertTrue(ui.statuses.values.all { it == SwapStepStatus.DONE })
        // The screen now offers the way back, and the TV's own list caught up.
        assertNotNull(ui.record)
        assertTrue(ui.record is com.prototype.tuneupfortv.safety.LauncherSwapRunner.RecordState.Present)
    }

    @Test
    fun pressingStartTwiceSendsOneInstallOnly() {
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        viewModel.startLauncher()
        viewModel.startLauncher()
        eventually("the run ends") { viewModel.uiState.value.launcher.outcome != null && !viewModel.uiState.value.launcher.running }
        assertEquals(1, fake.mutations.count { it.startsWith("install ") })
    }

    @Test
    fun theUndoPutsTheTvBackAndTheScreenSaysSo() {
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        viewModel.startLauncher()
        eventually("swap done") { viewModel.uiState.value.launcher.outcome is SwapOutcome.Done && !viewModel.uiState.value.launcher.running }
        viewModel.undoLauncher()
        eventually("undo done") { viewModel.uiState.value.launcher.undoing && !viewModel.uiState.value.launcher.running && viewModel.uiState.value.launcher.outcome is SwapOutcome.Done }
        assertEquals(FakeAdbClient.HOME_PACKAGE, fake.home)
        assertTrue(viewModel.uiState.value.launcher.record is com.prototype.tuneupfortv.safety.LauncherSwapRunner.RecordState.None)
        listOf(LauncherPackages.SETUPWRAITH, FakeAdbClient.HOME_PACKAGE, LauncherPackages.DREAMX).forEach { assertEquals(it, 0, fake.state(it).enabled) }
    }

    @Test
    fun aTvOutsideTheScopeShowsWhyAndNothingStarts() {
        fake.addPackage("com.duokan.factorytest", com.prototype.tuneupfortv.device.PackageState(true, 0, false))
        fake.homeCapable += "com.duokan.factorytest"
        val viewModel = viewModel()
        viewModel.openLauncherTab()
        eventually("looked") { viewModel.uiState.value.launcher.loaded }
        val preview = viewModel.uiState.value.launcher.preview as SwapPreview.Refused
        assertTrue(preview.refusal is SwapRefusal.Scope)
        viewModel.askLauncher()
        assertFalse(viewModel.uiState.value.launcher.confirming)
        assertTrue(fake.mutations.isEmpty())
    }
}
