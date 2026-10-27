package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.safety.DebloatEntryState
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.device.DebloatLists
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.safety.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatListTest {
    private fun parse(text: String): DebloatList = checkNotNull(DebloatLists.parse("test", text)) { "list was discarded" }

    private fun entry(list: DebloatList, packageId: String) = list.entries.single { it.packageId == packageId }

    private fun list(fileName: String, title: String): DebloatList =
        parse("# $title\ncom.example.app\n").copy(fileName = fileName)

    @Test
    fun lineOneIsTheTitleAndAShortWordBeforeAPackageOpensAGroup() {
        val result = parse("# BRAVIA_BF1_4K_A\n#\n# Demo\ncom.sony.dtv.demosupport\n")
        assertEquals("BRAVIA_BF1_4K_A", result.title)
        assertEquals(listOf("Demo"), result.groups.map { it.name })
    }

    @Test
    fun aCommentThatNamesAPackageIsTextNotAnEntry() {
        val result = parse("# Title\n\n# Keep\n# com.google.android.katniss voice, kept\ncom.example.app\n")
        assertEquals(listOf("com.example.app"), result.entries.map { it.packageId })
    }

    @Test
    fun theOptionalMarkerGivesAnUncheckedEntryWithItsNote() {
        val result = parse("# Title\n\n# Tuners\n#? com.x.tuner   # only without an antenna\ncom.y.other\n")
        val optional = entry(result, "com.x.tuner")
        assertFalse(optional.suggested)
        assertEquals("only without an antenna", optional.note)
        assertTrue(entry(result, "com.y.other").suggested)
    }

    @Test
    fun theNoteAfterAPackageIsKept() {
        val result = parse("# Title\ncom.x.app   # explains why\n")
        assertEquals("explains why", entry(result, "com.x.app").note)
    }

    @Test
    fun theScreenReaderIsNeverSuggestedInAnyList() {
        val result = parse("# Title\ncom.google.android.marvin.talkback\ncom.x.app\n")
        val screenReader = entry(result, "com.google.android.marvin.talkback")
        assertFalse(screenReader.suggested)
        assertEquals(DebloatLists.NEVER_SUGGESTED_NOTE, screenReader.note)
    }

    @Test
    fun aByteOrderMarkAndWindowsLineEndingsAreIgnored() {
        val result = parse("﻿# Title\r\n\r\n# Group\r\ncom.x.app\r\n")
        assertEquals("Title", result.title)
        assertEquals(listOf("com.x.app"), result.entries.map { it.packageId })
        assertEquals("Group", result.groups.single().name)
    }

    @Test
    fun anActiveLineThatIsNotAPackageDiscardsTheWholeList() {
        assertNull(DebloatLists.parse("test", "# Title\ncom.x.app\nMIT License\n"))
        assertNull(DebloatLists.parse("test", "# Title\nnodots\n"))
    }

    @Test
    fun aListWithoutPackagesIsDiscarded() {
        assertNull(DebloatLists.parse("test", "# Title\n# only comments\n"))
    }

    @Test
    fun aRepeatedPackageCountsOnce() {
        val result = parse("# Title\ncom.x.app\ncom.x.app\n")
        assertEquals(1, result.entries.size)
    }

    @Test
    fun theRankPutsTheOwnListFirstOnAVerifiedBravia() {
        val lists = listOf(
            list("xiaomi-mi-tv-4a", "Xiaomi"),
            list("sony-bravia-ae2", "Sony BRAVIA AE2"),
            list("sony-bravia-bf1", "Sony BRAVIA BF1"),
            list(DebloatLists.OWN_BF1, "Mine"),
        )
        val bf1 = DeviceIdentity("Sony", "BRAVIA BF1", "SERIAL123", 31)
        val ranked = DebloatLists.rank(bf1, lists)
        assertEquals(DebloatLists.OWN_BF1, ranked.recommended?.fileName)
        assertEquals(listOf("sony-bravia-bf1", "sony-bravia-ae2"), ranked.sameBrand.map { it.fileName })
        assertEquals(listOf("xiaomi-mi-tv-4a"), ranked.others.map { it.fileName })
    }

    @Test
    fun aSonyThatIsNotTheTestedBraviaGetsNoRecommendedListButStillItsBrand() {
        val lists = listOf(list(DebloatLists.OWN_BF1, "Mine"), list("sony-kd-55x85j", "Sony KD"), list("hisense-a4", "Hisense"))
        val other = DeviceIdentity("Sony", "BRAVIA XR", "SERIAL123", 33)
        val ranked = DebloatLists.rank(other, lists)
        assertNull(ranked.recommended)
        assertTrue(ranked.sameBrand.any { it.fileName == "sony-kd-55x85j" })
        assertTrue(ranked.others.any { it.fileName == "hisense-a4" })
    }

    @Test
    fun anotherBrandHasNoRecommendedListAndItsOwnBrandFirst() {
        val lists = listOf(list("sony-bravia-bf1", "Sony"), list("hisense-a4", "Hisense"))
        val ranked = DebloatLists.rank(DeviceIdentity("Hisense", "A4", "SERIAL123", 30), lists)
        assertNull(ranked.recommended)
        assertEquals(listOf("hisense-a4"), ranked.sameBrand.map { it.fileName })
        assertEquals(listOf("sony-bravia-bf1"), ranked.others.map { it.fileName })
    }

    @Test
    fun theEntryStateFollowsTheRejectReason() {
        assertEquals(DebloatEntryState.WILL_DISABLE, DebloatEntryState.from(null))
        assertEquals(DebloatEntryState.ALREADY_DISABLED, DebloatEntryState.from(RejectReason.ALREADY_DISABLED))
        assertEquals(DebloatEntryState.ALREADY_DISABLED, DebloatEntryState.from(RejectReason.NOT_CHANGEABLE_FROM_SHELL))
        assertEquals(DebloatEntryState.NOT_ON_TV, DebloatEntryState.from(RejectReason.UNREADABLE))
        assertEquals(DebloatEntryState.NOT_ON_TV, DebloatEntryState.from(RejectReason.NOT_INSTALLED))
        assertEquals(DebloatEntryState.PROTECTED, DebloatEntryState.from(RejectReason.ESSENTIAL))
        assertEquals(DebloatEntryState.PROTECTED, DebloatEntryState.from(RejectReason.THIS_APP))
        assertNotNull(DebloatEntryState.from(RejectReason.UNRELIABLE_DEVICE))
    }
}
