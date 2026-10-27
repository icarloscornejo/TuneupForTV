package com.prototype.tuneupfortv.safety

import com.prototype.tuneupfortv.device.Classification
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.ListFilter
import com.prototype.tuneupfortv.device.PackageList

/**
 * Which running packages "force stop all" would stop: the ones the processes list shows with the user's search and
 * filters. Pure: it only looks at what the last read shows. The guard still checks each one again on a live read,
 * right before it is stopped, so protected packages never make it, however the list is filtered.
 */
object ForceCloseAll {
    /** The listed processes that belong to no app (native daemons): nothing to force stop there, and the confirmation says so. */
    fun notCloseable(snapshot: DeviceSnapshot, classifications: Map<String, Classification>, filter: ListFilter): List<String> =
        PackageList.filterProcesses(PackageList.processItems(snapshot, classifications), filter)
            .filter { it.packageId == null }
            .map { it.process.name }
            .distinct()
            .sorted()

    fun shown(snapshot: DeviceSnapshot, classifications: Map<String, Classification>, filter: ListFilter): List<String> {
        val records = snapshot.packages.associateBy { it.id }
        val shownProcesses = PackageList.filterProcesses(PackageList.processItems(snapshot, classifications), filter)
        // A native daemon belongs to no package, so there is nothing to force stop: it stays on the list, untouched.
        return shownProcesses.mapNotNull { it.packageId }.distinct().filter { packageId ->
            val classification = classifications[packageId]
            val record = records[packageId]
            classification != null && record != null &&
                Guard.check(Operation.FORCE_STOP, LivePackage(record, classification.essentialReason)) == GuardVerdict.Allowed
        }.sorted()
    }
}
