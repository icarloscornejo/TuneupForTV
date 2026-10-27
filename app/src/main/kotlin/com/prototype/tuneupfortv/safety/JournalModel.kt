package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.PackageState
import kotlinx.serialization.Serializable

/** What the user asked for. A restore may be several commands (see [JournalEntry.steps]). */
@Serializable
enum class ActionKind { FORCE_STOP, DISABLE, RESTORE, UNINSTALL }

@Serializable
enum class EntryStatus {
    /** Written before the command is sent. If the app dies here, the next start finds out what happened. */
    PENDING,
    DONE,
    FAILED,
    /** Written by earlier versions, which could undo. It is read as a closed entry and never written any more. */
    UNDONE,

    /** The state is neither the old one nor the expected one: someone has to look. */
    NEEDS_REVIEW,

    /** The user looked at the current state and chose to keep it. */
    ACCEPTED,

    /** A force-stop whose result cannot be checked, because it leaves no state behind. */
    UNVERIFIED,
}

@Serializable
enum class WatchState { RUNNING, OK, ANOMALY, INCONCLUSIVE }

/** The post-change watch: pids of the critical processes before the change, and what the watch concluded. */
@Serializable
data class WatchRecord(
    val state: WatchState,
    /** The device clock before the change, in seconds: the crash log is read from here. */
    val baselineEpoch: String,
    /** Process name to its pids. An empty list means the process was not running. */
    val baselinePids: Map<String, List<Int>>,
    val detail: String? = null,
)

@Serializable
data class JournalEntry(
    val id: String,
    val timestampMillis: Long,
    val packageId: String,
    val kind: ActionKind,
    /** The exact state before the change. */
    val previous: PackageState,
    /** The state expected after it, or null for a force-stop. */
    val target: PackageState?,
    val steps: List<Operation>,
    val stepsDone: Int = 0,
    val status: EntryStatus,
    val watch: WatchRecord? = null,
    /** A command was sent and its outcome is not known (timeout, cut connection, app death). Kept after ACCEPTED. */
    val uncertainSend: Boolean = false,
    /** While this is in the future, a late-landing command is still possible. */
    val settleUntilMillis: Long? = null,
    val acceptedState: PackageState? = null,
) {
    /**
     * Whether the device [live] state is where this change was going. An uninstall only needs the package gone for the
     * user: its `enabled` value may be reset by the system and is not part of the answer.
     */
    fun reaches(live: PackageState): Boolean =
        target != null && if (kind == ActionKind.UNINSTALL) !live.installed else live.sameAs(target)

    /** An open entry stops new disables and force-stops until the user resolves it. Restores are never stopped. */
    val isBlocking: Boolean
        get() = status in setOf(EntryStatus.PENDING, EntryStatus.NEEDS_REVIEW, EntryStatus.UNVERIFIED) ||
            (status == EntryStatus.DONE && watch?.state in setOf(WatchState.RUNNING, WatchState.ANOMALY, WatchState.INCONCLUSIVE))
}

