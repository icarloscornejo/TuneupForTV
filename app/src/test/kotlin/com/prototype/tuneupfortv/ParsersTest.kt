package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.device.ProcessMapping
import com.prototype.tuneupfortv.adb.Parsers
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private val fixturesDirectory = File("src/test/resources/fixtures")

    private fun fixture(name: String): String = File(fixturesDirectory, name).readText()

    // region small hand-written samples

    @Test
    fun packageListHandlesPrefixAndCrlf() {
        assertEquals(listOf("android", "com.a.b"), Parsers.parsePackageList("package:android\r\npackage:com.a.b\r\n\r\n"))
        assertEquals(listOf("android", "com.a.b"), Parsers.parsePackageList("android\ncom.a.b\n"))
    }

    @Test
    fun launchablesAcceptIdsAndActivitiesAndIgnoreHeaders() {
        val text = "3 activities found:\n  priority=0 preferredOrder=0 match=0x108000\n  com.netflix.ninja/.MainActivity\ncom.spotify.tv\n"
        assertEquals(setOf("com.netflix.ninja", "com.spotify.tv"), Parsers.parseLaunchables(text))
    }

    @Test
    fun capturedBlockExtractsFlagsSharedUserInstallerAndUserState() {
        val text = """
            @@com.mediatek.tis
                sharedUser=SharedUserSetting{870e50a android.uid.system/1000}
                privateFlags=[ PRIVILEGED SYSTEM_EXT ]
                installerPackageName=null
                pkgFlags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_BACKUP ]
                User 0: ceDataInode=16290 installed=true hidden=false stopped=true notLaunched=false enabled=3 instant=false
                User 0:
                User 0:
        """.trimIndent()
        val record = Parsers.parseCapturedPackageInfo(text).getValue("com.mediatek.tis")
        assertTrue(record.isSystem)
        assertTrue(record.isPersistent)
        assertTrue(record.usesSystemUid)
        assertEquals("android.uid.system", record.sharedUser)
        assertNull(record.installer)
        assertEquals(PackageState(installed = true, enabled = 3, stopped = true), record.state)
    }

    @Test
    fun capturedBlockWithTwoRecordsKeepsFirstStateAndUnionsFlags() {
        val text = """
            @@com.example.katniss
                installerPackageName=com.android.vending
                pkgFlags=[ SYSTEM HAS_CODE UPDATED_SYSTEM_APP ]
                User 0: installed=true stopped=false enabled=3
                pkgFlags=[ SYSTEM HAS_CODE PERSISTENT ]
                User 0: installed=true stopped=false enabled=0
        """.trimIndent()
        val record = Parsers.parseCapturedPackageInfo(text).getValue("com.example.katniss")
        assertEquals(3, record.state?.enabled)
        assertTrue(record.isUpdatedSystemApp)
        assertTrue("PERSISTENT in the factory copy must not be lost", record.isPersistent)
        assertEquals("com.android.vending", record.installer)
    }

    @Test
    fun rawDumpStopsAtHiddenSystemPackages() {
        val text = """
            Activity Resolver Table:
              Non-Data Actions:
                  android.intent.action.MAIN:
            Packages:
              Package [com.example.katniss] (a1b2c3):
                userId=10050
                installerPackageName=com.android.vending
                pkgFlags=[ SYSTEM HAS_CODE UPDATED_SYSTEM_APP ]
                User 0: ceDataInode=5 installed=true hidden=false stopped=false enabled=3 instant=false
              Package [com.example.plain] (d4e5f6):
                sharedUser=SharedUserSetting{1234abc android.uid.system/1000}
                pkgFlags=[ SYSTEM HAS_CODE ]
                User 0: ceDataInode=6 installed=true hidden=false stopped=false enabled=0 instant=false

            Hidden system packages:
              Package [com.example.katniss] (zzzzzz):
                pkgFlags=[ SYSTEM HAS_CODE ]
                User 0: ceDataInode=7 installed=true hidden=false stopped=false enabled=0 instant=false
        """.trimIndent()
        val records = Parsers.parseRawPackageDump(text)
        assertEquals(setOf("com.example.katniss", "com.example.plain"), records.keys)
        val katniss = records.getValue("com.example.katniss")
        assertEquals(3, katniss.state?.enabled)
        assertTrue(katniss.isUpdatedSystemApp)
        assertTrue(records.getValue("com.example.plain").usesSystemUid)
    }

    @Test
    fun rawDumpWithoutPackagesSectionIsEmpty() {
        assertTrue(Parsers.parseRawPackageDump("Activity Resolver Table:\n  nothing\n").isEmpty())
    }

    @Test
    fun processesDropKernelThreadsAndKeepColonNames() {
        val text = """
            USER           PID    RSS NAME
            root             1   3344 init
            root             2      0 [kthreadd]
            root            77      0 [kworker/2:1-rcu_gp]
            system         512  90000 com.sony.dtv.airplayapp:airplayserver
        """.trimIndent().replace("\n", "\r\n")
        val processes = Parsers.parseProcesses(text)
        assertEquals(listOf("init", "com.sony.dtv.airplayapp:airplayserver"), processes.map { it.name })
        assertEquals(90000L, processes.last().rssKb)
    }

    @Test
    fun meminfoParsesKeysWithAndWithoutUnit() {
        val memory = Parsers.parseMeminfo("MemTotal:        2450808 kB\nHugePages_Total:       0\nActive(anon):  1234 kB\n")
        assertEquals(2450808L, memory["MemTotal"])
        assertEquals(0L, memory["HugePages_Total"])
        assertEquals(1234L, memory["Active(anon)"])
    }

    @Test
    fun processMappingUsesExactIdThenLongestDottedPrefix() {
        val ids = setOf("com.google.android.gms", "com.google.android", "com.sony.dtv.airplayapp")
        assertEquals("com.sony.dtv.airplayapp", ProcessMapping.packageOf("com.sony.dtv.airplayapp:airplayserver", ids))
        assertEquals("com.google.android.gms", ProcessMapping.packageOf("com.google.android.gms.ui", ids))
        assertNull(ProcessMapping.packageOf("airplayptp", ids))
        assertNull("a prefix must end at a dot", ProcessMapping.packageOf("com.sony.dtv.airplayapp2", ids))
    }

    @Test
    fun roleHoldersAreTheUnionOfEveryUserSection() {
        val text = """
            Roles:
              user 0:
                  {
                    name=android.app.role.HOME
                    holders=com.gathin.tvhome
                  }
                  {
                    name=android.app.role.SYSTEM_SPEECH_RECOGNIZER
                    holders=com.google.android.tts
                  }
              user 10:
                  {
                    name=android.app.role.HOME
                  }
        """.trimIndent()
        assertEquals(setOf("com.gathin.tvhome"), Parsers.parseRoleHolders(text, "android.app.role.HOME"))
        assertEquals(emptySet<String>(), Parsers.parseRoleHolders(text, "android.app.role.MISSING"))
        assertEquals(setOf("a", "b"), Parsers.parseRoleHolders("name=r\nholders=a, b\n", "r"))
    }

    @Test
    fun inputMethodKeepsOnlyThePackage() {
        assertEquals("com.google.android.inputmethod.latin", Parsers.parseInputMethodPackage("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n"))
        assertNull(Parsers.parseInputMethodPackage("null\n"))
        assertNull(Parsers.parseInputMethodPackage(""))
    }

    @Test
    fun resolvedHomeActivityParsesThroughTheLauncherParser() {
        val text = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.gathin.tvhome/.MainActivity\n"
        assertEquals(setOf("com.gathin.tvhome"), Parsers.parseLaunchables(text))
        assertEquals(emptySet<String>(), Parsers.parseLaunchables("No activity found\n"))
    }

    @Test
    fun aRecordWithoutPackageFlagsIsMarkedUnknown() {
        val record = Parsers.parseRawPackageDump("Packages:\n  Package [com.a.b] (1):\n    userId=1\n").getValue("com.a.b")
        assertEquals(false, record.isComplete)
    }

    @Test
    fun aSharedUserLineThatCannotBeReadMakesTheRecordIncompleteInsteadOfUnprotected() {
        // If the name is not understood, "uses the system uid" would silently be false: the record must not be trusted.
        val text = "Packages:\n  Package [com.a.b] (1):\n    sharedUser=Other{x}\n    pkgFlags=[ SYSTEM ]\n    User 0: installed=true enabled=0\n"
        val record = Parsers.parseRawPackageDump(text).getValue("com.a.b")
        assertFalse(record.isComplete)
        val readable = Parsers.parseRawPackageDump(text.replace("Other{x}", "SharedUserSetting{abc android.uid.system/1000}")).getValue("com.a.b")
        assertTrue(readable.isComplete)
        assertTrue(readable.usesSystemUid)
    }

    @Test
    fun aPackageHeaderThatAppearsTwiceInTheDumpMakesItsRecordIncomplete() {
        val text = "Packages:\n  Package [com.a.b] (1):\n    pkgFlags=[ SYSTEM ]\n    User 0: installed=true enabled=0\n" +
            "  Package [com.a.b] (2):\n    pkgFlags=[ ]\n    User 0: installed=true enabled=3\n"
        assertFalse(Parsers.parseRawPackageDump(text).getValue("com.a.b").isComplete)
    }

    @Test
    fun theProcessMappingCostsOneLookupPerDottedPrefix() {
        val ids = setOf("a.b", "a.b.c.d", "x")
        assertEquals("a.b", ProcessMapping.packageOf("a.b.zzz", ids))
        assertEquals("a.b.c.d", ProcessMapping.packageOf("a.b.c.d.e.f:remote", ids))
        assertEquals("x", ProcessMapping.packageOf("x", ids))
        assertNull(ProcessMapping.packageOf("y.z", ids))
        assertNull(ProcessMapping.packageOf("", ids))
    }

    // endregion

    // region the real captures from the Sony BRAVIA

    @Test
    fun realFixturesMatchTheCapturedCounts() {
        val packages = fixtureCount("pm_u.txt") to fixtureCount("pm_plain.txt")
        assertEquals(193 to 186, packages)
        assertEquals(175, fixtureCount("pm_s.txt"))
        assertEquals(11, fixtureCount("pm_3.txt"))
        assertEquals(29, fixtureCount("pm_d.txt"))
        assertEquals(157, fixtureCount("pm_e.txt"))
    }

    private fun fixtureCount(name: String): Int = Parsers.parsePackageList(fixture(name)).size

    @Test
    fun demoSnapshotLoadsAndHasTheSevenUninstalledPackages() {
        val snapshot = FixtureSnapshot.load(::fixture)
        assertEquals(193, snapshot.packages.size)
        assertEquals(
            setOf(
                "com.google.android.videos",
                "com.mediatek.wwtv.mediaplayer",
                "com.sony.dtv.ecodashboard",
                "com.sony.dtv.notificationcenter",
                "com.sony.dtv.smarthelp",
                "com.sony.dtv.soundslauncher",
                "com.sonypicturescore",
            ),
            snapshot.uninstalledForUser0,
        )
        snapshot.packages.filter { it.id in snapshot.uninstalledForUser0 }.forEach {
            assertEquals("${it.id} must be installed=false", false, it.state?.installed)
        }
    }

    @Test
    fun everyPackageHasStateAndTheTwentyPersistentOnesAreFound() {
        val snapshot = FixtureSnapshot.load(::fixture)
        assertTrue(snapshot.packages.all { it.state != null })
        val persistent = snapshot.packages.filter { it.isPersistent }.map { it.id }.toSet()
        assertEquals(20, persistent.size)
        assertTrue("com.sony.dtv.timers" in persistent)
        assertTrue("com.sony.dtv.sonysystemservice" in persistent)
        assertTrue("android" in persistent)
    }

    @Test
    fun systemUidRuleIsAPrefixMatchSoItAlsoCatchesSystemUi() {
        val snapshot = FixtureSnapshot.load(::fixture)
        val prefixMatches = snapshot.packages.filter { it.usesSystemUid }
        assertEquals(32, prefixMatches.size)
        // 31 use exactly android.uid.system; the extra one is android.uid.systemui, already PERSISTENT.
        val notExact = prefixMatches.filter { it.sharedUser != "android.uid.system" }
        assertEquals(listOf("com.android.systemui"), notExact.map { it.id })
        assertTrue(notExact.single().isPersistent)
    }

    @Test
    fun disabledPackagesSplitIntoTwentyOneRestorableAndEightNotChangeableFromShell() {
        val snapshot = FixtureSnapshot.load(::fixture)
        val disabledIds = Parsers.parsePackageList(fixture("pm_d.txt")).toSet()
        val byState = snapshot.packages.filter { it.id in disabledIds }.groupBy { it.state?.enabled }
        assertEquals(21, byState.getValue(3).size)
        assertEquals(8, byState.getValue(2).size)
    }

    @Test
    fun updatedSystemAppsKeepTheirCurrentState() {
        val snapshot = FixtureSnapshot.load(::fixture)
        val netflix = snapshot.packages.first { it.id == "com.netflix.ninja" }
        assertTrue(netflix.isSystem)
        assertTrue(netflix.isUpdatedSystemApp)
        assertEquals("com.android.vending", netflix.installer)
        assertTrue("com.netflix.ninja" in snapshot.launchable)
    }

    @Test
    fun processesAreUserspaceOnlyAndMapToPackages() {
        val snapshot = FixtureSnapshot.load(::fixture)
        assertTrue(snapshot.processes.none { it.name.startsWith("[") })
        assertEquals(snapshot.processes.sortedByDescending { it.rssKb }, snapshot.processes)
        val airplayServer = snapshot.processes.first { it.name == "com.sony.dtv.airplayapp:airplayserver" }
        assertEquals("com.sony.dtv.airplayapp", snapshot.processPackages[airplayServer])
        assertTrue(snapshot.processes.any { it.name == "airplayptp" })
        assertNull(snapshot.processPackages.getValue(snapshot.processes.first { it.name == "airplayptp" }))
        assertTrue(snapshot.memoryKb.getValue("MemTotal") > 0)
    }

    // endregion

    @Test
    fun cpuBusyComesFromTheLastTopSampleNotTheAverageSinceBoot() {
        val output = "400%cpu  4%user   0%nice   0%sys 396%idle   0%iow\n  PID\n400%cpu  100%user   0%nice  40%sys 260%idle   0%iow\n  PID\n"
        assertEquals(35, Parsers.parseCpuBusyPercent(output))
    }

    @Test
    fun aCoreWaitingForTheDiskCountsAsIdle() {
        // From the real TV: 249 idle and 99 waiting out of 400 leaves 52 busy, 13 percent.
        assertEquals(13, Parsers.parseCpuBusyPercent("400%cpu  10%user   1%nice  37%sys 249%idle  99%iow   3%irq   1%sirq   0%host\n"))
    }

    @Test
    fun cpuBusyIsUnknownWhenTheHeaderIsMissingOrBroken() {
        assertNull(Parsers.parseCpuBusyPercent(""))
        assertNull(Parsers.parseCpuBusyPercent("Tasks: 1 total\n"))
        assertNull(Parsers.parseCpuBusyPercent("0%cpu 0%user 0%idle\n"))
    }

    @Test
    fun crashEventsCountJavaNativeAndAnrLinesOnly() {
        val log = """
            --------- beginning of crash
            10-19 12:00:01.000  1  1 E AndroidRuntime: FATAL EXCEPTION: main
            10-19 12:00:01.000  1  1 E AndroidRuntime: Process: com.x, PID: 4
            10-19 12:00:02.000  1  1 F libc    : Fatal signal 11 (SIGSEGV)
            10-19 12:00:03.000  1  1 E ActivityManager: ANR in com.y
        """.trimIndent()
        assertEquals(3, Parsers.parseCrashEvents(log).size)
        assertEquals(0, Parsers.parseCrashEvents("--------- beginning of crash\n").size)
    }

    // region the launcher reads, with the answers of a real TV (a Xiaomi MiTV-AFMU0)

    private val realHomeCandidates = """
        3 activities found:
          Activity #0:
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.apps.tv.launcherx/.home.HomeActivity
          Activity #1:
            priority=1 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.tungsten.setupwraith/.RecoveryActivity
          Activity #2:
            priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.android.tv.settings/.system.FallbackHome
    """.trimIndent() + "\n"

    @Test
    fun theHomeCandidatesAreThePackagesOfTheActivitiesAndNothingFromTheHeaders() {
        assertEquals(
            setOf("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith", "com.android.tv.settings"),
            Parsers.parseLaunchables(realHomeCandidates),
        )
    }

    @Test
    fun theHomeComponentIsTheLastLineOfTheBriefAnswer() {
        val brief = "priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.google.android.apps.tv.launcherx/.home.HomeActivity\n"
        assertEquals("com.google.android.apps.tv.launcherx/.home.HomeActivity", Parsers.parseHomeComponent(brief))
        assertEquals(null, Parsers.parseHomeComponent("No activity found\n"))
        assertEquals(null, Parsers.parseHomeComponent(""))
    }

    @Test
    fun aSettingThatIsNotSetReadsAsAbsent() {
        assertEquals(null, Parsers.parseSettingValue("null\n"))
        assertEquals(null, Parsers.parseSettingValue("\n"))
        assertEquals("a.b/.C,d.e/.F", Parsers.parseSettingValue("a.b/.C,d.e/.F\n"))
    }

    @Test
    fun theVersionCodeComesFromTheDumpOfOnePackage() {
        assertEquals(10001L, Parsers.parseVersionCode("  Package [x] (1):\n    versionCode=10001 minSdk=26 targetSdk=34\n"))
        assertEquals(null, Parsers.parseVersionCode("Unable to find package: x\n"))
    }

    // endregion
}
