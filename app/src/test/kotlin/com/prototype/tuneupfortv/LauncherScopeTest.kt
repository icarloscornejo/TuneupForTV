package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.LauncherPlan
import com.prototype.tuneupfortv.safety.LauncherReading
import com.prototype.tuneupfortv.safety.LauncherScope
import com.prototype.tuneupfortv.safety.LauncherUndo
import com.prototype.tuneupfortv.safety.ScopeProblem
import com.prototype.tuneupfortv.safety.ScopeResult
import com.prototype.tuneupfortv.safety.SwapRecord
import com.prototype.tuneupfortv.safety.SwapStep
import com.prototype.tuneupfortv.safety.WifiOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which TVs the swap accepts, and what it would do on them. Pure: no device, no commands. */
class LauncherScopeTest {
    private val stock = "com.google.android.apps.tv.launcherx"
    private val enabled = PackageState(installed = true, enabled = 0, stopped = false)
    private val disabled = PackageState(installed = true, enabled = 3, stopped = false)

    private fun reading(
        home: String = "$stock/.MainActivity",
        candidates: Set<String> = setOf(stock),
        light: PackageState? = null,
        lightVersion: Long? = null,
        setupwraith: PackageState? = enabled,
        dreamx: PackageState? = enabled,
        screensaver: String? = null,
        wifi: WifiOwner = WifiOwner.OTHER,
        extra: Map<String, PackageState?> = emptyMap(),
    ): LauncherReading =
        LauncherReading(
            homeComponent = home,
            homeCandidates = candidates,
            packages = LauncherPackages.RELEVANT.associateWith { null } + mapOf(
                stock to enabled,
                LauncherPackages.LIGHT to light,
                LauncherPackages.SETUPWRAITH to setupwraith,
                LauncherPackages.DREAMX to dreamx,
            ) + extra,
            lightLauncherVersion = lightVersion,
            screensaver = screensaver,
            accessibility = null,
            wifi = wifi,
        )

    private fun plan(reading: LauncherReading, existing: SwapRecord? = null, release: Long = 10001L): LauncherPlan =
        (LauncherScope.evaluate(reading, existing, release) as ScopeResult.Ok).plan

    private fun problem(reading: LauncherReading, existing: SwapRecord? = null): ScopeProblem =
        (LauncherScope.evaluate(reading, existing, 10001L) as ScopeResult.Rejected).problem

    @Test
    fun aStockHomeIsReplacedAndTheSetupAppGoesBeforeIt() {
        val plan = plan(reading())
        assertEquals(stock, plan.stockToDisable)
        assertEquals("$stock/.MainActivity", plan.homeComponent)
        assertTrue(plan.install && plan.disableSetupwraith && plan.disableDreamx)
        val steps = plan.steps
        assertTrue(steps.indexOf(SwapStep.SETUPWRAITH) < steps.indexOf(SwapStep.STOCK))
        assertTrue(steps.indexOf(SwapStep.STOCK) < steps.indexOf(SwapStep.HOME))
        assertTrue(steps.indexOf(SwapStep.SCREENSAVER) < steps.indexOf(SwapStep.DREAMX))
        assertEquals(SwapStep.HOME, steps.filter { it == SwapStep.HOME }.single())
    }

    @Test
    fun theChooserIsAcceptedWithExactlyOneStockLauncher() {
        val chooser = "android/com.android.internal.app.ResolverActivity"
        assertEquals(stock, plan(reading(home = chooser)).stockToDisable)
        assertNull(plan(reading(home = chooser)).homeComponent)
        assertEquals(ScopeProblem.NO_STOCK, problem(reading(home = chooser, candidates = emptySet())))
        assertEquals(
            ScopeProblem.SEVERAL_STOCK,
            problem(reading(home = chooser, candidates = setOf(stock, "com.google.android.tvlauncher"))),
        )
    }

    @Test
    fun aLauncherOfTheUserIsKeptAndOnlyHomeMoves() {
        val plan = plan(reading(home = "com.gathin.tvhome/.MainActivity", candidates = setOf(stock, "com.gathin.tvhome")))
        assertNull(plan.stockToDisable)
        assertEquals("com.gathin.tvhome/.MainActivity", plan.homeComponent)
        assertFalse(SwapStep.STOCK in plan.steps)
    }

    @Test
    fun anUnknownLauncherThatCouldTakeHomeStopsEverythingBeforeTheFirstChange() {
        assertEquals(ScopeProblem.UNKNOWN_HOME_CANDIDATE, problem(reading(candidates = setOf(stock, "com.duokan.factorytest"))))
    }

    @Test
    fun anotherStockLauncherThatWouldWinOnPriorityIsRefused() {
        assertEquals(ScopeProblem.ANOTHER_STOCK_ENABLED, problem(reading(candidates = setOf(stock, "com.google.android.tvlauncher"))))
    }

    @Test
    fun aHomeThatAlreadyIsLightLauncherOnlyOffersAnUpdate() {
        val plan = plan(reading(home = "${LauncherPackages.LIGHT}/.MainActivity", candidates = setOf(LauncherPackages.LIGHT), light = enabled, lightVersion = 10000L))
        assertTrue(plan.updateOnly && plan.install)
        assertEquals(listOf(SwapStep.INSTALL, SwapStep.PERMISSIONS, SwapStep.COMPILE, SwapStep.HOME), plan.steps)
        assertNull(plan.stockToDisable)
        val current = plan(reading(home = "${LauncherPackages.LIGHT}/.MainActivity", candidates = setOf(LauncherPackages.LIGHT), light = enabled, lightVersion = 10001L))
        assertTrue(current.isUpToDate)
    }

    @Test
    fun aNewerInstalledVersionIsNeverReplaced() {
        assertFalse(plan(reading(light = enabled, lightVersion = 10500L)).install)
    }

    @Test
    fun aLauncherThatIsInstalledButOffIsEnabledBeforeAnythingIsTurnedOff() {
        val plan = plan(reading(light = disabled, lightVersion = 10001L))
        assertTrue(plan.enable && !plan.install)
        assertTrue(plan.steps.indexOf(SwapStep.ENABLE) < plan.steps.indexOf(SwapStep.SETUPWRAITH))
    }

    @Test
    fun aScreensaverThatCannotBeWrittenBackIsRefused() {
        assertEquals(ScopeProblem.SCREENSAVER_NOT_SUPPORTED, problem(reading(screensaver = "com.x/.Y\$Z")))
        assertEquals("a.b/.C,d.e/.F", plan(reading(screensaver = "a.b/.C,d.e/.F")).let { "a.b/.C,d.e/.F" })
    }

    @Test
    fun aTvThatUninstallsAddedLaunchersAtEveryStartIsRefusedWhateverTheSwapWouldDo() {
        val remover = "com.nes.coreservice"
        assertEquals(ScopeProblem.HOME_REMOVER, problem(reading().copy(homeRemover = remover)))
        // Also an update of a launcher that is already Home, and a swap that is already on record.
        assertEquals(ScopeProblem.HOME_REMOVER, problem(reading(home = "${LauncherPackages.LIGHT}/.MainActivity").copy(homeRemover = remover)))
        val record = SwapRecord(updateOnly = false, homeComponent = "$stock/.MainActivity", screensaver = null, packages = emptyMap())
        assertEquals(ScopeProblem.HOME_REMOVER, problem(reading().copy(homeRemover = remover), existing = record))
        // A product that could not be read is not exempt.
        assertEquals(ScopeProblem.HOME_REMOVER, problem(reading().copy(homeRemover = remover, product = null)))
        assertEquals(ScopeProblem.HOME_REMOVER, problem(reading().copy(homeRemover = remover, product = "SEI600NK4")))
    }

    @Test
    fun theNokia8010KeepsAnAddedLauncherSoItIsNotRefused() {
        assertTrue(plan(reading().copy(homeRemover = "com.nes.coreservice", product = "SEI804NK")).stockToDisable == stock)
        assertTrue(plan(reading().copy(homeRemover = null, product = "BRAVIA_BF1_4K_M_A")).stockToDisable == stock)
    }

    @Test
    fun theWifiWarningOnlyAppliesWhenTheSetupAppIsGoingOffAndTheNetworkMayBeItsOwn() {
        assertFalse(plan(reading(wifi = WifiOwner.OTHER)).wifiWarning)
        assertTrue(plan(reading(wifi = WifiOwner.SETUPWRAITH)).wifiWarning)
        // Unreadable counts as possibly its own: the warning is shown rather than skipped.
        assertTrue(plan(reading(wifi = WifiOwner.UNKNOWN)).wifiWarning)
        assertFalse(plan(reading(wifi = WifiOwner.SETUPWRAITH, setupwraith = disabled)).wifiWarning)
    }

    @Test
    fun theUndoGoesDreamThenStockThenSetupThenHomeThenScreensaver() {
        val record = SwapRecord(
            updateOnly = false,
            homeComponent = "$stock/.MainActivity",
            screensaver = null,
            packages = mapOf(
                LauncherPackages.SETUPWRAITH to enabled,
                stock to enabled,
                LauncherPackages.DREAMX to enabled,
                LauncherPackages.LIGHT to disabled,
            ),
        )
        assertEquals(
            listOf(SwapStep.DREAMX, SwapStep.STOCK, SwapStep.SETUPWRAITH, SwapStep.HOME, SwapStep.SCREENSAVER, SwapStep.RESTORE_LIGHT),
            LauncherUndo.steps(record),
        )
    }
}
