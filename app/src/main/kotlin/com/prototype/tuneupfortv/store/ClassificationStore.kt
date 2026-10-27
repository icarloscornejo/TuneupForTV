package com.prototype.tuneupfortv.store

import android.util.Log
import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.session.ClassificationStorage
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
private data class OverridesFile(val overrides: Map<String, String> = emptyMap())

@Serializable
private data class IconsFile(val packages: Set<String> = emptySet())

/**
 * What the user and the device taught the classifier: manual category choices, and which packages had
 * a launcher icon when last seen (a disabled package disappears from the launcher query, so without
 * this memory it would lose its icon and jump category).
 */
class ClassificationStore(
    directory: File,
    /** Called when a stored file cannot be read; the store then starts empty. Injected so JVM tests need no Android. */
    private val reportProblem: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) },
) : ClassificationStorage {
    private val store: JsonStore = JsonStore(directory)

    override fun loadOverrides(): Map<String, Category> =
        readOrDefault(OVERRIDES_FILE, OverridesFile.serializer(), OverridesFile()).overrides
            // A category renamed or removed in a later version is dropped instead of breaking the app.
            .mapNotNull { (id, name) -> Category.entries.firstOrNull { it.name == name }?.let { id to it } }
            .toMap()

    /** Saves the whole map. Choosing "automatic" for a package means removing it from [overrides] first. */
    override fun saveOverrides(overrides: Map<String, Category>) {
        store.write(OVERRIDES_FILE, OverridesFile.serializer(), OverridesFile(overrides.mapValues { it.value.name }))
    }

    /** Adds the packages that have an icon now to the remembered ones, saves, and returns everything remembered. */
    override fun rememberIcons(currentlyLaunchable: Set<String>): Set<String> {
        val remembered = readOrDefault(ICONS_FILE, IconsFile.serializer(), IconsFile()).packages
        val merged = remembered + currentlyLaunchable
        if (merged != remembered) store.write(ICONS_FILE, IconsFile.serializer(), IconsFile(merged))
        return merged
    }

    private fun <T> readOrDefault(name: String, serializer: kotlinx.serialization.KSerializer<T>, default: T): T =
        try {
            store.read(name, serializer) ?: default
        } catch (error: JsonStoreException) {
            reportProblem("$name unreadable, starting empty", error)
            default
        }

    private companion object {
        const val OVERRIDES_FILE: String = "overrides.json"
        const val ICONS_FILE: String = "icons.json"
        const val TAG: String = "ClassificationStore"
    }
}
