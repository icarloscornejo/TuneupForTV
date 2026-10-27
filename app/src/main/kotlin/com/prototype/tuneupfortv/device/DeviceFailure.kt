package com.prototype.tuneupfortv.device

/** Why the app could not talk to the device. Each one has its own message in the UI. */
sealed interface DeviceFailure {
    /** Nothing answers at the address. For a TV this usually means standby: the network is off. */
    data object Unreachable : DeviceFailure

    /** The device answers but nothing listens on the ADB port: ADB over network is off. */
    data object Refused : DeviceFailure

    /** Connected, and the device is waiting for the user to answer "Allow USB debugging?". */
    data object AwaitingAuthorization : DeviceFailure

    /** The device refused this app's key. */
    data object Unauthorized : DeviceFailure

    data object Timeout : DeviceFailure

    /** A different device (or none) answered than the one that was confirmed. */
    data class IdentityMismatch(val expected: String, val observed: String?) : DeviceFailure

    data class Other(val message: String) : DeviceFailure
}

class DeviceException(val failure: DeviceFailure, cause: Throwable? = null) :
    Exception("ADB failure: $failure", cause)

/** The device answered, but what it sent cannot be trusted. Nothing partial is ever shown as if it were complete. */
class SnapshotException(val problem: String) : Exception("Unreliable device data: $problem")

class LoadedDevice(val identity: DeviceIdentity, val snapshot: DeviceSnapshot)
