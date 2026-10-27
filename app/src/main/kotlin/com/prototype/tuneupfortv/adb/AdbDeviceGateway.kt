package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceIdentity
import com.prototype.tuneupfortv.device.HealthReading
import com.prototype.tuneupfortv.device.DeviceProfile
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.DeviceSnapshot
import com.prototype.tuneupfortv.device.LoadedDevice
import com.prototype.tuneupfortv.safety.ActionExecutor
import com.prototype.tuneupfortv.safety.Journal
import com.prototype.tuneupfortv.safety.SwapRecordStore
import kotlinx.coroutines.sync.Mutex
import com.prototype.tuneupfortv.session.DeviceGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** The session's door to the TV, over one ADB client. */
class AdbDeviceGateway(
    private val client: AdbClient,
    /** Where the executor does its work. Injected so tests run it inline. */
    private val executorDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The pause between watch polls and the clock the watch window is measured with: injected so tests do not wait in real time. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = ::monotonicMillis,
) : DeviceGateway {
    private val loader: SnapshotLoader = SnapshotLoader(client)

    /** The serial of the device read at connect time: every later read must come from the same one. */
    private var knownSerial: String? = null

    override suspend fun connect(): LoadedDevice = loader.connect().also { knownSerial = it.identity.serial }

    override suspend fun reload(expected: DeviceIdentity): DeviceSnapshot = loader.reload(expected)

    override suspend fun readHealth(): HealthReading {
        val result = client.shell(Commands.readHealth(), HEALTH_DEADLINE)
        val expected = knownSerial ?: throw DeviceException(DeviceFailure.Other("the device was not read yet"))
        if (result.observedSerial != expected) throw DeviceException(DeviceFailure.IdentityMismatch(expected, result.observedSerial))
        val sections = if (result.exitCode == 0) ScriptOutput.parse(result.stdout) else null
        fun output(name: String): String? = sections?.get(name)?.takeIf { it.exitCode == 0 }?.output
        val memory = output(Section.MEMINFO)?.let { Parsers.parseMeminfo(it) }
        val crashLog = output(Section.CRASHES)
        // The crash log complains on stderr (and may still exit 0): then "no crashes" would be a guess, so it is unknown.
        val crashEvents = if (crashLog != null && result.stderr.isBlank()) Parsers.parseCrashEvents(crashLog) else null
        val swapTotal = memory?.get("SwapTotal")
        val swapFree = memory?.get("SwapFree")
        return HealthReading(
            takenAtMillis = System.currentTimeMillis(),
            memoryTotalKb = memory?.get("MemTotal"),
            memoryAvailableKb = memory?.get("MemAvailable"),
            swapUsedKb = if (swapTotal != null && swapFree != null) swapTotal - swapFree else null,
            cpuBusyPercent = output(Section.CPU)?.let { Parsers.parseCpuBusyPercent(it) },
            crashes = crashEvents?.size,
            crashSample = crashEvents.orEmpty().take(CRASH_SAMPLE_SIZE).map { it.take(CRASH_LINE_CHARS) },
        )
    }

    override fun newExecutor(
        journal: Journal,
        identity: DeviceIdentity,
        profile: DeviceProfile,
        ownPackage: String,
        mutex: Mutex,
        swapStore: SwapRecordStore?,
    ): ActionExecutor {
        // An unreliable serial has no usable identity fence: the executor then refuses every change, so any placeholder is safe.
        val serial = DeviceSerial.orNull(identity.serial) ?: DeviceSerial(UNUSABLE_SERIAL)
        return ActionExecutor(
            liveSource = AdbLivePackageSource(client, identity.serial),
            sender = AdbPackageCommandSender(client, serial),
            watch = AdbHealthWatch(client, identity.serial, pause, clock),
            dispatcher = executorDispatcher,
            journal = journal,
            identity = identity,
            profile = profile,
            ownPackage = ownPackage,
            mutex = mutex,
            swapStore = swapStore,
            // Built here, on the gateway's own client: the swap shares its lock, its identity fence and its connection.
            launcher = AdbLauncherPort(client, serial),
        )
    }

    override fun close() = client.close()

    private companion object {
        const val UNUSABLE_SERIAL: String = "unusable"
        val HEALTH_DEADLINE = 15.seconds
        const val CRASH_SAMPLE_SIZE: Int = 3
        const val CRASH_LINE_CHARS: Int = 160
    }
}
