package com.prototype.tuneupfortv.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class JsonStoreException(message: String, cause: Throwable) : IOException(message, cause)

/** Small JSON files in [directory], written atomically (tmp file, fsync, rename) so a crash never leaves half a file. */
class JsonStore(private val directory: File) {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** Returns the stored value, or null when the file does not exist. Throws [JsonStoreException] if it is unreadable. */
    fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = File(directory, name)
        if (!file.exists()) return null
        try {
            return json.decodeFromString(serializer, file.readText())
        } catch (error: SerializationException) {
            throw JsonStoreException("Corrupt JSON in ${file.name}", error)
        } catch (error: IOException) {
            throw JsonStoreException("Cannot read ${file.name}", error)
        }
    }

    /**
     * Makes the rename itself durable: without it, a power cut right after a write could bring back the old file.
     * Some file systems refuse to fsync a directory; the file's own data is already synced, so that is tolerated.
     */
    private fun syncDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (error: IOException) {
            // Best effort by design: see above.
        }
    }

    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        directory.mkdirs()
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.encodeToString(serializer, value).toByteArray())
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory()
        } catch (error: IOException) {
            throw JsonStoreException("Cannot write ${target.name}", error)
        }
    }
}
