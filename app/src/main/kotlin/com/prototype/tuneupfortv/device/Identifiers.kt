package com.prototype.tuneupfortv.device

// Validated identifiers. Anything that ends up inside a shell command must pass through one of these
// types, so a free-form string can never be concatenated into a command.

/** An Android package id, for example `com.sony.dtv.timers`. */
@JvmInline
value class PackageName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid package name: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[a-zA-Z0-9_.]+$")

        /** The validated value, or null when [value] is not valid: for input that comes from the device and must not crash anything. */
        fun orNull(value: String): PackageName? = if (PATTERN.matches(value)) PackageName(value) else null
    }
}

/**
 * A process name as `pidof` needs it: a full package name with an optional `:suffix`
 * (`com.sony.dtv.airplayapp:airplayserver`), or a native daemon name (`airplayptp`).
 */
@JvmInline
value class ProcessName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid process name: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[a-zA-Z0-9_.@-]+(:[a-zA-Z0-9_.@-]+)?$")

        /** The validated value, or null when [value] is not valid: for input that comes from the device and must not crash anything. */
        fun orNull(value: String): ProcessName? = if (PATTERN.matches(value)) ProcessName(value) else null
    }
}

/** A device serial number, as reported by `ro.serialno`. */
@JvmInline
value class DeviceSerial(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid device serial: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[A-Za-z0-9._-]+$")

        /** The validated value, or null when [value] is not valid: for input that comes from the device and must not crash anything. */
        fun orNull(value: String): DeviceSerial? = if (PATTERN.matches(value)) DeviceSerial(value) else null
    }
}

/** A Unix timestamp in seconds, as printed by `date +%s` on the device clock. */
@JvmInline
value class Epoch(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid epoch: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[0-9]+$")

        /** The validated value, or null when [value] is not valid: for input that comes from the device and must not crash anything. */
        fun orNull(value: String): Epoch? = if (PATTERN.matches(value)) Epoch(value) else null
    }
}

/**
 * An activity or service as `package/.Class`, for the commands that name one. The `$` of nested classes is left out on
 * purpose: the shell would expand it, so such a component is simply not accepted.
 */
@JvmInline
value class ComponentName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid component name: '$value'" }
    }

    val packageName: String get() = value.substringBefore('/')

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$")

        /** The validated value, or null when [value] is not valid: for input that comes from the device and must not crash anything. */
        fun orNull(value: String): ComponentName? = if (PATTERN.matches(value)) ComponentName(value) else null
    }
}

/** An Android permission, for example `android.permission.WRITE_SECURE_SETTINGS`. */
@JvmInline
value class PermissionName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid permission name: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[A-Za-z0-9_.]+$")

        fun orNull(value: String): PermissionName? = if (PATTERN.matches(value)) PermissionName(value) else null
    }
}

/** The value of `screensaver_components`: components separated by commas. */
@JvmInline
value class ScreensaverValue(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid screensaver value: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+(,[A-Za-z0-9_.]+/[A-Za-z0-9_.]+)*$")

        fun orNull(value: String): ScreensaverValue? = if (PATTERN.matches(value)) ScreensaverValue(value) else null
    }
}

/** The value of `enabled_accessibility_services`: components separated by colons. */
@JvmInline
value class AccessibilityValue(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Invalid accessibility value: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN: Regex = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+(:[A-Za-z0-9_.]+/[A-Za-z0-9_.]+)*$")

        fun orNull(value: String): AccessibilityValue? = if (PATTERN.matches(value)) AccessibilityValue(value) else null
    }
}
