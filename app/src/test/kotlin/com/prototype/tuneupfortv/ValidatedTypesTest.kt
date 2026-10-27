package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.Epoch
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.ProcessName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidatedTypesTest {
    @Test
    fun packageNameAcceptsRealIds() {
        listOf("com.sony.dtv.timers", "android", "tv.samba.ssm", "com.mediatek.tv.keyevent", "a_b.c1").forEach {
            assertEquals(it, PackageName(it).value)
        }
    }

    @Test
    fun packageNameRejectsAnythingThatCouldInjectACommand() {
        listOf("", "a b", "a;b", "a&&b", "a|b", "\$(id)", "a`b`", "a'b", "a\"b", "a\nb", "a/b", "a:b", "-rf").forEach {
            assertThrows("'$it' must be rejected", IllegalArgumentException::class.java) { PackageName(it) }
        }
    }

    @Test
    fun processNameAcceptsSuffixesAndNativeDaemons() {
        listOf(
            "com.sony.dtv.airplayapp:airplayserver",
            "com.sony.dtv.sonysystemservice",
            "airplayptp",
            "android.hardware.drm@1.4-service.widevine",
        ).forEach { assertEquals(it, ProcessName(it).value) }
    }

    @Test
    fun processNameRejectsInjectionAndExtraColons() {
        listOf("", "a;b", "a b", "a:b:c", "a:", ":a", "a'b", "\$(id)", "a\nb").forEach {
            assertThrows("'$it' must be rejected", IllegalArgumentException::class.java) { ProcessName(it) }
        }
    }

    @Test
    fun epochOnlyAcceptsDigits() {
        assertEquals("1792316037", Epoch("1792316037").value)
        listOf("", "12a", "-1", "1.5", "1 2", "1;2").forEach {
            assertThrows("'$it' must be rejected", IllegalArgumentException::class.java) { Epoch(it) }
        }
    }

    @Test
    fun deviceSerialRejectsPathTricksAndEmpty() {
        assertEquals("ABC123.x_y-z", DeviceSerial("ABC123.x_y-z").value)
        listOf("", "../x", "a/b", "a b", "a;b", "a'b").forEach {
            assertThrows("'$it' must be rejected", IllegalArgumentException::class.java) { DeviceSerial(it) }
        }
    }
}
