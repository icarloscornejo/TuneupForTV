package com.prototype.tuneupfortv.session

import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.DeviceProfile
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.HealthReading
import com.prototype.tuneupfortv.device.LoadedDevice
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.SwapRecordStore
import kotlinx.coroutines.sync.Mutex

/** The one open door to the TV, as the session sees it. The ADB adapter implements it; tests plug in a fake TV. */
interface DeviceGateway : AutoCloseable {
    /** Opens the connection, learns who the device is and reads everything. Throws [com.prototype.tuneupfortv.device.DeviceException] or [com.prototype.tuneupfortv.device.SnapshotException]. */
    suspend fun connect(): LoadedDevice

    /** Reads the same device again; another device answering is refused. */
    suspend fun reload(expected: DeviceIdentity): DeviceSnapshot

    /** How the TV is doing right now. Takes about a second (the CPU is sampled over one). Throws [com.prototype.tuneupfortv.device.DeviceException]. */
    suspend fun readHealth(): HealthReading

    /** [mutex] is the lock every executor of the process shares, so two of them never work on the TV at once. */
    fun newExecutor(
        journal: Journal,
        identity: DeviceIdentity,
        profile: DeviceProfile,
        ownPackage: String,
        mutex: Mutex = Mutex(),
        swapStore: SwapRecordStore? = null,
    ): ActionExecutor
}
