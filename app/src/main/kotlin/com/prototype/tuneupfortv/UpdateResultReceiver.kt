package com.prototype.tuneupfortv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.prototype.tuneupfortv.store.SettingsStore
import com.prototype.tuneupfortv.ui.AppLanguage

/**
 * Gets the answer of the system installer for an update of Tuneup started from Settings. Not exported: only the
 * session we created holds the address of this receiver. The system first asks for the owner's confirmation (it comes as
 * an intent to start), then reports how it ended.
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            // A different signing key: the system refuses to put this APK over the installed app.
            PackageInstaller.STATUS_FAILURE_CONFLICT -> toast(context, R.string.update_other_key)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> toast(context, R.string.update_blocked)
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> toast(context, R.string.update_install_failed)
        }
    }

    /** The message in the language chosen in Settings, which a receiver does not get from the activity. */
    private fun toast(context: Context, message: Int) {
        val localized: Context = AppLanguage.fromTag(SettingsStore(context.filesDir).load().language).wrap(context)
        Toast.makeText(localized, message, Toast.LENGTH_LONG).show()
    }
}
