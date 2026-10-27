package com.prototype.tuneupfortv.device

enum class SortOrder { MEMORY, NAME, CATEGORY }

/** What the user chose on the packages and processes screens. */
data class ListFilter(
    val query: String = "",
    /** Off: only the apps a person can open (a launcher icon) from [Category.DEFAULT_VISIBLE]. On: everything, plus overlays and native processes. */
    val showAll: Boolean = false,
    /** With "show all" on: only these categories. Empty means all of them. */
    val categories: Set<Category> = emptySet(),
    val sort: SortOrder = SortOrder.MEMORY,
)

data class PackageItem(
    val record: PackageRecord,
    val classification: Classification,
    /** Memory of the package's running processes, in kB. Zero when none is running. */
    val memoryKb: Long,
    val commonlyDisabled: Boolean,
)

data class ProcessItem(
    val process: ProcessRecord,
    /** Null for native daemons that belong to no package. */
    val packageId: String?,
    val category: Category?,
    /** The package has a launcher icon: it is an app a person can open, not a background service. */
    val hasIcon: Boolean,
)

object PackageList {
    fun items(snapshot: DeviceSnapshot, classifications: Map<String, Classification>, profile: DeviceProfile): List<PackageItem> {
        val memory = memoryByPackage(snapshot)
        return snapshot.packages.map { record ->
            PackageItem(record, classifications.getValue(record.id), memory[record.id] ?: 0L, profile.isCommonlyDisabled(record.id))
        }
    }

    fun processItems(snapshot: DeviceSnapshot, classifications: Map<String, Classification>): List<ProcessItem> =
        snapshot.processes.map { process ->
            val packageId = snapshot.processPackages[process]
            ProcessItem(process, packageId, packageId?.let { classifications[it]?.category }, packageId?.let { classifications[it]?.hasIcon } ?: false)
        }

    fun filterPackages(items: List<PackageItem>, filter: ListFilter): List<PackageItem> {
        val query = filter.query.trim()
        val visible = items.filter { item ->
            categoryAllowed(item.classification.category, filter) &&
                // The same rule as the processes list: a service that happens to be Play-updated is not an app to open.
                (filter.showAll || item.classification.hasIcon) &&
                (query.isEmpty() || item.record.id.contains(query, ignoreCase = true))
        }
        return when (filter.sort) {
            SortOrder.NAME -> visible.sortedBy { it.record.id }
            SortOrder.MEMORY -> visible.sortedWith(compareByDescending<PackageItem> { it.memoryKb }.thenBy { it.record.id })
            SortOrder.CATEGORY -> visible.sortedWith(compareBy<PackageItem> { rank(it.classification.category) }.thenBy { it.record.id })
        }
    }

    fun filterProcesses(items: List<ProcessItem>, filter: ListFilter): List<ProcessItem> {
        val query = filter.query.trim()
        val visible = items.filter { item ->
            // A protected package cannot be stopped, so its processes are not a thing to manage here, whatever the filter says.
            if (item.category == Category.ESSENTIAL) return@filter false
            // A native daemon has no category: it only shows with "show all" and no category chip selected.
            // Without "show all" only apps a person can open: a Play-updated system service with no icon is still a service.
            val allowed = if (item.category == null) filter.showAll && filter.categories.isEmpty() else categoryAllowed(item.category, filter) && (filter.showAll || item.hasIcon)
            allowed &&
                (query.isEmpty() || item.process.name.contains(query, ignoreCase = true) || item.packageId?.contains(query, ignoreCase = true) == true)
        }
        return when (filter.sort) {
            SortOrder.NAME -> visible.sortedBy { it.process.name }
            SortOrder.MEMORY -> visible.sortedWith(compareByDescending<ProcessItem> { it.process.rssKb }.thenBy { it.process.name })
            SortOrder.CATEGORY -> visible.sortedWith(compareBy<ProcessItem> { it.category?.let(::rank) ?: Int.MAX_VALUE }.thenBy { it.process.name })
        }
    }

    private fun categoryAllowed(category: Category, filter: ListFilter): Boolean =
        if (!filter.showAll) category in Category.DEFAULT_VISIBLE else filter.categories.isEmpty() || category in filter.categories

    private fun rank(category: Category): Int = Category.DISPLAY_ORDER.indexOf(category)

    private fun memoryByPackage(snapshot: DeviceSnapshot): Map<String, Long> =
        snapshot.processPackages.entries
            .mapNotNull { (process, packageId) -> packageId?.let { it to process.rssKb } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
}
