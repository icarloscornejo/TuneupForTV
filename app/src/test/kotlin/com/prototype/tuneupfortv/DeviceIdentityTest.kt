package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    private fun device(serial: String, model: String = "BRAVIA_BF1"): DeviceIdentity = DeviceIdentity("Sony", model, serial, 31)

    @Test
    fun deviceKeyIsAStableHexHash() {
        val key = device("ABC123XYZ").deviceKey
        assertEquals(key, device("ABC123XYZ").deviceKey)
        assertTrue(key, Regex("^[0-9a-f]{64}$").matches(key))
        assertNotEquals(key, device("ABC123XYZ2").deviceKey)
        assertNotEquals(key, device("ABC123XYZ", model = "OTHER").deviceKey)
    }

    @Test
    fun aHostileSerialStillProducesASafeFolderName() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(device("../../etc/passwd").deviceKey))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(device("").deviceKey))
    }

    @Test
    fun emptyGenericOrRepeatedSerialsAreNotReliable() {
        listOf("", "unknown", "UNKNOWN", "0123456789ABCDEF", "AAAAAAAAAA", "abc", "null", "has space").forEach {
            assertFalse("'$it' must not be reliable", device(it).hasReliableSerial)
        }
        assertTrue(device("ABC123XYZ").hasReliableSerial)
    }
}
