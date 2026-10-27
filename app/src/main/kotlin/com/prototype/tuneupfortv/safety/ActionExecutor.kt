package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceProfile
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.ProcessName
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The result of one requested action. Everything but [Finished] means nothing was changed on the device. */
sealed interface ActionOutcome {
    /** Open entries stop new disables and force-stops until the user resolves them. */
    data class Blocked(val entries: List<JournalEntry>) : ActionOutcome
    data class Rejected(val reason: RejectReason) : ActionOutcome
    data class WatchUnavailable(val detail: String) : ActionOutcome
    data class JournalFailed(val message: String?) : ActionOutcome
    data class DeviceError(val failure: DeviceFailure) : ActionOutcome

    /** The app was stopped. A force stop leaves nothing to record or to watch: whoever asks for it knows what they want. */
    data object ForceStopped : ActionOutcome

    /** The stop did not go through. [exitCode] is null when the answer never came. */
    data class ForceStopFailed(val exitCode: Int?) : ActionOutcome

    /** Something was changed (or tried): the entry says what, and what the watch concluded. */
    data class Finished(val entry: JournalEntry) : ActionOutcome {
        /** Done, and the system stayed healthy: nothing for the user to look at. */
        val isClean: Boolean get() = entry.status == EntryStatus.DONE && entry.watch?.state == WatchState.OK
    }
}

enum class AcceptResult { ACCEPTED, NOT_FOUND, DEVICE_UNREADABLE, JOURNAL_FAILED }

/**
 * The only way anything is changed on the device. One mutex covers the whole transaction (live read,
 * guard, journal, command, verification, watch), so two actions can never interleave and the watch
 * window of one change can never contain another. Work runs on [dispatcher], never on the caller's thread,
 * because the journal fsyncs.
 *
 * Every action takes the same path:
 *  1. read the package, the Home launcher and the keyboard from the device, now;
 *  2. ask the [Guard], which fails closed;
 *  3. record the watch baseline (and refuse to go on if the watch cannot work). An uninstall is the exception: the
 *     user asked for it knowing what it does, so it is not watched and open entries do not hold it back;
 *  4. write the journal entry as PENDING, before sending anything: if that fails, nothing is sent;
 *  5. send the command behind the identity fence, and keep going whatever the caller does;
 *  6. read the package again, watch the system, and write the result.
 */
class ActionExecutor(
    private val liveSource: LivePackageSource,
    private val sender: PackageCommandSender,
    private val watch: HealthWatch,
    private val journal: Journal,
    private val identity: DeviceIdentity,
    private val profile: DeviceProfile,
    private val ownPackage: String,
    private val watchSeconds: Int = DEFAULT_WATCH_SECONDS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Shared by every session of the process: two executors must never work on the TV at the same time. */
    private val mutex: Mutex = Mutex(),
    private val swapStore: SwapRecordStore? = null,
    launcher: LauncherPort? = null,
) {
    /** The launcher swap, when this executor was given what it needs. It runs inside this executor's lock. */
    val launcherSwap: LauncherSwapRunner? =
        if (launcher != null && swapStore != null) LauncherSwapRunner(this, launcher, swapStore, journal, identity, nowMillis) else null

    private class Live(val live: LivePackage, val reading: LiveReading) {
        val state: PackageState? get() = live.record?.takeIf { it.isComplete }?.state
    }

    // region public actions

    suspend fun perform(packageId: String, kind: ActionKind): ActionOutcome =
        exclusively { performLocked(packageId, kind, SwapMode.NONE, null) }

    /** Runs [block] as the only thing touching the TV. The launcher swap uses this to keep all its steps in one transaction. */
    internal suspend fun <T> exclusively(block: suspend () -> T): T =
        withContext(dispatcher) { mutex.withLock { block() } }

    /**
     * One action, with the lock already held. [swap] is set by the launcher swap, which may turn off what is otherwise
     * protected; [exact] puts a package back in the exact state it had instead of the default one.
     */
    internal suspend fun performLocked(packageId: String, kind: ActionKind, swap: SwapMode, exact: PackageState?): ActionOutcome {
        // A device that cannot be told apart from another has no meaningful identity fence: it stays read-only.
        if (!identity.hasReliableSerial) return ActionOutcome.Rejected(RejectReason.UNRELIABLE_DEVICE)
        val packageName = PackageName.orNull(packageId) ?: return ActionOutcome.Rejected(RejectReason.UNREADABLE)
        return try {
            // A force stop is not a change of state: no journal, no barrier, no watch. Only the guard stands in the way.
            if (kind == ActionKind.FORCE_STOP) return forceStop(packageName)
            var live = readLive(packageName)
            // Only a reconciliation that changed something makes the first read stale: otherwise one read is enough.
            val reconciled = live.state?.let { reconcilePackage(packageId, it) } ?: false
            // New disables and force-stops wait for the user to resolve open entries. Repairs never wait.
            if (kind != ActionKind.RESTORE && kind != ActionKind.UNINSTALL) {
                val blocking = journal.blocking()
                if (blocking.isNotEmpty()) return ActionOutcome.Blocked(blocking)
            }
            if (reconciled) live = readLive(packageName)
            val planned = if (exact != null) ActionPlanner.planExact(exact, live.live, swap) else ActionPlanner.plan(kind, live.live, swap)
            when (planned) {
                is PlanResult.Rejected -> ActionOutcome.Rejected(planned.reason)
                is PlanResult.Ok -> run(packageName, planned.plan, live, swap)
            }
        } catch (error: DeviceException) {
            ActionOutcome.DeviceError(error.failure)
        } catch (error: JournalStoreException) {
            ActionOutcome.JournalFailed(error.message)
        }
    }

    /** Whether the Guard would let [packageId] be turned off by the swap, without changing anything. Lock held by the caller. */
    internal suspend fun swapDisableVerdict(packageId: String, swap: SwapMode): RejectReason? {
        val packageName = PackageName.orNull(packageId) ?: return RejectReason.UNREADABLE
        val live = readLive(packageName)
        return (Guard.check(Operation.DISABLE_USER, live.live, swap) as? GuardVerdict.Rejected)?.reason
    }

    /** "Keep the current state": closes an open entry without sending anything, but only after seeing the device. */
    suspend fun accept(entryId: String): AcceptResult =
        withContext(dispatcher) {
            mutex.withLock {
                val entry = journal.entry(entryId) ?: return@withLock AcceptResult.NOT_FOUND
                val packageName = PackageName.orNull(entry.packageId) ?: return@withLock AcceptResult.DEVICE_UNREADABLE
                val state = try {
                    readLive(packageName).state
                } catch (error: DeviceException) {
                    null
                } ?: return@withLock AcceptResult.DEVICE_UNREADABLE
                try {
                    journal.update(entryId) { it.copy(status = EntryStatus.ACCEPTED, acceptedState = state) }
                    AcceptResult.ACCEPTED
                } catch (error: JournalStoreException) {
                    AcceptResult.JOURNAL_FAILED
                }
            }
        }

    /** Compares the journal with a fresh read of the device. Called after connecting and after every refresh. */
    suspend fun reconcile(snapshot: DeviceSnapshot) {
        withContext(dispatcher) {
            mutex.withLock {
                val states = snapshot.packages.mapNotNull { record -> record.state?.let { record.id to it } }.toMap()
                // An uninstall of an app the user installed leaves no record at all: the whole listing was read, so it is gone.
                val present = snapshot.packages.mapTo(HashSet()) { it.id }
                val gone = journal.entries.filter { it.kind == ActionKind.UNINSTALL && it.packageId !in present }.associate { it.packageId to GONE_FOR_USER_ZERO }
                try {
                    journal.replaceAll(Reconciler.reconcileAll(journal.entries, states + gone, nowMillis()))
                } catch (error: JournalStoreException) {
                    // Published on journal.problem: the entries stay as they were, which only blocks more.
                }
            }
        }
    }

    // endregion

    private suspend fun forceStop(packageName: PackageName): ActionOutcome {
        val live = readLive(packageName)
        val planned = ActionPlanner.plan(ActionKind.FORCE_STOP, live.live)
        if (planned is PlanResult.Rejected) return ActionOutcome.Rejected(planned.reason)
        return when (val result = sender.send(Operation.FORCE_STOP, packageName)) {
            SendResult.Ran -> ActionOutcome.ForceStopped
            is SendResult.Failed -> ActionOutcome.ForceStopFailed(result.exitCode)
            SendResult.NotSent, SendResult.Uncertain, SendResult.FenceRefused -> ActionOutcome.ForceStopFailed(null)
        }
    }

    // region the transaction

    private suspend fun run(packageName: PackageName, plan: ActionPlan, live: Live, swap: SwapMode): ActionOutcome {
        val previous = live.state ?: return ActionOutcome.Rejected(RejectReason.UNREADABLE)
        val strict = plan.kind != ActionKind.RESTORE
        // An uninstall is not watched for three seconds: the user is responsible for it, and the check afterwards is only that it happened.
        val baseline: WatchBaseline? = if (plan.kind == ActionKind.UNINSTALL) {
            null
        } else {
            // What the swap turns off is meant to stop running: watching for its process would call that an anomaly.
            val unwatched = if (swap != SwapMode.NONE) packageName.value else null
            when (val result = watch.baseline(criticalProcesses(live.reading.homePackages, unwatched), if (strict) WatchMode.STRICT else WatchMode.LENIENT)) {
                is BaselineResult.Unavailable -> return ActionOutcome.WatchUnavailable(result.detail)
                is BaselineResult.Ready -> result.baseline
            }
        }
        val entry = JournalEntry(
            id = newId(),
            timestampMillis = nowMillis(),
            packageId = packageName.value,
            kind = plan.kind,
            previous = previous,
            target = plan.target,
            steps = plan.steps,
            status = EntryStatus.PENDING,
            watch = baseline?.let { WatchRecord(WatchState.RUNNING, it.epoch.value, it.pids.mapValues { pids -> pids.value }) },
        )
        // Written before anything is sent. If this fails, nothing has happened and nothing is sent.
        try {
            journal.add(entry)
        } catch (error: JournalStoreException) {
            return ActionOutcome.JournalFailed(error.message)
        }
        // From here the change goes on to the end whatever the caller does: a half-finished change is the worst outcome.
        return withContext(NonCancellable) { send(packageName, entry, baseline, swap) }
    }

    private suspend fun send(packageName: PackageName, started: JournalEntry, baseline: WatchBaseline?, swap: SwapMode): ActionOutcome {
        var entry = started
        for ((index, operation) in started.steps.withIndex()) {
            if (index > 0) {
                // The second step of a restore: look again, and ask the guard again, before sending.
                val again = try { readLive(packageName) } catch (error: DeviceException) { null }
                if (again == null || Guard.check(operation, again.live, swap) != GuardVerdict.Allowed) {
                    return ActionOutcome.Finished(needsReview(entry, "a restore stopped between its steps"))
                }
            }
            // About to send: from here on, if the app dies or the answer is lost, the outcome is unknown.
            // This write must succeed first, or the command is not sent.
            entry = try {
                journal.update(entry.id) { it.copy(uncertainSend = true, settleUntilMillis = nowMillis() + Reconciler.SETTLE_WINDOW_MILLIS) }
            } catch (error: JournalStoreException) {
                return if (index == 0) ActionOutcome.JournalFailed(error.message) else ActionOutcome.Finished(needsReview(entry, "the journal could not be written"))
            }
            when (val result = sender.send(operation, packageName)) {
                SendResult.Ran -> entry = journal.updateAfterSend(entry.id) { it.copy(stepsDone = index + 1, uncertainSend = false, settleUntilMillis = null) }
                // A timeout or a cut connection: it may have run. The entry stays PENDING until the device says what happened.
                SendResult.Uncertain -> return ActionOutcome.Finished(entry)
                // The fence stopped it: another device answered. Nothing ran; the entry waits for reconciliation.
                SendResult.FenceRefused ->
                    return ActionOutcome.Finished(
                        if (index == 0) journal.updateAfterSend(entry.id) { it.copy(uncertainSend = false, settleUntilMillis = null) }
                        else needsReview(entry, "the identity fence stopped a restore between its steps"),
                    )
                SendResult.NotSent, is SendResult.Failed ->
                    return ActionOutcome.Finished(
                        if (index == 0) journal.updateAfterSend(entry.id) { it.copy(status = EntryStatus.FAILED, uncertainSend = false, settleUntilMillis = null) }
                        else needsReview(entry, "a restore failed after its first step (${describe(result)})"),
                    )
            }
        }
        return ActionOutcome.Finished(verifyAndWatch(packageName, entry, baseline))
    }

    private fun describe(result: SendResult): String =
        when (result) {
            is SendResult.Failed -> "exit ${result.exitCode}"
            else -> "not sent"
        }

    /** Something is half done: the state is neither the old one nor the expected one, so a person has to look. */
    private fun needsReview(entry: JournalEntry, detail: String): JournalEntry =
        journal.updateAfterSend(entry.id) {
            it.copy(
                status = EntryStatus.NEEDS_REVIEW,
                uncertainSend = false,
                settleUntilMillis = null,
                watch = it.watch?.copy(state = WatchState.INCONCLUSIVE, detail = detail),
            )
        }

    private suspend fun verifyAndWatch(packageName: PackageName, sent: JournalEntry, baseline: WatchBaseline?): JournalEntry {
        val reading = try { readLive(packageName) } catch (error: DeviceException) { null }
        // A package removed for good no longer has a record: for an uninstall that is the expected end, not a doubt.
        val after = reading?.state ?: if (sent.kind == ActionKind.UNINSTALL && reading?.reading?.packageAbsent == true) GONE_FOR_USER_ZERO else null
        // Judged by what the device says now: a command that exited 0 but changed nothing is not "done".
        val reached = sent.target == null || (after != null && sent.reaches(after))
        val outcome = baseline?.let { watch.observe(it, watchSeconds) }
        val finished = journal.updateAfterSend(sent.id) {
            it.copy(
                status = if (reached) EntryStatus.DONE else EntryStatus.NEEDS_REVIEW,
                watch = if (baseline != null && outcome != null) WatchRecord(outcome.state, baseline.epoch.value, baseline.pids, outcome.detail) else null,
            )
        }
        return finished
    }

    // endregion

    // region reading the device

    /** Reads the package, the Home launcher and the keyboard from the device, now. Anything unreadable makes the package unreadable. */
    private suspend fun readLive(packageName: PackageName): Live {
        val reading = liveSource.read(packageName)
        val record = reading.record
        val essential = record?.let {
            Classifier.essentialReason(it, reading.homePackages, reading.keyboardPackage, ClassificationContext(profile, ownPackage))
        }
        val facts = reading.launchers?.let { snapshot ->
            LauncherFacts(snapshot.setupwraith, snapshot.lightLauncher, snapshot.homeCandidates, swapRecordPresent(), stockChangeOpen())
        }
        return Live(LivePackage(record, essential, facts, launcherRulesApply = true), reading)
    }

    /**
     * A swap on record, or a record that cannot be read, means the swap is not over: the launchers stay as they are.
     * A record of a plain update is not a swap, and holds nothing back.
     */
    private fun swapRecordPresent(): Boolean =
        try {
            swapStore?.load()?.let { !it.updateOnly } == true
        } catch (error: SwapRecordException) {
            true
        }

    /** A turn-off of a stock launcher that may still land: setupwraith must not come back on until it is settled. */
    private fun stockChangeOpen(): Boolean {
        val now = nowMillis()
        return journal.entries.any { entry ->
            entry.packageId in LauncherPackages.STOCK && (entry.kind == ActionKind.DISABLE || entry.kind == ActionKind.UNINSTALL) &&
                (entry.status == EntryStatus.PENDING || entry.uncertainSend || (entry.settleUntilMillis ?: 0L) > now)
        }
    }

    private fun criticalProcesses(homePackages: Set<String>, unwatched: String? = null): List<CriticalProcess> {
        fun watched(name: String): Boolean = unwatched == null || name.substringBefore(':') != unwatched
        val required = profile.criticalProcesses.filter(::watched).mapNotNull { name -> ProcessName.orNull(name) }
        val home = homePackages.filter { it !in profile.criticalProcesses && watched(it) }.mapNotNull { name -> ProcessName.orNull(name) }
        return required.map { CriticalProcess(it, required = true) } + home.map { CriticalProcess(it, required = false) }
    }

    // endregion

    /** Returns true when the journal changed. */
    private fun reconcilePackage(packageId: String, state: PackageState): Boolean {
        val changes = Reconciler.reconcileAll(journal.entries.filter { it.packageId == packageId }, mapOf(packageId to state), nowMillis())
        journal.replaceAll(changes)
        return changes.isNotEmpty()
    }

    companion object {
        /** Three seconds are enough to see a crash loop: the vendor service that broke restarted every 1 to 2 s. */
        const val DEFAULT_WATCH_SECONDS: Int = 3

        /** What an uninstalled package reads as when it is not even listed any more. */
        private val GONE_FOR_USER_ZERO: PackageState = PackageState(installed = false, enabled = 0, stopped = false)

    }
}
