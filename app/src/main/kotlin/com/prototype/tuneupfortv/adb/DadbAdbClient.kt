package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceSerial
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.AdbTimeoutException
import dadb.Dadb
import dadb.SyncResult
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * The real client, on top of dadb. This file and [AdbKeys] are the only ones that may import dadb.
 *
 * One call at a time (a mutex). Every call has a deadline enforced by a watchdog that closes the
 * transport, because a blocking socket read cannot be interrupted any other way. After any failure the
 * connection is dropped and the next call opens a new one.
 *
 * ponytail: dadb does not expose its socket, so a handshake that keeps making slow progress is only
 * bounded by the socket timeouts. A strict global deadline would need our own socket.
 */
class DadbAdbClient internal constructor(private val openConnection: () -> Dadb) : AdbClient {
    constructor(host: String, port: Int, keyPair: AdbKeyPair) :
        this({ Dadb.create(host, port, keyPair, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS) })

    private val mutex: Mutex = Mutex()

    @Volatile
    private var connection: Dadb? = null

    override suspend fun shell(command: ShellCommand, deadline: Duration): ShellResult =
        mutex.withLock {
            val dadb = connection ?: openConnection().also { connection = it }
            val deadlineReached = AtomicBoolean(false)
            try {
                coroutineScope {
                    val watchdog = launch {
                        delay(deadline)
                        deadlineReached.set(true)
                        dadb.close()
                    }
                    try {
                        val response = withContext(Dispatchers.IO) { blockingShell(dadb, command) }
                        toResult(command, response.exitCode, response.output, response.errorOutput)
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (error: IOException) {
                dropConnection()
                throw DeviceException(classify(error, deadlineReached.get()), error)
            }
        }

    override suspend fun push(bytes: ByteArray, remotePath: String, serial: DeviceSerial, deadline: Duration): PushResult =
        mutex.withLock {
            val dadb = connection ?: openConnection().also { connection = it }
            val deadlineReached = AtomicBoolean(false)
            try {
                coroutineScope {
                    val watchdog = launch {
                        delay(deadline)
                        deadlineReached.set(true)
                        dadb.close()
                    }
                    try {
                        withContext(Dispatchers.IO) { blockingPush(dadb, bytes, remotePath, serial) }
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (error: IOException) {
                dropConnection()
                throw DeviceException(classify(error, deadlineReached.get()), error)
            }
        }

    override fun close() {
        dropConnection()
    }

    /** The serial is read on the same connection that then receives the file: another box answering gets nothing written. */
    private fun blockingPush(dadb: Dadb, bytes: ByteArray, remotePath: String, serial: DeviceSerial): PushResult =
        try {
            val observed = dadb.shell(Commands.probeSerial().line).output.replace("\r", "").trim()
            if (observed != serial.value) {
                PushResult.IDENTITY_MISMATCH
            } else {
                // dadb reports a refused write as a value, not as an exception: a push that failed must not look like one that worked.
                val written = dadb.push(Buffer().write(bytes), remotePath, FILE_MODE, System.currentTimeMillis())
                if (written is SyncResult.Failure) throw IOException("The device refused the file: ${written.reason}")
                PushResult.PUSHED
            }
        } catch (error: IllegalStateException) {
            throw IOException("Connection closed while writing", error)
        }

    /**
     * Closing the transport (the watchdog, or the user leaving the screen) while this read is blocked
     * makes okio throw `IllegalStateException("closed")`, which is not an IOException. Left alone it
     * escapes to the main thread and kills the app, so it is turned into the IOException it really is.
     */
    private fun blockingShell(dadb: Dadb, command: ShellCommand): AdbShellResponse =
        try {
            dadb.shell(command.line)
        } catch (error: IllegalStateException) {
            throw IOException("Connection closed while reading", error)
        }

    private fun dropConnection() {
        val current = connection
        connection = null
        // Closing a connection that already failed can throw again: there is nothing left to release.
        runCatching { current?.close() }
    }

    private fun toResult(command: ShellCommand, exitCode: Int, output: String, errorOutput: String): ShellResult {
        val stdout = output.replace("\r", "")
        val stderr = errorOutput.replace("\r", "")
        if (command.kind != ShellCommand.Kind.READ) return ShellResult(exitCode, stdout, stderr, null)
        val (serial, rest) = ReadEnvelope.unwrap(stdout)
        return ShellResult(exitCode, rest, stderr, serial)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS: Int = 5_000

        // Longer than the longest command (an install can print nothing for a minute or two): the watchdog is what bounds the others.
        private const val SOCKET_TIMEOUT_MS: Int = 150_000

        /** rw-r--r--, as `pm install` needs to read it. */
        private const val FILE_MODE: Int = 420

        /** Maps what dadb and the socket throw to a failure with its own message. Pure, so it is unit tested. */
        fun classify(error: IOException, deadlineReached: Boolean): DeviceFailure =
            when {
                deadlineReached -> DeviceFailure.Timeout
                error is AdbAuthException -> DeviceFailure.Unauthorized
                error is AdbTimeoutException -> DeviceFailure.Timeout
                // dadb wraps the socket error: "connection refused" means the device answered but ADB is
                // off; anything else (timeout, no route, unknown host, network unreachable) means nobody answered.
                error is AdbConnectException ->
                    if (error.cause is ConnectException && error.cause?.message?.contains("refused", ignoreCase = true) == true) {
                        DeviceFailure.Refused
                    } else {
                        DeviceFailure.Unreachable
                    }
                else -> DeviceFailure.Other(error.message ?: error.javaClass.simpleName)
            }
    }
}
