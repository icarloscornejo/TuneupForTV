package com.prototype.tuneupfortv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.device.DebloatLists
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.safety.AcceptResult
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.BatchPlanner
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.ForceCloseAll
import com.prototype.tuneupfortv.safety.LauncherSwapRunner
import com.prototype.tuneupfortv.safety.LauncherUndo
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapRefusal
import com.prototype.tuneupfortv.safety.SwapStepStatus
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.store.SettingsStorage
import com.prototype.tuneupfortv.ui.ActionUi
import com.prototype.tuneupfortv.ui.AppActions
import com.prototype.tuneupfortv.ui.AppLanguage
import com.prototype.tuneupfortv.ui.AppUiState
import com.prototype.tuneupfortv.ui.BatchResult
import com.prototype.tuneupfortv.ui.DebloatPhase
import com.prototype.tuneupfortv.ui.DebloatTab
import com.prototype.tuneupfortv.ui.DebloatUi
import com.prototype.tuneupfortv.ui.DebloatWizard
import com.prototype.tuneupfortv.ui.LauncherUi
import com.prototype.tuneupfortv.ui.Screen
import com.prototype.tuneupfortv.ui.StepStatus
import com.prototype.tuneupfortv.ui.ViewMode
import com.prototype.tuneupfortv.ui.isInProcesses
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user is doing, apart from the device: where they are, what they typed, what is being confirmed. */
private data class LocalState(
    val settings: AppSettings,
    val settingsSaveFailed: Boolean = false,
    val backStack: List<Screen> = listOf(Screen.HOME),
    /** Each list keeps its own search, sort and filters: what Processes shows does not follow what Packages is set to. */
    val packagesFilter: ListFilter = ListFilter(),
    val processesFilter: ListFilter = ListFilter(),
    val selectedPackage: String? = null,
    val action: ActionUi = ActionUi.None,
    val selecting: Boolean = false,
    val selection: Set<String> = emptySet(),
    val debloat: DebloatUi = DebloatUi(),
    val launcher: LauncherUi = LauncherUi(),
) {
    private val onProcesses: Boolean get() = backStack.isInProcesses()

    /** The filter of the list the user is in (a package page opened from Processes belongs to Processes). */
    val filter: ListFilter get() = if (onProcesses) processesFilter else packagesFilter

    fun withFilter(transform: (ListFilter) -> ListFilter): LocalState =
        if (onProcesses) copy(processesFilter = transform(processesFilter)) else copy(packagesFilter = transform(packagesFilter))
}

/**
 * Turns what the user does into calls on the [DeviceSession], and the session plus the local state into one
 * [AppUiState]. It builds nothing itself: the stores and the session arrive through the constructor.
 */
class AppViewModel(
    private val settingsStorage: SettingsStorage,
    private val session: DeviceSession,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadDebloatLists: suspend () -> List<DebloatList> = { emptyList() },
    /** The latest release of the launcher on GitHub, or null when it is not available. */
    private val fetchLauncherRelease: suspend () -> LauncherRelease? = { null },
    /** The APK of that release, checked against its published hash, or null when it cannot be had. */
    private val downloadLauncher: suspend (LauncherRelease) -> LauncherApk? = { null },
) : ViewModel(), AppActions {
    private val localState: MutableStateFlow<LocalState> = MutableStateFlow(LocalState(settingsStorage.load()))
    private var connectJob: Job? = null

    val uiState: StateFlow<AppUiState> =
        combine(localState, session.state) { local, sessionState ->
            AppUiState(local.settings, local.settingsSaveFailed, local.backStack, sessionState, local.filter, local.selectedPackage, local.action, local.selecting, local.selection, local.debloat, local.launcher)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppUiState(localState.value.settings, false, localState.value.backStack, session.state.value, localState.value.filter, null, ActionUi.None, false, emptySet()),
        )

    init {
        // From the second launch on there is no intro: connect right away and land on Home.
        if (localState.value.settings.noticeAccepted) connect()
        // The bundled lists are small; read them once so Home can say how many apply to this TV.
        viewModelScope.launch {
            val lists = loadDebloatLists()
            localState.update { it.copy(debloat = it.debloat.copy(lists = lists)) }
        }
    }

    /** The latest release of the launcher found by the last look at the launcher step, or null when it was not available. */
    private var launcherRelease: LauncherRelease? = null

    override fun open(screen: Screen) {
        // A fresh visit starts at the top of the screen; going back from a package's page keeps its row.
        localState.update { it.copy(backStack = it.backStack + screen, selectedPackage = null) }
        // The health screen always shows a fresh look, and so do the processes: what is running changes between visits.
        if (screen == Screen.HEALTH) readHealth()
        if (screen == Screen.PROCESSES) refresh()
    }

    override fun readHealth() {
        viewModelScope.launch { session.readHealth() }
    }

    override fun back() = localState.update { if (it.backStack.size <= 1) it else it.copy(backStack = it.backStack.dropLast(1)) }

    /** Back closes an action screen first, then goes up a level. While a change is being made it does nothing. */
    override fun onBack() {
        // A debloat run keeps its screen: leaving would hide what it is doing to the TV.
        if (localState.value.debloat.running || localState.value.launcher.running) return
        when (localState.value.action) {
            ActionUi.Running, is ActionUi.ClosingAll, is ActionUi.RunningBatch -> Unit
            // Done selecting is the remote's Back key.
            ActionUi.None -> if (localState.value.selecting) stopSelecting() else if (!backInDebloat()) back()
            else -> setAction(ActionUi.None)
        }
    }

    /**
     * Back inside the Debloat assistant goes one step back, not out to Home: it closes the launcher confirmation, goes from
     * the launcher to the step before it, and from the first step (or a report) to the tabs. Returns false when there is
     * nothing of the assistant to go back from, so the caller leaves the screen.
     */
    private fun backInDebloat(): Boolean {
        val local = localState.value
        if (local.backStack.last() != Screen.DEBLOAT) return false
        val ui = local.debloat
        when {
            local.launcher.confirming -> cancelLauncher()
            ui.confirming -> cancelDebloat()
            ui.wizard == DebloatWizard.LAUNCHER -> localState.update { it.copy(debloat = it.debloat.copy(wizard = DebloatWizard.PICK)) }
            ui.wizard == DebloatWizard.PICK && (ui.stopOutcome != null || ui.toRestore.isNotEmpty()) -> finishDebloat()
            ui.wizard == DebloatWizard.PICK -> localState.update { it.copy(debloat = it.debloat.copy(wizard = null, tab = null)) }
            else -> return false
        }
        return true
    }

    /** The notice is the first step of the intro: once accepted, the app connects by itself. */
    override fun acceptNotice() = saveSettings(localState.value.settings.copy(noticeAccepted = true), onSaved = ::connect)

    override fun dismissUntested(model: String) {
        val settings = localState.value.settings
        saveSettings(settings.copy(dismissedUntestedModels = settings.dismissedUntestedModels + model))
    }

    // region connection

    private fun connect() {
        connectJob?.cancel()
        localState.update { it.copy(backStack = listOf(Screen.HOME), action = ActionUi.None) }
        connectJob = viewModelScope.launch {
            // If the unattended job is running, wait for it: two sessions must not write the same journal.
            session.connect()
            // The first look at the TV's health is taken in the background, once Home is already showing.
            session.readHealth()
        }
    }

    override fun retry() {
        if (session.state.value !is SessionState.Idle && session.state.value !is SessionState.Connecting) connect()
    }

    /** Reads the device again, without going back through the intro. */
    override fun refresh() {
        viewModelScope.launch { session.refresh() }
    }

    // endregion

    // region lists

    override fun updateFilter(transform: (ListFilter) -> ListFilter) = localState.update { it.withFilter(transform) }

    override fun openPackage(packageId: String) {
        localState.update { it.copy(selectedPackage = packageId, backStack = it.backStack + Screen.PACKAGE_DETAIL) }
    }

    override fun setOverride(packageId: String, category: Category?) {
        viewModelScope.launch { session.setOverride(packageId, category) }
    }

    // endregion

    // region actions

    override fun askAction(packageId: String, kind: ActionKind) {
        setAction(ActionUi.Confirm(packageId, kind))
        // Whoever stops an app knows what they want: no confirmation screen.
        if (kind == ActionKind.FORCE_STOP) confirmAction()
    }

    /** The user confirmed: run the action, show the result at once, and read the device again. */
    override fun confirmAction() {
        val ui = localState.value.action as? ActionUi.Confirm ?: return
        setAction(ActionUi.Running)
        viewModelScope.launch {
            val outcome = session.perform(ui.packageId, ui.kind)
            // An uninstalled app has no page worth going back to: leave it now, under the report, so closing the report
            // lands on the list instead of showing the page of an app that is gone for a moment.
            if (ui.kind == ActionKind.UNINSTALL && outcome is ActionOutcome.Finished && outcome.entry.status == EntryStatus.DONE &&
                localState.value.backStack.last() == Screen.PACKAGE_DETAIL
            ) {
                back()
            }
            // A successful force stop needs no report: the list shows it. Everything else is told.
            setAction(if (outcome == null || outcome == ActionOutcome.ForceStopped) ActionUi.None else ActionUi.Result(outcome))
            session.refresh()
        }
    }

    /** "Keep the current state": closes an open entry without sending anything, after seeing the device. */
    override fun acceptEntry(entryId: String) {
        viewModelScope.launch {
            val result = session.accept(entryId)
            // A change kept from the debloat report is settled: the report goes away.
            if (result == AcceptResult.ACCEPTED) {
                localState.update { local ->
                    val stop = local.debloat.stopOutcome as? ActionOutcome.Finished
                    if (stop?.entry?.id == entryId) local.copy(debloat = local.debloat.copy(stopOutcome = null)) else local
                }
            }
            val stillOpen = (session.state.value as? SessionState.Connected)?.openEntries.orEmpty().isNotEmpty()
            val inReview = localState.value.action == ActionUi.Review
            setAction(
                when {
                    result != AcceptResult.ACCEPTED -> ActionUi.AcceptRefused(result)
                    // From the review list, stay there while something is still open; from a result, just close.
                    inReview && stillOpen -> ActionUi.Review
                    else -> ActionUi.None
                },
            )
        }
    }

    override fun dismissAction() {
        if (localState.value.debloat.running || localState.value.launcher.running) return
        val action = localState.value.action
        if (action != ActionUi.Running && action !is ActionUi.ClosingAll && action !is ActionUi.RunningBatch) setAction(ActionUi.None)
    }

    override fun openReview() = setAction(ActionUi.Review)

    // region batch

    override fun startSelecting() = localState.update { it.copy(selecting = true, selection = emptySet()) }

    override fun stopSelecting() = localState.update { it.copy(selecting = false, selection = emptySet()) }

    override fun toggleSelected(packageId: String) =
        localState.update { it.copy(selection = if (packageId in it.selection) it.selection - packageId else it.selection + packageId) }

    override fun selectAll(packageIds: List<String>) = localState.update { it.copy(selection = it.selection + packageIds) }

    override fun clearSelection() = localState.update { it.copy(selection = emptySet()) }

    override fun askBatch(kind: ActionKind) {
        if (localState.value.selection.isNotEmpty()) setAction(ActionUi.ConfirmBatch(kind))
    }

    /** Changes the selected packages one at a time, each through the full safe path, and stops at the first change that is not clean. */
    override fun confirmBatch() {
        val ui = localState.value.action as? ActionUi.ConfirmBatch ?: return
        val connected = session.state.value as? SessionState.Connected ?: return
        val plan = planBatch(ui.kind, localState.value.selection, connected)
        setAction(ActionUi.RunningBatch(ui.kind, 0, plan.runnable.size))
        viewModelScope.launch {
            val result = runBatch(ui.kind, plan.runnable, plan.skipped.size, stopOnUnclean = true, onStart = {}) { _, _, attempted ->
                setAction(ActionUi.RunningBatch(ui.kind, attempted, plan.runnable.size))
            }
            localState.update { it.copy(selecting = false, selection = emptySet()) }
            setAction(ActionUi.BatchDone(result))
            session.refresh()
        }
    }

    private fun planBatch(kind: ActionKind, packageIds: Set<String>, connected: SessionState.Connected) =
        BatchPlanner.plan(
            kind,
            packageIds,
            connected.snapshot.packages,
            connected.classifications,
            connected.snapshot.processPackages.values.filterNotNull().toSet(),
        )

    /**
     * Runs [kind] on each package, one at a time through the full safe path. With [stopOnUnclean] the first change that
     * is not clean ends the run and the rest are left untried; without it every package gets its turn (a restore never
     * waits on another). [onStart] and [onStep] let a screen follow along.
     */
    private suspend fun runBatch(
        kind: ActionKind,
        packageIds: List<String>,
        alreadySkipped: Int,
        stopOnUnclean: Boolean,
        onStart: (String) -> Unit,
        onStep: (packageId: String, outcome: ActionOutcome?, attempted: Int) -> Unit,
    ): BatchResult {
        var succeeded = 0
        var skipped = alreadySkipped
        var stoppedAt: String? = null
        var stoppedBecause: ActionOutcome? = null
        var attempted = 0
        for (packageId in packageIds) {
            onStart(packageId)
            val outcome = session.perform(packageId, kind)
            attempted++
            when {
                outcome == ActionOutcome.ForceStopped || (outcome is ActionOutcome.Finished && outcome.isClean) -> succeeded++
                // Changed since the last read: the guard says no now, nothing was touched, the rest can go on.
                outcome is ActionOutcome.Rejected -> skipped++
                stopOnUnclean && stoppedAt == null -> {
                    stoppedAt = packageId
                    stoppedBecause = outcome
                }
            }
            onStep(packageId, outcome, attempted)
            if (stoppedAt != null) break
        }
        return BatchResult(kind, succeeded, skipped, packageIds.size - attempted, stoppedAt, stoppedBecause)
    }

    // endregion

    // region launcher

    /** Looks for the latest launcher release and at the TV for the launcher tab: what the swap would do, and whether one is already on record. */
    private fun lookAtLauncher() {
        if (localState.value.launcher.running) return
        localState.update { it.copy(launcher = it.launcher.copy(loaded = false, confirming = false)) }
        viewModelScope.launch {
            val release = fetchLauncherRelease()
            launcherRelease = release
            val preview = if (release == null) null else session.previewLauncher(release.versionCode)
            val record = session.launcherRecord()
            localState.update { local ->
                if (local.launcher.running) local else local.copy(launcher = local.launcher.copy(loaded = true, availableVersion = release?.versionName, preview = preview, record = record))
            }
        }
    }

    override fun retryLauncher() = lookAtLauncher()

    override fun askLauncher() {
        val connected = session.state.value as? SessionState.Connected ?: return
        localState.update { local ->
            val ui = local.launcher
            if (ui.running || ui.preview !is SwapPreview.Ready || connected.openEntries.isNotEmpty()) local else local.copy(launcher = ui.copy(confirming = true))
        }
    }

    override fun cancelLauncher() = localState.update { it.copy(launcher = it.launcher.copy(confirming = false)) }

    override fun startLauncher() {
        val release = launcherRelease ?: return
        val plan = (localState.value.launcher.preview as? SwapPreview.Ready)?.plan ?: return
        runLauncher(undoing = false, steps = plan.steps) { report ->
            // The APK is downloaded and checked only now, once the user has confirmed; if it fails nothing has been sent to the TV.
            val apk = downloadLauncher(release)
            if (apk == null) SwapOutcome.Refused(SwapRefusal.DownloadFailed) else session.runLauncherSwap(apk, report)
        }
    }

    override fun undoLauncher() {
        val record = (localState.value.launcher.record as? LauncherSwapRunner.RecordState.Present)?.record ?: return
        runLauncher(undoing = true, steps = LauncherUndo.steps(record)) { report -> session.undoLauncherSwap(report) }
    }

    /** One run at a time: the second press of a double click finds the first one already marked as running. */
    private fun runLauncher(undoing: Boolean, steps: List<com.prototype.tuneupfortv.safety.SwapStep>, run: suspend ((com.prototype.tuneupfortv.safety.SwapStep, SwapStepStatus) -> Unit) -> SwapOutcome?) {
        val before = localState.getAndUpdate { local ->
            if (local.launcher.running) local else local.copy(launcher = local.launcher.copy(running = true, undoing = undoing, confirming = false, steps = steps, statuses = emptyMap(), outcome = null))
        }
        if (before.launcher.running) return
        viewModelScope.launch {
            val outcome = run { step, status -> localState.update { it.copy(launcher = it.launcher.copy(statuses = it.launcher.statuses + (step to status))) } }
            // What the swap did changes what the TV reports: read it again before saying anything.
            session.refresh()
            localState.update { it.copy(launcher = it.launcher.copy(running = false, outcome = outcome ?: SwapOutcome.Refused(SwapRefusal.Unreadable))) }
            val release = launcherRelease
            val preview = if (release == null) null else session.previewLauncher(release.versionCode)
            val record = session.launcherRecord()
            localState.update { it.copy(launcher = it.launcher.copy(preview = preview, record = record)) }
        }
    }

    // endregion

    // region debloat

    /** Opens the debloat list that belongs to this TV; there is nothing to choose. */
    override fun openDebloat() {
        val connected = session.state.value as? SessionState.Connected
        localState.update { it.copy(backStack = it.backStack + Screen.DEBLOAT, selectedPackage = null) }
        localState.update { local ->
            val ui = local.debloat
            val fileName = connected?.let { DebloatLists.forDevice(it.snapshot.identity, ui.lists.orEmpty()) }?.fileName
            when {
                ui.running || fileName == null -> local
                // Coming back to the list that was just run keeps its report, what can still be restored, and the step it was on.
                ui.openList == fileName -> local.copy(debloat = ui.copy(confirming = false))
                else -> {
                    // Only what applies to this TV starts ticked.
                    val applicable = if (connected == null) emptySet() else applicableSuggestions(ui, fileName, connected)
                    // The first time, the assistant leads: with nothing to clean it goes straight to the launcher.
                    val wizard = when {
                        local.settings.debloatWizardDone -> null
                        applicable.isEmpty() -> DebloatWizard.LAUNCHER
                        else -> DebloatWizard.PICK
                    }
                    local.copy(debloat = ui.copy(openList = fileName, chosen = applicable, tab = null, chosenEnable = emptySet(), confirming = false, phase = null, statuses = emptyMap(), toRestore = emptyList(), stopOutcome = null, done = 0, total = 0, runEntries = emptyMap(), wizard = wizard))
                }
            }
        }
        if (localState.value.debloat.wizard == DebloatWizard.LAUNCHER) lookAtLauncher()
    }

    /** What the list suggests that applies to this TV: the packages that start ticked. */
    private fun applicableSuggestions(ui: DebloatUi, fileName: String, connected: SessionState.Connected): Set<String> {
        val suggested = ui.lists?.firstOrNull { it.fileName == fileName }
            ?.groups?.flatMap { it.entries }?.filter { it.suggested }?.map { it.packageId }?.toSet().orEmpty()
        return planBatch(ActionKind.DISABLE, suggested, connected).runnable.toSet()
    }

    override fun nextDebloat() {
        if (localState.value.debloat.running || localState.value.launcher.running) return
        localState.update { it.copy(debloat = it.debloat.copy(wizard = DebloatWizard.LAUNCHER)) }
        lookAtLauncher()
    }

    override fun finishDebloat() {
        if (localState.value.debloat.running || localState.value.launcher.running) return
        // The report is closed with the assistant: what the run did is already in the journal, and the tabs show the TV as it is now.
        localState.update { local ->
            local.copy(
                debloat = local.debloat.copy(wizard = null, tab = null, confirming = false, phase = null, statuses = emptyMap(), toRestore = emptyList(), stopOutcome = null, done = 0, total = 0, runEntries = emptyMap()),
                launcher = local.launcher.copy(outcome = null, confirming = false),
            )
        }
        val current = localState.value.settings
        if (!current.debloatWizardDone) saveSettings(current.copy(debloatWizardDone = true))
    }

    override fun restartDebloatWizard() {
        val connected = session.state.value as? SessionState.Connected ?: return
        if (localState.value.debloat.running || localState.value.launcher.running) return
        localState.update { local ->
            val ui = local.debloat
            val fileName = ui.openList ?: return@update local
            val applicable = applicableSuggestions(ui, fileName, connected)
            local.copy(
                debloat = ui.copy(chosen = applicable, tab = null, confirming = false, phase = null, statuses = emptyMap(), toRestore = emptyList(), stopOutcome = null, done = 0, total = 0, runEntries = emptyMap(), wizard = if (applicable.isEmpty()) DebloatWizard.LAUNCHER else DebloatWizard.PICK),
                launcher = local.launcher.copy(outcome = null, confirming = false),
            )
        }
        if (localState.value.debloat.wizard == DebloatWizard.LAUNCHER) lookAtLauncher()
    }

    override fun toggleDebloatEntry(packageId: String) =
        localState.update { local ->
            val ui = local.debloat
            if (ui.running) local else local.copy(debloat = ui.copy(chosen = if (packageId in ui.chosen) ui.chosen - packageId else ui.chosen + packageId))
        }

    override fun selectDebloatTab(tab: DebloatTab) =
        localState.update { local -> if (local.debloat.running) local else local.copy(debloat = local.debloat.copy(tab = tab, confirming = false)) }

    override fun toggleDebloatEnable(packageId: String) =
        localState.update { local ->
            val ui = local.debloat
            if (ui.running) local else local.copy(debloat = ui.copy(chosenEnable = if (packageId in ui.chosenEnable) ui.chosenEnable - packageId else ui.chosenEnable + packageId))
        }

    /**
     * Switches the ticked, already-disabled packages back on, each through the guarded restore. Nothing waits on another,
     * and nothing needs a second question: it only gives back what the TV shipped with.
     */
    override fun enableDebloat() {
        val current = localState.value.debloat
        val list = current.openedList ?: return
        val ids = list.entries.map { it.packageId }.distinct().filter { it in current.chosenEnable }
        if (ids.isEmpty()) return
        val before = localState.getAndUpdate { local ->
            if (local.debloat.running) local else local.copy(debloat = local.debloat.copy(running = true, confirming = false, phase = DebloatPhase.RESTORING, statuses = ids.associateWith { StepStatus.Pending }, stopOutcome = null, done = 0, total = ids.size))
        }
        if (before.debloat.running) return
        viewModelScope.launch {
            runBatch(ActionKind.RESTORE, ids, 0, stopOnUnclean = false, onStart = { setStep(it, StepStatus.Working) }) { packageId, outcome, attempted ->
                val status = when {
                    outcome is ActionOutcome.Finished && outcome.entry.status == EntryStatus.DONE -> StepStatus.Restored
                    outcome is ActionOutcome.Rejected -> StepStatus.Skipped(outcome.reason)
                    else -> StepStatus.Failed(outcome)
                }
                localState.update { local -> local.copy(debloat = local.debloat.copy(statuses = local.debloat.statuses + (packageId to status), done = attempted)) }
            }
            // The tabs show the real state again once the TV is read: what came back moves to "to disable".
            localState.update { it.copy(debloat = it.debloat.copy(running = false, phase = null, statuses = emptyMap(), chosenEnable = emptySet())) }
            session.refresh()
        }
    }

    /** Asks for the last confirmation. Nothing starts while a change still waits for a decision. */
    override fun askDebloat() {
        val connected = session.state.value as? SessionState.Connected ?: return
        localState.update { local ->
            val ui = local.debloat
            if (ui.running || ui.chosen.isEmpty() || connected.openEntries.isNotEmpty()) local else local.copy(debloat = ui.copy(confirming = true))
        }
    }

    override fun cancelDebloat() = localState.update { it.copy(debloat = it.debloat.copy(confirming = false)) }

    override fun clearDebloatStop() = localState.update { it.copy(debloat = it.debloat.copy(stopOutcome = null)) }

    /** Disables the ticked packages one at a time, each through the full safe path, and stops at the first change that is not clean. */
    override fun startDebloat() {
        val connected = session.state.value as? SessionState.Connected ?: return
        if (connected.openEntries.isNotEmpty()) return
        val current = localState.value.debloat
        val list = current.openedList ?: return
        val ordered = list.groups.flatMap { it.entries }.map { it.packageId }.distinct().filter { it in current.chosen }
        if (ordered.isEmpty()) return
        val plan = planBatch(ActionKind.DISABLE, ordered.toSet(), connected)
        val runOrder = ordered.filter { it in plan.runnable }
        // Checked and set in one step, so a repeated press of the remote cannot start a second run.
        val before = localState.getAndUpdate { local ->
            if (local.debloat.running) {
                local
            } else {
                val statuses = ordered.associateWith { packageId -> plan.skipped[packageId]?.let { StepStatus.Skipped(it) } ?: StepStatus.Pending }
                local.copy(debloat = local.debloat.copy(running = true, confirming = false, phase = DebloatPhase.DISABLING, statuses = statuses, stopOutcome = null, done = 0, total = runOrder.size))
            }
        }
        if (before.debloat.running) return
        viewModelScope.launch {
            val result = runBatch(ActionKind.DISABLE, runOrder, plan.skipped.size, stopOnUnclean = true, onStart = { setStep(it, StepStatus.Working) }) { packageId, outcome, attempted ->
                val entry = (outcome as? ActionOutcome.Finished)?.entry
                val status = when {
                    outcome is ActionOutcome.Finished && outcome.isClean -> StepStatus.Done
                    outcome is ActionOutcome.Rejected -> StepStatus.Skipped(outcome.reason)
                    else -> StepStatus.Failed(outcome)
                }
                localState.update { local ->
                    val ui = local.debloat
                    // Anything that was sent and not marked failed may have changed the TV, so Restore covers it.
                    val changed = entry != null && entry.status != EntryStatus.FAILED
                    local.copy(
                        debloat = ui.copy(
                            statuses = ui.statuses + (packageId to status),
                            done = attempted,
                            toRestore = if (changed) ui.toRestore + packageId else ui.toRestore,
                            runEntries = if (entry != null) ui.runEntries + (packageId to entry.id) else ui.runEntries,
                        ),
                    )
                }
            }
            localState.update { local ->
                val ui = local.debloat
                val untried = runOrder.takeLast(result.untried).associateWith { StepStatus.Untried }
                local.copy(debloat = ui.copy(running = false, phase = null, statuses = ui.statuses + untried, stopOutcome = result.stoppedBecause))
            }
            session.refresh()
        }
    }

    /**
     * Undoes this run, the last change first (it is the likeliest to have hurt). Each package goes straight to the
     * guarded restore, which reads the TV live: the snapshot may be older than the run. No package waits on another.
     */
    override fun restoreDebloat() {
        val ids = localState.value.debloat.toRestore.asReversed()
        if (ids.isEmpty()) return
        val before = localState.getAndUpdate { local ->
            if (local.debloat.running) {
                local
            } else {
                local.copy(debloat = local.debloat.copy(running = true, confirming = false, phase = DebloatPhase.RESTORING, statuses = local.debloat.statuses + ids.associateWith { StepStatus.Pending }, stopOutcome = null, done = 0, total = ids.size))
            }
        }
        if (before.debloat.running) return
        viewModelScope.launch {
            runBatch(ActionKind.RESTORE, ids, 0, stopOnUnclean = false, onStart = { setStep(it, StepStatus.Working) }) { packageId, outcome, attempted ->
                val journal = (session.state.value as? SessionState.Connected)?.journalEntries.orEmpty()
                localState.update { local ->
                    val ui = local.debloat
                    val status = restoreStatus(outcome, journal.firstOrNull { it.id == ui.runEntries[packageId] })
                    local.copy(debloat = ui.copy(statuses = ui.statuses + (packageId to status), done = attempted, toRestore = if (status == StepStatus.Restored) ui.toRestore - packageId else ui.toRestore))
                }
            }
            localState.update { local ->
                val ui = local.debloat
                // Everything is back as it was: the screen starts over, ready for another run.
                if (ui.toRestore.isEmpty()) {
                    val suggested = ui.openedList?.entries?.filter { it.suggested }?.map { it.packageId }?.toSet().orEmpty()
                    local.copy(debloat = ui.copy(running = false, phase = null, statuses = emptyMap(), stopOutcome = null, runEntries = emptyMap(), chosen = suggested, done = 0, total = 0))
                } else {
                    local.copy(debloat = ui.copy(running = false, phase = null))
                }
            }
            session.refresh()
        }
    }

    /**
     * A restore is final when it was done, or when there was nothing to undo (the executor then records the
     * request, so the unattended re-apply leaves the package alone). The exception is a disable that was sent
     * without an answer: it can still land minutes later, so the package stays on the list.
     */
    private fun restoreStatus(outcome: ActionOutcome?, disableEntry: com.prototype.tuneupfortv.safety.JournalEntry?): StepStatus =
        when {
            outcome is ActionOutcome.Finished && outcome.entry.status == EntryStatus.DONE -> StepStatus.Restored
            outcome is ActionOutcome.Rejected && outcome.reason == RejectReason.NOTHING_TO_RESTORE ->
                if (disableEntry != null && (disableEntry.status == EntryStatus.PENDING || disableEntry.uncertainSend)) StepStatus.MayLand else StepStatus.Restored
            else -> StepStatus.Failed(outcome)
        }

    private fun setStep(packageId: String, status: StepStatus) =
        localState.update { it.copy(debloat = it.debloat.copy(statuses = it.debloat.statuses + (packageId to status))) }

    // endregion

    /** Asks to stop everything the processes list shows right now, with its search and filters. */
    override fun askCloseAll() {
        val connected = session.state.value as? SessionState.Connected ?: return
        val filter = localState.value.processesFilter
        setAction(
            ActionUi.ConfirmCloseAll(
                ForceCloseAll.shown(connected.snapshot, connected.classifications, filter),
                ForceCloseAll.notCloseable(connected.snapshot, connected.classifications, filter),
            ),
        )
    }

    /** Stops the confirmed list one app at a time, each through the guard, and reports how many went through. */
    override fun confirmCloseAll() {
        val ui = localState.value.action as? ActionUi.ConfirmCloseAll ?: return
        val targets = ui.targets
        setAction(ActionUi.ClosingAll(0, targets.size))
        viewModelScope.launch {
            var stopped = 0
            var failed = 0
            for ((index, packageId) in targets.withIndex()) {
                when (val outcome = session.perform(packageId, ActionKind.FORCE_STOP)) {
                    ActionOutcome.ForceStopped -> stopped++
                    // The connection is gone: the rest were not tried, and they are not counted as stopped.
                    null, is ActionOutcome.DeviceError -> {
                        failed += targets.size - index
                        break
                    }
                    else -> failed++
                }
                setAction(ActionUi.ClosingAll(index + 1, targets.size))
            }
            setAction(ActionUi.ClosedAll(stopped, failed))
            session.refresh()
        }
    }

    private fun setAction(action: ActionUi) = localState.update { it.copy(action = action) }

    // endregion

    /** Saves the language; the activity sees the new value in the state and restarts with it. */
    override fun setLanguage(language: AppLanguage) {
        saveSettings(localState.value.settings.copy(language = language.tag))
    }

    override fun setViewMode(mode: ViewMode) {
        val local = localState.value
        saveSettings(
            if (local.backStack.isInProcesses()) local.settings.copy(processesViewMode = mode.tag) else local.settings.copy(packagesViewMode = mode.tag),
        )
    }

    private fun saveSettings(newSettings: AppSettings, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { settingsStorage.save(newSettings) }
                localState.update { it.copy(settings = newSettings, settingsSaveFailed = false) }
                onSaved()
            } catch (error: IOException) {
                // The change could not be stored, so the screen keeps the previous value and says so.
                localState.update { it.copy(settingsSaveFailed = true) }
            }
        }
    }

    override fun onCleared() {
        connectJob?.cancel()
        session.close()
        super.onCleared()
    }
}
