package com.prototype.tuneupfortv.device

/**
 * What the app knows about a family of devices: which packages must never be touched, which
 * prefixes mark vendor services, which processes prove the system is healthy, and which packages
 * other people commonly disable.
 */
data class DeviceProfile(
    val id: String,
    /** True only for hardware this profile was actually tested on. Anything else gets extra care. */
    val verified: Boolean,
    /** Packages that are Essential by name, on top of the PERSISTENT and system-uid rules. */
    val protectedPackages: Set<String>,
    /** A system package whose id starts with one of these is a vendor service. */
    val vendorPrefixes: List<String>,
    /** Full process names whose pids the post-change watch compares (used from M3). */
    val criticalProcesses: List<String>,
    /** Community hints: packages many users disable. Only a badge, never a recommendation or a permission. */
    val commonlyDisabled: Set<String>,
) {
    fun isCommonlyDisabled(packageId: String): Boolean = packageId in commonlyDisabled
}

object DeviceProfiles {
    private const val SONY_VERIFIED_MODEL: String = "BRAVIA_BF1"
    private const val SONY_VERIFIED_SDK: Int = 31

    /** Sony gets its own profile; the rest get the generic one. Only the tested Sony model counts as verified. */
    fun forDevice(identity: DeviceIdentity): DeviceProfile {
        if (!identity.manufacturer.equals("Sony", ignoreCase = true)) return GENERIC
        // getprop reports "BRAVIA BF1" with a space; adb tooling shows it with an underscore.
        val verified = identity.model.replace(' ', '_') == SONY_VERIFIED_MODEL && identity.sdk == SONY_VERIFIED_SDK
        return SONY.copy(verified = verified)
    }

    /** The Sony BRAVIA protection list, taken from the packages that broke a real TV when removed. */
    private val SONY_PROTECTED: Set<String> = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.inputmethod.latin",
        "com.sony.dtv.ime.chww",
        "com.android.bluetooth",
        "com.mediatek.autopair",
        "com.mediatek.tv.overlay.bluetooth",
        "com.google.android.tv.remote.service",
        "com.mediatek.tv.simpleremote",
        "com.sony.dtv.remotecontrolsettings",
        "com.mediatek.tv.keyevent",
        "com.mediatek.android.tv.globalkeyoverlay",
        "com.mediatek.tv.sonyonekey",
        "com.mediatek.tis",
        "com.mediatek.dtv.tvinput.framework.server",
        "com.mediatek.tv.oneworld.tvcenter",
        "com.mediatek.tv.oneworld.tvcenteroverlay",
        "com.mediatek.tv.agent",
        "com.android.providers.tv",
        "com.sony.dtv.connecteddevicesettings",
        "com.sony.dtv.audiosysteminfoprovider",
        "com.dolby.android.audio.service",
        "com.mediatek.tv.game",
        "com.sony.dtv.airplayapp",
        "com.google.android.apps.mediashell",
        "com.mediatek.dialservice",
        "android",
        "com.android.systemui",
        "com.android.tv.settings",
        "com.sony.dtv.sonysystemservice",
        "com.sony.dtv.smartui",
        "com.google.android.webview",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.mediatek.tv.systemupdater",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith",
    )

    val SONY: DeviceProfile = DeviceProfile(
        id = "sony-bravia",
        verified = false,
        protectedPackages = SONY_PROTECTED,
        vendorPrefixes = listOf("com.sony.", "com.mediatek.", "tv.samba."),
        criticalProcesses = listOf(
            "com.sony.dtv.sonysystemservice",
            "com.sony.dtv.airplayapp",
            "com.sony.dtv.airplayapp:airplayserver",
            "airplayptp",
        ),
        // Seen in at least five of the eight Sony lists of github.com/Gathin23/cleanyourtv (MIT), commit 6bcd649.
        commonlyDisabled = setOf(
            "com.google.android.feedback",
            "com.sony.dtv.demosupport",
            "com.sony.dtv.multiscreendemo",
            "tv.samba.ssm",
            "com.google.android.play.games",
            "com.qterics.da.product",
            "com.sony.dtv.da.service",
            "com.sony.dtv.sonyselect",
            "com.google.android.videos",
            "com.sony.dtv.demosystemsupport",
            "com.sony.dtv.promos",
            "com.google.android.youtube.tvmusic",
            "com.sony.dtv.customersupport",
            "com.sony.dtv.smarthelp",
            "com.sony.dtv.sonybugreportsys",
            "com.sonyericsson.idd.agent",
        ),
    )

    /** Any other brand: only the Google and Android part of the protection list, and never "verified". */
    val GENERIC: DeviceProfile = DeviceProfile(
        id = "generic",
        verified = false,
        protectedPackages = SONY_PROTECTED.filterNotTo(HashSet()) { it.startsWith("com.sony.") || it.startsWith("com.mediatek.") },
        vendorPrefixes = emptyList(),
        criticalProcesses = emptyList(),
        commonlyDisabled = emptySet(),
    )
}
