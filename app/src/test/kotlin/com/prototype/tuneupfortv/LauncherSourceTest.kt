package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.store.LauncherSource
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The source finds the release and downloads it through a fake network: every failure is the same null. */
class LauncherSourceTest {
    private val apkBytes: ByteArray = ByteArray(2000) { (it * 7).toByte() }
    private val apkHash: String = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val apkUrl = "https://github.com/o/r/releases/download/v1.0.1/l.apk"
    private val hashUrl = "$apkUrl.sha256"
    private val releaseUrl = "https://api.github.com/repos/o/r/releases/latest"
    private val releaseJson =
        """{"tag_name":"v1.0.1","assets":[{"name":"l.apk","browser_download_url":"$apkUrl"},{"name":"l.apk.sha256","browser_download_url":"$hashUrl"}]}"""

    private val asked = mutableListOf<String>()
    private var answers: MutableMap<String, ByteArray?> = mutableMapOf(
        releaseUrl to releaseJson.toByteArray(),
        hashUrl to "$apkHash  l.apk\n".toByteArray(),
        apkUrl to apkBytes,
    )

    private fun source() = LauncherSource("o/r") { url, maxBytes ->
        asked += url
        answers[url]?.takeIf { it.size <= maxBytes }
    }

    @Test
    fun itFindsTheLatestReleaseWithItsPublishedHash() {
        val release = runBlocking { source().latest() }!!
        assertEquals("1.0.1", release.versionName)
        assertEquals(10001L, release.versionCode)
        assertEquals(apkHash, release.sha256)
        assertEquals("only the release and its hash, the APK waits for the confirmation", listOf(releaseUrl, hashUrl), asked)
    }

    @Test
    fun itDownloadsTheApkAndChecksIt() = runBlocking {
        val source = source()
        val apk = source.download(source.latest()!!)!!
        assertEquals(apkBytes.toList(), apk.bytes.toList())
        assertEquals(10001L, apk.versionCode)
    }

    @Test
    fun noRepoNoReleaseOrNoNetworkIsNotAvailable() = runBlocking {
        answers[releaseUrl] = null
        assertNull(source().latest())
        answers[releaseUrl] = """{"message":"Not Found"}""".toByteArray()
        assertNull(source().latest())
    }

    @Test
    fun aMissingHashFileIsNotAvailable() = runBlocking {
        answers[hashUrl] = null
        assertNull(source().latest())
        answers[hashUrl] = "no hash in here".toByteArray()
        assertNull(source().latest())
    }

    @Test
    fun aDownloadThatIsNotTheFilePublishedIsRefused() = runBlocking {
        val source = source()
        val release = source.latest()!!
        answers[apkUrl] = apkBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull("changed byte", source.download(release))
        answers[apkUrl] = apkBytes.copyOf(100)
        assertNull("truncated", source.download(release))
        answers[apkUrl] = null
        assertNull("network error", source.download(release))
    }

    @Test
    fun aReleaseWhoseHashWasNeverReadCannotBeInstalled() = runBlocking {
        val release = source().latest()!!.copy(sha256 = "")
        assertNull(source().download(release))
    }

    @Test
    fun theRealGetRefusesPlainHttpAndHugeAnswersWithoutTouchingTheNetwork() {
        assertNull(LauncherSource.httpsGet("http://example.test/l.apk", 100))
        assertNull(LauncherSource.httpsGet("ftp://example.test/l.apk", 100))
        assertTrue(LauncherSource.MAX_APK_BYTES in 1..(100 * 1024 * 1024))
        assertNotNull(LauncherSource("o/r"))
    }
}
