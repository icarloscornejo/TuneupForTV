package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.device.Classification
import com.prototype.tuneupfortv.device.ClassificationContext
import com.prototype.tuneupfortv.device.Classifier
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.EssentialReason
import com.prototype.tuneupfortv.adb.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    private val snapshot: DeviceSnapshot = FixtureSnapshot.load()
    private val ownPackage = "com.prototype.tuneupfortv"

    private fun classify(
        snapshot: DeviceSnapshot = this.snapshot,
        overrides: Map<String, Category> = emptyMap(),
        iconPackages: Set<String> = emptySet(),
    ): Map<String, Classification> =
        Classifier.classify(snapshot, ClassificationContext(DeviceProfiles.SONY, ownPackage, overrides, iconPackages))

    private fun idsOf(category: Category, result: Map<String, Classification> = classify()): Set<String> =
        result.filterValues { it.category == category }.keys

    @Test
    fun reproducesTheExpectedCountsOfTheRealCapture() {
        val counts = classify().values.groupingBy { it.category }.eachCount()
        // 65 essential: the earlier 46 plus the packages that share the system uid (the user approved that rule).
        assertEquals(
            mapOf(
                Category.ESSENTIAL to 65,
                Category.OVERLAY to 25,
                Category.VENDOR_SERVICE to 36,
                Category.GOOGLE_ANDROID_SERVICE to 41,
                Category.PREINSTALLED_USER to 8,
                Category.USER_INSTALLED to 11,
                Category.UNINSTALLED_USER0 to 7,
            ),
            counts,
        )
        assertEquals(193, counts.values.sum())
    }

    @Test
    fun everyPersistentPackageIsEssential() {
        val persistent = snapshot.packages.filter { it.isPersistent }.map { it.id }
        assertEquals(20, persistent.size)
        val result = classify()
        persistent.forEach { assertEquals(it, Category.ESSENTIAL, result.getValue(it).category) }
        assertEquals(EssentialReason.PERSISTENT, result.getValue("com.sony.dtv.timers").essentialReason)
    }

    @Test
    fun netflixAndPrimeVideoAreNormalApps() {
        val result = classify()
        assertEquals(Category.PREINSTALLED_USER, result.getValue("com.netflix.ninja").category)
        assertEquals(Category.PREINSTALLED_USER, result.getValue("com.amazon.amazonvideo.livingroom").category)
    }

    @Test
    fun theElevenUserAppsAreUserInstalled() {
        val userApps = Parsers.parsePackageList(FixtureSnapshot.read("pm_3.txt"))
        assertEquals(11, userApps.size)
        val result = classify()
        userApps.forEach { assertEquals(it, Category.USER_INSTALLED, result.getValue(it).category) }
    }

    @Test
    fun theSevenUninstalledPackagesAreUninstalledForUserZero() {
        assertEquals(snapshot.uninstalledForUser0, idsOf(Category.UNINSTALLED_USER0))
    }

    @Test
    fun theDefaultViewIsTheTwoAppCategories() {
        assertEquals(setOf(Category.PREINSTALLED_USER, Category.USER_INSTALLED), Category.DEFAULT_VISIBLE)
    }

    @Test
    fun sonySystemServiceIsHiddenByDefaultBecauseItIsEssential() {
        val classification = classify().getValue("com.sony.dtv.sonysystemservice")
        assertEquals(Category.ESSENTIAL, classification.category)
        assertFalse(classification.category in Category.DEFAULT_VISIBLE)
    }

    @Test
    fun theCurrentHomeLauncherIsEssentialEvenWhenItIsAUserApp() {
        // On the real TV the Home role is held by a user-installed launcher, not the stock one.
        val withUserHome = snapshot.copy(homePackages = setOf("org.localsend.localsend_app"))
        val result = classify(withUserHome)
        assertEquals(Category.ESSENTIAL, result.getValue("org.localsend.localsend_app").category)
        assertEquals(EssentialReason.HOME_LAUNCHER, result.getValue("org.localsend.localsend_app").essentialReason)
        assertEquals(10, idsOf(Category.USER_INSTALLED, result).size)
    }

    @Test
    fun theDefaultKeyboardAndThisAppAreEssential() {
        val withOwnApp = snapshot.copy(inputMethodPackage = "org.smarttube.stable")
        val result = Classifier.classify(withOwnApp, ClassificationContext(DeviceProfiles.SONY, ownPackage = "com.spotify.tv.android"))
        assertEquals(EssentialReason.KEYBOARD, result.getValue("org.smarttube.stable").essentialReason)
        assertEquals(EssentialReason.THIS_APP, result.getValue("com.spotify.tv.android").essentialReason)
    }

    @Test
    fun aManualChoiceChangesTheCategoryButNeverEssential() {
        val result = classify(overrides = mapOf(
            "com.netflix.ninja" to Category.GOOGLE_ANDROID_SERVICE,
            "com.sony.dtv.timers" to Category.USER_INSTALLED, // PERSISTENT: must be ignored
        ))
        val netflix = result.getValue("com.netflix.ninja")
        assertEquals(Category.GOOGLE_ANDROID_SERVICE, netflix.category)
        assertEquals(Category.PREINSTALLED_USER, netflix.automatic)
        assertTrue(netflix.isOverridden)
        assertEquals(Category.ESSENTIAL, result.getValue("com.sony.dtv.timers").category)
        assertFalse(result.getValue("com.sony.dtv.timers").isOverridden)
    }

    @Test
    fun anOverrideCanNeverMakeAnythingEssential() {
        val result = classify(overrides = mapOf("com.netflix.ninja" to Category.ESSENTIAL))
        assertEquals(Category.PREINSTALLED_USER, result.getValue("com.netflix.ninja").category)
    }

    @Test
    fun aDisabledPackageKeepsItsIconFromTheRememberedSet() {
        val disabledWithoutIcon = snapshot.packages.first { it.id == "com.google.android.katniss" }
        assertFalse(disabledWithoutIcon.id in snapshot.launchable)
        // katniss is already PREINSTALLED_USER through UPDATED_SYSTEM_APP + Play Store, so use a vendor service.
        val vendor = idsOf(Category.VENDOR_SERVICE).first()
        assertFalse(classify().getValue(vendor).hasIcon)
        val remembered = classify(iconPackages = setOf(vendor))
        assertTrue(remembered.getValue(vendor).hasIcon)
        assertEquals(Category.PREINSTALLED_USER, remembered.getValue(vendor).category)
    }

    @Test
    fun onlyTheTestedSonyModelIsVerified() {
        val tested = DeviceProfiles.forDevice(DeviceIdentity("Sony", "BRAVIA BF1", "ABC123XYZ", 31))
        assertTrue(tested.verified)
        assertEquals(DeviceProfiles.SONY.protectedPackages, tested.protectedPackages)
        assertFalse(DeviceProfiles.forDevice(DeviceIdentity("Sony", "BRAVIA_BF1", "ABC123XYZ", 33)).verified)
        assertFalse(DeviceProfiles.forDevice(DeviceIdentity("Sony", "KD-65X80J", "ABC123XYZ", 31)).verified)
    }

    @Test
    fun otherBrandsGetTheGenericProfileWithoutVendorPackages() {
        val generic = DeviceProfiles.forDevice(DeviceIdentity("TCL", "55P735", "ABC123XYZ", 31))
        assertFalse(generic.verified)
        assertTrue(generic.vendorPrefixes.isEmpty())
        assertTrue("com.android.vending" in generic.protectedPackages)
        assertTrue(generic.protectedPackages.none { it.startsWith("com.sony.") || it.startsWith("com.mediatek.") })
    }

    @Test
    fun theSonyProtectionListHasThirtyEightPackagesAndKeepsTheSystemBasics() {
        val protectedPackages = DeviceProfiles.SONY.protectedPackages
        assertEquals(38, protectedPackages.size)
        listOf("android", "com.android.systemui", "com.google.android.gms", "com.android.vending", "com.sony.dtv.sonysystemservice")
            .forEach { assertTrue(it, it in protectedPackages) }
    }
}
