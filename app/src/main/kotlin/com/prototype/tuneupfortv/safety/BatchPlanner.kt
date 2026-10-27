package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.Classification
import com.prototype.tuneupfortv.device.PackageRecord

/** What a batch would do: the packages it will act on, in order, and the ones it will leave alone and why. */
data class BatchPlan(val kind: ActionKind, val runnable: List<String>, val skipped: Map<String, RejectReason>)

/**
 * Splits a selection into what can be done and what cannot, from what the last read shows. Pure. The executor
 * still reads each package live and asks the guard again right before acting on it, so this is a preview.
 */
object BatchPlanner {
    fun plan(
        kind: ActionKind,
        selection: Set<String>,
        records: List<PackageRecord>,
        classifications: Map<String, Classification>,
        running: Set<String>,
    ): BatchPlan {
        val byId = records.associateBy { it.id }
        val runnable = ArrayList<String>()
        val skipped = LinkedHashMap<String, RejectReason>()
        for (packageId in selection.sorted()) {
            val record = byId[packageId]
            val live = LivePackage(record, classifications[packageId]?.essentialReason)
            when {
                // Stopping something that is not running would do nothing: it is left out, not reported as a problem.
                kind == ActionKind.FORCE_STOP && packageId !in running -> skipped[packageId] = RejectReason.NOT_RUNNING
                else -> when (val planned = ActionPlanner.plan(kind, live)) {
                    is PlanResult.Ok -> runnable += packageId
                    is PlanResult.Rejected -> skipped[packageId] = planned.reason
                }
            }
        }
        return BatchPlan(kind, runnable, skipped)
    }
}
