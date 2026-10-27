package com.prototype.tuneupfortv.device

/** Everything the app knows about a device at one moment, from a live read. */
data class DeviceSnapshot(
    val identity: DeviceIdentity,
    /** Every package of `pm list packages -u`, sorted by id. */
    val packages: List<PackageRecord>,
    /** Present in `-u` but not in the plain list: uninstalled for user 0. */
    val uninstalledForUser0: Set<String>,
    val launchable: Set<String>,
    /** The packages that act as the Home launcher: the resolved activity and the HOME role holders. */
    val homePackages: Set<String>,
    /** The package of the default keyboard, or null. */
    val inputMethodPackage: String?,
    /** Userspace processes sorted by memory, biggest first. */
    val processes: List<ProcessRecord>,
    val memoryKb: Map<String, Long>,
    /** The full product name (`ro.product.name`, for example BRAVIA_BF1_4K_M_A), or empty when the TV did not say. */
    val productName: String = "",
    /** The firmware build (`ro.build.id`), or empty when the TV did not say. */
    val buildId: String = "",
) {
    /** The package each process belongs to, or null for native daemons. */
    val processPackages: Map<ProcessRecord, String?> by lazy {
        val ids = packages.mapTo(HashSet()) { it.id }
        processes.associateWith { ProcessMapping.packageOf(it.name, ids) }
    }
}
