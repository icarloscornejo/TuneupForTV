package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.PackageRecord
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.ProcessRecord

/**
 * Parsers for the text the device prints. They are pure and tolerate CRLF, because adb output has it.
 *
 * Malformed process rows are skipped; whatever must be complete (package records) says so through
 * [PackageRecord.isComplete], and the loader rejects the whole read if any record is not.
 */
object Parsers {
    private val SHARED_USER_NAME: Regex = Regex("""SharedUserSetting\{\S+ ([^/}\s]+)""")
    private val INSTALLED: Regex = Regex("""\binstalled=(true|false)\b""")
    private val ENABLED: Regex = Regex("""\benabled=(\d+)\b""")
    private val STOPPED: Regex = Regex("""\bstopped=(true|false)\b""")
    private const val PACKAGE_HEADER_PREFIX: String = "  Package ["
    private val LAUNCHABLE_LINE: Regex = Regex("""^\s*([a-zA-Z0-9_.]+)(/\S+)?\s*$""")
    private val VERSION_CODE: Regex = Regex("""\bversionCode=(\d+)""")
    private val MEMINFO_LINE: Regex = Regex("""^(\S+):\s+(\d+)(\s+kB)?\s*$""")
    private val WHITESPACE: Regex = Regex("""\s+""")
    private val CPU_HEADER: Regex = Regex("""(\d+)%cpu\b.*?\b(\d+)%idle(?:\s+(\d+)%iow)?""")
    private val CRASH_MARKERS: List<String> = listOf("FATAL EXCEPTION", "Fatal signal", "ANR in")
    private const val PERCENT: Int = 100

    private fun lines(text: String): List<String> = text.lineSequence().map { it.trimEnd('\r') }.toList()

    /** `pm list packages` output, with or without the `package:` prefix. */
    fun parsePackageList(text: String): List<String> =
        lines(text)
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }

    /** Launcher activities (`pkg` or `pkg/activity` per line). Header and priority lines are ignored. */
    fun parseLaunchables(text: String): Set<String> =
        lines(text).mapNotNull { LAUNCHABLE_LINE.matchEntire(it)?.groupValues?.get(1) }.toSet()

    /** The `@@id` capture format used by the fixtures: one block per package, lines of `dumpsys package` inside. */
    fun parseCapturedPackageInfo(text: String): Map<String, PackageRecord> {
        val builders = LinkedHashMap<String, RecordBuilder>()
        var current: RecordBuilder? = null
        for (line in lines(text)) {
            if (line.startsWith("@@")) {
                val id = line.removePrefix("@@").trim()
                current = builders.getOrPut(id) { RecordBuilder(id) }
            } else {
                current?.consume(line)
            }
        }
        return builders.mapValues { it.value.build() }
    }

    /**
     * Raw `dumpsys package packages` output. Reads the `Packages:` section and stops at the next
     * top-level header (`Hidden system packages:` and others), because hidden system copies of
     * updated apps carry stale state.
     */
    fun parseRawPackageDump(text: String): Map<String, PackageRecord> {
        val builders = LinkedHashMap<String, RecordBuilder>()
        var inPackages = false
        var current: RecordBuilder? = null
        for (rawLine in text.lineSequence()) {
            val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
            if (!inPackages) {
                inPackages = line.trimEnd() == "Packages:"
                continue
            }
            if (line.isNotEmpty() && !line[0].isWhitespace()) break
            val id = packageHeaderId(line)
            if (id != null) {
                // A header repeated inside `Packages:` means something forged or broke the dump: neither copy can be trusted.
                val existing = builders[id]
                current = existing?.also { it.markDuplicate() } ?: RecordBuilder(id).also { builders[id] = it }
            } else {
                current?.consume(line)
            }
        }
        return builders.mapValues { it.value.build() }
    }

    /** The id of a `  Package [id] (hash):` header (exactly two spaces of indent), or null. A plain prefix check: this runs on every line of a 700 KB dump. */
    private fun packageHeaderId(line: String): String? {
        if (!line.startsWith(PACKAGE_HEADER_PREFIX)) return null
        val end = line.indexOf(']', PACKAGE_HEADER_PREFIX.length)
        return if (end > PACKAGE_HEADER_PREFIX.length) line.substring(PACKAGE_HEADER_PREFIX.length, end) else null
    }

    /** `ps -A -o USER,PID,RSS,NAME`. Kernel threads (names in brackets) are dropped. */
    fun parseProcesses(text: String): List<ProcessRecord> =
        lines(text).mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE, limit = 4)
            if (parts.size < 4 || parts[0] == "USER") return@mapNotNull null
            val pid = parts[1].toIntOrNull() ?: return@mapNotNull null
            val rssKb = parts[2].toLongOrNull() ?: return@mapNotNull null
            val name = parts[3].trim()
            if (name.startsWith("[")) null else ProcessRecord(pid, rssKb, name)
        }

    /**
     * The holders of [role] in `dumpsys role`. The dump lists the role once per user, some with no
     * holders, so this returns the union: an extra protected package is harmless, a missing one is not.
     */
    fun parseRoleHolders(text: String, role: String): Set<String> {
        val holders = LinkedHashSet<String>()
        val allLines = lines(text)
        for ((index, line) in allLines.withIndex()) {
            if (line.trim() != "name=$role") continue
            val next = allLines.getOrNull(index + 1)?.trim() ?: continue
            if (next.startsWith("holders=")) {
                holders += next.removePrefix("holders=").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return holders
    }

    /** `settings get secure default_input_method` prints `package/Service`, or `null`. */
    fun parseInputMethodPackage(text: String): String? {
        val value = lines(text).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (value == "null") return null
        return value.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    /** The component the Home button opens, from `resolve-activity --brief`: its last line. Null for "No activity found". */
    fun parseHomeComponent(text: String): String? =
        lines(text).lastOrNull { it.isNotBlank() }?.trim()?.takeIf { LAUNCHABLE_LINE.matches(it) && '/' in it }

    /** `settings get` prints the value, or the word `null` when nothing is set. */
    fun parseSettingValue(text: String): String? =
        lines(text).firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it != "null" }

    /** The `versionCode=` of a `dumpsys package` of one package. */
    fun parseVersionCode(dump: String): Long? = VERSION_CODE.find(dump)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * The CPU busy share from `top -b -n 2`: the header line of the LAST sample, `400%cpu 12%user ... 353%idle ...`.
     * The first sample covers the time since boot, so only the second one says how the CPU is doing now. Waiting for the disk (`iow`) counts as idle. Null when absent.
     */
    fun parseCpuBusyPercent(topOutput: String): Int? {
        val match = CPU_HEADER.findAll(topOutput).lastOrNull() ?: return null
        val total = match.groupValues[1].toIntOrNull() ?: return null
        val idle = match.groupValues[2].toIntOrNull() ?: return null
        // A core waiting for the disk is not busy: it counts as idle, like the usual CPU meters do.
        val waiting = match.groupValues[3].toIntOrNull() ?: 0
        if (total <= 0) return null
        return ((total - (idle + waiting).coerceIn(0, total)) * PERCENT / total)
    }

    /** The crash events in `logcat -b crash` output (Java exceptions, native signals, ANRs), and the first few of them. */
    fun parseCrashEvents(logcatOutput: String): List<String> =
        lines(logcatOutput).filter { line -> CRASH_MARKERS.any { it in line } }.map { it.trim() }

    /** `/proc/meminfo` as key to value in kB. */
    fun parseMeminfo(text: String): Map<String, Long> =
        lines(text).mapNotNull { line ->
            val match = MEMINFO_LINE.matchEntire(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].toLong()
        }.toMap()

    /**
     * Collects the lines of one package record. A package can appear twice (an updated system app
     * and its factory copy): the first record wins for state, installer and shared user, and flags
     * are the union, so PERSISTENT or a system uid is never lost.
     */
    private class RecordBuilder(private val id: String) {
        private val flags = LinkedHashSet<String>()
        private var sawPackageFlags = false
        private var sharedUserUnparsed = false
        private var duplicate = false
        private var sharedUser: String? = null
        private var installer: String? = null
        private var state: PackageState? = null

        /** Dispatches on the start of the line with plain prefix checks: nearly every line of a dump is irrelevant and must cost almost nothing. */
        fun consume(line: String) {
            var start = 0
            while (start < line.length && line[start].isWhitespace()) start++
            when {
                line.startsWith("pkgFlags=[", start) -> {
                    flags += flagTokens(line, start)
                    sawPackageFlags = true
                }
                line.startsWith("sharedUser=", start) -> {
                    if (sharedUser == null) {
                        val raw = line.substring(start + "sharedUser=".length).trim()
                        val name = SHARED_USER_NAME.find(raw)?.groupValues?.get(1)
                        // If the name cannot be read, the system-uid protection cannot be applied: fail closed, never open.
                        if (name == null) sharedUserUnparsed = true else sharedUser = name
                    }
                }
                line.startsWith("installerPackageName=", start) -> {
                    val value = line.substring(start + "installerPackageName=".length).trim().substringBefore(' ')
                    if (installer == null && value.isNotEmpty() && value != "null") installer = value
                }
                line.startsWith("User 0:", start) -> if (state == null) state = parseUserState(line.substring(start + "User 0:".length))
            }
        }

        private fun flagTokens(line: String, start: Int): List<String> {
            val open = line.indexOf('[', start)
            val close = line.lastIndexOf(']')
            if (open < 0 || close <= open) return emptyList()
            return line.substring(open + 1, close).split(' ').filter { it.isNotEmpty() }
        }

        // Some dumps carry empty `User 0:` lines next to the real one: those have no installed= token.
        private fun parseUserState(fields: String): PackageState? {
            val installed = INSTALLED.find(fields)?.groupValues?.get(1) ?: return null
            val enabled = ENABLED.find(fields)?.groupValues?.get(1)?.toInt() ?: return null
            val stopped = STOPPED.find(fields)?.groupValues?.get(1) ?: "false"
            return PackageState(installed.toBoolean(), enabled, stopped.toBoolean())
        }

        fun markDuplicate() {
            duplicate = true
        }

        fun build(): PackageRecord =
            PackageRecord(id, flags, sharedUser, installer, state, isComplete = sawPackageFlags && !sharedUserUnparsed && !duplicate)
    }
}
