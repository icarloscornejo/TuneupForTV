package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.safety.ForceCloseAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceCloseAllTest {
    private val snapshot: DeviceSnapshot = ExecutorHarness(java.io.File("unused")).snapshot()
    private val classifications = Classifier.classify(snapshot, ClassificationContext(DeviceProfiles.SONY, ExecutorHarness.OWN_PACKAGE))
    private val everything = ListFilter(showAll = true)

    @Test
    fun protectedPackagesAreNeverTargetsEvenWhenTheListShowsThem() {
        val targets = ForceCloseAll.shown(snapshot, classifications, everything)
        assertTrue(targets.isNotEmpty())
        targets.forEach { assertEquals(it, null, classifications.getValue(it).essentialReason) }
        assertFalse(ExecutorHarness.PERSISTENT in targets)
    }

    @Test
    fun withoutShowAllOnlyTheAppsTheListShowsAreTargets() {
        val byDefault = ForceCloseAll.shown(snapshot, classifications, ListFilter())
        val all = ForceCloseAll.shown(snapshot, classifications, everything)
        byDefault.forEach {
            assertTrue(it, classifications.getValue(it).category in Category.DEFAULT_VISIBLE)
            assertTrue("$it has no launcher icon", classifications.getValue(it).hasIcon)
        }
        assertTrue(all.containsAll(byDefault))
        assertTrue("the fixture has running services that only Show all reveals", all.size > byDefault.size)
    }

    @Test
    fun aSearchNarrowsTheTargetsToWhatItMatches() {
        val one = ForceCloseAll.shown(snapshot, classifications, ListFilter()).first()
        assertEquals(listOf(one), ForceCloseAll.shown(snapshot, classifications, ListFilter(query = one)))
    }

    @Test
    fun onlyRunningPackagesAreTargets() {
        val running = snapshot.processPackages.values.filterNotNull().toSet()
        assertTrue(running.containsAll(ForceCloseAll.shown(snapshot, classifications, everything)))
    }
}
