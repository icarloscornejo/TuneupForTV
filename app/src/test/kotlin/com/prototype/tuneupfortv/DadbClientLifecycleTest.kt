package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.adb.Commands
import com.prototype.tuneupfortv.adb.DadbAdbClient
import dadb.Dadb
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for a real crash: closing dadb while a read was blocked made okio throw
 * IllegalStateException("closed"), which escaped to the main thread and killed the app on the TV.
 */
class DadbClientLifecycleTest {
    /** A Dadb whose shell blocks until closed, then fails the way okio does. */
    private fun blockingDadb(onShellStarted: CountDownLatch = CountDownLatch(1)): Dadb {
        val closed = CountDownLatch(1)
        return Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, _ ->
            when (method.name) {
                "shell" -> {
                    onShellStarted.countDown()
                    closed.await()
                    throw IllegalStateException("closed")
                }
                "close" -> {
                    closed.countDown()
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Dadb
    }

    @Test
    fun theWatchdogClosingABlockedReadIsATimeoutNotACrash() {
        val client = DadbAdbClient { blockingDadb() }
        val error = assertThrows(DeviceException::class.java) {
            runBlocking { client.shell(Commands.readDevice(), 100.milliseconds) }
        }
        assertEquals(DeviceFailure.Timeout, error.failure)
    }

    @Test
    fun closingTheClientWhileAReadIsBlockedDoesNotCrashTheCaller() {
        val started = CountDownLatch(1)
        val client = DadbAdbClient { blockingDadb(started) }
        var failure: DeviceFailure? = null
        val reader = thread {
            failure = assertThrows(DeviceException::class.java) {
                runBlocking { client.shell(Commands.readDevice(), 30.seconds) }
            }.failure
        }
        started.await()
        client.close() // what leaving the screen does
        reader.join(5_000)
        assertTrue("the reader must have finished", !reader.isAlive)
        assertTrue("got $failure", failure is DeviceFailure.Other)
    }

    @Test
    fun theClientOpensANewConnectionAfterAFailure() {
        var opened = 0
        val client = DadbAdbClient { opened++; blockingDadb() }
        repeat(2) {
            assertThrows(DeviceException::class.java) {
                runBlocking { client.shell(Commands.readDevice(), 50.milliseconds) }
            }
        }
        assertEquals(2, opened)
    }
}

/** The file transfer of the launcher swap: checked on the same connection that receives it. */
class DadbPushTest {
    private val serial = com.prototype.tuneupfortv.device.DeviceSerial("FAKE0001")

    /** A Dadb that answers `getprop` with [observed], records the pushes and behaves as [onPush] says. */
    private fun dadb(observed: String, pushes: MutableList<String>, onPush: () -> Any? = { null }): Dadb =
        Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, args ->
            when (method.name) {
                "shell" -> dadb.AdbShellResponse("$observed\n", "", 0)
                "push" -> {
                    pushes += args[1] as String
                    onPush()
                }
                "close" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Dadb

    @Test
    fun theFileIsWrittenOnlyWhenTheDeviceOnThatConnectionHasTheExpectedSerial() {
        val pushes = mutableListOf<String>()
        val client = DadbAdbClient { dadb("FAKE0001", pushes) }
        val result = runBlocking { client.push(ByteArray(3), "/data/local/tmp/x.apk", serial, 5.seconds) }
        assertEquals(com.prototype.tuneupfortv.adb.PushResult.PUSHED, result)
        assertEquals(listOf("/data/local/tmp/x.apk"), pushes)
    }

    @Test
    fun anotherDeviceAnsweringGetsNothingWritten() {
        val pushes = mutableListOf<String>()
        val client = DadbAdbClient { dadb("OTHER", pushes) }
        val result = runBlocking { client.push(ByteArray(3), "/data/local/tmp/x.apk", serial, 5.seconds) }
        assertEquals(com.prototype.tuneupfortv.adb.PushResult.IDENTITY_MISMATCH, result)
        assertTrue(pushes.isEmpty())
    }

    @Test
    fun aWriteTheDeviceRefusesIsAnErrorNotASuccess() {
        val client = DadbAdbClient { dadb("FAKE0001", mutableListOf()) { dadb.SyncResult.Failure("no space left") } }
        val error = assertThrows(DeviceException::class.java) {
            runBlocking { client.push(ByteArray(3), "/data/local/tmp/x.apk", serial, 5.seconds) }
        }
        assertTrue(error.failure is DeviceFailure.Other)
    }

    @Test
    fun aPushThatHangsPastItsDeadlineIsATimeoutAndAClosedTransportIsNotACrash() {
        val closed = CountDownLatch(1)
        val hanging = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, _ ->
            when (method.name) {
                "shell" -> dadb.AdbShellResponse("FAKE0001\n", "", 0)
                "push" -> {
                    closed.await()
                    throw IllegalStateException("closed")
                }
                "close" -> {
                    closed.countDown()
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Dadb
        val client = DadbAdbClient { hanging }
        val error = assertThrows(DeviceException::class.java) {
            runBlocking { client.push(ByteArray(3), "/data/local/tmp/x.apk", serial, 100.milliseconds) }
        }
        assertEquals(DeviceFailure.Timeout, error.failure)
    }
}
