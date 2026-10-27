package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.device.AccessibilityValue
import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.PermissionName
import com.prototype.tuneupfortv.device.ProcessName
import com.prototype.tuneupfortv.device.ScreensaverValue
import com.prototype.tuneupfortv.adb.ReadEnvelope
import com.prototype.tuneupfortv.adb.ScriptOutput
import com.prototype.tuneupfortv.adb.ShellCommand
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CommandsTest {
    private fun sectionCommands(command: ShellCommand): List<String> = command.line.split("; run ").drop(1).map { it.substringAfter(" ") }

    @Test
    fun everyPackageManagerReadTargetsUserZero() {
        val commands = sectionCommands(Commands.readDevice()) + sectionCommands(Commands.readLivePackage(com.prototype.tuneupfortv.device.PackageName("a.b")))
        commands.filter { it.startsWith("pm ") || it.startsWith("cmd ") || it.startsWith("settings ") }.forEach {
            assertTrue(it, it.contains("--user 0"))
        }
        assertEquals(16, sectionCommands(Commands.readDevice()).size)
    }

    @Test
    fun homeResolutionNeedsTheMainAction() {
        // Without -a android.intent.action.MAIN, Android 12 answers "No activity found".
        assertTrue(Commands.readDevice().line.contains("-a android.intent.action.MAIN -c android.intent.category.HOME"))
    }

    @Test
    fun noRedundantListsAreRead() {
        // The disabled, enabled, system and third-party lists are derived from the dump: only two lists are asked for.
        assertEquals(2, sectionCommands(Commands.readDevice()).count { it.startsWith("pm list packages") })
    }

    @Test
    fun readsAreWrappedSoTheAnswerCarriesTheSerial() {
        val read = Commands.readDevice()
        assertEquals(ShellCommand.Kind.READ, read.kind)
        assertTrue(read.line.startsWith("echo \"@@S:\$(getprop ro.serialno)\"; "))
    }

    @Test
    fun envelopeSplitsTheSerialFromTheOutput() {
        assertEquals("ABC123" to "line1\nline2\n", ReadEnvelope.unwrap("@@S:ABC123\nline1\nline2\n"))
        assertEquals("" to "x\n", ReadEnvelope.unwrap("@@S:\nx\n"))
        assertEquals("ABC123" to "", ReadEnvelope.unwrap("@@S:ABC123"))
    }

    @Test
    fun envelopeWithoutTheMarkerReportsNoSerial() {
        val (serial, rest) = ReadEnvelope.unwrap("just output\n")
        assertNull(serial)
        assertEquals("just output\n", rest)
    }

    // region the script answer

    @Test
    fun scriptOutputSplitsEverySectionWithItsExitCode() {
        val answer = "@@BEGIN|a\nhello\n\n@@END|0\n@@BEGIN|b\n\n@@END|1\n@@BEGIN|c\nno newline\n@@END|0\n"
        val sections = ScriptOutput.parse(answer)!!
        assertEquals("hello\n", sections.getValue("a").output)
        assertEquals(0, sections.getValue("a").exitCode)
        assertEquals("", sections.getValue("b").output)
        assertEquals(1, sections.getValue("b").exitCode)
        assertEquals("no newline", sections.getValue("c").output)
    }

    @Test
    fun aCutOrMalformedScriptAnswerIsNullNeverAPartialMap() {
        assertNull(ScriptOutput.parse("@@BEGIN|a\nhello\n"))
        assertNull(ScriptOutput.parse("@@BEGIN|a"))
        assertNull(ScriptOutput.parse("@@BEGIN|a\nx\n\n@@END|notanumber\n"))
        assertTrue(ScriptOutput.parse("nothing at all")!!.isEmpty())
    }

    /** Runs the real script prelude with the local shell: the exit codes and newlines must come out exactly as parsed. */
    @Test
    fun theRunFunctionBehavesAsTheParserExpectsInARealShell() {
        assumeTrue(File("/bin/sh").exists())
        val script = "${Commands.RUN_FUNCTION}; run ok echo hi; run fails sh -c 'exit 3'; run nonl printf abc; run empty true; run multi printf 'a\\nb\\n'"
        val output = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start().let { it.inputStream.readBytes().decodeToString().also { _ -> it.waitFor() } }
        val sections = assertNotNull(ScriptOutput.parse(output)).let { ScriptOutput.parse(output)!! }
        assertEquals("hi\n", sections.getValue("ok").output)
        assertEquals(0, sections.getValue("ok").exitCode)
        assertEquals(3, sections.getValue("fails").exitCode)
        assertEquals("abc", sections.getValue("nonl").output)
        assertEquals("", sections.getValue("empty").output)
        assertEquals("a\nb\n", sections.getValue("multi").output)
    }

    @Test
    fun thePidsFunctionReportsEachProcessWithItsOwnExitCode() {
        assumeTrue(File("/bin/sh").exists())
        // A stand-in for the device's pidof: "a" runs with two pids, anything else is not running (exit 1, no output).
        val script = "pidof() { case \$1 in a) echo '12 13';; b:c) echo 7;; *) return 1;; esac; }; ${Commands.RUN_FUNCTION}; ${Commands.PIDS_FUNCTION}; run pids pids a b:c missing"
        val output = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start().let { it.inputStream.readBytes().decodeToString().also { _ -> it.waitFor() } }
        val lines = ScriptOutput.parse(output)!!.getValue("pids").output.lines().filter { it.isNotBlank() }
        assertEquals(listOf("@@a|0|12 13", "@@b:c|0|7", "@@missing|1|"), lines)
    }

    @Test
    fun theWatchBaselineAsksForTheClockAndThePidsInOneRound() {
        val command = Commands.readWatchBaseline(listOf(ProcessName("a.b"), ProcessName("airplayptp")))
        assertTrue(command.line, command.line.endsWith("run epoch date +%s; run pids pids a.b airplayptp"))
    }

    // endregion

    // region the launcher swap

    private val serial = DeviceSerial("FAKE0001")

    private val launcherWrites: List<ShellCommand> = listOf(
        Commands.installPushed(serial),
        Commands.grant(PackageName("com.prototype.lightlauncher"), PermissionName("android.permission.WRITE_SECURE_SETTINGS"), serial),
        Commands.compileSpeed(PackageName("com.prototype.lightlauncher"), serial),
        Commands.setHome(ComponentName("com.prototype.lightlauncher/.MainActivity"), serial),
        Commands.putScreensaver(ScreensaverValue("com.prototype.lightlauncher/.ClockDream"), serial),
        Commands.deleteScreensaver(serial),
        Commands.putAccessibility(AccessibilityValue("a.b/.C:d.e/.F"), serial),
    )

    @Test
    fun everyWriteOfTheLauncherSwapIsBehindTheIdentityFence() {
        launcherWrites.forEach {
            assertEquals(ShellCommand.Kind.MUTATE, it.kind)
            assertTrue(it.line, it.line.startsWith("if [ \"\$(getprop ro.serialno)\" = \"FAKE0001\" ]; then "))
            assertTrue(it.line, it.line.endsWith("; else exit ${Commands.IDENTITY_MISMATCH_EXIT}; fi"))
        }
    }

    @Test
    fun theComponentsAndValuesInACommandAreQuotedAndTargetUserZero() {
        assertTrue(launcherWrites[3].line, launcherWrites[3].line.contains("set-home-activity --user 0 'com.prototype.lightlauncher/.MainActivity'"))
        assertTrue(launcherWrites[4].line, launcherWrites[4].line.contains("settings put --user 0 secure screensaver_components 'com.prototype.lightlauncher/.ClockDream'"))
        assertTrue(launcherWrites[5].line, launcherWrites[5].line.contains("settings delete --user 0 secure screensaver_components"))
        assertTrue(launcherWrites[6].line, launcherWrites[6].line.contains("settings put --user 0 secure enabled_accessibility_services 'a.b/.C:d.e/.F'"))
        assertTrue(launcherWrites[1].line, launcherWrites[1].line.contains("pm grant com.prototype.lightlauncher 'android.permission.WRITE_SECURE_SETTINGS'"))
    }

    @Test
    fun theInstallReadsAFixedPathAndAlwaysRemovesTheFile() {
        val line = launcherWrites[0].line
        assertTrue(line, line.contains("pm install -r '/data/local/tmp/tuneup-launcher.apk'; s=\$?; rm -f '/data/local/tmp/tuneup-launcher.apk'; exit \$s"))
    }

    @Test
    fun aValueThatTheShellCouldExpandOrSplitIsNeverAccepted() {
        listOf("a.b/.C\$D", "a.b/.C d.e/.F", "a.b/.C;reboot", "a.b/.C'x", "").forEach {
            assertNull(it, ComponentName.orNull(it))
            assertNull(it, ScreensaverValue.orNull(it))
            assertNull(it, AccessibilityValue.orNull(it))
        }
        assertNull(PermissionName.orNull("android.permission.X; reboot"))
        assertEquals("a.b/.C,d.e/.F", ScreensaverValue.orNull("a.b/.C,d.e/.F")?.value)
        assertNull(ScreensaverValue.orNull("a.b/.C:d.e/.F"))
        assertEquals("a.b/.C:d.e/.F", AccessibilityValue.orNull("a.b/.C:d.e/.F")?.value)
        assertNull(AccessibilityValue.orNull("a.b/.C,d.e/.F"))
        assertEquals("a.b", ComponentName("a.b/.C").packageName)
    }

    @Test
    fun theLauncherStateIsOneRoundTripWithTheReadsTheSwapNeeds() {
        val sections = sectionCommands(Commands.readLauncherState())
        assertEquals(8, sections.size)
        assertTrue(sections.any { it == "getprop ro.product.name" })
        assertTrue(sections.any { it.contains("query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME") })
        assertTrue(sections.any { it == "dumpsys package packages" })
        assertTrue(sections.any { it == "wifi_owners" })
        sections.filter { it.startsWith("settings ") }.forEach { assertTrue(it, it.contains("--user 0")) }
        // The extra reads are only asked for when the target is one of the packages of the swap.
        val plain = sectionCommands(Commands.readLivePackage(PackageName("a.b")))
        val withLaunchers = sectionCommands(Commands.readLivePackage(PackageName("a.b"), withLaunchers = true))
        assertEquals(plain.size + 2, withLaunchers.size)
    }

    @Test
    fun theWifiOwnersFunctionExitsZeroWheneverDumpsysAnsweredAndNonZeroWhenItFailed() {
        assumeTrue(File("/bin/sh").exists())
        fun answer(dumpsys: String): Pair<Int, String> {
            val script = "dumpsys() { $dumpsys; }; ${Commands.RUN_FUNCTION}; ${Commands.WIFI_OWNERS_FUNCTION}; run wifi wifi_owners"
            val output = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start().let { it.inputStream.readBytes().decodeToString().also { _ -> it.waitFor() } }
            val section = ScriptOutput.parse(output)!!.getValue("wifi")
            return section.exitCode to section.output
        }
        assertEquals(0 to "cname=com.google.android.tungsten.setupwraith\n", answer("echo 'x cname=com.google.android.tungsten.setupwraith y'"))
        // No network of the setup app: the read worked, there is simply nothing to report.
        assertEquals(0, answer("echo nothing here").first)
        // dumpsys itself failed: that is an unreadable answer, never "no owner".
        assertEquals(1, answer("return 1").first)
    }

    // endregion

    // region source guards: the safety promises that no test of behavior can prove

    private val sourceRoot = File("src/main/kotlin")

    private fun mainSources(): List<File> = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun onlyCommandsKtCreatesShellCommands() {
        val constructorCall = Regex("""\bShellCommand\(""")
        mainSources().filter { it.name != "Commands.kt" }.forEach {
            assertFalse("${it.name} builds a ShellCommand by hand", constructorCall.containsMatchIn(it.readText()))
        }
    }

    @Test
    fun onlyTheAdbAdapterFilesImportDadb() {
        mainSources().filter { it.name !in setOf("DadbAdbClient.kt", "AdbKeys.kt") }.forEach {
            assertFalse("${it.name} imports dadb", it.readText().contains("import dadb"))
        }
    }

    @Test
    fun theOnlyUninstallIsTheUserZeroOne() {
        // The one command that removes a package lives in Commands.kt, always with -k (keep data) and --user 0.
        // Nothing wipes data (`pm clear`) and nothing uninstalls through another door.
        val forbiddenText = Regex(""""[^"\n]*(cmd package uninstall|pm clear|package clear)[^"\n]*"""")
        val forbiddenCall = Regex("""\.uninstall\(""")
        mainSources().forEach {
            val text = it.readText()
            assertFalse("${it.name} has a wipe command", forbiddenText.containsMatchIn(text))
            assertFalse("${it.name} calls uninstall()", forbiddenCall.containsMatchIn(text))
            if (it.name != "Commands.kt") assertFalse("${it.name} builds an uninstall by hand", text.contains("pm uninstall"))
        }
        val uninstalls = Regex("""pm uninstall[^"\n]*""").findAll(mainSources().first { it.name == "Commands.kt" }.readText()).map { it.value }.toList()
        assertEquals(1, uninstalls.size)
        assertTrue(uninstalls.single(), uninstalls.single().startsWith("pm uninstall \$USER_ZERO "))
    }

    // endregion
}
