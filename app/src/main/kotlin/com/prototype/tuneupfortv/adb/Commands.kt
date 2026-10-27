package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.AccessibilityValue
import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.Epoch
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.PermissionName
import com.prototype.tuneupfortv.device.ProcessName
import com.prototype.tuneupfortv.device.ScreensaverValue

// The only place where shell command lines are built. Anything that ends up inside a command goes through a validated
// identifier type (see device/Identifiers.kt), so a free-form string can never be concatenated into a command.
// A test checks that `ShellCommand(` is only called from this file.

/** A command line for the device shell. Create one only through [Commands]. */
class ShellCommand internal constructor(val line: String, val kind: Kind) {
    enum class Kind {
        /** Run as is: used to learn who the device is, before any serial is known. */
        PROBE,

        /** Wrapped in [ReadEnvelope], so every answer says which device produced it. */
        READ,

        /** Changes the device. Runs only if the device's serial is the expected one, else exits with [Commands.IDENTITY_MISMATCH_EXIT]. */
        MUTATE,
    }

    override fun toString(): String = line
}

enum class DeviceProperty(val key: String) {
    SERIAL("ro.serialno"),
    MODEL("ro.product.model"),
    MANUFACTURER("ro.product.manufacturer"),
    SDK("ro.build.version.sdk"),
    PRODUCT_NAME("ro.product.name"),
    BUILD_ID("ro.build.id"),
}

enum class LauncherCategory(val category: String) {
    LAUNCHER("android.intent.category.LAUNCHER"),
    LEANBACK_LAUNCHER("android.intent.category.LEANBACK_LAUNCHER"),
}

/** The names of the sections of a script answer: one per command, each with its own exit code. */
object Section {
    const val SERIAL: String = "serial"
    const val MODEL: String = "model"
    const val MANUFACTURER: String = "manufacturer"
    const val SDK: String = "sdk"
    const val PRODUCT_NAME: String = "product_name"
    const val BUILD_ID: String = "build_id"
    const val PACKAGES_ALL: String = "packages_all"
    const val PACKAGES_INSTALLED: String = "packages_installed"
    const val DUMP: String = "dump"
    const val LAUNCHERS_LEANBACK: String = "launchers_leanback"
    const val LAUNCHERS: String = "launchers"
    const val HOME: String = "home"
    const val ROLES: String = "roles"
    const val IME: String = "ime"
    const val PROCESSES: String = "processes"
    const val MEMINFO: String = "meminfo"
    const val PACKAGE: String = "package"
    const val EPOCH: String = "epoch"
    const val PIDS: String = "pids"
    const val CPU: String = "cpu"
    const val CRASHES: String = "crashes"
    const val HOME_CANDIDATES: String = "home_candidates"
    const val LIGHT_VERSION: String = "light_version"
    const val SCREENSAVER: String = "screensaver"
    const val ACCESSIBILITY: String = "accessibility"
    const val WIFI_OWNERS: String = "wifi_owners"
}

/** One command's part of a script answer. */
data class ScriptSection(val exitCode: Int, val output: String)

/** Splits the answer of a script built by [Commands]: every command between `@@BEGIN|name` and `@@END|exit`. */
object ScriptOutput {
    private const val BEGIN: String = "@@BEGIN|"
    private const val END: String = "\n@@END|"

    /** The sections by name, or null if the answer is cut short or malformed: a partial answer is never trusted. */
    fun parse(stdout: String): Map<String, ScriptSection>? {
        val sections = LinkedHashMap<String, ScriptSection>()
        var cursor = 0
        while (true) {
            val begin = stdout.indexOf(BEGIN, cursor)
            if (begin < 0) return sections
            val nameEnd = stdout.indexOf('\n', begin)
            if (nameEnd < 0) return null
            val name = stdout.substring(begin + BEGIN.length, nameEnd)
            val end = stdout.indexOf(END, nameEnd)
            if (end < nameEnd + 1) return null
            val exitStart = end + END.length
            val exitEnd = stdout.indexOf('\n', exitStart).let { if (it < 0) stdout.length else it }
            val exit = stdout.substring(exitStart, exitEnd).trim().toIntOrNull() ?: return null
            // `end` is the newline that `echo` printed just before the marker: what lies before it is exactly what the command printed.
            if (name in sections) return null
            sections[name] = ScriptSection(exit, stdout.substring(nameEnd + 1, end))
            cursor = exitEnd
        }
    }
}

/** Every command the app can send. */
object Commands {
    private const val USER_ZERO: String = "--user 0"
    private const val MAIN_ACTION: String = "android.intent.action.MAIN"

    // region reads: one round trip each. Several commands share a script, but each keeps its own exit code.

    /** Everything the app needs from a device, in one round trip: who it is, its packages, launchers, Home, keyboard, processes, memory. */
    fun readDevice(): ShellCommand =
        script(
            listOf(
                Section.SERIAL to "getprop ${DeviceProperty.SERIAL.key}",
                Section.MODEL to "getprop ${DeviceProperty.MODEL.key}",
                Section.MANUFACTURER to "getprop ${DeviceProperty.MANUFACTURER.key}",
                Section.SDK to "getprop ${DeviceProperty.SDK.key}",
                Section.PRODUCT_NAME to "getprop ${DeviceProperty.PRODUCT_NAME.key}",
                Section.BUILD_ID to "getprop ${DeviceProperty.BUILD_ID.key}",
                Section.PACKAGES_ALL to "pm list packages -u $USER_ZERO",
                Section.PACKAGES_INSTALLED to "pm list packages $USER_ZERO",
                // One raw dump of every package: much faster than one `dumpsys` per package, with the same content.
                Section.DUMP to "dumpsys package packages",
                Section.LAUNCHERS_LEANBACK to launchers(LauncherCategory.LEANBACK_LAUNCHER),
                Section.LAUNCHERS to launchers(LauncherCategory.LAUNCHER),
                Section.HOME to RESOLVE_HOME,
                Section.ROLES to ROLES,
                Section.IME to INPUT_METHOD,
                Section.PROCESSES to "ps -A -o USER,PID,RSS,NAME",
                Section.MEMINFO to "cat /proc/meminfo",
            ),
        )

    /**
     * One package, plus the Home launcher and the keyboard, read right before acting on it. With [withLaunchers], also the
     * state of every package the launcher swap cares about and the packages that can open as Home: the rules that keep
     * the setup app from becoming the only Home need them.
     */
    fun readLivePackage(packageName: PackageName, withLaunchers: Boolean = false): ShellCommand =
        script(
            listOf(
                Section.PACKAGE to "dumpsys package $packageName",
                Section.ROLES to ROLES,
                Section.HOME to RESOLVE_HOME,
                Section.IME to INPUT_METHOD,
            ) + if (withLaunchers) {
                listOf(Section.DUMP to "dumpsys package packages", Section.HOME_CANDIDATES to HOME_CANDIDATES_QUERY)
            } else {
                emptyList()
            },
        )

    /** Everything the launcher swap needs to know about the TV, in one round trip. */
    fun readLauncherState(): ShellCommand =
        script(
            listOf(
                Section.HOME to RESOLVE_HOME,
                Section.HOME_CANDIDATES to HOME_CANDIDATES_QUERY,
                Section.DUMP to "dumpsys package packages",
                Section.LIGHT_VERSION to "dumpsys package $LIGHT_PACKAGE",
                Section.SCREENSAVER to "settings get $USER_ZERO secure screensaver_components",
                Section.ACCESSIBILITY to "settings get $USER_ZERO secure enabled_accessibility_services",
                Section.WIFI_OWNERS to "wifi_owners",
                Section.PRODUCT_NAME to "getprop ${DeviceProperty.PRODUCT_NAME.key}",
            ),
            preamble = WIFI_OWNERS_FUNCTION,
        )

    /**
     * How the TV is doing, in one round trip: memory, CPU over a one second window (two samples of `top`), and the
     * crashes of the last minute, counted on the device's own clock.
     */
    fun readHealth(): ShellCommand =
        script(
            listOf(
                Section.MEMINFO to "cat /proc/meminfo",
                Section.CPU to "top -b -n 2 -d 1",
                Section.CRASHES to "recent_crashes",
            ),
            preamble = CRASHES_FUNCTION,
        )

    /** The device clock and the pids of [names], before a change. */
    fun readWatchBaseline(names: List<ProcessName>): ShellCommand =
        script(
            listOf(
                Section.EPOCH to "date +%s",
                Section.PIDS to "pids ${names.joinToString(" ")}",
            ),
            preamble = PIDS_FUNCTION,
        )

    /**
     * The pids of several processes in one round trip: one line per process, `@@name|exit|pids`.
     * `pidof` exits 1 with no output when a process is not running: that is an observation, not an error.
     */
    fun pidofAll(names: List<ProcessName>): ShellCommand =
        read("for n in ${names.joinToString(" ")}; do p=\$(pidof \$n); echo \"@@\$n|\$?|\$p\"; done")

    /** Crashes since [since], on the device clock. Empty output with exit 0 means no crash. */
    fun crashLog(since: Epoch): ShellCommand = read("logcat -d -b crash -T $since.000")

    private fun launchers(category: LauncherCategory): String =
        "cmd package query-activities --brief $USER_ZERO -a $MAIN_ACTION -c ${category.category}"

    /** The packages with an enabled HOME activity. */
    private const val HOME_CANDIDATES_QUERY: String =
        "cmd package query-activities --brief $USER_ZERO -a $MAIN_ACTION -c android.intent.category.HOME"

    private const val LIGHT_PACKAGE: String = "com.prototype.lightlauncher"

    /** The launcher the system would open for the Home button. */
    private const val RESOLVE_HOME: String =
        "cmd package resolve-activity --brief -a $MAIN_ACTION -c android.intent.category.HOME $USER_ZERO"

    /** Holds the HOME role holders: Android 12 has no `get-role-holders`, so this is parsed. */
    private const val ROLES: String = "dumpsys role"

    private const val INPUT_METHOD: String = "settings get $USER_ZERO secure default_input_method"

    /** Runs a command and wraps its output in markers with its own exit code. `$?` is read right after the command, never after a pipe. */
    internal const val RUN_FUNCTION: String =
        "run() { echo \"@@BEGIN|\$1\"; shift; \"\$@\"; s=\$?; echo; echo \"@@END|\$s\"; }"

    internal const val PIDS_FUNCTION: String =
        "pids() { for n in \"\$@\"; do p=\$(pidof \$n); echo \"@@\$n|\$?|\$p\"; done; }"

    internal const val CRASHES_FUNCTION: String =
        "recent_crashes() { logcat -d -b crash -T \$(( \$(date +%s) - 60 )).000; }"

    /**
     * The owners of the saved Wi-Fi networks, one `cname=...` per line. Filtered on the device and exiting 0 whenever
     * `dumpsys` answered, so "no network of the setup app" is not mistaken for "could not be read".
     */
    internal const val WIFI_OWNERS_FUNCTION: String =
        "wifi_owners() { out=\$(dumpsys wifi) || return 1; echo \"\$out\" | grep -o 'cname=[^ ]*'; return 0; }"

    private fun script(sections: List<Pair<String, String>>, preamble: String = ""): ShellCommand {
        val head = if (preamble.isEmpty()) RUN_FUNCTION else "$RUN_FUNCTION; $preamble"
        return read("$head; " + sections.joinToString("; ") { (name, command) -> "run $name $command" })
    }

    // endregion

    // region mutations: the only commands that change the device. None of them removes anything.

    fun forceStop(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("am force-stop $USER_ZERO $packageName", serial)

    fun disableUser(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("pm disable-user $USER_ZERO $packageName", serial)

    /** Back to the package's default enabled state (what "enabled=0" means). */
    fun defaultState(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("pm default-state $USER_ZERO $packageName", serial)

    /** Explicitly enabled (what "enabled=1" means). */
    fun enable(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("pm enable $USER_ZERO $packageName", serial)

    /** Reinstalls a package that was removed for user 0, from the copy still in the system image. */
    fun installExisting(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("cmd package install-existing $USER_ZERO $packageName", serial)

    /**
     * Removes a package for user 0, data included: the uninstall is final. A system app stays listed as uninstalled for
     * the user and comes back with [installExisting] (its data does not); one the user installed is gone altogether,
     * because its APK is gone.
     */
    fun uninstallUser(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("pm uninstall $USER_ZERO $packageName", serial)

    // region the launcher swap: nothing here removes anything

    /** Where the downloaded APK waits for `pm install`, which removes it either way. */
    const val APK_PATH: String = "/data/local/tmp/tuneup-launcher.apk"

    fun installPushed(serial: DeviceSerial): ShellCommand =
        mutation("pm install -r '$APK_PATH'; s=\$?; rm -f '$APK_PATH'; exit \$s", serial)

    fun grant(packageName: PackageName, permission: PermissionName, serial: DeviceSerial): ShellCommand =
        mutation("pm grant $packageName '$permission'", serial)

    fun compileSpeed(packageName: PackageName, serial: DeviceSerial): ShellCommand =
        mutation("cmd package compile -m speed -f $packageName", serial)

    fun setHome(component: ComponentName, serial: DeviceSerial): ShellCommand =
        mutation("cmd package set-home-activity $USER_ZERO '$component'", serial)

    fun putScreensaver(value: ScreensaverValue, serial: DeviceSerial): ShellCommand =
        mutation("settings put $USER_ZERO secure screensaver_components '$value'", serial)

    fun deleteScreensaver(serial: DeviceSerial): ShellCommand =
        mutation("settings delete $USER_ZERO secure screensaver_components", serial)

    fun putAccessibility(value: AccessibilityValue, serial: DeviceSerial): ShellCommand =
        mutation("settings put $USER_ZERO secure enabled_accessibility_services '$value'", serial)

    /** Reads the serial of the device on the connection that is about to receive a file. */
    fun probeSerial(): ShellCommand = ShellCommand("getprop ${DeviceProperty.SERIAL.key}", ShellCommand.Kind.PROBE)

    // endregion

    /** The exit code of a mutation that did not run because another device answered. */
    const val IDENTITY_MISMATCH_EXIT: Int = 97

    /**
     * The fence: the command runs only if this device's serial is [serial]. The check happens on the
     * device, so even a connection that silently moved to another box cannot run it there.
     */
    private fun mutation(line: String, serial: DeviceSerial): ShellCommand =
        ShellCommand(
            "if [ \"\$(getprop ${DeviceProperty.SERIAL.key})\" = \"$serial\" ]; then $line; else exit $IDENTITY_MISMATCH_EXIT; fi",
            ShellCommand.Kind.MUTATE,
        )

    private fun read(line: String): ShellCommand =
        ShellCommand("echo \"${ReadEnvelope.MARKER}\$(getprop ${DeviceProperty.SERIAL.key})\"; $line", ShellCommand.Kind.READ)
}

/** The first line of every read answer is `@@S:<serial>`, so the app can tell which device answered. */
object ReadEnvelope {
    const val MARKER: String = "@@S:"

    /** Splits a wrapped answer into the observed serial (null if the marker is missing) and the real output. */
    fun unwrap(rawOutput: String): Pair<String?, String> {
        if (!rawOutput.startsWith(MARKER)) return null to rawOutput
        val firstLineEnd = rawOutput.indexOf('\n')
        val serial = rawOutput.substring(MARKER.length, if (firstLineEnd < 0) rawOutput.length else firstLineEnd)
        val rest = if (firstLineEnd < 0) "" else rawOutput.substring(firstLineEnd + 1)
        return serial.trim() to rest
    }
}
