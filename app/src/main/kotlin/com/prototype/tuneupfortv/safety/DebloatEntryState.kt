package com.prototype.tuneupfortv.safety

/** Why a list entry can or cannot be disabled on this device, for the pill and the counters. */
enum class DebloatEntryState {
    WILL_DISABLE,
    ALREADY_DISABLED,
    NOT_ON_TV,
    PROTECTED,
    ;

    companion object {
        /** [reason] is what the batch planner answered for a disable: null means it will run. */
        fun from(reason: RejectReason?): DebloatEntryState =
            when (reason) {
                null -> WILL_DISABLE
                RejectReason.ALREADY_DISABLED, RejectReason.NOT_CHANGEABLE_FROM_SHELL -> ALREADY_DISABLED
                // A package missing from the snapshot has no record, which the planner reports as unreadable.
                RejectReason.UNREADABLE, RejectReason.NOT_INSTALLED -> NOT_ON_TV
                // Anything else cannot be run either, and a state that is not understood fails closed.
                else -> PROTECTED
            }
    }
}
