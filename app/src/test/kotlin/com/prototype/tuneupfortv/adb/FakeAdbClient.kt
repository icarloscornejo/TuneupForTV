package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageRecord
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.adb.Parsers
import com.prototype.tuneupfortv.adb.SnapshotLoader.Companion.HOME_ROLE
import kotlin.time.Duration

/**
 * A device that lives in memory and answers from the real fixtures. Its packages really change when
 * a command runs, with the same rules the real ADB shell has on Android 12, and its switches reproduce
 * the trouble a real device causes: another serial, cut or empty dumps, lost answers, commands that
 * land late, processes that restart, crashes.
 */
class FakeAdbClient(
    private val readFixture: (String) -> String,
    var identity: DeviceIdentity = DeviceIdentity("Sony", "BRAVIA_BF1", "FAKE0001", 31),
) : AdbClient {
    enum class DumpMode { FULL, TRUNCATED, EMPTY }

    companion object {
        // The fixtures were captured with these defaults; both are already in the Sony protection list.
        const val HOME_PACKAGE: String = "com.google.android.apps.tv.launcherx"
        const val KEYBOARD_PACKAGE: String = "com.google.android.inputmethod.latin"
        const val LIGHT_PACKAGE: String = "com.prototype.lightlauncher"
    }

    // region switches for the tests

    /** Thrown by every call, like an unreachable or unauthorized device. */
    var failure: DeviceFailure? = null

    /** READ commands answer with this serial instead of the device's, like another box behind the same address. */
    var observedSerialOverride: String? = null
    var dumpMode: DumpMode = DumpMode.FULL
    var home: String = HOME_PACKAGE

    /** Replaces the stdout of a command, by its command line. */
    val outputOverrides: MutableMap<String, String> = mutableMapOf()

    /** Replaces the output of one command inside a script answer, by section name (`dump`, `roles`, `package`...). */
    val sectionOutputOverrides: MutableMap<String, String> = mutableMapOf()

    /** Makes one command inside a script answer exit with this code, by section name. */
    val sectionExitOverrides: MutableMap<String, Int> = mutableMapOf()

    /** Makes a command exit with this code, by its command line. */
    val exitCodeOverrides: MutableMap<String, Int> = mutableMapOf()

    /** Only mutations fail, with this reason, and nothing is applied: the reads before them worked. */
    var mutationFailure: DeviceFailure? = null

    /** A mutation is applied, but the answer is lost: the caller sees a timeout. */
    var timeoutAfterApply: Boolean = false

    /** A mutation is not applied and the caller sees a timeout. */
    var timeoutWithoutApply: Boolean = false

    /** A mutation is queued instead of applied, and the caller sees a timeout: it lands when [landPendingMutations] runs. */
    var landLater: Boolean = false

    /** Called after a mutation is applied, with the package and the operation: tests use it to restart a process or add a crash. */
    var onMutation: ((packageId: String, operation: String) -> Unit)? = null

    /** Makes `pidof` of one process exit with this code (for example 2, a real error), by process name. */
    val pidofExitOverrides: MutableMap<String, Int> = mutableMapOf()

    /** `logcat` complains on stderr instead of answering. */
    var crashLogFails: Boolean = false

    // endregion

    // region the launcher swap

    /** The Home launcher's component, as `resolve-activity` answers it. Null follows [home]. */
    var homeComponentOverride: String? = null

    /** Packages that have an enabled HOME activity, besides the ones that are enabled and listed in [homeCapable]. */
    val homeCapable: MutableSet<String> = mutableSetOf(HOME_PACKAGE)

    /** `set-home-activity` answers Success but changes nothing, like it does while a stock launcher outranks the new one. */
    var setHomeIgnored: Boolean = false

    /** Installing a launcher makes Android forget which one was picked as Home. */
    var installDropsHome: Boolean = false

    var screensaver: String? = null
    var accessibility: String? = null

    /** Installing drops the accessibility helpers of the installed app, like Android does on every reinstall. */
    var installDropsAccessibility: Boolean = false

    /** The text `dumpsys wifi | grep cname` gives. */
    var wifiOwners: String = "cname=com.android.tv.settings\n"

    /** The version a launcher has after `pm install`. */
    var installedLauncherVersion: Long = 10001L

    /** Makes `pm install` exit with this code instead of installing. */
    var installExit: Int? = null

    /** Makes the next `pm install` apply and then lose its answer. */
    var installTimeoutAfterApply: Boolean = false

    /** Making `set-home-activity` exit with this code. */
    var setHomeExit: Int? = null

    /** Every push: the remote path and the number of bytes. */
    val pushes: MutableList<Pair<String, Int>> = mutableListOf()

    /** A push fails like a cut connection. */
    var pushFailure: DeviceFailure? = null

    /** The version `dumpsys package` reports for the launcher, once it is installed. */
    private var launcherVersion: Long? = null

    // endregion

    /** `pm uninstall` removes the package altogether, like an app the user installed, instead of leaving it listed as uninstalled. */
    var removeOnUninstall: Boolean = false

    /** The processes that are running: name to pids. A missing name is not running. */
    val pids: MutableMap<String, List<Int>> = mutableMapOf(
        "com.sony.dtv.sonysystemservice" to listOf(1329),
        "com.sony.dtv.airplayapp" to listOf(1875),
        "com.sony.dtv.airplayapp:airplayserver" to listOf(2190),
        "airplayptp" to listOf(613),
        HOME_PACKAGE to listOf(8120),
    )

    /** Crashes: device time in seconds, and the line. */
    val crashes: MutableList<Pair<Long, String>> = mutableListOf()

    var clockSeconds: Long = 1_700_000_000L

    /** Every call takes this long on the device clock, like a slow round trip. */
    var secondsPerCall: Long = 0

    /** Every command line received, in order. */
    val sent: MutableList<String> = mutableListOf()

    /** The mutating commands that ran, as "operation package". */
    val mutations: MutableList<String> = mutableListOf()

    private val packages: LinkedHashMap<String, PackageRecord> by lazy {
        val all = Parsers.parsePackageList(readFixture("pm_u.txt"))
        val captured = Parsers.parseCapturedPackageInfo(readFixture("pkginfo.txt"))
        LinkedHashMap(all.associateWith { requireNotNull(captured[it]) { "Fixture pkginfo.txt has no block for $it" } })
    }

    private val pendingMutations: MutableList<() -> Unit> = mutableListOf()

    /** The current state of a package. */
    fun state(packageId: String): PackageState = requireNotNull(packages.getValue(packageId).state)

    /** Changes a package behind the app's back, like Sony's applicationdisabler or the user in Settings. */
    fun setState(packageId: String, state: PackageState) {
        packages[packageId] = packages.getValue(packageId).copy(state = state)
    }

    /** Puts a package on the device that the fixtures do not have. */
    fun addPackage(packageId: String, state: PackageState, flags: Set<String> = setOf("SYSTEM", "HAS_CODE")) {
        packages[packageId] = PackageRecord(packageId, flags, null, null, state)
    }

    /** Makes a package PERSISTENT, the one flag that no protection can be lifted from. */
    fun markPersistent(packageId: String) {
        packages[packageId] = packages.getValue(packageId).let { it.copy(flags = it.flags + "PERSISTENT") }
    }

    /** Forgets queued mutations without applying them. */
    fun pendingClear() = pendingMutations.clear()

    /** Applies the mutations that were queued by [landLater]. */
    fun landPendingMutations() {
        pendingMutations.toList().forEach { it() }
        pendingMutations.clear()
    }

    /** Moves the device clock, in milliseconds: use it as the `pause` of the code under test. */
    fun advance(milliseconds: Long) {
        clockSeconds += milliseconds / 1000
    }

    override suspend fun shell(command: ShellCommand, deadline: Duration): ShellResult {
        sent += command.line
        clockSeconds += secondsPerCall
        failure?.let { throw DeviceException(it) }
        if (command.kind == ShellCommand.Kind.MUTATE) return mutate(command)
        val inner = innerOf(command)
        if (command.kind == ShellCommand.Kind.READ && inner.startsWith("run() {")) {
            val whole = outputOverrides[command.line] ?: script(inner)
            // The crash log complains on stderr, not through the exit code, like the real one.
            val complaint = if (crashLogFails && inner.contains("recent_crashes")) "logcat: -T 'x' not in time format." else ""
            return ShellResult(exitCodeOverrides[command.line] ?: 0, whole, complaint, observedSerialOverride ?: identity.serial)
        }
        val stdout = outputOverrides[command.line] ?: answer(inner)
        val serial = if (command.kind == ShellCommand.Kind.READ) observedSerialOverride ?: identity.serial else null
        val (exit, output, error) = commandResult(inner, stdout)
        return ShellResult(exitCodeOverrides[command.line] ?: exit, output, error, serial)
    }

    override suspend fun push(bytes: ByteArray, remotePath: String, serial: DeviceSerial, deadline: Duration): PushResult {
        failure?.let { throw DeviceException(it) }
        pushFailure?.let { throw DeviceException(it) }
        if ((observedSerialOverride ?: identity.serial) != serial.value) return PushResult.IDENTITY_MISMATCH
        pushes += remotePath to bytes.size
        return PushResult.PUSHED
    }

    override fun close() = Unit

    // region reads

    /** Answers a script like the real shell does: each command between markers, with its own exit code and the blank line `echo` adds. */
    private fun script(inner: String): String =
        inner.split("; run ").drop(1).joinToString("") { piece ->
            val name = piece.substringBefore(" ")
            val command = piece.substringAfter(" ")
            val output = sectionOutputOverrides[name] ?: answer(command)
            val exit = sectionExitOverrides[name] ?: commandResult(command, output).first
            "@@BEGIN|$name\n$output\n@@END|$exit\n"
        }

    private fun innerOf(command: ShellCommand): String =
        if (command.kind == ShellCommand.Kind.READ) command.line.substringAfter("; ") else command.line

    private fun answer(inner: String): String =
        when {
            inner == "getprop ro.serialno" -> identity.serial + "\n"
            inner == "getprop ro.product.model" -> identity.model + "\n"
            inner == "getprop ro.product.manufacturer" -> identity.manufacturer + "\n"
            inner == "getprop ro.build.version.sdk" -> identity.sdk.toString() + "\n"
            inner == "getprop ro.product.name" -> "BRAVIA_BF1_4K_M_A\n"
            inner == "getprop ro.build.id" -> "STT2.230811.001\n"
            inner.startsWith("pm list packages") -> listPackages(inner)
            inner == "dumpsys package packages" -> dump(packages.values)
            inner.startsWith("dumpsys package ") -> packageDump(inner.removePrefix("dumpsys package "))
            inner.startsWith("cmd package query-activities") && inner.contains("category.HOME") -> homeCandidates()
            inner.startsWith("cmd package query-activities") -> launchables(inner)
            inner.startsWith("cmd package resolve-activity") -> "priority=0 preferredOrder=0 match=0x108000\n${homeComponentOverride ?: "$home/.MainActivity"}\n"
            inner == "wifi_owners" -> wifiOwners
            inner.startsWith("settings get") && inner.endsWith("screensaver_components") -> (screensaver ?: "null") + "\n"
            inner.startsWith("settings get") && inner.endsWith("enabled_accessibility_services") -> (accessibility ?: "null") + "\n"
            inner == "dumpsys role" -> "        {\n          name=$HOME_ROLE\n          holders=$home\n        }\n"
            inner.startsWith("settings get") -> "$KEYBOARD_PACKAGE/.LatinIME\n"
            inner.startsWith("ps -A") -> readFixture("ps.txt")
            inner == "cat /proc/meminfo" -> readFixture("meminfo.txt")
            inner == "date +%s" -> "$clockSeconds\n"
            inner == "top -b -n 2 -d 1" -> topOutput()
            inner == "recent_crashes" -> crashLog("logcat -d -b crash -T ${clockSeconds - 60}.000")
            inner.startsWith("logcat -d -b crash") -> crashLog(inner)
            inner.startsWith("for n in ") -> pidLines(inner.removePrefix("for n in ").substringBefore(";"))
            inner.startsWith("pids") -> pidLines(inner.removePrefix("pids"))
            else -> ""
        }

    /** Exit code, stdout and stderr, as the real tools give them: `pidof` exits 1 when nothing runs, `logcat` complains on stderr. */
    private fun commandResult(inner: String, stdout: String): Triple<Int, String, String> =
        when {
            inner.startsWith("logcat -d -b crash") && crashLogFails -> Triple(0, "", "logcat: -T 'x' not in time format.")
            inner.startsWith("pm list packages") || inner.startsWith("cmd package") || inner.startsWith("dumpsys") ||
                inner.startsWith("getprop") || inner.startsWith("settings") || inner.startsWith("ps ") || inner.startsWith("cat ") ||
                inner.startsWith("date") || inner.startsWith("logcat") || inner.startsWith("top ") || inner == "recent_crashes" || inner == "wifi_owners" || inner.startsWith("for n in ") || inner.startsWith("pids") -> Triple(0, stdout, "")
            else -> Triple(127, "", "unknown command: $inner")
        }

    /** How busy the fake CPU is, in percent, for the health read. */
    var cpuBusyPercent: Int = 12

    /** Two samples, like `top -b -n 2`: the first is the average since boot (idle), the second is the last second. */
    private fun topOutput(): String {
        fun sample(busy: Int) = "Tasks: 347 total\n  Mem: 2450808K total\n400%cpu  ${busy * 4}%user   0%nice   0%sys ${(100 - busy) * 4}%idle   0%iow\n  PID USER\n"
        return sample(0) + sample(cpuBusyPercent)
    }

    /** `@@name|exit|pids` per process, with `pidof`'s exit 1 for a process that is not running. */
    private fun pidLines(names: String): String =
        names.split(" ").filter { it.isNotEmpty() }.joinToString("") { name ->
            val running = pids[name]
            val exit = pidofExitOverrides[name] ?: if (running.isNullOrEmpty()) 1 else 0
            "@@$name|$exit|${running?.joinToString(" ").orEmpty()}\n"
        }

    private fun listPackages(inner: String): String {
        val flag = inner.removePrefix("pm list packages").trim().substringBefore("--user").trim()
        val chosen = when (flag) {
            "-u" -> packages.values
            "" -> packages.values.filter { it.state?.installed == true }
            "-s" -> packages.values.filter { it.state?.installed == true && it.isSystem }
            "-3" -> packages.values.filter { it.state?.installed == true && !it.isSystem }
            "-d" -> packages.values.filter { it.state?.installed == true && it.state?.isDisabled == true }
            "-e" -> packages.values.filter { it.state?.installed == true && it.state?.isEnabled == true }
            else -> emptyList()
        }
        return chosen.joinToString("") { "${it.id}\n" }
    }

    private fun packageDump(packageId: String): String {
        val record = packages[packageId] ?: return "Packages:\n"
        val version = if (packageId == LIGHT_PACKAGE) launcherVersion else null
        return FakeDump.render(listOf(record)) + (version?.let { "    versionCode=$it minSdk=26 targetSdk=34\n" } ?: "")
    }

    /** The packages whose HOME activity is enabled right now. */
    private fun homeCandidates(): String =
        (homeCapable + home).filter { id -> packages[id]?.state?.let { it.installed && it.isEnabled } == true }.joinToString("") { "$it/.MainActivity\n" }

    private fun launchables(inner: String): String {
        val file = if (inner.contains("LEANBACK_LAUNCHER")) "launchable_lb.txt" else "launchable_l.txt"
        return Parsers.parseLaunchables(readFixture(file)).filter { packages[it]?.state?.isEnabled == true }.joinToString("") { "$it/.MainActivity\n" }
    }

    private fun crashLog(inner: String): String {
        val since = inner.substringAfter("-T ").substringBefore(".").toLong()
        val lines = crashes.filter { it.first >= since }.map { it.second }
        return if (lines.isEmpty()) "" else "--------- beginning of crash\n" + lines.joinToString("\n", postfix = "\n")
    }

    private fun dump(records: Collection<PackageRecord>): String {
        val text = FakeDump.render(records)
        return when (dumpMode) {
            DumpMode.FULL -> text
            DumpMode.TRUNCATED -> text.take(text.length / 2)
            DumpMode.EMPTY -> ""
        }
    }

    // endregion

    // region mutations

    private val fence = Regex("""^if \[ "\$\(getprop ro\.serialno\)" = "([^"]*)" \]; then (.*); else exit 97; fi$""")

    private fun mutate(command: ShellCommand): ShellResult {
        val match = requireNotNull(fence.matchEntire(command.line)) { "A mutation must carry the identity fence: ${command.line}" }
        val (expectedSerial, inner) = match.destructured
        // The fence runs on the device: another device refuses, and nothing changes.
        if (expectedSerial != identity.serial) return ShellResult(Commands.IDENTITY_MISMATCH_EXIT, "", "", null)
        mutationFailure?.let { throw DeviceException(it) }
        if (timeoutWithoutApply) throw DeviceException(DeviceFailure.Timeout)
        launcherMutation(inner, command)?.let { return it }
        val parts = inner.split(" ")
        val operation = when {
            parts[0] == "am" && parts[1] == "force-stop" -> "force-stop"
            parts[0] == "pm" -> parts[1]
            parts[0] == "cmd" && parts[2] == "install-existing" -> "install-existing"
            else -> return ShellResult(127, "", "unknown mutation: $inner", null)
        }
        val packageId = parts.last()
        require(packageId in packages) { "Unknown package $packageId" }
        exitCodeOverrides[command.line]?.let { return ShellResult(it, "", "", null) }
        if (landLater) {
            pendingMutations += { apply(operation, packageId) }
            throw DeviceException(DeviceFailure.Timeout)
        }
        val error = apply(operation, packageId)
        if (error != null) return ShellResult(255, "", error, null)
        if (timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
        return ShellResult(0, "Success\n", "", null)
    }

    /** The writes of the launcher swap, or null when [inner] is not one of them. */
    private fun launcherMutation(inner: String, command: ShellCommand): ShellResult? {
        val record = { operation: String, subject: String -> mutations += "$operation $subject" }
        return when {
            inner.startsWith("pm install -r") -> {
                if (landLater) {
                    pendingMutations += { installLauncher() }
                    throw DeviceException(DeviceFailure.Timeout)
                }
                installExit?.let { return ShellResult(it, "", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", null) }
                installLauncher()
                if (installTimeoutAfterApply || timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
                ShellResult(0, "Success\n", "", null)
            }
            inner.startsWith("pm grant ") -> {
                exitCodeOverrides[command.line]?.let { return ShellResult(it, "", "", null) }
                record("grant", inner.removePrefix("pm grant ").replace("'", ""))
                if (timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
                ShellResult(0, "", "", null)
            }
            inner.startsWith("cmd package compile ") -> {
                exitCodeOverrides[command.line]?.let { return ShellResult(it, "", "", null) }
                record("compile", inner.substringAfterLast(" "))
                if (timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
                ShellResult(0, "Success\n", "", null)
            }
            inner.startsWith("cmd package set-home-activity ") -> {
                setHomeExit?.let { return ShellResult(it, "", "", null) }
                val component = inner.substringAfterLast(" ").replace("'", "")
                if (landLater) {
                    pendingMutations += { home = component.substringBefore('/') }
                    throw DeviceException(DeviceFailure.Timeout)
                }
                record("set-home", component)
                // The preference only sticks when nothing outranks it, like on a TV whose stock launcher is still on.
                if (!setHomeIgnored) {
                    home = component.substringBefore('/')
                    homeComponentOverride = component
                }
                if (timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
                ShellResult(0, "Success\n", "", null)
            }
            inner.startsWith("settings put ") && inner.contains("screensaver_components") -> {
                record("screensaver", inner.substringAfterLast(" ").replace("'", ""))
                screensaver = inner.substringAfterLast(" ").replace("'", "")
                if (timeoutAfterApply) throw DeviceException(DeviceFailure.Timeout)
                ShellResult(0, "", "", null)
            }
            inner.startsWith("settings delete ") && inner.contains("screensaver_components") -> {
                record("screensaver", "delete")
                screensaver = null
                ShellResult(0, "", "", null)
            }
            inner.startsWith("settings put ") && inner.contains("enabled_accessibility_services") -> {
                record("accessibility", inner.substringAfterLast(" ").replace("'", ""))
                accessibility = inner.substringAfterLast(" ").replace("'", "")
                ShellResult(0, "", "", null)
            }
            else -> null
        }
    }

    private fun installLauncher() {
        val existing = packages[LIGHT_PACKAGE]
        packages[LIGHT_PACKAGE] = existing?.copy(state = existing.state?.copy(installed = true))
            ?: PackageRecord(LIGHT_PACKAGE, setOf("HAS_CODE"), null, null, PackageState(installed = true, enabled = 0, stopped = false))
        launcherVersion = installedLauncherVersion
        homeCapable += LIGHT_PACKAGE
        mutations += "install $LIGHT_PACKAGE"
        if (installDropsHome) home = HOME_PACKAGE.also { homeComponentOverride = null }
        if (installDropsAccessibility) accessibility = accessibility?.split(':')?.filterNot { it.startsWith("$LIGHT_PACKAGE/") }?.joinToString(":")?.ifEmpty { null }
    }

    /** Applies a mutation with the rules of the ADB shell on Android 12. Returns an error message, or null. */
    private fun apply(operation: String, packageId: String): String? {
        val record = packages.getValue(packageId)
        val state = requireNotNull(record.state)
        if (operation == "uninstall" && removeOnUninstall) {
            packages.remove(packageId)
            mutations += "$operation $packageId"
            onMutation?.invoke(packageId, operation)
            return null
        }
        val newState = when (operation) {
            "force-stop" -> state.copy(stopped = true)
            "disable-user" -> shellMove(state, 3) ?: return shellRefusal(packageId)
            "default-state" -> shellMove(state, 0) ?: return shellRefusal(packageId)
            "enable" -> shellMove(state, 1) ?: return shellRefusal(packageId)
            "install-existing" -> state.copy(installed = true)
            // A system app stays listed as uninstalled for the user.
            "uninstall" -> state.copy(installed = false)
            else -> return "unknown operation $operation"
        }
        packages[packageId] = record.copy(state = newState)
        mutations += "$operation $packageId"
        onMutation?.invoke(packageId, operation)
        return null
    }

    /** The shell can only move a package between states 0, 1 and 3: neither the old nor the new state may be anything else. */
    private fun shellMove(state: PackageState, newEnabled: Int): PackageState? =
        if (state.isShellMovable) state.copy(enabled = newEnabled) else null

    private fun shellRefusal(packageId: String): String = "java.lang.SecurityException: Shell cannot change component state for $packageId"

    // endregion
}

/** Renders package records in the shape of `dumpsys package packages`, with a hidden-copies section like the real one. */
object FakeDump {
    fun render(records: Collection<PackageRecord>): String =
        buildString {
            appendLine("Packages:")
            records.forEach { record ->
                appendLine("  Package [${record.id}] (0000000):")
                appendLine("    userId=10000")
                record.sharedUser?.let { appendLine("    sharedUser=SharedUserSetting{abc1234 $it/1000}") }
                appendLine("    pkgFlags=[ ${record.flags.joinToString(" ")} ]")
                record.installer?.let { appendLine("    installerPackageName=$it") }
                record.state?.let {
                    appendLine("    User 0: ceDataInode=1 installed=${it.installed} hidden=false suspended=false stopped=${it.stopped} enabled=${it.enabled} instant=false")
                }
            }
            appendLine()
            appendLine("Hidden system packages:")
            appendLine("  Package [com.example.hidden] (1111111):")
            appendLine("    pkgFlags=[ SYSTEM ]")
            appendLine("    User 0: ceDataInode=1 installed=true hidden=false stopped=false enabled=0 instant=false")
        }
}
