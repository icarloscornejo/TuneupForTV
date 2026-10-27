package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.EssentialReason
import com.prototype.tuneupfortv.device.PackageRecord
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.Guard
import com.prototype.tuneupfortv.safety.LauncherFacts
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.SwapMode
import com.prototype.tuneupfortv.safety.GuardVerdict
import com.prototype.tuneupfortv.safety.LivePackage
import com.prototype.tuneupfortv.safety.Operation
import com.prototype.tuneupfortv.safety.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardTest {
    private fun live(
        enabled: Int = 0,
        installed: Boolean = true,
        essential: EssentialReason? = null,
        flagsKnown: Boolean = true,
        hasState: Boolean = true,
    ): LivePackage = LivePackage(
        PackageRecord(
            id = "com.example.app",
            flags = setOf("SYSTEM"),
            sharedUser = null,
            installer = null,
            state = if (hasState) PackageState(installed, enabled, stopped = false) else null,
            isComplete = flagsKnown,
        ),
        essential,
    )

    private fun verdict(operation: Operation, live: LivePackage): GuardVerdict = Guard.check(operation, live)
    private fun rejected(reason: RejectReason): GuardVerdict = GuardVerdict.Rejected(reason)

    @Test
    fun aNormalAppCanBeStoppedAndDisabled() {
        assertEquals(GuardVerdict.Allowed, verdict(Operation.FORCE_STOP, live()))
        assertEquals(GuardVerdict.Allowed, verdict(Operation.DISABLE_USER, live(enabled = 0)))
        assertEquals(GuardVerdict.Allowed, verdict(Operation.DISABLE_USER, live(enabled = 1)))
    }

    @Test
    fun uninstallIsAllowedForANormalAppEvenIfItIsDisabled() {
        assertEquals(GuardVerdict.Allowed, verdict(Operation.UNINSTALL_USER, live(enabled = 0)))
        assertEquals(GuardVerdict.Allowed, verdict(Operation.UNINSTALL_USER, live(enabled = 3)))
    }

    @Test
    fun uninstallIsRefusedForProtectedThisAppMissingAndUnreadablePackages() {
        EssentialReason.entries.filter { it != EssentialReason.THIS_APP }.forEach { reason ->
            assertEquals(reason.name, rejected(RejectReason.ESSENTIAL), verdict(Operation.UNINSTALL_USER, live(essential = reason)))
        }
        assertEquals(rejected(RejectReason.THIS_APP), verdict(Operation.UNINSTALL_USER, live(essential = EssentialReason.THIS_APP)))
        assertEquals(rejected(RejectReason.NOT_INSTALLED), verdict(Operation.UNINSTALL_USER, live(installed = false)))
        assertEquals(rejected(RejectReason.UNREADABLE), verdict(Operation.UNINSTALL_USER, live(flagsKnown = false)))
        assertEquals(rejected(RejectReason.UNREADABLE), verdict(Operation.UNINSTALL_USER, live(hasState = false)))
    }

    @Test
    fun everyEssentialReasonBlocksDisableAndForceStop() {
        EssentialReason.entries.filter { it != EssentialReason.THIS_APP }.forEach { reason ->
            assertEquals(reason.name, rejected(RejectReason.ESSENTIAL), verdict(Operation.DISABLE_USER, live(essential = reason)))
            assertEquals(reason.name, rejected(RejectReason.ESSENTIAL), verdict(Operation.FORCE_STOP, live(essential = reason)))
        }
    }

    @Test
    fun aPersistentPackageLikeTimersIsRejectedEvenThoughItIsNotOnTheList() {
        assertEquals(rejected(RejectReason.ESSENTIAL), verdict(Operation.DISABLE_USER, live(essential = EssentialReason.PERSISTENT)))
    }

    @Test
    fun enablingAProtectedPackageIsAllowedBecauseItCanOnlyRepair() {
        assertEquals(GuardVerdict.Allowed, verdict(Operation.DEFAULT_STATE, live(enabled = 3, essential = EssentialReason.PERSISTENT)))
        assertEquals(GuardVerdict.Allowed, verdict(Operation.ENABLE, live(enabled = 3, essential = EssentialReason.SYSTEM_UID)))
    }

    @Test
    fun thisAppIsNeverTouchedByAnyOperation() {
        Operation.entries.forEach {
            assertEquals(it.name, rejected(RejectReason.THIS_APP), verdict(it, live(essential = EssentialReason.THIS_APP)))
        }
    }

    @Test
    fun itFailsClosedWhenTheLiveReadIsMissingOrIncomplete() {
        val unreadable = listOf(
            LivePackage(null, null),
            live(flagsKnown = false),
            live(hasState = false),
        )
        unreadable.forEach { bad ->
            Operation.entries.forEach { assertEquals("$it with $bad", rejected(RejectReason.UNREADABLE), verdict(it, bad)) }
        }
    }

    @Test
    fun disableAndForceStopNeedAnInstalledPackage() {
        assertEquals(rejected(RejectReason.NOT_INSTALLED), verdict(Operation.DISABLE_USER, live(installed = false)))
        assertEquals(rejected(RejectReason.NOT_INSTALLED), verdict(Operation.FORCE_STOP, live(installed = false)))
    }

    @Test
    fun disableOnlyStartsFromStateZeroOrOne() {
        assertEquals(rejected(RejectReason.ALREADY_DISABLED), verdict(Operation.DISABLE_USER, live(enabled = 3)))
        assertEquals(rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), verdict(Operation.DISABLE_USER, live(enabled = 2)))
        assertEquals(rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), verdict(Operation.DISABLE_USER, live(enabled = 4)))
    }

    @Test
    fun restoreFromStateTwoOrFourIsRejectedBecauseTheShellCannotLeaveThem() {
        listOf(Operation.DEFAULT_STATE, Operation.ENABLE).forEach {
            assertEquals(rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), verdict(it, live(enabled = 2)))
            assertEquals(rejected(RejectReason.NOT_CHANGEABLE_FROM_SHELL), verdict(it, live(enabled = 4)))
            assertEquals(GuardVerdict.Allowed, verdict(it, live(enabled = 3)))
        }
    }

    @Test
    fun enablingNeedsAnInstalledPackage() {
        assertEquals(rejected(RejectReason.NOT_INSTALLED), verdict(Operation.DEFAULT_STATE, live(installed = false)))
    }

    @Test
    fun installExistingOnlyForAPackageThatIsNotInstalled() {
        assertEquals(GuardVerdict.Allowed, verdict(Operation.INSTALL_EXISTING, live(installed = false, enabled = 2)))
        assertEquals(rejected(RejectReason.ALREADY_INSTALLED), verdict(Operation.INSTALL_EXISTING, live(installed = true)))
    }

    // region the launcher rules

    private val on = PackageState(installed = true, enabled = 0, stopped = false)
    private val off = PackageState(installed = true, enabled = 3, stopped = false)
    private val stock = "com.google.android.apps.tv.launcherx"

    private fun launcherLive(
        packageId: String,
        state: PackageState,
        candidates: Set<String> = setOf(stock),
        setupwraith: PackageState? = on,
        light: PackageState? = on,
        recordPresent: Boolean = false,
        stockChangeOpen: Boolean = false,
        essential: EssentialReason? = EssentialReason.PROFILE_PROTECTED,
        facts: Boolean = true,
    ): LivePackage =
        LivePackage(
            PackageRecord(packageId, setOf("SYSTEM"), null, null, state),
            essential,
            if (facts) LauncherFacts(setupwraith, light, candidates, recordPresent, stockChangeOpen) else null,
            launcherRulesApply = true,
        )

    @Test
    fun theSetupAppOnlyComesBackWhileAStockLauncherCanTakeHome() {
        val setup = LauncherPackages.SETUPWRAITH
        listOf(Operation.ENABLE, Operation.DEFAULT_STATE).forEach { operation ->
            assertEquals(GuardVerdict.Allowed, Guard.check(operation, launcherLive(setup, off)))
            // No stock launcher among the Home candidates: the crash loop.
            assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(operation, launcherLive(setup, off, candidates = emptySet())))
            // A stock launcher that is enabled but has no HOME activity is not among the candidates, and another launcher does not count.
            assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(operation, launcherLive(setup, off, candidates = setOf("com.gathin.tvhome"))))
            // The read failed: unknown is never safe.
            assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(operation, launcherLive(setup, off, facts = false)))
            // A turn-off of a stock launcher may still land.
            assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(operation, launcherLive(setup, off, stockChangeOpen = true)))
        }
        val gone = PackageState(installed = false, enabled = 0, stopped = false)
        assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(Operation.INSTALL_EXISTING, launcherLive(setup, gone, candidates = emptySet())))
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.INSTALL_EXISTING, launcherLive(setup, gone)))
    }

    @Test
    fun theScreensDoNotApplyTheLauncherRulesWithoutALiveRead() {
        val preview = LivePackage(PackageRecord(LauncherPackages.SETUPWRAITH, setOf("SYSTEM"), null, null, off), EssentialReason.PROFILE_PROTECTED)
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.ENABLE, preview))
    }

    @Test
    fun aStockLauncherIsNeverUninstalledWhileASwapIsOnRecord() {
        val tvlauncher = "com.google.android.tvlauncher"
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.UNINSTALL_USER, launcherLive(tvlauncher, on, essential = null)))
        assertEquals(rejected(RejectReason.LAUNCHER_SWAP_ACTIVE), Guard.check(Operation.UNINSTALL_USER, launcherLive(tvlauncher, on, essential = null, recordPresent = true)))
        assertEquals(rejected(RejectReason.LAUNCHER_SWAP_ACTIVE), Guard.check(Operation.UNINSTALL_USER, launcherLive(tvlauncher, on, essential = null, facts = false)))
    }

    @Test
    fun theSwapLiftsTheProtectionOfWhatItReplacesAndNothingElse() {
        val setup = LauncherPackages.SETUPWRAITH
        // Without the swap the protection stands.
        assertEquals(rejected(RejectReason.ESSENTIAL), Guard.check(Operation.DISABLE_USER, launcherLive(setup, on)))
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.DISABLE_USER, launcherLive(setup, on), SwapMode.ACTIVE))
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = off, essential = EssentialReason.HOME_LAUNCHER), SwapMode.ACTIVE))
        // Persistent, system uid and keyboard keep blocking, even for a package the swap replaces.
        listOf(EssentialReason.PERSISTENT, EssentialReason.SYSTEM_UID, EssentialReason.KEYBOARD).forEach { reason ->
            assertEquals(reason.name, rejected(RejectReason.ESSENTIAL), Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = off, essential = reason), SwapMode.ACTIVE))
        }
        // Nothing outside the list is lifted.
        assertEquals(rejected(RejectReason.ESSENTIAL), Guard.check(Operation.DISABLE_USER, launcherLive("com.android.vending", on), SwapMode.ACTIVE))
    }

    @Test
    fun theStockLauncherGoesOffOnlyAfterTheSetupAppAndWithTheNewLauncherReady() {
        assertEquals(rejected(RejectReason.WOULD_CRASH_LOOP), Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = on), SwapMode.ACTIVE))
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = off), SwapMode.ACTIVE))
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = null), SwapMode.ACTIVE))
        assertEquals(rejected(RejectReason.LAUNCHER_NOT_READY), Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = off, light = null), SwapMode.ACTIVE))
        assertEquals(rejected(RejectReason.LAUNCHER_NOT_READY), Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = off, light = off), SwapMode.ACTIVE))
        assertEquals(rejected(RejectReason.LAUNCHER_NOT_READY), Guard.check(Operation.DISABLE_USER, launcherLive(LauncherPackages.DREAMX, on, light = null, essential = null), SwapMode.ACTIVE))
        // The pre-check runs before anything is installed or turned off, and only asks what else would refuse.
        assertEquals(GuardVerdict.Allowed, Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, setupwraith = on, light = null), SwapMode.PRECHECK))
        assertEquals(rejected(RejectReason.ESSENTIAL), Guard.check(Operation.DISABLE_USER, launcherLive(stock, on, essential = EssentialReason.PERSISTENT), SwapMode.PRECHECK))
    }

    // endregion
}
