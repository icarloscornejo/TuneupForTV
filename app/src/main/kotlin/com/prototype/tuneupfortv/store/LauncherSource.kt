package com.prototype.tuneupfortv.store

import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.device.LauncherReleases
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launcher is not bundled: it is the latest public release of [repository] on GitHub. Anything that goes wrong
 * (no internet, no repo, no release, a missing file, a checksum that does not match) is the same answer, null: the
 * launcher is not available, and nothing is installed.
 */
class LauncherSource(
    private val repository: String,
    /** Reads up to the given number of bytes from an https address; null when it cannot. Replaced in tests. */
    private val get: (url: String, maxBytes: Int) -> ByteArray? = ::httpsGet,
) {
    suspend fun latest(): LauncherRelease? =
        withContext(Dispatchers.IO) {
            val json = get("https://api.github.com/repos/$repository/releases/latest", MAX_TEXT_BYTES)?.decodeToString() ?: return@withContext null
            val release = LauncherReleases.parse(json) ?: return@withContext null
            // The hash was already read when the release was found, so confirming only has to download the APK.
            val sha256 = get(release.sha256Url, MAX_TEXT_BYTES)?.decodeToString()?.let(LauncherReleases::sha256Of) ?: return@withContext null
            release.copy(sha256 = sha256)
        }

    /** The APK of [release], or null when it cannot be downloaded or is not the file whose hash was published. */
    suspend fun download(release: LauncherRelease): LauncherApk? =
        withContext(Dispatchers.IO) {
            val bytes = get(release.apkUrl, MAX_APK_BYTES) ?: return@withContext null
            LauncherReleases.verified(release, release.sha256, bytes)
        }

    companion object {
        private const val MAX_TEXT_BYTES = 256 * 1024
        const val MAX_APK_BYTES = 40 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000

        /** One attempt, https only, and it stops as soon as the answer is bigger than [maxBytes]. */
        fun httpsGet(url: String, maxBytes: Int): ByteArray? {
            if (!url.startsWith("https://")) return null
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    if (url.startsWith("https://api.github.com/")) setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > maxBytes) return null
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            } catch (failure: IOException) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
