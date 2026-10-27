package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.adb.DadbAdbClient
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DadbFailureTest {
    private fun classify(error: IOException, deadlineReached: Boolean = false): DeviceFailure = DadbAdbClient.classify(error, deadlineReached)

    @Test
    fun aRefusedConnectionMeansAdbOverNetworkIsOff() {
        val refused = AdbConnectException("connect failed", ConnectException("failed to connect: ECONNREFUSED (Connection refused)"))
        assertEquals(DeviceFailure.Refused, classify(refused))
    }

    @Test
    fun nobodyAnsweringMeansUnreachable() {
        assertEquals(DeviceFailure.Unreachable, classify(AdbConnectException("x", SocketTimeoutException("failed to connect after 5000ms"))))
        assertEquals(DeviceFailure.Unreachable, classify(AdbConnectException("x", NoRouteToHostException("No route to host"))))
        assertEquals(DeviceFailure.Unreachable, classify(AdbConnectException("x", ConnectException("Network is unreachable"))))
        assertEquals(DeviceFailure.Unreachable, classify(AdbConnectException("x", null)))
    }

    @Test
    fun aRejectedKeyIsUnauthorized() {
        assertEquals(DeviceFailure.Unauthorized, classify(AdbAuthException("rejected", null)))
    }

    @Test
    fun timeoutsAreTimeouts() {
        assertEquals(DeviceFailure.Timeout, classify(AdbTimeoutException("slow", null)))
        // When our own watchdog closed the socket, the resulting error is a timeout whatever it looks like.
        assertEquals(DeviceFailure.Timeout, classify(IOException("Socket closed"), deadlineReached = true))
    }

    @Test
    fun anythingElseKeepsItsMessage() {
        val failure = classify(IOException("broken pipe"))
        assertTrue(failure is DeviceFailure.Other)
        assertEquals("broken pipe", (failure as DeviceFailure.Other).message)
    }
}
