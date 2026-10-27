package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.EssentialReason
import com.prototype.tuneupfortv.device.PackageState

/** What one requested action will do: the commands in order and the state expected after them. */
data class ActionPlan(
    val kind: ActionKind,
    val steps: List<Operation>,
    /** Null for a force-stop, which leaves no state behind. */
    val target: PackageState?,
)

sealed interface PlanResult {
    data class Ok(val plan: ActionPlan) : PlanResult
    data class Rejected(val reason: RejectReason) : PlanResult
}

/**
 * Decides, without sending anything, what an action would do to a package. The executor, the confirmation
 * screen and the detail screen all ask this one function, so what the user is told is what will happen.
 */
object ActionPlanner {
    fun plan(kind: ActionKind, live: LivePackage, swap: SwapMode = SwapMode.NONE): PlanResult {
        val state = live.record?.takeIf { it.isComplete }?.state ?: return PlanResult.Rejected(RejectReason.UNREADABLE)
        // This app is never touched, whatever is asked: that rule must win over any other answer.
        if (live.essentialReason == EssentialReason.THIS_APP) return PlanResult.Rejected(RejectReason.THIS_APP)
        val steps = when (kind) {
            ActionKind.FORCE_STOP -> listOf(Operation.FORCE_STOP)
            ActionKind.DISABLE -> listOf(Operation.DISABLE_USER)
            ActionKind.UNINSTALL -> listOf(Operation.UNINSTALL_USER)
            ActionKind.RESTORE -> when {
                !state.installed -> if (state.isDisabledByUser) listOf(Operation.INSTALL_EXISTING, Operation.DEFAULT_STATE) else listOf(Operation.INSTALL_EXISTING)
                state.isDisabledByUser -> listOf(Operation.DEFAULT_STATE)
                state.isEnabled -> return PlanResult.Rejected(RejectReason.NOTHING_TO_RESTORE)
                // States 2 and 4: the guard answers with NOT_CHANGEABLE_FROM_SHELL.
                else -> listOf(Operation.DEFAULT_STATE)
            }
        }
        val verdict = Guard.check(steps.first(), live, swap)
        if (verdict is GuardVerdict.Rejected) return PlanResult.Rejected(verdict.reason)
        val target = if (kind == ActionKind.FORCE_STOP) null else steps.fold(state) { current, operation -> stateAfter(operation, current) }
        return PlanResult.Ok(ActionPlan(kind, steps, target))
    }

    /**
     * What it takes to put a package back in the exact state it had ([original]), which a plain restore cannot say: it
     * only knows "default", and a package that was explicitly enabled would come back different. Rejected with
     * [RejectReason.NOTHING_TO_RESTORE] when it is already there.
     */
    fun planExact(original: PackageState, live: LivePackage, swap: SwapMode = SwapMode.NONE): PlanResult {
        val state = live.record?.takeIf { it.isComplete }?.state ?: return PlanResult.Rejected(RejectReason.UNREADABLE)
        if (live.essentialReason == EssentialReason.THIS_APP) return PlanResult.Rejected(RejectReason.THIS_APP)
        val steps = buildList {
            if (!state.installed) add(Operation.INSTALL_EXISTING)
            if (state.enabled != original.enabled) {
                add(
                    when (original.enabled) {
                        PackageState.DEFAULT -> Operation.DEFAULT_STATE
                        PackageState.ENABLED -> Operation.ENABLE
                        PackageState.DISABLED_USER -> Operation.DISABLE_USER
                        else -> return PlanResult.Rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL)
                    },
                )
            }
        }
        if (steps.isEmpty()) return PlanResult.Rejected(RejectReason.NOTHING_TO_RESTORE)
        val verdict = Guard.check(steps.first(), live, swap)
        if (verdict is GuardVerdict.Rejected) return PlanResult.Rejected(verdict.reason)
        val kind = if (steps.last() == Operation.DISABLE_USER) ActionKind.DISABLE else ActionKind.RESTORE
        val target = steps.fold(state) { current, operation -> stateAfter(operation, current) }
        return PlanResult.Ok(ActionPlan(kind, steps, target))
    }

    /**
     * The actions to offer for a package, from what the last read shows. The real check runs again, on a live read,
     * right before anything is sent. A force stop is only offered for something that is running: otherwise it does nothing.
     */
    fun availableActions(live: LivePackage, isRunning: Boolean): List<ActionKind> =
        ActionKind.entries.filter { kind ->
            (kind != ActionKind.FORCE_STOP || isRunning) && plan(kind, live) is PlanResult.Ok
        }

    /** Why disabling is refused, when the reason is worth telling (a protected package already says so on its own). */
    fun disableBlockedReason(live: LivePackage): RejectReason? =
        (plan(ActionKind.DISABLE, live) as? PlanResult.Rejected)?.reason?.takeIf { it != RejectReason.ESSENTIAL }

    fun stateAfter(operation: Operation, state: PackageState): PackageState =
        when (operation) {
            Operation.DISABLE_USER -> state.copy(enabled = PackageState.DISABLED_USER)
            Operation.DEFAULT_STATE -> state.copy(enabled = PackageState.DEFAULT)
            Operation.ENABLE -> state.copy(enabled = PackageState.ENABLED)
            Operation.INSTALL_EXISTING -> state.copy(installed = true)
            Operation.UNINSTALL_USER -> state.copy(installed = false)
            Operation.FORCE_STOP -> state
        }
}
