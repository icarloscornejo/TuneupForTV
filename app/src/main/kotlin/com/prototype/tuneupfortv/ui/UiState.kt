package com.prototype.tuneupfortv.ui

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.safety.AcceptResult
import com.prototype.tuneupfortv.safety.ActionKind
import com.prototype.tuneupfortv.safety.ActionOutcome
import com.prototype.tuneupfortv.safety.LauncherSwapRunner
import com.prototype.tuneupfortv.safety.RejectReason
import com.prototype.tuneupfortv.safety.SwapOutcome
import com.prototype.tuneupfortv.safety.SwapPreview
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.SwapStepStatus
import com.prototype.tuneupfortv.session.SessionState
import com.prototype.tuneupfortv.store.AppSettings

/** How the packages and processes screens lay out their items. */
enum class ViewMode(val tag: String) {
    LIST("list"),
    GRID("grid");

    companion object {
        fun fromTag(tag: String): ViewMode = entries.firstOrNull { it.tag == tag } ?: LIST
    }
}

enum class Screen { HOME, PACKAGES, PACKAGE_DETAIL, PROCESSES, HEALTH, DEBLOAT, SETTINGS }

/** Whether the list the user is in is Processes: a package page opened from Processes belongs to it. */
fun List<Screen>.isInProcesses(): Boolean = lastOrNull { it == Screen.PROCESSES || it == Screen.PACKAGES } == Screen.PROCESSES

/** The flow of one action, shown over the rest of the app: ask, work, report. */
sealed interface ActionUi {
    data object None : ActionUi
    data class Confirm(val packageId: String, val kind: ActionKind) : ActionUi

    /** The changes that still need a decision: for each one, keep it as it is, or fix it from the package's page. */
    data object Review : ActionUi

    /** The command is being sent and the watch is running: it cannot be cancelled halfway. */
    data object Running : ActionUi
    data class Result(val outcome: ActionOutcome) : ActionUi

    /** Before stopping what the processes list shows: [targets] are the packages that will be stopped, [notCloseable] the listed processes that will stay as they are. */
    data class ConfirmCloseAll(val targets: List<String>, val notCloseable: List<String> = emptyList()) : ActionUi

    /** Stopping one app after another. It cannot be cancelled halfway. */
    data class ClosingAll(val done: Int, val total: Int) : ActionUi
    data class ClosedAll(val stopped: Int, val failed: Int) : ActionUi

    /** Before changing several packages at once: what will be done and what will be left alone. */
    data class ConfirmBatch(val kind: ActionKind) : ActionUi

    /** One package after another. It cannot be cancelled halfway. */
    data class RunningBatch(val kind: ActionKind, val done: Int, val total: Int) : ActionUi
    data class BatchDone(val result: BatchResult) : ActionUi

    /** "Keep the current state" was refused: the device could not be read, or the journal could not be written. */
    data class AcceptRefused(val result: AcceptResult) : ActionUi
}

/** How a batch ended. A batch stops at the first change that is not clean; the rest are then [untried]. */
data class BatchResult(
    val kind: ActionKind,
    val succeeded: Int,
    /** Left alone because the guard would not allow them. */
    val skipped: Int,
    val untried: Int,
    val stoppedAt: String?,
    val stoppedBecause: ActionOutcome?,
)

/** What one package shows while a debloat or its restore runs. */
sealed interface StepStatus {
    data object Pending : StepStatus
    data object Working : StepStatus
    data object Done : StepStatus
    data class Skipped(val reason: RejectReason) : StepStatus
    data class Failed(val outcome: ActionOutcome?) : StepStatus

    /** The run stopped before this package. */
    data object Untried : StepStatus
    data object Restored : StepStatus

    /** Restoring found it enabled, but its disable was sent without an answer and can still land. */
    data object MayLand : StepStatus
}

enum class DebloatPhase { DISABLING, RESTORING }

/** The views of a debloat list once the assistant is over: what can be disabled, what is already off, what is protected. */
enum class DebloatTab { TO_DISABLE, DISABLED, PROTECTED }

/**
 * The two steps of the assistant that the user moves between by hand: choosing what to clean, and the optional launcher
 * at the end. The steps in between (confirm, run, result) follow from what is going on, and a null assistant means the tabs.
 */
enum class DebloatWizard { PICK, LAUNCHER }

/**
 * The debloat screens: the lists, the one that is open, what is ticked and, while a run is going (or after it),
 * what each package did. [toRestore] is what "Restore" will undo, in the order it was changed; it is kept apart
 * from [statuses], which only shows the last attempt.
 */
data class DebloatUi(
    /** Null until the bundled lists are read. */
    val lists: List<DebloatList>? = null,
    val openList: String? = null,
    val chosen: Set<String> = emptySet(),
    /** The tab the user picked, or null to let the screen choose (what can be disabled, or else what is already off). */
    val tab: DebloatTab? = null,
    /** Already-disabled packages ticked to be switched back on. */
    val chosenEnable: Set<String> = emptySet(),
    val confirming: Boolean = false,
    val running: Boolean = false,
    val phase: DebloatPhase? = null,
    val statuses: Map<String, StepStatus> = emptyMap(),
    val toRestore: List<String> = emptyList(),
    /** The change that stopped the run, shown with the usual report. */
    val stopOutcome: ActionOutcome? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** The journal entry of each package's disable in this run. */
    val runEntries: Map<String, String> = emptyMap(),
    /** Where the assistant is, or null when it is over (or was never asked for) and the screen shows its tabs. */
    val wizard: DebloatWizard? = null,
) {
    val openedList: DebloatList? get() = lists?.firstOrNull { it.fileName == openList }
}

/** The launcher screen: what the swap would do, the run in progress and how it ended. */
data class LauncherUi(
    /** The version of the latest release of the launcher, or null when it is not available (no internet, no repo, no release). */
    val availableVersion: String? = null,
    /** The TV and the latest release have been looked at for this visit. */
    val loaded: Boolean = false,
    val preview: SwapPreview? = null,
    val record: LauncherSwapRunner.RecordState = LauncherSwapRunner.RecordState.None,
    val confirming: Boolean = false,
    val running: Boolean = false,
    /** True while the run in progress (or the last one) is the undo, not the swap. */
    val undoing: Boolean = false,
    val steps: List<SwapStep> = emptyList(),
    val statuses: Map<SwapStep, SwapStepStatus> = emptyMap(),
    val outcome: SwapOutcome? = null,
)

/** Everything the screens draw, as one immutable value. */
data class AppUiState(
    val settings: AppSettings,
    val settingsSaveFailed: Boolean,
    val backStack: List<Screen>,
    val session: SessionState,
    /** The search, filters and sort of the packages and processes screens. */
    val filter: ListFilter,
    val selectedPackage: String?,
    val action: ActionUi,
    /** Choosing several packages on the packages screen: taps select instead of opening. */
    val selecting: Boolean,
    val selection: Set<String>,
    val debloat: DebloatUi = DebloatUi(),
    val launcher: LauncherUi = LauncherUi(),
) {
    val screen: Screen get() = backStack.last()
    val connected: SessionState.Connected? get() = session as? SessionState.Connected
    val language: AppLanguage get() = AppLanguage.fromTag(settings.language)

    /** The view of the list the user is in: Packages and Processes each have their own. */
    val viewMode: ViewMode get() = ViewMode.fromTag(if (backStack.isInProcesses()) settings.processesViewMode else settings.packagesViewMode)
}

/** Everything the screens can ask for. The view model implements it; tests and previews can fake it. */
interface AppActions {
    fun open(screen: Screen)
    fun back()
    fun onBack()
    fun acceptNotice()

    /** The user read the "this model has not been tested" warning: Home hides it for [model] from now on. */
    fun dismissUntested(model: String)
    fun retry()
    fun refresh()
    fun readHealth()
    fun updateFilter(transform: (ListFilter) -> ListFilter)
    fun openPackage(packageId: String)
    fun setOverride(packageId: String, category: Category?)
    fun askAction(packageId: String, kind: ActionKind)
    fun confirmAction()
    fun acceptEntry(entryId: String)
    fun dismissAction()
    fun startSelecting()
    fun stopSelecting()
    fun toggleSelected(packageId: String)
    fun selectAll(packageIds: List<String>)
    fun clearSelection()
    fun askBatch(kind: ActionKind)
    fun confirmBatch()
    fun askCloseAll()
    fun confirmCloseAll()
    fun openReview()
    fun setLanguage(language: AppLanguage)
    fun setViewMode(mode: ViewMode)
    fun openDebloat()
    fun toggleDebloatEntry(packageId: String)
    fun selectDebloatTab(tab: DebloatTab)
    fun toggleDebloatEnable(packageId: String)
    fun enableDebloat()
    fun askDebloat()
    fun cancelDebloat()
    fun startDebloat()
    fun restoreDebloat()
    fun clearDebloatStop()

    /** From the result of a run to the optional launcher step. */
    fun nextDebloat()

    /** Ends the assistant (or a run's report): the screen goes to its tabs and the assistant is not offered again by itself. */
    fun finishDebloat()

    /** Starts the assistant again, for whoever forgot an app. */
    fun restartDebloatWizard()
    fun askLauncher()
    fun cancelLauncher()

    /** Looks again for the latest launcher release (and at the TV). */
    fun retryLauncher()
    fun startLauncher()
    fun undoLauncher()
}
