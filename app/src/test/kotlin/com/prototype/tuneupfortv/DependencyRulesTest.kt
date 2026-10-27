package com.prototype.tuneupfortv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The architecture, enforced: the inner layers never import the outer ones. */
class DependencyRulesTest {
    private val sourceRoot: File = File("src/main/kotlin/com/prototype/tuneupfortv")

    private fun importsOf(layer: String): List<Pair<File, String>> {
        val files = File(sourceRoot, layer).walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no sources found in $layer: the test would pass for the wrong reason", files.isNotEmpty())
        return files.flatMap { file -> file.readLines().filter { it.startsWith("import ") }.map { file to it.removePrefix("import ") } }
    }

    private fun assertNoImports(layer: String, forbiddenPrefixes: List<String>) {
        val offenders = importsOf(layer).filter { (_, import) -> forbiddenPrefixes.any { import.startsWith(it) } }
        assertTrue("$layer must not import: ${offenders.map { "${it.first.name} -> ${it.second}" }}", offenders.isEmpty())
    }

    private val outerLayers: List<String> = listOf(
        "com.prototype.tuneupfortv.adb.",
        "com.prototype.tuneupfortv.store.",
        "com.prototype.tuneupfortv.session.",
        "com.prototype.tuneupfortv.ui.",
        "android.",
        "androidx.",
        "dadb.",
        "java.io.File",
    )

    @Test
    fun theDeviceModelKnowsNothingOfAdbStorageOrAndroid() = assertNoImports("device", outerLayers)

    @Test
    fun theSafetyLogicKnowsNothingOfAdbStorageOrAndroid() = assertNoImports("safety", outerLayers)

    @Test
    fun theSessionKnowsNothingOfAdbOrAndroid() =
        assertNoImports("session", listOf("com.prototype.tuneupfortv.adb.", "com.prototype.tuneupfortv.store.", "com.prototype.tuneupfortv.ui.", "android.", "androidx.", "dadb."))

    @Test
    fun onlyTheAdbAdaptersTouchDadb() {
        val offenders = sourceRoot.walkTopDown().filter { it.extension == "kt" }
            .filter { file -> file.readLines().any { it.startsWith("import dadb.") } }
            .map { it.name }.toSet()
        assertTrue("$offenders", offenders.all { it in setOf("DadbAdbClient.kt", "AdbKeys.kt") })
    }

    @Test
    fun theUiDoesNotReachIntoAdbOrStorage() =
        assertNoImports("ui", listOf("com.prototype.tuneupfortv.adb.", "com.prototype.tuneupfortv.store.JsonStore", "dadb."))
}
