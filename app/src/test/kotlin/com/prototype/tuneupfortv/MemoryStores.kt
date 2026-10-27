package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.Category
import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.JournalStore
import com.prototype.tuneupfortv.safety.JournalStoreException
import com.prototype.tuneupfortv.session.ClassificationStorage
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.store.SettingsStorage
import java.io.IOException

/** A journal in memory whose disk can be made to fail, to test what happens when it does. */
class MemoryJournalStore(var stored: List<JournalEntry> = emptyList()) : JournalStore {
    var failOnLoad: Boolean = false

    /** The number of saves that still succeed. Null means all of them. */
    var savesLeft: Int? = null
    var saveCount: Int = 0

    override fun load(): List<JournalEntry> {
        if (failOnLoad) throw JournalStoreException("the journal is corrupt")
        return stored
    }

    override fun save(entries: List<JournalEntry>) {
        val left = savesLeft
        if (left != null && left <= 0) throw JournalStoreException("the disk is full")
        savesLeft = left?.minus(1)
        saveCount++
        stored = entries
    }
}

class MemoryClassificationStorage : ClassificationStorage {
    var overrides: Map<String, Category> = emptyMap()
    var icons: Set<String> = emptySet()
    var failOnSave: Boolean = false

    override fun loadOverrides(): Map<String, Category> = overrides

    override fun saveOverrides(overrides: Map<String, Category>) {
        if (failOnSave) throw IOException("cannot write")
        this.overrides = overrides
    }

    override fun rememberIcons(currentlyLaunchable: Set<String>): Set<String> {
        icons = icons + currentlyLaunchable
        return icons
    }
}

class MemorySettings(var settings: AppSettings = AppSettings(noticeAccepted = true)) : SettingsStorage {
    var failOnSave: Boolean = false

    override fun load(): AppSettings = settings

    override fun save(settings: AppSettings) {
        if (failOnSave) throw IOException("cannot write")
        this.settings = settings
    }
}
