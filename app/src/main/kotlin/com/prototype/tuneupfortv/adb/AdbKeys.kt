package com.prototype.tuneupfortv.adb

import dadb.AdbKeyPair
import java.io.File

/**
 * The RSA key pair this app uses to authenticate to devices. It lives in app-private storage that is
 * excluded from every backup, and it is never logged or exported.
 */
object AdbKeys {
    private const val PRIVATE_KEY_NAME: String = "adbkey"
    private const val PUBLIC_KEY_NAME: String = "adbkey.pub"

    /** Loads the key pair from [directory], generating it on the first run. */
    fun loadOrCreate(directory: File): AdbKeyPair {
        val privateKey = File(directory, PRIVATE_KEY_NAME)
        val publicKey = File(directory, PUBLIC_KEY_NAME)
        if (!privateKey.exists() || !publicKey.exists()) {
            // A half-written pair from an interrupted first run is useless: start clean.
            privateKey.delete()
            publicKey.delete()
            directory.mkdirs()
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
