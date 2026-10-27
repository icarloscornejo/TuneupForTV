package com.prototype.tuneupfortv.device

import kotlinx.serialization.Serializable

/**
 * The user-0 state of a package, from the `User 0:` line of `dumpsys package`.
 *
 * Android 12 lets the ADB shell move a package only between the states 0, 1 and 3 (anything else throws a
 * SecurityException): that rule lives here, once, so the guard, the planner and the screens cannot disagree about it.
 */
@Serializable
data class PackageState(
    val installed: Boolean,
    /** [DEFAULT], [ENABLED], [DISABLED], [DISABLED_USER] or [DISABLED_UNTIL_USED]. */
    val enabled: Int,
    /** Informational only: the system clears it as soon as any service of the package rebinds. */
    val stopped: Boolean,
) {
    val isDisabledByUser: Boolean get() = enabled == DISABLED_USER

    /** Disabled in any way: by the user, by the system or until first use. */
    val isDisabled: Boolean get() = enabled in DISABLED_STATES

    /** Enabled, by default or explicitly: the states from which a package can be disabled. */
    val isEnabled: Boolean get() = enabled == DEFAULT || enabled == ENABLED

    /** The ADB shell can change a package from this state, and into it. */
    val isShellMovable: Boolean get() = enabled in SHELL_STATES

    /** Compares only what disable and restore can change. `stopped` is informational and never compared. */
    fun sameAs(other: PackageState): Boolean = installed == other.installed && enabled == other.enabled

    companion object {
        const val DEFAULT: Int = 0
        const val ENABLED: Int = 1
        const val DISABLED: Int = 2
        const val DISABLED_USER: Int = 3
        const val DISABLED_UNTIL_USED: Int = 4

        private val DISABLED_STATES: Set<Int> = setOf(DISABLED, DISABLED_USER, DISABLED_UNTIL_USED)
        private val SHELL_STATES: Set<Int> = setOf(DEFAULT, ENABLED, DISABLED_USER)
    }
}

/** What `dumpsys package` knows about one package. Ids are device output, not yet validated for commands. */
data class PackageRecord(
    val id: String,
    val flags: Set<String>,
    /** The shared user name, for example `android.uid.system`, or null. */
    val sharedUser: String?,
    val installer: String?,
    /** Null when no `User 0:` line carried data. */
    val state: PackageState?,
    /**
     * False when the record cannot be trusted as a whole: it had no `pkgFlags=` line (cut short), its shared user
     * line could not be understood (the system-uid protection would silently turn off), or its header appeared twice.
     */
    val isComplete: Boolean = true,
) {
    val isSystem: Boolean get() = "SYSTEM" in flags
    val isPersistent: Boolean get() = "PERSISTENT" in flags
    val isUpdatedSystemApp: Boolean get() = "UPDATED_SYSTEM_APP" in flags
    val usesSystemUid: Boolean get() = sharedUser?.startsWith("android.uid.system") == true
}

/** One userspace row of `ps -A -o USER,PID,RSS,NAME`. Kernel threads are not included. */
data class ProcessRecord(
    val pid: Int,
    val rssKb: Long,
    val name: String,
)
