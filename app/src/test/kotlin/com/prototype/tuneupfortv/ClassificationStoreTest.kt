package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.store.ClassificationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClassificationStoreTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun overridesSurviveARestart() {
        ClassificationStore(folder.root).saveOverrides(mapOf("com.netflix.ninja" to Category.VENDOR_SERVICE))
        assertEquals(mapOf("com.netflix.ninja" to Category.VENDOR_SERVICE), ClassificationStore(folder.root).loadOverrides())
    }

    @Test
    fun aCategoryThatNoLongerExistsIsDroppedInsteadOfBreakingTheApp() {
        File(folder.root, "overrides.json").writeText("""{"overrides":{"a.b":"VENDOR_SERVICE","c.d":"REMOVED_CATEGORY"}}""")
        assertEquals(mapOf("a.b" to Category.VENDOR_SERVICE), ClassificationStore(folder.root).loadOverrides())
    }

    @Test
    fun aCorruptFileStartsEmptyInsteadOfCrashing() {
        File(folder.root, "overrides.json").writeText("{ not json")
        var reported = false
        val store = ClassificationStore(folder.root) { _, _ -> reported = true }
        assertEquals(emptyMap<String, Category>(), store.loadOverrides())
        assertEquals("the problem must be reported, not swallowed", true, reported)
    }

    @Test
    fun rememberedIconsAccumulateAcrossReads() {
        val store = ClassificationStore(folder.root)
        assertEquals(setOf("a", "b"), store.rememberIcons(setOf("a", "b")))
        // "b" is disabled now and no longer in the launcher query, but it is still remembered.
        assertEquals(setOf("a", "b", "c"), ClassificationStore(folder.root).rememberIcons(setOf("a", "c")))
    }
}
