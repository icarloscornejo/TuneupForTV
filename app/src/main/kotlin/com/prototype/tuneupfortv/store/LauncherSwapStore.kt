package com.prototype.tuneupfortv.store

import com.prototype.tuneupfortv.safety.SwapRecord
import com.prototype.tuneupfortv.safety.SwapRecordException
import com.prototype.tuneupfortv.safety.SwapRecordStore
import java.io.File
import java.io.IOException

/** The record of the launcher swap as `launcher-swap.json` in [directory], written atomically like the journal. */
class LauncherSwapStore(private val directory: File) : SwapRecordStore {
    private val store: JsonStore = JsonStore(directory)

    override fun load(): SwapRecord? =
        try {
            store.read(FILE_NAME, SwapRecord.serializer())
        } catch (error: JsonStoreException) {
            throw SwapRecordException("the launcher record cannot be read: ${error.message}", error)
        }

    override fun save(record: SwapRecord) {
        try {
            store.write(FILE_NAME, SwapRecord.serializer(), record)
        } catch (error: JsonStoreException) {
            throw SwapRecordException("the launcher record cannot be written: ${error.message}", error)
        }
    }

    override fun delete() {
        try {
            val file = File(directory, FILE_NAME)
            if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.name}")
        } catch (error: IOException) {
            throw SwapRecordException("the launcher record cannot be removed: ${error.message}", error)
        }
    }

    private companion object {
        const val FILE_NAME: String = "launcher-swap.json"
    }
}
