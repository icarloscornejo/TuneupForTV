package com.prototype.tuneupfortv.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The record of every change the app made. One owner in memory: everything that changes an entry goes
 * through here, and a failed write is never silent: it is published on [problem] for the screens to show.
 *
 * Loading a journal that cannot be read is an error, never an empty journal: starting over would lose the
 * only record of what was done.
 */
class Journal(private val store: JournalStore) {
    private val entriesState: MutableStateFlow<List<JournalEntry>> = MutableStateFlow(store.load())
    private val problemState: MutableStateFlow<String?> = MutableStateFlow(null)

    val entries: List<JournalEntry> get() = entriesState.value

    /** The journal as it changes, for the screens. */
    val entriesFlow: StateFlow<List<JournalEntry>> = entriesState.asStateFlow()

    /** Why the last write failed, or null when the disk and the memory agree. */
    val problem: StateFlow<String?> = problemState.asStateFlow()

    /** Entries that stop new disables and force-stops until the user resolves them. */
    fun blocking(): List<JournalEntry> = entries.filter { it.isBlocking }

    fun entry(id: String): JournalEntry? = entries.firstOrNull { it.id == id }

    /** All or nothing: the entry is on disk before it exists in memory, or nothing changes and this throws. */
    fun add(entry: JournalEntry) {
        replaceEntries(entries + entry)
    }

    /** All or nothing, like [add]. Used before a command is sent, when an unrecorded change would be dangerous. */
    fun update(id: String, transform: (JournalEntry) -> JournalEntry): JournalEntry {
        val changed = transform(requireNotNull(entry(id)) { "No journal entry $id" })
        replaceEntries(entries.map { if (it.id == id) changed else it })
        return changed
    }

    /**
     * Memory first, then the disk, and a failed write is only reported. Used once a command has been sent: the
     * session must keep seeing what really happened to the device, even if the disk refuses to record it.
     */
    fun updateAfterSend(id: String, transform: (JournalEntry) -> JournalEntry): JournalEntry {
        val changed = transform(requireNotNull(entry(id)) { "No journal entry $id" })
        entriesState.value = entries.map { if (it.id == id) changed else it }
        try {
            persist(entries)
        } catch (error: JournalStoreException) {
            // Already published on [problem]: the caller goes on with the correct in-memory state.
        }
        return changed
    }

    /** Replaces many entries at once (reconciliation), with a single write. All or nothing. */
    fun replaceAll(changed: Map<String, JournalEntry>) {
        if (changed.isEmpty()) return
        replaceEntries(entries.map { changed[it.id] ?: it })
    }

    private fun replaceEntries(newEntries: List<JournalEntry>) {
        persist(newEntries)
        entriesState.value = newEntries
    }

    private fun persist(toSave: List<JournalEntry>) {
        try {
            store.save(toSave)
            problemState.value = null
        } catch (error: JournalStoreException) {
            problemState.value = error.message ?: "the journal could not be written"
            throw error
        }
    }
}
