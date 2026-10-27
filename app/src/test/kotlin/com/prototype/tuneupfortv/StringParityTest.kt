package com.prototype.tuneupfortv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every UI string must exist in English and Spanish, with the same placeholders. */
class StringParityTest {
    private val resources = File("src/main/res")
    private val entry = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d+\$[sd]""")

    private data class Entry(val translatable: Boolean, val placeholders: List<String>)

    private fun load(directory: String): Map<String, Entry> =
        entry.findAll(File(resources, "$directory/strings.xml").readText()).associate { match ->
            match.groupValues[1] to Entry(
                translatable = "translatable=\"false\"" !in match.groupValues[2],
                placeholders = placeholder.findAll(match.groupValues[3]).map { it.value }.toList(),
            )
        }

    @Test
    fun spanishHasExactlyTheTranslatableEnglishKeys() {
        val english = load("values")
        val spanish = load("values-es")
        val englishKeys = english.filterValues { it.translatable }.keys
        assertTrue("English strings must not be empty", englishKeys.isNotEmpty())
        assertEquals("Missing in Spanish", emptySet<String>(), englishKeys - spanish.keys)
        assertEquals("Extra in Spanish", emptySet<String>(), spanish.keys - english.keys)
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        val english = load("values")
        val spanish = load("values-es")
        english.filterValues { it.translatable }.forEach { (key, value) ->
            assertEquals("Placeholders differ for '$key'", value.placeholders.sorted(), spanish.getValue(key).placeholders.sorted())
        }
    }
}
