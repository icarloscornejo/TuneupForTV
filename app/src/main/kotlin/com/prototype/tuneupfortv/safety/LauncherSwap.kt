package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.ScreensaverValue
import java.io.IOException
import kotlinx.serialization.Serializable

/** The packages the launcher swap cares about. */
object LauncherPackages {
    const val LIGHT: String = "com.prototype.lightlauncher"
    const val SETUPWRAITH: String = "com.google.android.tungsten.setupwraith"
    const val DREAMX: String = "com.google.android.apps.tv.dreamx"
    const val FALLBACK_HOME: String = "com.android.tv.settings"

    /** What `resolve-activity` answers when no launcher was picked: the system chooser. */
    const val CHOOSER: String = "android"

    val STOCK: Set<String> = linkedSetOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",
    )

    /** Firmware services that uninstall, at every start, the launchers that are not part of the system (NES firmware). */
    val HOME_REMOVERS: Set<String> = linkedSetOf("com.nes.coreservice")

    /** `ro.product.name` of the TVs that carry a [HOME_REMOVERS] package but keep an added launcher across restarts (Nokia Streaming Box 8010). */
    val HOME_REMOVER_SAFE_PRODUCTS: Set<String> = linkedSetOf("SEI804NK")

    /** What the swap may turn off, and only while it runs. Everything else keeps its protection. */
    val REPLACEABLE: Set<String> = STOCK + SETUPWRAITH + DREAMX

    /** The packages a live read has to describe when one of them is the target. */
    val RELEVANT: Set<String> = REPLACEABLE + LIGHT

    const val HOME_COMPONENT: String = "$LIGHT/.MainActivity"
    const val DREAM_COMPONENT: String = "$LIGHT/.ClockDream"

    val PERMISSIONS: List<String> = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.READ_TV_LISTINGS",
        "android.permission.WRITE_SECURE_SETTINGS",
    )
}

enum class WifiOwner { SETUPWRAITH, OTHER, UNKNOWN }

/** Everything the swap needs to know about the TV, read at once. */
data class LauncherReading(
    /** The component the Home button would open, or null when it could not be read. */
    val homeComponent: String?,
    /** The packages with an enabled HOME activity. */
    val homeCandidates: Set<String>,
    /** For each of [LauncherPackages.RELEVANT]: its state, or null when the package has no record at all. */
    val packages: Map<String, PackageState?>,
    val lightLauncherVersion: Long?,
    /** `screensaver_components`, or null when it is not set. */
    val screensaver: String?,
    /** `enabled_accessibility_services`, or null when it is not set. */
    val accessibility: String?,
    val wifi: WifiOwner,
    /** The first of [LauncherPackages.HOME_REMOVERS] that is on the TV, whatever its state. */
    val homeRemover: String? = null,
    /** `ro.product.name`, or null when it could not be read. */
    val product: String? = null,
) {
    val homePackage: String? get() = homeComponent?.substringBefore('/')
}

/** What the Guard needs to know about the launchers, read live. */
data class LauncherFacts(
    val setupwraith: PackageState?,
    val lightLauncher: PackageState?,
    val homeCandidates: Set<String>,
    /** A swap record exists, or the record cannot be read: either way the swap is not over. */
    val swapRecordPresent: Boolean,
    /** A change to a stock launcher is still open in the journal: it may land at any moment. */
    val stockChangeOpen: Boolean,
) {
    val lightLauncherReady: Boolean get() = lightLauncher?.let { it.installed && it.isEnabled } == true
    val setupwraithOn: Boolean get() = setupwraith?.let { it.installed && it.isEnabled } == true
}

/** Which kind of write may still be on its way after an answer was lost. */
@Serializable
enum class PendingKind { INSTALL, HOME, SCREENSAVER }

@Serializable
data class PendingWrite(
    val kind: PendingKind,
    val sinceMillis: Long,
    /** What a read shows once the write has landed: the Home package, or the screensaver ("" for none). Null for an install. */
    val expected: String? = null,
)

/**
 * What the undo needs, written before anything changes. [packages] holds the exact state each touched package had;
 * a package that was not installed has no entry.
 */
@Serializable
data class SwapRecord(
    /** An update of a launcher that was already Home: there is nothing to undo. */
    val updateOnly: Boolean,
    /** The Home component before the swap, or null when the chooser was showing. */
    val homeComponent: String?,
    val screensaver: String?,
    val packages: Map<String, PackageState>,
    /** setupwraith was turned off while the saved Wi-Fi network might be its own. */
    val wifiMayBeLost: Boolean = false,
    val pending: PendingWrite? = null,
)

/** Where the record is kept. */
interface SwapRecordStore {
    /** Null when there is no record. Throws [SwapRecordException] when it exists and cannot be read: never "no record" instead. */
    fun load(): SwapRecord?

    /** Writes durably, or throws [SwapRecordException]. */
    fun save(record: SwapRecord)

    fun delete()
}

class SwapRecordException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** For tests and sessions that never swap. */
class InMemorySwapRecordStore(private var record: SwapRecord? = null) : SwapRecordStore {
    override fun load(): SwapRecord? = record

    override fun save(record: SwapRecord) {
        this.record = record
    }

    override fun delete() {
        record = null
    }
}

/** The steps of the swap, in order, and of its undo. [ACCESSIBILITY] only shows up when it could not be confirmed. */
enum class SwapStep { INSTALL, ENABLE, PERMISSIONS, COMPILE, SCREENSAVER, SETUPWRAITH, STOCK, HOME, DREAMX, ACCESSIBILITY, RESTORE_LIGHT }

enum class ScopeProblem {
    UNREADABLE,
    RECORD_UNREADABLE,
    HOME_NOT_SUPPORTED,
    NO_STOCK,
    SEVERAL_STOCK,
    UNKNOWN_HOME_CANDIDATE,
    ANOTHER_STOCK_ENABLED,
    SCREENSAVER_NOT_SUPPORTED,
    HOME_REMOVER,
}

/** What the swap will do on this TV. */
data class LauncherPlan(
    val updateOnly: Boolean,
    val install: Boolean,
    val enable: Boolean,
    val stockToDisable: String?,
    val disableSetupwraith: Boolean,
    val disableDreamx: Boolean,
    /** The Home before the swap: a component, or null for the chooser. */
    val homeComponent: String?,
    val wifiWarning: Boolean,
    /** Home already opens LightLauncher. */
    val homeIsLight: Boolean,
) {
    val steps: List<SwapStep> by lazy {
        buildList {
            if (install) add(SwapStep.INSTALL)
            if (enable) add(SwapStep.ENABLE)
            if (install || !updateOnly) add(SwapStep.PERMISSIONS)
            if (install) add(SwapStep.COMPILE)
            if (!updateOnly) add(SwapStep.SCREENSAVER)
            if (disableSetupwraith) add(SwapStep.SETUPWRAITH)
            if (stockToDisable != null) add(SwapStep.STOCK)
            add(SwapStep.HOME)
            if (disableDreamx) add(SwapStep.DREAMX)
        }
    }

    /** Nothing to install, enable or turn off, and Home already opens the launcher: the TV has what the swap would give it. */
    val isUpToDate: Boolean get() = homeIsLight && !install && !enable && stockToDisable == null && !disableSetupwraith && !disableDreamx
}

sealed interface ScopeResult {
    data class Ok(val plan: LauncherPlan) : ScopeResult
    data class Rejected(val problem: ScopeProblem, val detail: String? = null) : ScopeResult
}

/**
 * Which TVs the swap accepts, and what it would do on them. Anything unusual is refused before a single change, with
 * the reason: that is how the undo can always be possible.
 */
object LauncherScope {
    fun evaluate(reading: LauncherReading, existing: SwapRecord?, releaseVersion: Long): ScopeResult {
        val home = reading.homeComponent ?: return ScopeResult.Rejected(ScopeProblem.UNREADABLE, "home")
        // Even a turned off or suspended remover comes back at the next start, so only a known-safe product is let through.
        if (reading.homeRemover != null && reading.product !in LauncherPackages.HOME_REMOVER_SAFE_PRODUCTS) {
            return ScopeResult.Rejected(ScopeProblem.HOME_REMOVER, reading.homeRemover)
        }
        val homePackage = reading.homePackage.orEmpty()
        val light = reading.packages[LauncherPackages.LIGHT]
        val resuming = existing != null && !existing.updateOnly
        val homeIsLight = homePackage == LauncherPackages.LIGHT
        val chooser = homePackage == LauncherPackages.CHOOSER

        val known = LauncherPackages.STOCK + LauncherPackages.LIGHT + LauncherPackages.SETUPWRAITH + LauncherPackages.FALLBACK_HOME + homePackage
        reading.homeCandidates.firstOrNull { it !in known }?.let { return ScopeResult.Rejected(ScopeProblem.UNKNOWN_HOME_CANDIDATE, it) }

        val enabledStock = reading.homeCandidates.filter { it in LauncherPackages.STOCK }
        val stock: String? = when {
            resuming -> existing?.packages?.keys?.firstOrNull { it in LauncherPackages.STOCK && reading.packages[it]?.let { s -> s.installed && s.isEnabled } == true }
            homeIsLight -> null
            chooser -> when (enabledStock.size) {
                0 -> return ScopeResult.Rejected(ScopeProblem.NO_STOCK)
                1 -> enabledStock.first()
                else -> return ScopeResult.Rejected(ScopeProblem.SEVERAL_STOCK, enabledStock.joinToString())
            }
            homePackage in LauncherPackages.STOCK -> {
                enabledStock.firstOrNull { it != homePackage }?.let { return ScopeResult.Rejected(ScopeProblem.ANOTHER_STOCK_ENABLED, it) }
                homePackage
            }
            else -> null
        }

        val updateOnly = homeIsLight && !resuming
        if (!updateOnly && reading.screensaver != null && ScreensaverValue.orNull(reading.screensaver) == null) {
            return ScopeResult.Rejected(ScopeProblem.SCREENSAVER_NOT_SUPPORTED)
        }
        if (!updateOnly && !chooser && !homeIsLight && ComponentName.orNull(home) == null && !resuming) {
            return ScopeResult.Rejected(ScopeProblem.HOME_NOT_SUPPORTED, home)
        }

        val installed = light?.installed == true
        val install = !installed || (reading.lightLauncherVersion?.let { it < releaseVersion } == true)
        val enable = installed && light?.isEnabled != true
        val setupwraith = reading.packages[LauncherPackages.SETUPWRAITH]
        val dreamx = reading.packages[LauncherPackages.DREAMX]
        val disableSetupwraith = !updateOnly && setupwraith?.let { it.installed && it.isEnabled } == true
        val disableDreamx = !updateOnly && dreamx?.let { it.installed && it.isEnabled } == true
        return ScopeResult.Ok(
            LauncherPlan(
                updateOnly = updateOnly,
                install = install,
                enable = enable,
                stockToDisable = stock,
                disableSetupwraith = disableSetupwraith,
                disableDreamx = disableDreamx,
                homeComponent = if (resuming) existing?.homeComponent else if (chooser || homeIsLight) null else home,
                wifiWarning = disableSetupwraith && reading.wifi != WifiOwner.OTHER,
                homeIsLight = homeIsLight,
            ),
        )
    }
}

object LauncherUndo {
    /** What the undo will go through for [record], in order: the stock launcher comes back before the setup app. */
    fun steps(record: SwapRecord): List<SwapStep> =
        buildList {
            if (LauncherPackages.DREAMX in record.packages) add(SwapStep.DREAMX)
            if (record.packages.keys.any { it in LauncherPackages.STOCK }) add(SwapStep.STOCK)
            if (LauncherPackages.SETUPWRAITH in record.packages) add(SwapStep.SETUPWRAITH)
            add(SwapStep.HOME)
            add(SwapStep.SCREENSAVER)
            if (record.packages[LauncherPackages.LIGHT]?.isDisabledByUser == true) add(SwapStep.RESTORE_LIGHT)
        }
}
