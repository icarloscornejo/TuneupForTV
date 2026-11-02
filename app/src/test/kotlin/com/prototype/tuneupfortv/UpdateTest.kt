package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.ExecutorHarness.Companion.USER_APP
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.ui.ActionUi
import com.prototype.tuneupfortv.ui.UpdateInstallStart
import com.prototype.tuneupfortv.ui.UpdateProblem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
class UpdateTest {
    private val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)

    /** What GitHub answers as the latest release: null is no connection. */
    private var latest: LauncherRelease? = null
    private var downloads = 0
    private var installs = 0
    private var downloadGate: CompletableDeferred<Boolean>? = null
    private var installStart: UpdateInstallStart = UpdateInstallStart.STARTED

    @Before
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun release(code: Long): LauncherRelease =
        LauncherRelease("${code / 10000}.${code / 100 % 100}.${code % 100}", code, "https://example.invalid/app.apk", "https://example.invalid/app.apk.sha256", "00")

    /** [executorDispatcher]: inline by default; a real thread when a test has to keep a change in flight. */
    private fun viewModel(noticeAccepted: Boolean = true, executorDispatcher: CoroutineDispatcher = Dispatchers.Unconfined): AppViewModel {
        val viewModel = AppViewModel(
            settingsStorage = MemorySettings(AppSettings(noticeAccepted = noticeAccepted)),
            session = DeviceSession(
                openGateway = { AdbDeviceGateway(fake, executorDispatcher, { fake.advance(it) }, { fake.clockSeconds * 1000 }) },
                journalStore = MemoryJournalStore(),
                classificationStorage = MemoryClassificationStorage(),
                ownPackage = ExecutorHarness.OWN_PACKAGE,
                ioDispatcher = Dispatchers.Unconfined,
                computeDispatcher = Dispatchers.Unconfined,
                closeInBackground = { it.run() },
            ),
            ioDispatcher = Dispatchers.Unconfined,
            fetchAppRelease = { latest },
            downloadAppUpdate = {
                downloads++
                downloadGate?.await() ?: true
            },
            startAppUpdateInstall = {
                installs++
                installStart
            },
            installedVersionName = "1.0.0",
            installedVersionCode = 10000,
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

    @Test
    fun aNewerReleaseIsOfferedAndAnEqualOrOlderOneIsNot() {
        latest = release(10100)
        val viewModel = viewModel()
        assertEquals("1.1.0", viewModel.uiState.value.update.available?.versionName)

        latest = release(10000)
        viewModel.checkForUpdate()
        assertNull(viewModel.uiState.value.update.available)
        assertTrue(viewModel.uiState.value.update.checked)

        latest = release(900)
        viewModel.checkForUpdate()
        assertNull("an older release is never offered", viewModel.uiState.value.update.available)
    }

    @Test
    fun theLookAtStartIsSilentWhenGithubCannotBeReachedButTheOneAskedForSaysSo() {
        latest = null
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.update.couldNotCheck)
        assertFalse(viewModel.uiState.value.update.checking)

        viewModel.checkForUpdate()
        assertTrue(viewModel.uiState.value.update.couldNotCheck)
    }

    @Test
    fun nothingIsLookedUpBeforeTheNoticeIsAccepted() {
        latest = release(10100)
        val viewModel = viewModel(noticeAccepted = false)
        assertNull(viewModel.uiState.value.update.available)
        assertFalse(viewModel.uiState.value.update.checked)
    }

    @Test
    fun installingAllowsTheInstallOverAdbThenHandsTheFileToTheInstaller() {
        latest = release(10100)
        val viewModel = viewModel()
        viewModel.installUpdate()
        eventually { !viewModel.uiState.value.update.installing }
        assertEquals(1, downloads)
        assertEquals(1, installs)
        assertNull(viewModel.uiState.value.update.problem)
        assertTrue("the switch is sent over the fenced ADB connection", "${ExecutorHarness.OWN_PACKAGE} REQUEST_INSTALL_PACKAGES allow" in fake.mutations)
    }

    @Test
    fun aRepeatedPressStartsOneDownloadOnly() {
        latest = release(10100)
        val viewModel = viewModel()
        downloadGate = CompletableDeferred()
        viewModel.installUpdate()
        viewModel.installUpdate()
        assertEquals(1, downloads)
        downloadGate!!.complete(true)
        eventually { !viewModel.uiState.value.update.installing }
        assertEquals(1, installs)
    }

    @Test
    fun aDownloadThatFailsInstallsNothingAndSaysSo() {
        latest = release(10100)
        val viewModel = viewModel()
        downloadGate = CompletableDeferred(false)
        viewModel.installUpdate()
        eventually { !viewModel.uiState.value.update.installing }
        assertEquals(UpdateProblem.DOWNLOAD, viewModel.uiState.value.update.problem)
        assertEquals(0, installs)
    }

    @Test
    fun anInstallerThatNeedsThePermissionIsReported() {
        latest = release(10100)
        installStart = UpdateInstallStart.NEEDS_PERMISSION
        val viewModel = viewModel()
        viewModel.installUpdate()
        eventually { !viewModel.uiState.value.update.installing }
        assertEquals(UpdateProblem.NEEDS_PERMISSION, viewModel.uiState.value.update.problem)
    }

    @Test
    fun theUpdateIsRefusedWhileABatchIsChangingTheTv() {
        latest = release(10100)
        val viewModel = viewModel(executorDispatcher = Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The disable is held on the TV, so the batch is still running when the update is asked for.
        fake.onMutation = { _, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        viewModel.startSelecting()
        viewModel.toggleSelected(USER_APP)
        viewModel.askBatch(ActionKind.DISABLE)
        viewModel.confirmBatch()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertTrue(viewModel.uiState.value.action is ActionUi.RunningBatch)

        viewModel.installUpdate()
        assertEquals("nothing was downloaded or handed over", 0, downloads + installs)
        assertFalse(viewModel.uiState.value.update.installing)
        assertNotNull(viewModel.uiState.value.update.available)

        release.countDown()
        eventually { viewModel.uiState.value.action is ActionUi.BatchDone }
    }
}
