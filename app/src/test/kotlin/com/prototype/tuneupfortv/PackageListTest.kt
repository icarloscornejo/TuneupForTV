package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.device.PackageList
import com.prototype.tuneupfortv.device.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageListTest {
    private val snapshot = FixtureSnapshot.load()
    private val classifications = Classifier.classify(snapshot, ClassificationContext(DeviceProfiles.SONY, "com.prototype.tuneupfortv"))
    private val items = PackageList.items(snapshot, classifications, DeviceProfiles.SONY)
    private val processes = PackageList.processItems(snapshot, classifications)

    private fun ids(filter: ListFilter): List<String> = PackageList.filterPackages(items, filter).map { it.record.id }

    @Test
    fun theDefaultViewShowsNetflixAndHidesTheVendorSystemService() {
        val shown = ids(ListFilter())
        assertTrue("com.netflix.ninja" in shown)
        assertFalse("com.sony.dtv.sonysystemservice" in shown)
    }

    @Test
    fun theDefaultViewIsTheAppsAPersonCanOpen() {
        val shown = PackageList.filterPackages(items, ListFilter())
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.classification.category in Category.DEFAULT_VISIBLE && it.classification.hasIcon })
        // An app without a launcher icon (a Play-updated service, say) only shows with "show all".
        val iconless = items.filter { it.classification.category in Category.DEFAULT_VISIBLE && !it.classification.hasIcon }
        assertTrue("the fixture has such a package", iconless.isNotEmpty())
        assertTrue(iconless.none { it.record.id in shown.map { shownItem -> shownItem.record.id } })
        assertTrue(iconless.all { it.record.id in ids(ListFilter(showAll = true)) })
    }

    @Test
    fun showAllRevealsEveryPackageIncludingEssentialOnes() {
        val shown = ids(ListFilter(showAll = true))
        assertEquals(193, shown.size)
        assertTrue("com.sony.dtv.sonysystemservice" in shown)
    }

    @Test
    fun categoryChipsNarrowTheShowAllView() {
        val overlays = ids(ListFilter(showAll = true, categories = setOf(Category.OVERLAY)))
        assertEquals(25, overlays.size)
        val two = ids(ListFilter(showAll = true, categories = setOf(Category.OVERLAY, Category.UNINSTALLED_USER0)))
        assertEquals(32, two.size)
    }

    @Test
    fun categoryChipsAreIgnoredWhileShowAllIsOff() {
        assertEquals(ids(ListFilter()), ids(ListFilter(showAll = false, categories = setOf(Category.OVERLAY))))
    }

    @Test
    fun searchMatchesPartOfTheIdIgnoringCase() {
        assertEquals(listOf("com.netflix.ninja"), ids(ListFilter(query = "  NETFLIX ")))
        assertEquals(emptyList<String>(), ids(ListFilter(query = "nothing-like-this")))
    }

    @Test
    fun sortByNameIsAlphabeticalAndByMemoryPutsTheBiggestFirst() {
        val byName = ids(ListFilter(showAll = true, sort = SortOrder.NAME))
        assertEquals(byName.sorted(), byName)
        val byMemory = PackageList.filterPackages(items, ListFilter(showAll = true, sort = SortOrder.MEMORY))
        assertEquals(byMemory.map { it.memoryKb }.sortedDescending(), byMemory.map { it.memoryKb })
        assertTrue(byMemory.first().memoryKb > 0)
    }

    @Test
    fun sortByCategoryFollowsTheChipOrder() {
        val ranks = PackageList.filterPackages(items, ListFilter(showAll = true, sort = SortOrder.CATEGORY))
            .map { Category.DISPLAY_ORDER.indexOf(it.classification.category) }
        assertEquals(ranks.sorted(), ranks)
    }

    @Test
    fun theSonyHintsMarkCommonlyDisabledPackagesButOnlyAsABadge() {
        val hinted = items.filter { it.commonlyDisabled }.map { it.record.id }
        assertTrue(hinted.isNotEmpty())
        // A hint never changes a category: the vendor demo apps stay vendor services.
        hinted.forEach { assertTrue(it, it in DeviceProfiles.SONY.commonlyDisabled) }
        assertEquals(Category.VENDOR_SERVICE, classifications.getValue("com.sony.dtv.promos").category)
    }

    @Test
    fun processesFollowTheSameDefaultsAndShowNativeDaemonsOnlyWithShowAll() {
        val defaults = PackageList.filterProcesses(processes, ListFilter())
        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.all { it.category in Category.DEFAULT_VISIBLE })
        assertTrue(defaults.none { it.process.name == "airplayptp" })

        val everything = PackageList.filterProcesses(processes, ListFilter(showAll = true))
        // Protected packages are never listed: their processes cannot be stopped.
        assertEquals(processes.count { it.category != Category.ESSENTIAL }, everything.size)
        assertTrue(everything.none { it.category == Category.ESSENTIAL })
        assertTrue(everything.any { it.process.name == "airplayptp" && it.category == null })

        val onlyVendor = PackageList.filterProcesses(processes, ListFilter(showAll = true, categories = setOf(Category.VENDOR_SERVICE)))
        assertTrue(onlyVendor.none { it.category == null })
        assertTrue(PackageList.filterProcesses(processes, ListFilter(showAll = true, categories = setOf(Category.ESSENTIAL))).isEmpty())
    }

    @Test
    fun processSearchMatchesTheProcessOrItsPackage() {
        val sample = processes.first { it.packageId != null && it.category != null && it.category != Category.ESSENTIAL }
        val byName = PackageList.filterProcesses(processes, ListFilter(showAll = true, query = sample.process.name))
        assertTrue(byName.any { it.process.name == sample.process.name })
        val byPackage = PackageList.filterProcesses(processes, ListFilter(showAll = true, query = sample.packageId!!))
        assertTrue(byPackage.any { it.packageId == sample.packageId })
    }
}
