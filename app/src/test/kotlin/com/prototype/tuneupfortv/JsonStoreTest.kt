package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.store.JsonStore
import com.prototype.tuneupfortv.store.JsonStoreException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonStoreTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun missingFileReadsAsNull() {
        assertNull(JsonStore(folder.root).read("settings.json", AppSettings.serializer()))
    }

    @Test
    fun writeThenReadRoundTripsAndLeavesNoTemporaryFile() {
        val store = JsonStore(File(folder.root, "nested"))
        store.write("settings.json", AppSettings.serializer(), AppSettings(language = "es"))
        assertEquals(AppSettings(language = "es"), store.read("settings.json", AppSettings.serializer()))
        assertFalse(File(folder.root, "nested/settings.json.tmp").exists())
    }

    @Test
    fun overwriteReplacesTheOldValue() {
        val store = JsonStore(folder.root)
        store.write("settings.json", AppSettings.serializer(), AppSettings(language = "en"))
        store.write("settings.json", AppSettings.serializer(), AppSettings(language = "es"))
        assertEquals("es", store.read("settings.json", AppSettings.serializer())?.language)
    }

    @Test
    fun corruptFileThrowsInsteadOfSilentlyReturningDefaults() {
        File(folder.root, "settings.json").writeText("{ not json")
        assertThrows(JsonStoreException::class.java) { JsonStore(folder.root).read("settings.json", AppSettings.serializer()) }
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() {
        File(folder.root, "settings.json").writeText("""{"language":"en","somethingNew":42}""")
        assertEquals("en", JsonStore(folder.root).read("settings.json", AppSettings.serializer())?.language)
    }
}
