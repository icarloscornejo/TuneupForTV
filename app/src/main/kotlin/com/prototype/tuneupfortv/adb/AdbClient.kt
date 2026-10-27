package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceSerial
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What came back from one command. CRLF is already stripped. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** The serial the device reported with a READ command; null for PROBE commands or a missing marker. */
    val observedSerial: String?,
)

enum class PushResult { PUSHED, IDENTITY_MISMATCH }

interface AdbClient : AutoCloseable {
    /**
     * Runs [command] and returns its result, or throws [DeviceException] if the device cannot be
     * reached or does not answer within [deadline]. A command that runs and exits non-zero is a
     * normal [ShellResult], not an exception.
     */
    suspend fun shell(command: ShellCommand, deadline: Duration = DEFAULT_DEADLINE): ShellResult

    /**
     * Writes [bytes] to [remotePath] on the device, but only if the device on this very connection has [serial]: the
     * check and the write share one connection, with no reconnecting in between. Throws [DeviceException].
     */
    suspend fun push(bytes: ByteArray, remotePath: String, serial: DeviceSerial, deadline: Duration = PUSH_DEADLINE): PushResult

    companion object {
        val DEFAULT_DEADLINE: Duration = 30.seconds
        val PUSH_DEADLINE: Duration = 120.seconds
    }
}
