package com.prototype.tuneupfortv.device

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The launcher APK, already checked against the checksum its release published: the only thing ever pushed to the TV. */
class LauncherApk(
    val bytes: ByteArray,
    val versionName: String,
    val versionCode: Long,
)

/**
 * What the latest release of the launcher offers. The APK is not downloaded until the user confirms. [sha256] is
 * what its `.sha256` file says, filled in once that has been read: empty, nothing can be verified against it.
 */
data class LauncherRelease(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256Url: String,
    val sha256: String = "",
)

object LauncherReleases {
    /** The launcher derives its version code from the tag as major*10000 + minor*100 + patch, so the parts are bounded. */
    private val TAG = Regex("""v(\d{1,4})\.(\d{1,2})\.(\d{1,2})""")
    private val SHA256 = Regex("""\b[0-9a-fA-F]{64}\b""")

    /**
     * Reads the answer of GitHub's "latest release" call. Null unless the tag is `vX.Y.Z`, there is exactly one `.apk`,
     * and it has its `.sha256` beside it, all on https: whatever is missing or odd means the release is not available.
     */
    fun parse(json: String): LauncherRelease? {
        val root: JsonObject = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (notJson: IllegalArgumentException) {
            return null
        } catch (notJson: kotlinx.serialization.SerializationException) {
            return null
        }
        return try {
            if (root["draft"]?.jsonPrimitive?.booleanOrNull == true || root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
            val match = TAG.matchEntire(root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null) ?: return null
            val (major, minor, patch) = match.destructured.toList().map { it.toLong() }
            val assets: Map<String, String> = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                name to url
            }.toMap()
            val apkName = assets.keys.filter { it.endsWith(".apk", ignoreCase = true) }.singleOrNull() ?: return null
            val apkUrl = assets[apkName] ?: return null
            val sha256Url = assets["$apkName.sha256"] ?: return null
            if (!apkUrl.startsWith("https://") || !sha256Url.startsWith("https://")) return null
            LauncherRelease("$major.$minor.$patch", major * 10000 + minor * 100 + patch, apkUrl, sha256Url)
        } catch (unexpectedShape: IllegalArgumentException) {
            null
        }
    }

    /** The first 64-hex word of a `.sha256` file, which is either the bare hash or `hash  file` as `shasum` writes it. */
    fun sha256Of(text: String): String? = SHA256.find(text)?.value?.lowercase()

    /** The APK is accepted only if its SHA-256 is the one its release published. */
    fun verified(release: LauncherRelease, sha256: String, bytes: ByteArray): LauncherApk? {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return if (actual == sha256.lowercase()) LauncherApk(bytes, release.versionName, release.versionCode) else null
    }
}
