package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.device.LauncherReleases
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules for accepting a launcher release and its APK: anything odd means "not available", never a guess. */
class LauncherReleaseTest {
    private fun json(
        tag: String = "v1.0.1",
        assets: List<Pair<String, String>> = listOf(
            "lightlauncher.apk" to "https://github.com/o/r/releases/download/v1.0.1/lightlauncher.apk",
            "lightlauncher.apk.sha256" to "https://github.com/o/r/releases/download/v1.0.1/lightlauncher.apk.sha256",
        ),
        extra: String = "",
    ): String =
        """{"tag_name":"$tag"$extra,"assets":[${assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"$url"}""" }}]}"""

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theTagGivesTheVersionAndItsCodeTheWayTheLauncherCountsIt() {
        val release = LauncherReleases.parse(json("v1.0.1"))!!
        assertEquals("1.0.1", release.versionName)
        assertEquals(10001L, release.versionCode)
        assertEquals(20305L, LauncherReleases.parse(json("v2.3.5"))!!.versionCode)
        assertEquals("https://github.com/o/r/releases/download/v1.0.1/lightlauncher.apk", release.apkUrl)
        assertEquals("https://github.com/o/r/releases/download/v1.0.1/lightlauncher.apk.sha256", release.sha256Url)
    }

    @Test
    fun anUnexpectedTagIsNotAvailable() {
        listOf("1.0.1", "v1.0", "v1.0.1-beta", "v1.100.0", "latest", "").forEach { tag ->
            assertNull(tag, LauncherReleases.parse(json(tag)))
        }
    }

    @Test
    fun theApkAndItsHashMustBothBeThereAndOnlyOneApk() {
        val apk = "lightlauncher.apk" to "https://x.test/lightlauncher.apk"
        val hash = "lightlauncher.apk.sha256" to "https://x.test/lightlauncher.apk.sha256"
        assertNotNull(LauncherReleases.parse(json(assets = listOf(apk, hash))))
        assertNull("no hash", LauncherReleases.parse(json(assets = listOf(apk))))
        assertNull("no apk", LauncherReleases.parse(json(assets = listOf(hash))))
        assertNull("the hash of another file", LauncherReleases.parse(json(assets = listOf(apk, "other.apk.sha256" to "https://x.test/other.apk.sha256"))))
        assertNull("two apks", LauncherReleases.parse(json(assets = listOf(apk, "second.apk" to "https://x.test/second.apk", hash))))
        assertNull("no assets", LauncherReleases.parse(json(assets = emptyList())))
    }

    @Test
    fun onlyHttpsAddressesAreAccepted() {
        assertNull(LauncherReleases.parse(json(assets = listOf("l.apk" to "http://x.test/l.apk", "l.apk.sha256" to "https://x.test/l.apk.sha256"))))
        assertNull(LauncherReleases.parse(json(assets = listOf("l.apk" to "https://x.test/l.apk", "l.apk.sha256" to "http://x.test/l.apk.sha256"))))
    }

    @Test
    fun draftsPrereleasesAndNonsenseAreNotAvailable() {
        assertNull(LauncherReleases.parse(json(extra = ""","draft":true""")))
        assertNull(LauncherReleases.parse(json(extra = ""","prerelease":true""")))
        listOf("", "not json", "[]", "null", """{"message":"Not Found","status":"404"}""", """{"tag_name":{"a":1},"assets":3}""").forEach { body ->
            assertNull(body, LauncherReleases.parse(body))
        }
    }

    @Test
    fun theHashFileMayBeJustTheHashOrWhatShasumWrites() {
        val hash = "ab".repeat(32)
        assertEquals(hash, LauncherReleases.sha256Of(hash))
        assertEquals(hash, LauncherReleases.sha256Of("$hash  app-release.apk\n"))
        assertEquals(hash, LauncherReleases.sha256Of(hash.uppercase() + "\n"))
        assertNull(LauncherReleases.sha256Of(""))
        assertNull(LauncherReleases.sha256Of("ab".repeat(31)))
        assertNull(LauncherReleases.sha256Of("zz".repeat(32)))
    }

    @Test
    fun anApkIsAcceptedOnlyIfItsHashIsTheOnePublished() {
        val release = LauncherRelease("1.0.1", 10001L, "https://x.test/l.apk", "https://x.test/l.apk.sha256")
        val bytes = ByteArray(1000) { it.toByte() }
        val good = LauncherReleases.verified(release, sha256(bytes), bytes)!!
        assertEquals("1.0.1", good.versionName)
        assertEquals(10001L, good.versionCode)
        assertNull("a truncated download", LauncherReleases.verified(release, sha256(bytes), bytes.copyOf(500)))
        assertNull("a changed byte", LauncherReleases.verified(release, sha256(bytes), bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertNull("no hash read", LauncherReleases.verified(release, "", bytes))
    }
}
