package com.prototype.tuneupfortv.store

import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AppSettings(
    /** `system`, `en` or `es`. */
    val language: String = "system",
    /** The first-run notice (use only on your own devices) was accepted. */
    val noticeAccepted: Boolean = false,
    /** `list` or `grid`, for the packages screen. */
    val packagesViewMode: String = "list",
    /** `list` or `grid`, for the processes screen: it is chosen apart from the packages one. */
    val processesViewMode: String = "list",
    /** The Debloat assistant was finished once: from then on the screen opens on its tabs, and the assistant is a button. */
    val debloatWizardDone: Boolean = false,
    /** The models whose "not tested" warning the user acknowledged: Home stops showing it for them. */
    val dismissedUntestedModels: Set<String> = emptySet(),
)

interface SettingsStorage {
    /** Never throws: settings that cannot be read fall back to the defaults. */
    fun load(): AppSettings

    /** Throws [java.io.IOException] when they cannot be stored. */
    fun save(settings: AppSettings)
}

/** Global (not per-device) settings in `settings.json`. */
class SettingsStore(directory: File) : SettingsStorage {
    private val store: JsonStore = JsonStore(directory)

    /** Settings that cannot be read fall back to the defaults, and the problem is logged. */
    override fun load(): AppSettings =
        try {
            store.read(FILE_NAME, AppSettings.serializer()) ?: AppSettings()
        } catch (error: JsonStoreException) {
            Log.w(TAG, "Settings unreadable, using defaults", error)
            AppSettings()
        }

    override fun save(settings: AppSettings) {
        store.write(FILE_NAME, AppSettings.serializer(), settings)
    }

    private companion object {
        const val FILE_NAME: String = "settings.json"
        const val TAG: String = "SettingsStore"
    }
}
