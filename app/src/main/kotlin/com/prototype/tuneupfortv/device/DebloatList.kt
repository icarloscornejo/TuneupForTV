package com.prototype.tuneupfortv.device

/** One package of a debloat list. [suggested] false means it starts unchecked. */
data class DebloatEntry(val packageId: String, val note: String?, val suggested: Boolean)

data class DebloatGroup(val name: String, val entries: List<DebloatEntry>)

/** A debloat list. [fileName] is the asset name without its extension, for example `sony-bravia-bf1`. */
data class DebloatList(val fileName: String, val title: String, val groups: List<DebloatGroup>) {
    val entries: List<DebloatEntry> get() = groups.flatMap { it.entries }
}

/** The lists for this device in the order the screen shows them. */
data class RankedDebloatLists(val recommended: DebloatList?, val sameBrand: List<DebloatList>, val others: List<DebloatList>)

object DebloatLists {
    /** The list written and tested by hand for the BRAVIA BF1. */
    const val OWN_BF1: String = "mi-bravia-bf1"

    /** Packages no list may suggest: switching them off can leave the user without a way to use the TV. */
    val NEVER_SUGGESTED: Set<String> = setOf("com.google.android.marvin.talkback")

    const val NEVER_SUGGESTED_NOTE: String = "Screen reader: if you use it, do not disable it"

    private const val OPTIONAL_MARKER: String = "#?"
    private val PACKAGE_NAME: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * Reads a list. Returns null when it must not be offered: an active line that is not a package name (same rule as
     * the original disable script, which refuses the whole file), or no packages at all.
     *
     * - Line 1, when it is a comment, is the title.
     * - A plain line is a suggested package; text after `#` is its note.
     * - `#? package` is an optional package that starts unchecked.
     * - Any other comment is only text, even when it names a package: sources use them for packages to keep.
     *   The comment right before a package, with no blank line between, names its group.
     */
    fun parse(fileName: String, text: String): DebloatList? {
        val lines: List<String> = text.removePrefix("\uFEFF").lines().map { it.trimEnd('\r').trim() }
        var title: String = fileName
        var startIndex = 0
        if (lines.firstOrNull()?.startsWith("#") == true && !lines.first().startsWith(OPTIONAL_MARKER)) {
            title = lines.first().trimStart('#').trim().ifEmpty { fileName }
            startIndex = 1
        }
        val groups = mutableListOf<DebloatGroup>()
        val seen = mutableSetOf<String>()
        var groupName: String? = null
        var groupEntries = mutableListOf<DebloatEntry>()
        var pendingComment: String? = null

        fun closeGroup() {
            if (groupEntries.isNotEmpty()) groups += DebloatGroup(groupName.orEmpty(), groupEntries)
            groupEntries = mutableListOf()
        }

        fun add(rawEntry: String, suggested: Boolean): Boolean {
            val packageId: String = rawEntry.substringBefore('#').trim().substringBefore(' ').substringBefore('\t')
            if (!PACKAGE_NAME.matches(packageId)) return false
            val note: String? = rawEntry.substringAfter('#', "").trim().ifEmpty { null }
            if (!seen.add(packageId)) return true
            if (pendingComment != null) {
                closeGroup()
                groupName = pendingComment
                pendingComment = null
            }
            groupEntries += if (packageId in NEVER_SUGGESTED) {
                DebloatEntry(packageId, NEVER_SUGGESTED_NOTE, suggested = false)
            } else {
                DebloatEntry(packageId, note, suggested)
            }
            return true
        }

        for (line in lines.drop(startIndex)) {
            when {
                line.isEmpty() -> pendingComment = null
                line.startsWith(OPTIONAL_MARKER) -> {
                    val body: String = line.removePrefix(OPTIONAL_MARKER).trim()
                    // An optional marker with something that is not a package is just text.
                    if (!add(body, suggested = false)) pendingComment = body.ifEmpty { null }
                }
                line.startsWith("#") -> pendingComment = line.trimStart('#').trim().ifEmpty { null }
                else -> if (!add(line, suggested = true)) return null
            }
        }
        closeGroup()
        return if (groups.isEmpty()) null else DebloatList(fileName, title, groups)
    }

    /** The one list that belongs to this TV: the hand-made one on a verified BF1, else the best fit of the same brand, else none. */
    fun forDevice(identity: DeviceIdentity, lists: List<DebloatList>): DebloatList? =
        rank(identity, lists).let { it.recommended ?: it.sameBrand.firstOrNull() }

    /**
     * Orders [lists] for [identity]: the hand-made BF1 list first on a verified BF1, then the lists of the same
     * brand (by how well the model fits the title), then the rest by name.
     */
    fun rank(identity: DeviceIdentity, lists: List<DebloatList>): RankedDebloatLists {
        val recommended: DebloatList? =
            if (DeviceProfiles.forDevice(identity).verified) lists.firstOrNull { it.fileName == OWN_BF1 } else null
        val remaining: List<DebloatList> = lists.filter { it != recommended }
        val brand: String = identity.manufacturer.lowercase()
        val modelWords: List<String> = identity.model.lowercase().split(' ', '_', '-').filter { it.length >= 3 }
        val (sameBrand, others) = remaining.partition { brand.isNotEmpty() && it.fileName.lowercase().startsWith(brand) }
        fun fit(list: DebloatList): Int {
            val haystack: String = (list.fileName + " " + list.title).lowercase()
            return modelWords.count { it in haystack }
        }
        return RankedDebloatLists(
            recommended = recommended,
            sameBrand = sameBrand.sortedWith(compareByDescending<DebloatList> { fit(it) }.thenBy { it.fileName }),
            others = others.sortedBy { it.fileName },
        )
    }
}
