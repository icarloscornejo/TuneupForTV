package com.prototype.tuneupfortv.adb

import com.prototype.tuneupfortv.device.AccessibilityValue
import com.prototype.tuneupfortv.device.LauncherApk
import com.prototype.tuneupfortv.device.ComponentName
import com.prototype.tuneupfortv.device.DeviceException
import com.prototype.tuneupfortv.device.DeviceFailure
import com.prototype.tuneupfortv.device.DeviceSerial
import com.prototype.tuneupfortv.device.PackageName
import com.prototype.tuneupfortv.device.PermissionName
import com.prototype.tuneupfortv.device.ScreensaverValue
import com.prototype.tuneupfortv.safety.LauncherPackages
import com.prototype.tuneupfortv.safety.LauncherPort
import com.prototype.tuneupfortv.safety.LauncherReading
import com.prototype.tuneupfortv.safety.SendResult
import com.prototype.tuneupfortv.safety.WifiOwner
import kotlin.time.Duration.Companion.seconds

/**
 * The launcher swap's door to the TV. Reads come back under the identity envelope; every write is behind the serial
 * fence. A push that fails never reached `pm install`, so it is reported as not sent: nothing changed.
 */
class AdbLauncherPort(
    private val client: AdbClient,
    private val serial: DeviceSerial,
) : LauncherPort {
    override suspend fun read(): LauncherReading? {
        val result = client.shell(Commands.readLauncherState(), READ_DEADLINE)
        if (result.observedSerial != serial.value) throw DeviceException(DeviceFailure.IdentityMismatch(serial.value, result.observedSerial))
        val sections = if (result.exitCode == 0) ScriptOutput.parse(result.stdout) else null
        fun output(name: String): String? = sections?.get(name)?.takeIf { it.exitCode == 0 }?.output
        val home = output(Section.HOME)
        val candidates = output(Section.HOME_CANDIDATES)
        val dump = output(Section.DUMP)
        val screensaver = output(Section.SCREENSAVER)
        val accessibility = output(Section.ACCESSIBILITY)
        if (home == null || candidates == null || dump == null || screensaver == null || accessibility == null) return null
        val records = Parsers.parseRawPackageDump(dump)
        val packages = LauncherPackages.RELEVANT.associateWith { id ->
            val record = records[id]
            // A record that cannot be trusted makes the whole read untrustworthy: the swap then refuses.
            if (record != null && (!record.isComplete || record.state == null)) return null
            record?.state
        }
        val wifi = sections?.get(Section.WIFI_OWNERS)
        return LauncherReading(
            homeComponent = Parsers.parseHomeComponent(home),
            homeCandidates = Parsers.parseLaunchables(candidates),
            packages = packages,
            lightLauncherVersion = output(Section.LIGHT_VERSION)?.let { Parsers.parseVersionCode(it) },
            screensaver = Parsers.parseSettingValue(screensaver),
            accessibility = Parsers.parseSettingValue(accessibility),
            wifi = when {
                wifi == null || wifi.exitCode != 0 -> WifiOwner.UNKNOWN
                "setupwraith" in wifi.output -> WifiOwner.SETUPWRAITH
                else -> WifiOwner.OTHER
            },
            // A record that cannot be read still counts as present: better to refuse than to lose the launcher at the next start.
            homeRemover = LauncherPackages.HOME_REMOVERS.firstOrNull { id -> records[id]?.let { it.state?.installed != false } == true },
            product = output(Section.PRODUCT_NAME)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun install(apk: LauncherApk): SendResult {
        val pushed = try {
            client.push(apk.bytes, Commands.APK_PATH, serial, AdbClient.PUSH_DEADLINE)
        } catch (error: DeviceException) {
            return SendResult.NotSent
        }
        if (pushed == PushResult.IDENTITY_MISMATCH) return SendResult.FenceRefused
        return client.sendMutation(Commands.installPushed(serial), LONG_DEADLINE)
    }

    override suspend fun grant(permission: PermissionName): SendResult =
        client.sendMutation(Commands.grant(PackageName(LauncherPackages.LIGHT), permission, serial), COMMAND_DEADLINE)

    override suspend fun compile(): SendResult =
        client.sendMutation(Commands.compileSpeed(PackageName(LauncherPackages.LIGHT), serial), LONG_DEADLINE)

    override suspend fun setHome(component: ComponentName): SendResult =
        client.sendMutation(Commands.setHome(component, serial), COMMAND_DEADLINE)

    override suspend fun putScreensaver(value: ScreensaverValue): SendResult =
        client.sendMutation(Commands.putScreensaver(value, serial), COMMAND_DEADLINE)

    override suspend fun deleteScreensaver(): SendResult =
        client.sendMutation(Commands.deleteScreensaver(serial), COMMAND_DEADLINE)

    override suspend fun putAccessibility(value: AccessibilityValue): SendResult =
        client.sendMutation(Commands.putAccessibility(value, serial), COMMAND_DEADLINE)

    private companion object {
        val READ_DEADLINE = 30.seconds
        val COMMAND_DEADLINE = 30.seconds

        /** An install or an ahead-of-time compile prints nothing until it is done, and slow TVs take a while. */
        val LONG_DEADLINE = 120.seconds
    }
}
