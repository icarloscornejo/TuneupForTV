package com.prototype.tuneupfortv.device

import java.security.MessageDigest

/** Who a device says it is. The confirmation the user gave is stored under [deviceKey]. */
data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val serial: String,
    val sdk: Int,
) {
    /**
     * A stable, path-safe id: SHA-256 of manufacturer, model and serial. The raw serial is never used
     * as a file name, because it comes from the device and could contain `../`.
     */
    val deviceKey: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("$manufacturer|$model|$serial".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * False for an empty or generic serial (`unknown`, `0123456789ABCDEF`, all the same character):
     * two different boxes could then share one confirmation and one journal, so such a device stays read-only.
     */
    val hasReliableSerial: Boolean
        get() = serial.length >= MIN_SERIAL_LENGTH &&
            SERIAL_CHARACTERS.matches(serial) &&
            serial.lowercase() !in GENERIC_SERIALS &&
            serial.toSet().size > 1

    private companion object {
        const val MIN_SERIAL_LENGTH: Int = 6
        val SERIAL_CHARACTERS: Regex = Regex("^[A-Za-z0-9._-]+$")
        val GENERIC_SERIALS: Set<String> = setOf(
            "unknown",
            "0123456789abcdef",
            "1234567890abcdef",
            "123456789abcdef",
            "1234567890",
            "null",
        )
    }
}
