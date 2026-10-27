package com.prototype.tuneupfortv.device

/** Finds which package a running process belongs to. */
object ProcessMapping {
    /**
     * Drops the `:suffix`, then takes an exact id or the longest id that is a dotted prefix
     * (`com.google.android.gms.ui` belongs to `com.google.android.gms`). Native daemons and unrelated names return null.
     * One set lookup per dotted prefix, so the cost depends on the length of the name, not on the number of packages.
     */
    fun packageOf(processName: String, packageIds: Set<String>): String? {
        var candidate = processName.substringBefore(':')
        if (candidate in packageIds) return candidate
        while (true) {
            val lastDot = candidate.lastIndexOf('.')
            if (lastDot < 0) return null
            candidate = candidate.substring(0, lastDot)
            if (candidate in packageIds) return candidate
        }
    }
}
