package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.EssentialReason
import com.prototype.tuneupfortv.device.PackageRecord
import kotlinx.serialization.Serializable

/**
 * The six things the app can do to a package. Only [UNINSTALL_USER] removes it, and only for user 0, data included:
 * the one command here that can lose an app and what it holds, and the user asks for it knowing that.
 */
@Serializable
enum class Operation { FORCE_STOP, DISABLE_USER, DEFAULT_STATE, ENABLE, INSTALL_EXISTING, UNINSTALL_USER }

enum class RejectReason {
    /** The live read failed, came back empty or incomplete: nothing is done on data that cannot be trusted. */
    UNREADABLE,
    THIS_APP,
    ESSENTIAL,
    NOT_INSTALLED,
    ALREADY_INSTALLED,
    ALREADY_DISABLED,

    /** A force stop was asked for something that is not running: it would do nothing. */
    NOT_RUNNING,

    /** A restore was asked for a package that is already enabled. */
    NOTHING_TO_RESTORE,

    /** The device has no serial number that can tell it apart from another: the identity fence would mean nothing, so it stays read-only. */
    UNRELIABLE_DEVICE,

    /** Android 12 lets the ADB shell move a package only between states 0, 1 and 3. */
    NOT_CHANGEABLE_FROM_SHELL,

    /** Turning this on (or turning a stock launcher off) would leave the TV with the setup app as its only Home: the crash loop. */
    WOULD_CRASH_LOOP,

    /** The launcher swap is not over (it has a record), and this would make it impossible to undo. */
    LAUNCHER_SWAP_ACTIVE,

    /** The launcher that is meant to take over is not installed and enabled. */
    LAUNCHER_NOT_READY,
}

sealed interface GuardVerdict {
    data object Allowed : GuardVerdict
    data class Rejected(val reason: RejectReason) : GuardVerdict
}

/**
 * A package as read from the device right now, with why it is protected. A null [record] means the read failed.
 * [launchers] is what the launcher rules need; they only apply when [launcherRulesApply] is set, which the executor
 * does on every live read. The screens check a package from the last snapshot and leave it off: the real check runs again.
 */
data class LivePackage(
    val record: PackageRecord?,
    val essentialReason: EssentialReason?,
    val launchers: LauncherFacts? = null,
    val launcherRulesApply: Boolean = false,
)

/** Whether the launcher swap is the one asking, and whether it can assume the setup app will already be off. */
enum class SwapMode { NONE, ACTIVE, PRECHECK }

/**
 * The last line of defense. It runs before every command, on a live read, and it fails closed: when
 * anything is unclear, the answer is no. It is a pure function of (what the device says now, what is asked).
 */
object Guard {
    fun check(operation: Operation, live: LivePackage, swap: SwapMode = SwapMode.NONE): GuardVerdict {
        val record = live.record
        val state = record?.state
        if (record == null || !record.isComplete || state == null) return reject(RejectReason.UNREADABLE)
        if (live.essentialReason == EssentialReason.THIS_APP) return reject(RejectReason.THIS_APP)
        if (live.launcherRulesApply) launcherVerdict(operation, record.id, live, swap)?.let { return it }
        // The swap lifts exactly two protections, and only for the packages it replaces: never PERSISTENT, system uid or the keyboard.
        val lifted = swap != SwapMode.NONE && record.id in LauncherPackages.REPLACEABLE &&
            live.essentialReason in setOf(EssentialReason.PROFILE_PROTECTED, EssentialReason.HOME_LAUNCHER)
        val essentialReason = if (lifted) null else live.essentialReason
        return when (operation) {
            Operation.FORCE_STOP -> when {
                essentialReason != null -> reject(RejectReason.ESSENTIAL)
                !state.installed -> reject(RejectReason.NOT_INSTALLED)
                else -> GuardVerdict.Allowed
            }
            Operation.DISABLE_USER -> when {
                essentialReason != null -> reject(RejectReason.ESSENTIAL)
                !state.installed -> reject(RejectReason.NOT_INSTALLED)
                state.isDisabledByUser -> reject(RejectReason.ALREADY_DISABLED)
                !state.isEnabled -> reject(RejectReason.NOT_CHANGEABLE_FROM_SHELL)
                else -> GuardVerdict.Allowed
            }
            // Enabling is always allowed for protected packages too: it can only repair, never break.
            Operation.DEFAULT_STATE, Operation.ENABLE -> when {
                !state.installed -> reject(RejectReason.NOT_INSTALLED)
                !state.isShellMovable -> reject(RejectReason.NOT_CHANGEABLE_FROM_SHELL)
                else -> GuardVerdict.Allowed
            }
            Operation.UNINSTALL_USER -> when {
                essentialReason != null -> reject(RejectReason.ESSENTIAL)
                !state.installed -> reject(RejectReason.NOT_INSTALLED)
                else -> GuardVerdict.Allowed
            }
            Operation.INSTALL_EXISTING -> if (state.installed) reject(RejectReason.ALREADY_INSTALLED) else GuardVerdict.Allowed
        }
    }

    /**
     * The rules that keep the TV from ending with the setup app as its only Home. A verdict means "stop here"; null means
     * these rules have nothing against it and the general ones decide.
     */
    private fun launcherVerdict(operation: Operation, packageId: String, live: LivePackage, swap: SwapMode): GuardVerdict? {
        val facts = live.launchers
        val comesBack = operation == Operation.ENABLE || operation == Operation.DEFAULT_STATE || operation == Operation.INSTALL_EXISTING
        if (packageId == LauncherPackages.SETUPWRAITH && comesBack) {
            // Turning it on is only safe while a stock launcher can take Home: if that is not known, it is not safe.
            val stockCanTakeHome = facts != null && !facts.stockChangeOpen && facts.homeCandidates.any { it in LauncherPackages.STOCK }
            return if (stockCanTakeHome) null else reject(RejectReason.WOULD_CRASH_LOOP)
        }
        if (operation == Operation.UNINSTALL_USER && packageId in LauncherPackages.STOCK) {
            return if (facts == null || facts.swapRecordPresent) reject(RejectReason.LAUNCHER_SWAP_ACTIVE) else null
        }
        if (operation == Operation.DISABLE_USER && swap != SwapMode.NONE && packageId in LauncherPackages.REPLACEABLE) {
            // Before the swap starts the launcher is not installed yet: the pre-check only needs the read to have worked.
            if (facts == null || (swap == SwapMode.ACTIVE && !facts.lightLauncherReady)) return reject(RejectReason.LAUNCHER_NOT_READY)
            val settingUpTheStock = packageId in LauncherPackages.STOCK
            if (settingUpTheStock && swap == SwapMode.ACTIVE && facts.setupwraithOn) return reject(RejectReason.WOULD_CRASH_LOOP)
        }
        return null
    }

    private fun reject(reason: RejectReason): GuardVerdict = GuardVerdict.Rejected(reason)
}
