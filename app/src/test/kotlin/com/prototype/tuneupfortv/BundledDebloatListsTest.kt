package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DebloatLists
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists shipped inside the APK are exactly the ones in MANIFEST, byte for byte, and all of them parse. */
class BundledDebloatListsTest {
    private val assetsDirectory: File = File("src/main/assets")
    private val listsDirectory: File = File(assetsDirectory, "debloat")

    /** The id git gives a file: sha1 of "blob <size>", a zero byte, then the bytes. */
    private fun gitBlobSha1(file: File): String {
        val bytes: ByteArray = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val manifest: Map<String, String> =
        File(listsDirectory, "MANIFEST").readLines().filter { it.isNotBlank() }.associate { line ->
            val (blobSha1, fileName) = line.split(Regex("\\s+"), limit = 2)
            fileName.trim() to blobSha1
        }

    @Test
    fun theThirdPartyListsAreExactlyTheOnesInTheManifest() {
        val onDisk: Set<String> = listsDirectory.listFiles { file -> file.name.endsWith(".txt") }!!.map { it.name }.toSet() - "${DebloatLists.OWN_BF1}.txt"
        assertTrue("the manifest is empty", manifest.isNotEmpty())
        assertEquals(manifest.keys, onDisk)
    }

    @Test
    fun everyListMatchesItsGitHash() {
        for ((fileName, expected) in manifest) {
            assertEquals("$fileName was changed or cut short", expected, gitBlobSha1(File(listsDirectory, fileName)))
        }
    }

    @Test
    fun everyListParses() {
        for (file in listsDirectory.listFiles { file -> file.name.endsWith(".txt") }!!) {
            assertNotNull("${file.name} does not parse", DebloatLists.parse(file.name.removeSuffix(".txt"), file.readText()))
        }
    }

    @Test
    fun theOwnListIsThereAndIsNotInTheManifest() {
        assertTrue(File(listsDirectory, "${DebloatLists.OWN_BF1}.txt").isFile)
        assertTrue("${DebloatLists.OWN_BF1}.txt" !in manifest)
    }

    @Test
    fun theLicenseIsKeptOutsideTheListsFolder() {
        val license = File(assetsDirectory, "licenses/cleanyourtv.txt")
        assertTrue(license.readText().startsWith("MIT License"))
    }
}
