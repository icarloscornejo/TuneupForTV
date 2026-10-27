package com.prototype.tuneupfortv

import android.app.Application
import com.prototype.tuneupfortv.adb.AdbDeviceGateway
import com.prototype.tuneupfortv.adb.AdbKeys
import com.prototype.tuneupfortv.adb.DadbAdbClient
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.LauncherRelease
import com.prototype.tuneupfortv.session.DeviceSession
import com.prototype.tuneupfortv.session.SharedDeviceState
import com.prototype.tuneupfortv.store.LauncherSource
import com.prototype.tuneupfortv.store.LauncherSwapStore
import com.prototype.tuneupfortv.device.DebloatList
import com.prototype.tuneupfortv.store.DebloatAssets
import com.prototype.tuneupfortv.store.ClassificationStore
import com.prototype.tuneupfortv.store.JsonJournalStore
import com.prototype.tuneupfortv.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one place that knows which concrete classes the app is made of: manual constructor injection, no framework.
 * One per process.
 */
class AppContainer private constructor(private val application: Application) {
    val settingsStorage: SettingsStore = SettingsStore(application.filesDir)

    private val debloatAssets: DebloatAssets = DebloatAssets(application.assets)

    /** The bundled debloat lists. Reading is on the IO dispatcher inside [DebloatAssets]. */
    suspend fun loadDebloatLists(): List<DebloatList> = debloatAssets.load()

    /** The launcher is not bundled: it is the latest public release of its repository on GitHub. */
    private val launcherSource: LauncherSource = LauncherSource(LAUNCHER_REPOSITORY)

    suspend fun fetchLauncherRelease(): LauncherRelease? = launcherSource.latest()

    suspend fun downloadLauncher(release: LauncherRelease): LauncherApk? = launcherSource.download(release)

    /** The lock of changes and the journal, one of each for the whole process: a new session never works from an old copy. */
    private val shared: SharedDeviceState = SharedDeviceState(JsonJournalStore(application.filesDir))

    fun newSession(): DeviceSession =
        DeviceSession(
            openGateway = {
                // The key is read from disk (and created on the very first run): never on the main thread.
                val keyPair = withContext(Dispatchers.IO) { AdbKeys.loadOrCreate(application.noBackupFilesDir) }
                AdbDeviceGateway(DadbAdbClient(THIS_DEVICE_HOST, THIS_DEVICE_PORT, keyPair))
            },
            journalStore = JsonJournalStore(application.filesDir),
            shared = shared,
            swapStore = LauncherSwapStore(application.filesDir),
            classificationStorage = ClassificationStore(application.filesDir),
            ownPackage = application.packageName,
        )

    companion object {
        /** The app only ever manages the TV it runs on, through its own ADB over network. */
        private const val THIS_DEVICE_HOST: String = "127.0.0.1"
        private const val THIS_DEVICE_PORT: Int = 5555

        /** Where the LightLauncher releases are published. */
        private const val LAUNCHER_REPOSITORY: String = "icarloscornejo/LightLauncher"

        @Volatile
        private var instance: AppContainer? = null

        fun of(application: Application): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(application).also { instance = it } }
    }
}
