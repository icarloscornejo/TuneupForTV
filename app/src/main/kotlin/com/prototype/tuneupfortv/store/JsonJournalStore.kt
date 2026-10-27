package com.prototype.tuneupfortv.store

import com.prototype.tuneupfortv.safety.JournalEntry
import com.prototype.tuneupfortv.safety.JournalStore
import com.prototype.tuneupfortv.safety.JournalStoreException
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
private data class JournalFile(val entries: List<JournalEntry> = emptyList())

/** The journal as `journal.json` in [directory], written atomically. */
class JsonJournalStore(directory: File) : JournalStore {
    private val store: JsonStore = JsonStore(directory)

    override fun load(): List<JournalEntry> =
        try {
            store.read(FILE_NAME, JournalFile.serializer())?.entries ?: emptyList()
        } catch (error: JsonStoreException) {
            throw JournalStoreException("the journal cannot be read: ${error.message}", error)
        }

    override fun save(entries: List<JournalEntry>) {
        try {
            store.write(FILE_NAME, JournalFile.serializer(), JournalFile(entries))
        } catch (error: JsonStoreException) {
            throw JournalStoreException("the journal cannot be written: ${error.message}", error)
        }
    }

    private companion object {
        const val FILE_NAME: String = "journal.json"
    }
}
