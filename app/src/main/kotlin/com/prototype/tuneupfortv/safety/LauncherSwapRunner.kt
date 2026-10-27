package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.AccessibilityValue
import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.PermissionName
import com.prototype.tuneupfortv.device.ScreensaverValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class SwapStepStatus { WORKING, DONE, SKIPPED, UNCONFIRMED, FAILED }

/** Why a swap or an undo did not start. Nothing was changed. */
sealed interface SwapRefusal {
    data object UnreliableDevice : SwapRefusal
    data object RecordUnreadable : SwapRefusal
    data object RecordNotWritten : SwapRefusal
    data object OpenEntries : SwapRefusal

    /** The TV could not be read well enough to decide anything. */
    data object Unreadable : SwapRefusal

    /** A write whose answer was lost may still land: nothing else is done until it is settled. */
    data object WriteStillPending : SwapRefusal
    data object NothingToUndo : SwapRefusal
    data class Scope(val problem: ScopeProblem, val detail: String?) : SwapRefusal
    data class Guarded(val packageId: String, val reason: RejectReason) : SwapRefusal
    data class Device(val failure: DeviceFailure) : SwapRefusal

    /** The launcher could not be downloaded, or was not the file its release published. Nothing was changed. */
    data object DownloadFailed : SwapRefusal
}

sealed interface SwapPreview {
    data class Ready(val plan: LauncherPlan) : SwapPreview
    data class Refused(val refusal: SwapRefusal) : SwapPreview
}

enum class StopCause { UNCLEAN, REJECTED, FAILED, UNCERTAIN, NOT_CONFIRMED, DEVICE, RECORD }

sealed interface SwapOutcome {
    /** Nothing was changed. */
    data class Refused(val refusal: SwapRefusal) : SwapOutcome

    /** [unconfirmed] are the optional steps that could not be confirmed; [wifiReminder] says the saved network may be gone. */
    data class Done(val unconfirmed: Set<SwapStep>, val wifiReminder: Boolean) : SwapOutcome

    /** Something may have changed. [rolledBack] is set when Home did not move and the stock launcher was put back. */
    data class Stopped(
        val step: SwapStep,
        val cause: StopCause,
        val outcome: ActionOutcome? = null,
        val rolledBack: Boolean = false,
        val rollbackIncomplete: Boolean = false,
    ) : SwapOutcome
}

/**
 * Puts the downloaded launcher on the TV and makes it Home, or takes it all back. It runs inside the executor's lock, one
 * step after another, and stops at the first one that is not clean: the order is what keeps the TV from ending with the
 * setup app as its only Home, so a step is never skipped over a doubt.
 */
class LauncherSwapRunner(
    private val executor: ActionExecutor,
    private val port: LauncherPort,
    private val store: SwapRecordStore,
    private val journal: Journal,
    private val identity: DeviceIdentity,
    private val nowMillis: () -> Long,
) {
    /** What the TV would get from a launcher of [versionCode], without changing anything (nothing is downloaded for this). */
    suspend fun preview(versionCode: Long): SwapPreview =
        try {
            when (val prepared = prepare(versionCode, checkOpenEntries = false)) {
                is Prepared.Refused -> SwapPreview.Refused(prepared.refusal)
                is Prepared.Ready -> SwapPreview.Ready(prepared.plan)
            }
        } catch (error: DeviceException) {
            SwapPreview.Refused(SwapRefusal.Device(error.failure))
        }

    /** Whether a swap is on record, for the screen: null when none, throws nothing. */
    fun recordState(): RecordState =
        try {
            store.load()?.let { RecordState.Present(it) } ?: RecordState.None
        } catch (error: SwapRecordException) {
            RecordState.Unreadable
        }

    sealed interface RecordState {
        data object None : RecordState
        data object Unreadable : RecordState
        data class Present(val record: SwapRecord) : RecordState
    }

    suspend fun run(apk: LauncherApk, report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome =
        executor.exclusively { withContext(NonCancellable) { guarded(null) { runLocked(apk, report) } } }

    suspend fun undo(report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome =
        executor.exclusively { withContext(NonCancellable) { guarded(null) { undoLocked(report) } } }

    /** A lost connection is a refusal if nothing was sent yet, a stop if something was. */
    private suspend fun guarded(current: SwapStep?, block: suspend () -> SwapOutcome): SwapOutcome =
        try {
            block()
        } catch (error: DeviceException) {
            if (current == null) SwapOutcome.Refused(SwapRefusal.Device(error.failure)) else SwapOutcome.Stopped(current, StopCause.DEVICE)
        } catch (error: SwapRecordException) {
            SwapOutcome.Refused(SwapRefusal.RecordUnreadable)
        }

    // region the swap

    private sealed interface Prepared {
        data class Refused(val refusal: SwapRefusal) : Prepared
        data class Ready(val plan: LauncherPlan, val reading: LauncherReading, val existing: SwapRecord?) : Prepared
    }

    private suspend fun prepare(versionCode: Long, checkOpenEntries: Boolean): Prepared {
        if (!identity.hasReliableSerial) return Prepared.Refused(SwapRefusal.UnreliableDevice)
        if (checkOpenEntries && journal.blocking().isNotEmpty()) return Prepared.Refused(SwapRefusal.OpenEntries)
        val existing = try {
            store.load()
        } catch (error: SwapRecordException) {
            return Prepared.Refused(SwapRefusal.RecordUnreadable)
        }
        val reading = port.read() ?: return Prepared.Refused(SwapRefusal.Unreadable)
        if (existing?.pending != null && !settled(existing.pending, reading)) return Prepared.Refused(SwapRefusal.WriteStillPending)
        val plan = when (val scope = LauncherScope.evaluate(reading, existing, versionCode)) {
            is ScopeResult.Rejected -> return Prepared.Refused(SwapRefusal.Scope(scope.problem, scope.detail))
            is ScopeResult.Ok -> scope.plan
        }
        // Everything the Guard would refuse is found now, before the first change: not after the setup app is already off.
        val disables = listOfNotNull(
            LauncherPackages.SETUPWRAITH.takeIf { plan.disableSetupwraith },
            plan.stockToDisable,
            LauncherPackages.DREAMX.takeIf { plan.disableDreamx },
        )
        for (packageId in disables) {
            executor.swapDisableVerdict(packageId, SwapMode.PRECHECK)?.let { return Prepared.Refused(SwapRefusal.Guarded(packageId, it)) }
        }
        return Prepared.Ready(plan, reading, existing)
    }

    /** A write whose answer was lost is settled once the TV shows its effect, or once it could no longer land. */
    private fun settled(pending: PendingWrite, reading: LauncherReading): Boolean {
        if (nowMillis() - pending.sinceMillis > Reconciler.SETTLE_WINDOW_MILLIS) return true
        return when (pending.kind) {
            PendingKind.INSTALL -> reading.packages[LauncherPackages.LIGHT]?.installed == true
            PendingKind.HOME -> reading.homePackage == pending.expected
            PendingKind.SCREENSAVER -> (reading.screensaver ?: "") == pending.expected
        }
    }

    /** Everything one run changes and remembers while it goes. */
    private class Run(var record: SwapRecord, val report: (SwapStep, SwapStepStatus) -> Unit) {
        val unconfirmed: MutableSet<SwapStep> = linkedSetOf()
        var current: SwapStep? = null
    }

    private suspend fun runLocked(apk: LauncherApk, report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome {
        val prepared = when (val result = prepare(apk.versionCode, checkOpenEntries = true)) {
            is Prepared.Refused -> return SwapOutcome.Refused(result.refusal)
            is Prepared.Ready -> result
        }
        val plan = prepared.plan
        val reading = prepared.reading
        val record = recordFor(plan, reading, prepared.existing)
        // The undo depends on this record, so it is on disk before anything changes: if it cannot be written, nothing is.
        if (!save(record)) return SwapOutcome.Refused(SwapRefusal.RecordNotWritten)
        val run = Run(record, report)
        val outcome = try {
            steps(plan, reading, apk, run)
        } catch (error: DeviceException) {
            SwapOutcome.Stopped(run.current ?: SwapStep.INSTALL, StopCause.DEVICE)
        } catch (error: SwapRecordException) {
            SwapOutcome.Stopped(run.current ?: SwapStep.INSTALL, StopCause.RECORD)
        }
        // An update has nothing to undo, so its record only lives while a write of it may still land.
        if (plan.updateOnly && run.record.pending == null) {
            try {
                store.delete()
            } catch (error: SwapRecordException) {
                // It stays as an update record: it blocks nothing, and the next update replaces it.
            }
        }
        return outcome
    }

    /** The record of this run. A second run keeps what the first one saw: the original Home, screensaver and states. */
    private fun recordFor(plan: LauncherPlan, reading: LauncherReading, existing: SwapRecord?): SwapRecord {
        val touched = listOfNotNull(
            LauncherPackages.LIGHT,
            LauncherPackages.SETUPWRAITH.takeIf { plan.disableSetupwraith },
            plan.stockToDisable,
            LauncherPackages.DREAMX.takeIf { plan.disableDreamx },
        )
        val states = touched.mapNotNull { id -> reading.packages[id]?.takeIf { it.installed }?.let { id to it } }.toMap()
        if (existing != null && !existing.updateOnly) {
            return existing.copy(pending = null, packages = states + existing.packages, wifiMayBeLost = existing.wifiMayBeLost || plan.wifiWarning)
        }
        return SwapRecord(
            updateOnly = plan.updateOnly,
            homeComponent = plan.homeComponent,
            screensaver = reading.screensaver,
            packages = states,
            wifiMayBeLost = plan.wifiWarning,
        )
    }

    private suspend fun steps(plan: LauncherPlan, reading: LauncherReading, apk: LauncherApk, run: Run): SwapOutcome {
        var screensaverVerified = false
        var stockUncleanButOff: SwapOutcome.Stopped? = null
        for (step in plan.steps) {
            run.current = step
            run.report(step, SwapStepStatus.WORKING)
            val stop: SwapOutcome.Stopped? = when (step) {
                SwapStep.INSTALL -> install(apk, reading, run)
                SwapStep.ENABLE -> packageStep(step, LauncherPackages.LIGHT, ActionKind.RESTORE, SwapMode.NONE, PackageState(installed = true, enabled = PackageState.ENABLED, stopped = false), run)
                SwapStep.PERMISSIONS -> optionalWrites(step, LauncherPackages.PERMISSIONS.map { permission -> suspend { port.grant(PermissionName(permission)) } }, run)
                SwapStep.COMPILE -> optionalWrites(step, listOf(suspend { port.compile() }), run)
                SwapStep.SCREENSAVER -> screensaver(run).also { if (it == null) screensaverVerified = true }
                SwapStep.SETUPWRAITH -> packageStep(step, LauncherPackages.SETUPWRAITH, ActionKind.DISABLE, SwapMode.ACTIVE, null, run)
                SwapStep.STOCK -> {
                    val stop = packageStep(step, plan.stockToDisable.orEmpty(), ActionKind.DISABLE, SwapMode.ACTIVE, null, run)
                    // Not clean, but if the stock launcher is off all the same, Home still has to be set and checked: never left to chance.
                    if (stop != null && stockReadsOff(plan.stockToDisable.orEmpty())) {
                        stockUncleanButOff = stop
                        null
                    } else {
                        stop
                    }
                }
                SwapStep.HOME -> home(plan, run)
                SwapStep.DREAMX ->
                    if (screensaverVerified) {
                        packageStep(step, LauncherPackages.DREAMX, ActionKind.DISABLE, SwapMode.ACTIVE, null, run)
                    } else {
                        // Without the new screensaver in place the stock one stays: the TV would be left with none.
                        done(run, step, SwapStepStatus.SKIPPED)
                        null
                    }
                SwapStep.ACCESSIBILITY, SwapStep.RESTORE_LIGHT -> null
            }
            if (stop != null) return stop
            // After Home is set the unclean stock step still ends the run: nothing more is turned off over a doubt.
            if (step == SwapStep.HOME && stockUncleanButOff != null) return stockUncleanButOff
        }
        val setupwraithOff = port.read()?.packages?.get(LauncherPackages.SETUPWRAITH)?.let { it.installed && !it.isEnabled } == true
        return SwapOutcome.Done(run.unconfirmed, wifiReminder = run.record.wifiMayBeLost && setupwraithOff)
    }

    private fun done(run: Run, step: SwapStep, status: SwapStepStatus = SwapStepStatus.DONE) = run.report(step, status)

    private suspend fun stockReadsOff(packageId: String): Boolean =
        port.read()?.packages?.get(packageId)?.let { it.installed && it.isDisabled } == true

    private fun clean(outcome: ActionOutcome): Boolean = outcome is ActionOutcome.Finished && outcome.isClean && journal.problem.value == null

    /** One package, through the executor: journal, fence, verification and watch. Null when the step is done or had nothing to do. */
    private suspend fun packageStep(
        step: SwapStep,
        packageId: String,
        kind: ActionKind,
        swap: SwapMode,
        exact: PackageState?,
        run: Run,
    ): SwapOutcome.Stopped? {
        val outcome = executor.performLocked(packageId, kind, swap, exact)
        return when {
            clean(outcome) -> {
                done(run, step)
                null
            }
            outcome is ActionOutcome.Rejected && outcome.reason in NOTHING_TO_DO -> {
                done(run, step, SwapStepStatus.SKIPPED)
                null
            }
            else -> {
                run.report(step, SwapStepStatus.FAILED)
                SwapOutcome.Stopped(step, if (outcome is ActionOutcome.Rejected) StopCause.REJECTED else StopCause.UNCLEAN, outcome)
            }
        }
    }

    private suspend fun install(apk: LauncherApk, reading: LauncherReading, run: Run): SwapOutcome.Stopped? {
        val before = lightEntries(reading.accessibility)
        when (val result = writeWithPending(run, PendingKind.INSTALL, expected = null) { port.install(apk) }) {
            SendResult.Ran -> Unit
            else -> return stoppedBy(SwapStep.INSTALL, result, run)
        }
        val after = port.read()
        val installedNow = after?.packages?.get(LauncherPackages.LIGHT)?.installed == true &&
            (after.lightLauncherVersion ?: apk.versionCode) >= apk.versionCode
        if (!installedNow) return failed(SwapStep.INSTALL, StopCause.NOT_CONFIRMED, run)
        if (!clearPending(run)) return failed(SwapStep.INSTALL, StopCause.RECORD, run)
        // Android turns the accessibility helpers off on every reinstall: put back exactly the ones that were on, and nothing else.
        if (before.isNotEmpty() && !restoreAccessibility(before, after)) run.unconfirmed += SwapStep.ACCESSIBILITY
        done(run, SwapStep.INSTALL)
        return null
    }

    private fun lightEntries(value: String?): List<String> =
        value?.split(':').orEmpty().filter { it.startsWith("${LauncherPackages.LIGHT}/") }

    private suspend fun restoreAccessibility(before: List<String>, after: LauncherReading): Boolean {
        val current = after.accessibility?.split(':').orEmpty().filter { it.isNotEmpty() }
        val missing = before.filter { it !in current }
        if (missing.isEmpty()) return true
        val value = AccessibilityValue.orNull((current + missing).joinToString(":")) ?: return false
        if (port.putAccessibility(value) != SendResult.Ran) return false
        val confirmed = port.read()?.accessibility?.split(':').orEmpty()
        return before.all { it in confirmed }
    }

    /** Grants and the compile are a nicety: a refusal is noted, but a lost connection or a fence refusal ends the run. */
    private suspend fun optionalWrites(step: SwapStep, writes: List<suspend () -> SendResult>, run: Run): SwapOutcome.Stopped? {
        var allRan = true
        for (write in writes) {
            when (write()) {
                SendResult.Ran -> Unit
                is SendResult.Failed, SendResult.Uncertain -> allRan = false
                SendResult.NotSent -> return failed(step, StopCause.DEVICE, run)
                SendResult.FenceRefused -> return failed(step, StopCause.FAILED, run)
            }
        }
        if (allRan) {
            done(run, step)
        } else {
            run.unconfirmed += step
            done(run, step, SwapStepStatus.UNCONFIRMED)
        }
        return null
    }

    private suspend fun screensaver(run: Run): SwapOutcome.Stopped? {
        val component = ScreensaverValue(LauncherPackages.DREAM_COMPONENT)
        when (val result = writeWithPending(run, PendingKind.SCREENSAVER, LauncherPackages.DREAM_COMPONENT) { port.putScreensaver(component) }) {
            SendResult.Ran -> Unit
            else -> return stoppedBy(SwapStep.SCREENSAVER, result, run)
        }
        if (port.read()?.screensaver != LauncherPackages.DREAM_COMPONENT) return failed(SwapStep.SCREENSAVER, StopCause.NOT_CONFIRMED, run)
        if (!clearPending(run)) return failed(SwapStep.SCREENSAVER, StopCause.RECORD, run)
        done(run, SwapStep.SCREENSAVER)
        return null
    }

    private suspend fun home(plan: LauncherPlan, run: Run): SwapOutcome.Stopped? {
        val result = writeWithPending(run, PendingKind.HOME, LauncherPackages.LIGHT) { port.setHome(ComponentName(LauncherPackages.HOME_COMPONENT)) }
        // A lost answer: the write may still land, so nothing is undone while it can.
        if (result == SendResult.Uncertain) return stoppedBy(SwapStep.HOME, result, run)
        val landed = result == SendResult.Ran && port.read()?.homePackage == LauncherPackages.LIGHT
        if (landed) {
            if (!clearPending(run)) return failed(SwapStep.HOME, StopCause.RECORD, run)
            done(run, SwapStep.HOME)
            return null
        }
        clearPending(run)
        run.report(SwapStep.HOME, SwapStepStatus.FAILED)
        if (plan.updateOnly) return SwapOutcome.Stopped(SwapStep.HOME, StopCause.NOT_CONFIRMED)
        val clean = rollback(plan, run)
        return SwapOutcome.Stopped(SwapStep.HOME, StopCause.NOT_CONFIRMED, rolledBack = clean, rollbackIncomplete = !clean)
    }

    /** Home did not move: the stock launcher comes back first, then the setup app, and only when the first is clean. */
    private suspend fun rollback(plan: LauncherPlan, run: Run): Boolean {
        val stock = plan.stockToDisable
        if (stock != null) {
            val original = run.record.packages[stock] ?: return false
            if (!restoreExact(stock, original)) return false
        }
        if (plan.disableSetupwraith) {
            val original = run.record.packages[LauncherPackages.SETUPWRAITH] ?: return false
            if (!restoreExact(LauncherPackages.SETUPWRAITH, original)) return false
        }
        return true
    }

    private suspend fun restoreExact(packageId: String, original: PackageState): Boolean {
        val outcome = executor.performLocked(packageId, ActionKind.RESTORE, SwapMode.ACTIVE, original)
        return clean(outcome) || (outcome is ActionOutcome.Rejected && outcome.reason == RejectReason.NOTHING_TO_RESTORE)
    }

    // endregion

    // region the undo

    private suspend fun undoLocked(report: (SwapStep, SwapStepStatus) -> Unit): SwapOutcome {
        if (!identity.hasReliableSerial) return SwapOutcome.Refused(SwapRefusal.UnreliableDevice)
        val record = store.load()
        if (record == null || record.updateOnly) return SwapOutcome.Refused(SwapRefusal.NothingToUndo)
        val reading = port.read() ?: return SwapOutcome.Refused(SwapRefusal.Unreadable)
        if (record.pending != null && !settled(record.pending, reading)) return SwapOutcome.Refused(SwapRefusal.WriteStillPending)
        val run = Run(record.copy(pending = null), report)
        return try {
            undoSteps(record, run)
        } catch (error: DeviceException) {
            SwapOutcome.Stopped(run.current ?: SwapStep.HOME, StopCause.DEVICE)
        } catch (error: SwapRecordException) {
            SwapOutcome.Stopped(run.current ?: SwapStep.HOME, StopCause.RECORD)
        }
    }

    private suspend fun undoSteps(record: SwapRecord, run: Run): SwapOutcome {
        for (step in LauncherUndo.steps(record)) {
            run.current = step
            run.report(step, SwapStepStatus.WORKING)
            val stop: SwapOutcome.Stopped? = when (step) {
                SwapStep.DREAMX -> restoreStep(step, LauncherPackages.DREAMX, record, run)
                SwapStep.STOCK -> {
                    var stopped: SwapOutcome.Stopped? = null
                    for (stock in record.packages.keys.filter { it in LauncherPackages.STOCK }) {
                        stopped = restoreStep(step, stock, record, run)
                        if (stopped != null) break
                    }
                    stopped
                }
                SwapStep.SETUPWRAITH -> restoreStep(step, LauncherPackages.SETUPWRAITH, record, run)
                SwapStep.HOME -> undoHome(record, run)
                SwapStep.SCREENSAVER -> undoScreensaver(record, run)
                SwapStep.RESTORE_LIGHT -> {
                    // A disable like any other: if open entries hold it back, the record stays and the user sees why.
                    val outcome = executor.performLocked(LauncherPackages.LIGHT, ActionKind.DISABLE, SwapMode.NONE, null)
                    if (clean(outcome) || (outcome is ActionOutcome.Rejected && outcome.reason in NOTHING_TO_DO)) {
                        done(run, step)
                        null
                    } else {
                        run.report(step, SwapStepStatus.FAILED)
                        SwapOutcome.Stopped(step, if (outcome is ActionOutcome.Rejected) StopCause.REJECTED else StopCause.UNCLEAN, outcome)
                    }
                }
                else -> null
            }
            if (stop != null) return stop
        }
        store.delete()
        return SwapOutcome.Done(emptySet(), wifiReminder = false)
    }

    private suspend fun restoreStep(step: SwapStep, packageId: String, record: SwapRecord, run: Run): SwapOutcome.Stopped? {
        val original = record.packages[packageId] ?: return null
        val outcome = executor.performLocked(packageId, ActionKind.RESTORE, SwapMode.ACTIVE, original)
        return when {
            clean(outcome) || (outcome is ActionOutcome.Rejected && outcome.reason == RejectReason.NOTHING_TO_RESTORE) -> {
                done(run, step)
                null
            }
            else -> {
                run.report(step, SwapStepStatus.FAILED)
                SwapOutcome.Stopped(step, if (outcome is ActionOutcome.Rejected) StopCause.REJECTED else StopCause.UNCLEAN, outcome)
            }
        }
    }

    private suspend fun undoHome(record: SwapRecord, run: Run): SwapOutcome.Stopped? {
        val original = record.homeComponent?.let { ComponentName.orNull(it) }
        if (original != null) {
            val result = writeWithPending(run, PendingKind.HOME, original.packageName) { port.setHome(original) }
            if (result != SendResult.Ran) return stoppedBy(SwapStep.HOME, result, run)
            if (port.read()?.homePackage != original.packageName) return failed(SwapStep.HOME, StopCause.NOT_CONFIRMED, run)
            if (!clearPending(run)) return failed(SwapStep.HOME, StopCause.RECORD, run)
        } else if (port.read()?.homePackage == LauncherPackages.LIGHT) {
            // The chooser has nothing to set: Home only has to stop being the new launcher. The system let go of it when the stock one came back.
            return failed(SwapStep.HOME, StopCause.NOT_CONFIRMED, run)
        }
        done(run, SwapStep.HOME)
        return null
    }

    private suspend fun undoScreensaver(record: SwapRecord, run: Run): SwapOutcome.Stopped? {
        val original = record.screensaver
        val value = original?.let { ScreensaverValue.orNull(it) }
        val result = writeWithPending(run, PendingKind.SCREENSAVER, original.orEmpty()) {
            if (value != null) port.putScreensaver(value) else port.deleteScreensaver()
        }
        if (result != SendResult.Ran) return stoppedBy(SwapStep.SCREENSAVER, result, run)
        if ((port.read()?.screensaver ?: "") != original.orEmpty()) return failed(SwapStep.SCREENSAVER, StopCause.NOT_CONFIRMED, run)
        if (!clearPending(run)) return failed(SwapStep.SCREENSAVER, StopCause.RECORD, run)
        done(run, SwapStep.SCREENSAVER)
        return null
    }

    // endregion

    // region writes that are not about a package

    /** Marks the record before the write and sends it: if the answer is lost, the next start knows it may still land. */
    private suspend fun writeWithPending(run: Run, kind: PendingKind, expected: String?, send: suspend () -> SendResult): SendResult {
        run.record = run.record.copy(pending = PendingWrite(kind, nowMillis(), expected))
        if (!save(run.record)) return SendResult.NotSent
        return send()
    }

    private fun clearPending(run: Run): Boolean {
        run.record = run.record.copy(pending = null)
        return save(run.record)
    }

    private fun save(record: SwapRecord): Boolean =
        try {
            store.save(record)
            true
        } catch (error: SwapRecordException) {
            false
        }

    private fun stoppedBy(step: SwapStep, result: SendResult, run: Run): SwapOutcome.Stopped {
        run.report(step, SwapStepStatus.FAILED)
        return when (result) {
            // The answer was lost: the write may still land, so the pending mark stays.
            SendResult.Uncertain -> SwapOutcome.Stopped(step, StopCause.UNCERTAIN)
            SendResult.NotSent -> {
                clearPending(run)
                SwapOutcome.Stopped(step, StopCause.DEVICE)
            }
            else -> {
                clearPending(run)
                SwapOutcome.Stopped(step, StopCause.FAILED)
            }
        }
    }

    private fun failed(step: SwapStep, cause: StopCause, run: Run): SwapOutcome.Stopped {
        run.report(step, SwapStepStatus.FAILED)
        return SwapOutcome.Stopped(step, cause)
    }

    // endregion

    private companion object {
        /** Rejections that only mean the package is already where the step wanted it. */
        val NOTHING_TO_DO: Set<RejectReason> = setOf(RejectReason.NOTHING_TO_RESTORE, RejectReason.ALREADY_DISABLED, RejectReason.NOT_INSTALLED)
    }
}
