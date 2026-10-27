package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.safety.Operation
import com.prototype.tuneupfortv.safety.PackageCommandSender
import com.prototype.tuneupfortv.safety.SendResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Sends one mutation behind the identity fence. The only place an [Operation] becomes a shell command. */
class AdbPackageCommandSender(
    private val client: AdbClient,
    private val serial: DeviceSerial,
) : PackageCommandSender {
    override suspend fun send(operation: Operation, packageName: PackageName): SendResult {
        val command = when (operation) {
            Operation.FORCE_STOP -> Commands.forceStop(packageName, serial)
            Operation.DISABLE_USER -> Commands.disableUser(packageName, serial)
            Operation.DEFAULT_STATE -> Commands.defaultState(packageName, serial)
            Operation.ENABLE -> Commands.enable(packageName, serial)
            Operation.INSTALL_EXISTING -> Commands.installExisting(packageName, serial)
            Operation.UNINSTALL_USER -> Commands.uninstallUser(packageName, serial)
        }
        return client.sendMutation(command, COMMAND_DEADLINE)
    }

    private companion object {
        val COMMAND_DEADLINE = 30.seconds
    }
}

/** Sends one mutation and says what became of it: the only place an answer, or the lack of one, becomes a [SendResult]. */
internal suspend fun AdbClient.sendMutation(command: ShellCommand, deadline: Duration): SendResult {
    val result = try {
        shell(command, deadline)
    } catch (error: DeviceException) {
        return sendResultOf(error.failure)
    }
    return when (result.exitCode) {
        0 -> SendResult.Ran
        Commands.IDENTITY_MISMATCH_EXIT -> SendResult.FenceRefused
        else -> SendResult.Failed(result.exitCode)
    }
}

internal fun sendResultOf(failure: DeviceFailure): SendResult =
    when (failure) {
        // The connection never came up, so the command was never sent.
        DeviceFailure.Unreachable, DeviceFailure.Refused, DeviceFailure.Unauthorized, DeviceFailure.AwaitingAuthorization -> SendResult.NotSent
        // A timeout or a cut connection: it may have run.
        else -> SendResult.Uncertain
    }
