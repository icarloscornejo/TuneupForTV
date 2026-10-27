package com.prototype.tuneupfortv.session

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.Classification
import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceProfile
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.HealthReading
import com.prototype.tuneupfortv.device.SnapshotException
import com.prototype.tuneupfortv.safety.AcceptResult
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.safety.InMemorySwapRecordStore
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.JournalStore
import com.prototype.tuneupfortv.safety.JournalStoreException
import com.prototype.tuneupfortv.safety.LauncherSwapRunner
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapRecordStore
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.SwapStepStatus
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user taught the classifier. Implemented over files; tests use memory. */
interface ClassificationStorage {
    /** Never throws: an unreadable file starts empty. */
    fun loadOverrides(): Map<String, Category>

    /** Throws [IOException] when the choice cannot be stored. */
    fun saveOverrides(overrides: Map<String, Category>)

    /** Adds the packages that have an icon now to the remembered ones and returns everything remembered. */
    fun rememberIcons(currentlyLaunchable: Set<String>): Set<String>
}

/** Things that went wrong without breaking the session: the screens say so, and the session goes on. */
enum class SessionProblem { REFRESH_FAILED, CHOICE_NOT_SAVED, JOURNAL_NOT_WRITTEN, HEALTH_NOT_READ }

/** The health of the TV when the session started and now, so the screen can show what changed. */
data class HealthState(val atStart: HealthReading? = null, val now: HealthReading? = null, val reading: Boolean = false)

sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState

    /** The device shows "Allow USB debugging?" and waits for the user. */
    data object NeedsAuthorization : SessionState

    /** Either a [DeviceFailure] or, for data that cannot be trusted, a [detail]. */
    data class Failed(val failure: DeviceFailure?, val detail: String?) : SessionState

    data class Connected(
        val identity: DeviceIdentity,
        val snapshot: DeviceSnapshot,
        val profile: DeviceProfile,
        /** The category of every package, by id. */
        val classifications: Map<String, Classification>,
        /** The journal, newest last. */
        val journalEntries: List<JournalEntry>,
        val refreshing: Boolean = false,
        val health: HealthState = HealthState(),
        val problems: Set<SessionProblem> = emptySet(),
    ) : SessionState {
        /** Entries that stop new disables and force-stops until the user resolves them. */
        val openEntries: List<JournalEntry> get() = journalEntries.filter { it.isBlocking }
    }
}

/**
 * One connection to the TV and everything that lives and dies with it: the gateway, the journal and the
 * executor. The screens only see [state], one immutable value that is replaced atomically. Nothing here
 * knows about Android or about ADB: both arrive through [openGateway] and the stores.
 */
class DeviceSession(
    private val openGateway: suspend () -> DeviceGateway,
    private val journalStore: JournalStore,
    private val classificationStorage: ClassificationStorage,
    private val ownPackage: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Closing touches the network, so it never runs on the caller's thread. */
    private val closeInBackground: (Runnable) -> Unit = { Thread(it, "adb-close").start() },
    /** The lock and the journal of the whole process: one of each, whichever session is current. */
    private val shared: SharedDeviceState = SharedDeviceState(journalStore),
    private val swapStore: SwapRecordStore = InMemorySwapRecordStore(),
) {
    private val mutableState: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Idle)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var gateway: DeviceGateway? = null
    private var journal: Journal? = null
    private var executor: ActionExecutor? = null
    private var overrides: Map<String, Category> = emptyMap()
    private var iconPackages: Set<String> = emptySet()

    suspend fun connect() {
        close()
        mutableState.value = SessionState.Connecting
        mutableState.value = try {
            val newGateway = openGateway()
            gateway = newGateway
            val device = newGateway.connect()
            val storedOverrides = withContext(ioDispatcher) { classificationStorage.loadOverrides() }
            val icons = withContext(ioDispatcher) { classificationStorage.rememberIcons(device.snapshot.launchable) }
            overrides = storedOverrides
            iconPackages = icons
            val profile = DeviceProfiles.forDevice(device.snapshot.identity)
            val classifications = classify(device.snapshot, profile)
            val openedJournal = withContext(ioDispatcher) { shared.journal() }
            val newExecutor = newGateway.newExecutor(openedJournal, device.identity, profile, ownPackage, shared.mutex, swapStore)
            newExecutor.reconcile(device.snapshot)
            journal = openedJournal
            executor = newExecutor
            SessionState.Connected(device.identity, device.snapshot, profile, classifications, openedJournal.entries, problems = journalProblems(openedJournal))
        } catch (error: CancellationException) {
            throw error
        } catch (error: DeviceException) {
            when (error.failure) {
                DeviceFailure.AwaitingAuthorization -> SessionState.NeedsAuthorization
                else -> SessionState.Failed(error.failure, null)
            }
        } catch (error: SnapshotException) {
            SessionState.Failed(null, error.problem)
        } catch (error: JournalStoreException) {
            SessionState.Failed(null, error.message)
        } catch (error: IOException) {
            SessionState.Failed(null, error.message ?: error.javaClass.simpleName)
        } catch (error: GeneralSecurityException) {
            SessionState.Failed(null, error.message ?: error.javaClass.simpleName)
        }
    }

    /** Reads the device again. If that fails, what was read before stays on screen and the problem is reported. */
    suspend fun refresh() {
        val connected = mutableState.value as? SessionState.Connected ?: return
        val currentGateway = gateway ?: return
        updateConnected { it.copy(refreshing = true) }
        try {
            val fresh = currentGateway.reload(connected.identity)
            val profile = DeviceProfiles.forDevice(fresh.identity)
            val icons = withContext(ioDispatcher) { classificationStorage.rememberIcons(fresh.launchable) }
            iconPackages = icons
            val classifications = classify(fresh, profile)
            executor?.reconcile(fresh)
            updateConnected {
                it.copy(snapshot = fresh, profile = profile, classifications = classifications, journalEntries = journal?.entries.orEmpty(), refreshing = false, problems = it.problems - SessionProblem.REFRESH_FAILED + journalProblems(journal))
            }
        } catch (error: CancellationException) {
            updateConnected { it.copy(refreshing = false) }
            throw error
        } catch (error: DeviceException) {
            refreshFailed()
        } catch (error: SnapshotException) {
            refreshFailed()
        }
    }

    /** Looks at how the TV is doing. The first look of the session is kept to compare the later ones with. */
    suspend fun readHealth() {
        val currentGateway = gateway ?: return
        updateConnected { it.copy(health = it.health.copy(reading = true)) }
        try {
            val reading = currentGateway.readHealth()
            updateConnected {
                it.copy(health = HealthState(atStart = it.health.atStart ?: reading, now = reading), problems = it.problems - SessionProblem.HEALTH_NOT_READ)
            }
        } catch (error: CancellationException) {
            updateConnected { it.copy(health = it.health.copy(reading = false)) }
            throw error
        } catch (error: DeviceException) {
            updateConnected { it.copy(health = it.health.copy(reading = false), problems = it.problems + SessionProblem.HEALTH_NOT_READ) }
        }
    }

    suspend fun perform(packageId: String, kind: ActionKind): ActionOutcome? {
        val outcome = executor?.perform(packageId, kind) ?: return null
        syncJournal()
        return outcome
    }

    // region the launcher swap

    /** What the swap would do on this TV, without changing anything. Null when there is no connection. */
    suspend fun previewLauncher(versionCode: Long): SwapPreview? = executor?.launcherSwap?.preview(versionCode)

    suspend fun runLauncherSwap(apk: LauncherApk, report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome? {
        val outcome = executor?.launcherSwap?.run(apk, report) ?: return null
        syncJournal()
        return outcome
    }

    suspend fun undoLauncherSwap(report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome? {
        val outcome = executor?.launcherSwap?.undo(report) ?: return null
        syncJournal()
        return outcome
    }

    /** What is on record for the launcher swap, read from disk. */
    fun launcherRecord(): LauncherSwapRunner.RecordState = executor?.launcherSwap?.recordState() ?: LauncherSwapRunner.RecordState.None

    // endregion

    suspend fun accept(entryId: String): AcceptResult {
        val result = executor?.accept(entryId) ?: return AcceptResult.DEVICE_UNREADABLE
        syncJournal()
        return result
    }

    /** Chooses a category by hand, or goes back to the automatic one with null. Never applies to Essential packages. */
    suspend fun setOverride(packageId: String, category: Category?) {
        val connected = mutableState.value as? SessionState.Connected ?: return
        if (connected.classifications[packageId]?.category == Category.ESSENTIAL) return
        val updated = if (category == null) overrides - packageId else overrides + (packageId to category)
        try {
            withContext(ioDispatcher) { classificationStorage.saveOverrides(updated) }
        } catch (error: IOException) {
            // The list keeps showing the previous category, and the screen says the choice was not stored.
            updateConnected { it.copy(problems = it.problems + SessionProblem.CHOICE_NOT_SAVED) }
            return
        }
        overrides = updated
        val classifications = classify(connected.snapshot, connected.profile)
        updateConnected { it.copy(classifications = classifications, problems = it.problems - SessionProblem.CHOICE_NOT_SAVED) }
    }

    fun close() {
        val closing = gateway
        gateway = null
        journal = null
        executor = null
        mutableState.value = SessionState.Idle
        // A swap that is still going keeps its connection until it ends: cutting it halfway is the worst thing that can happen.
        if (closing != null) {
            closeInBackground {
                if (shared.mutex.isLocked) runBlocking { shared.mutex.withLock { } }
                closing.close()
            }
        }
    }

    private suspend fun classify(snapshot: DeviceSnapshot, profile: DeviceProfile): Map<String, Classification> {
        val context = ClassificationContext(profile, ownPackage, overrides, iconPackages)
        return withContext(computeDispatcher) { Classifier.classify(snapshot, context) }
    }

    private fun refreshFailed() {
        updateConnected { it.copy(refreshing = false, problems = it.problems + SessionProblem.REFRESH_FAILED) }
    }

    private fun syncJournal() {
        val currentJournal = journal ?: return
        updateConnected { it.copy(journalEntries = currentJournal.entries, problems = it.problems - SessionProblem.JOURNAL_NOT_WRITTEN + journalProblems(currentJournal)) }
    }

    private fun journalProblems(source: Journal?): Set<SessionProblem> =
        if (source?.problem?.value != null) setOf(SessionProblem.JOURNAL_NOT_WRITTEN) else emptySet()

    private fun updateConnected(transform: (SessionState.Connected) -> SessionState.Connected) {
        mutableState.update { current -> if (current is SessionState.Connected) transform(current) else current }
    }
}
