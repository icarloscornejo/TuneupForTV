package com.prototype.tuneupfortv.store

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.prototype.tuneupfortv.ui.UpdateInstallStart
import java.io.File
import java.io.IOException

/**
 * Hands a downloaded APK of this very app to the system installer (a PackageInstaller session). The system asks the
 * owner to confirm, and only installs it over this app if it is signed with the same key. [resultReceiver] is the
 * broadcast receiver that gets the answer of the session.
 */
class ApkInstaller(
    private val context: Context,
    private val resultReceiver: Class<*>,
) {
    /**
     * Checks that [apk] is this app at [expectedVersion], then commits a session for it. [UpdateInstallStart.STARTED]
     * means the session was committed (the answer comes to the receiver); anything else means nothing was installed and
     * no session is left behind.
     */
    fun install(apk: File, expectedVersion: String): UpdateInstallStart {
        if (!isExpected(apk, expectedVersion)) return UpdateInstallStart.FAILED
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownAppsSettings()
            return UpdateInstallStart.NEEDS_PERMISSION
        }
        val installer: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SESSION_FILE, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Explicit and mutable: the system adds the status and the confirmation screen to it.
                val intent = Intent(context, resultReceiver)
                val pending: PendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pending.intentSender)
            }
            return UpdateInstallStart.STARTED
        } catch (error: IOException) {
            // The session could not be written.
        } catch (error: SecurityException) {
            // Not allowed to install.
        } catch (error: IllegalStateException) {
            // The session is not usable.
        } catch (error: IllegalArgumentException) {
            // The session was refused.
        }
        if (sessionId != -1) {
            try {
                installer.abandonSession(sessionId)
            } catch (error: SecurityException) {
                // Nothing more to do: the system cleans up abandoned sessions on its own.
            }
        }
        return UpdateInstallStart.FAILED
    }

    private fun isExpected(apk: File, expectedVersion: String): Boolean {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        return info.packageName == context.packageName && info.versionName == expectedVersion
    }

    /** Where the owner can allow installs from this app by hand, when the switch over ADB did not take. */
    private fun openUnknownAppsSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: android.content.ActivityNotFoundException) {
            // A TV without that screen: the message on Tuneup's own Settings says what to switch on.
        }
    }

    private companion object {
        const val SESSION_FILE: String = "TuneupForTV.apk"
    }
}
