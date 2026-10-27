package com.prototype.tuneupfortv.store

import android.content.res.AssetManager
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.device.DebloatLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the debloat lists bundled under `assets/debloat`. A file that does not parse is left out. */
class DebloatAssets(private val assets: AssetManager) {
    suspend fun load(): List<DebloatList> =
        withContext(Dispatchers.IO) {
            assets.list(DIRECTORY).orEmpty()
                .filter { it.endsWith(EXTENSION) }
                .sorted()
                .mapNotNull { fileName ->
                    val text: String = assets.open("$DIRECTORY/$fileName").bufferedReader().use { it.readText() }
                    DebloatLists.parse(fileName.removeSuffix(EXTENSION), text)
                }
        }

    private companion object {
        const val DIRECTORY: String = "debloat"
        const val EXTENSION: String = ".txt"
    }
}
