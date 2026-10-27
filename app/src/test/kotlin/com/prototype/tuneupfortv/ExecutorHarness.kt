package com.prototype.tuneupfortv

import com.prototype.tuneupfortv.adb.FakeAdbClient
import com.prototype.tuneupfortv.device.DeviceProfiles
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.PackageState
import com.prototype.tuneupfortv.adb.SnapshotLoader
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.adb.AdbHealthWatch
import com.prototype.tuneupfortv.adb.AdbLivePackageSource
import com.prototype.tuneupfortv.adb.AdbPackageCommandSender
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.safety.EntryStatus
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.JournalStore
import com.prototype.tuneupfortv.safety.WatchState
import com.prototype.tuneupfortv.store.JsonJournalStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** A fake TV, a journal on disk and an executor wired together, with a clock the test controls. */
class ExecutorHarness(
    private val directory: File,
    initialWatchSeconds: Int = 2,
    private val ownPackage: String = OWN_PACKAGE,
    private val journalStoreFactory: () -> JournalStore = { JsonJournalStore(directory) },
) {
    val fake: FakeAdbClient = FakeAdbClient(FixtureSnapshot::read)
    var nowMillis: Long = 1_000_000L
    var journal: Journal = Journal(journalStoreFactory())
    var watchSeconds: Int = initialWatchSeconds
        set(value) {
            field = value
            executor = newExecutor()
        }

    // The executor reads the watch length once, so changing it above builds a new executor over the same journal.
    var executor: ActionExecutor = newExecutor()

    private fun newExecutor(): ActionExecutor =
        ActionExecutor(
            liveSource = AdbLivePackageSource(fake, fake.identity.serial),
            sender = AdbPackageCommandSender(fake, DeviceSerial(fake.identity.serial)),
            watch = AdbHealthWatch(fake, fake.identity.serial, pause = { fake.advance(it) }, clock = { fake.clockSeconds * 1000 }),
            journal = journal,
            identity = fake.identity,
            profile = DeviceProfiles.SONY,
            ownPackage = ownPackage,
            watchSeconds = watchSeconds,
            dispatcher = Dispatchers.Unconfined,
            nowMillis = { nowMillis },
        )

    /** The app is killed and started again: a new journal object reads the file, a new executor is built. */
    fun restartApp() {
        journal = Journal(journalStoreFactory())
        executor = newExecutor()
    }

    fun snapshot(): DeviceSnapshot = runBlocking { SnapshotLoader(fake).connect().snapshot }

    fun reconcile() = runBlocking { executor.reconcile(snapshot()) }

    fun expectState(packageId: String, installed: Boolean, enabled: Int) {
        val state = fake.state(packageId)
        check(state.installed == installed && state.enabled == enabled) { "$packageId is $state, the test needs installed=$installed enabled=$enabled" }
    }

    fun setState(packageId: String, installed: Boolean, enabled: Int) = fake.setState(packageId, PackageState(installed, enabled, stopped = false))

    companion object {
        const val OWN_PACKAGE: String = "com.prototype.tuneupfortv"
        const val USER_APP: String = "org.localsend.localsend_app"
        const val SECOND_USER_APP: String = "org.smarttube.stable"
        const val PERSISTENT: String = "com.sony.dtv.timers"
        const val DISABLED_USER_APP: String = "com.crunchyroll.crunchyroid"
    }
}
