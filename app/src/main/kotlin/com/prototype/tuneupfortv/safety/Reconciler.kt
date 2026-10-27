package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.PackageState

/**
 * Decides what really happened to an entry by comparing it with what the device says now.
 * Pure: it only looks at the entry, the live state of its package and the clock.
 *
 * Only `installed` and `enabled` are compared; `stopped` is informational.
 */
object Reconciler {
    /** How long after an uncertain send a late-landing command is still considered possible. */
    const val SETTLE_WINDOW_MILLIS: Long = 10 * 60 * 1000L

    /** Reconciles every entry against [liveStates] (package id to state). Returns only the entries that changed. */
    fun reconcileAll(entries: List<JournalEntry>, liveStates: Map<String, PackageState>, nowMillis: Long): Map<String, JournalEntry> =
        entries.mapNotNull { entry ->
            val live = liveStates[entry.packageId] ?: return@mapNotNull null
            val later = entries.any { it.packageId == entry.packageId && it.timestampMillis > entry.timestampMillis }
            val reconciled = reconcile(entry, live, nowMillis, laterEntryExists = later)
            if (reconciled != entry) entry.id to reconciled else null
        }.toMap()

    fun reconcile(entry: JournalEntry, live: PackageState, nowMillis: Long, laterEntryExists: Boolean): JournalEntry =
        when {
            entry.status == EntryStatus.PENDING -> reconcilePending(entry, live, nowMillis)
            entry.status == EntryStatus.DONE && entry.watch?.state == WatchState.RUNNING -> entry.withWatch(WatchState.INCONCLUSIVE, "the app stopped while watching")
            entry.uncertainSend -> reconcileUncertain(entry, live, nowMillis, laterEntryExists)
            else -> entry
        }

    private fun reconcilePending(entry: JournalEntry, live: PackageState, nowMillis: Long): JournalEntry {
        // A force-stop leaves no state behind, so its result cannot be checked: say so, and keep the watch obligation open.
        if (entry.kind == ActionKind.FORCE_STOP) {
            return entry.copy(status = EntryStatus.UNVERIFIED, uncertainSend = false).withWatch(WatchState.INCONCLUSIVE, "interrupted")
        }
        val target = entry.target
        return when {
            // An entry that was never watched (an uninstall) has no watch to leave unfinished.
            target != null && entry.reaches(live) ->
                entry.copy(status = EntryStatus.DONE, uncertainSend = false).let { done ->
                    if (entry.watch == null) done else done.withWatch(WatchState.INCONCLUSIVE, "interrupted before the watch finished")
                }
            live.sameAs(entry.previous) -> when {
                // Never sent: the app died between writing the entry and sending the command.
                !entry.uncertainSend -> entry.copy(status = EntryStatus.FAILED)
                // Sent, outcome unknown: it may still land, so wait out the settle window before calling it failed.
                entry.settleUntilMillis != null && nowMillis >= entry.settleUntilMillis ->
                    entry.copy(status = EntryStatus.FAILED, uncertainSend = false, settleUntilMillis = null)
                else -> entry
            }
            else -> entry.copy(status = EntryStatus.NEEDS_REVIEW, uncertainSend = false).withWatch(WatchState.INCONCLUSIVE, "the state matches neither before nor after")
        }
    }

    /** An entry that closed while a command might still land. */
    private fun reconcileUncertain(entry: JournalEntry, live: PackageState, nowMillis: Long, laterEntryExists: Boolean): JournalEntry {
        val settleUntil = entry.settleUntilMillis
        if (settleUntil != null && nowMillis >= settleUntil) return entry.copy(uncertainSend = false, settleUntilMillis = null)
        val target = entry.target ?: return entry
        val landed = entry.reaches(live) &&
            (entry.acceptedState == null || !live.sameAs(entry.acceptedState)) &&
            !laterEntryExists &&
            entry.status in setOf(EntryStatus.UNDONE, EntryStatus.ACCEPTED, EntryStatus.FAILED)
        // It landed after the entry was closed, with no watch and no undo: reopen it so someone looks.
        return if (landed) entry.copy(status = EntryStatus.NEEDS_REVIEW, uncertainSend = false).withWatch(WatchState.INCONCLUSIVE, "the command landed late") else entry
    }

    private fun JournalEntry.withWatch(state: WatchState, detail: String): JournalEntry {
        val current = watch ?: WatchRecord(state, baselineEpoch = "", baselinePids = emptyMap(), detail = detail)
        return copy(watch = current.copy(state = state, detail = detail))
    }
}
