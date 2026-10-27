package com.prototype.tuneupfortv.session

import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.JournalStore
import kotlinx.coroutines.sync.Mutex

/**
 * What every session of the process shares: the one lock of changes and the one journal. A session that was replaced
 * while a swap was still running must not load its own copy of the journal: it would miss the entries the swap writes
 * and then save its old list over them.
 */
class SharedDeviceState(private val journalStore: JournalStore) {
    val mutex: Mutex = Mutex()

    private var cached: Journal? = null

    /**
     * The journal, read from disk the first time and cached only when that worked: an unreadable journal stays an error
     * the session reports, and a later try reads it again. Blocking, so call it on an IO dispatcher.
     */
    @Synchronized
    fun journal(): Journal = cached ?: Journal(journalStore).also { cached = it }
}
