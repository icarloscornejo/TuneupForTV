package com.prototype.tuneupfortv.device

/**
 * The seven categories, in the order the classifier decides them. Each package gets exactly one.
 * Rules and tests use these names, never numbers.
 */
enum class Category {
    /** Protected: no action is allowed, ever. */
    ESSENTIAL,

    /** Known to the device but uninstalled for user 0. It can only be restored. */
    UNINSTALLED_USER0,

    /** Resource overlays: no process, hidden by default. */
    OVERLAY,

    /** An app the user installed. */
    USER_INSTALLED,

    /** Pre-installed, but really an app (it has an icon, or it is a Play-updated system app), like Netflix. */
    PREINSTALLED_USER,

    VENDOR_SERVICE,
    GOOGLE_ANDROID_SERVICE;

    companion object {
        /** What the list shows until "Show all" is on. */
        val DEFAULT_VISIBLE: Set<Category> = setOf(PREINSTALLED_USER, USER_INSTALLED)

        /** The order of the category chips: the interesting ones first. */
        val DISPLAY_ORDER: List<Category> =
            listOf(PREINSTALLED_USER, USER_INSTALLED, VENDOR_SERVICE, GOOGLE_ANDROID_SERVICE, OVERLAY, UNINSTALLED_USER0, ESSENTIAL)
    }
}

/** Why a package is protected, in plain words for the detail screen. */
enum class EssentialReason { PROFILE_PROTECTED, PERSISTENT, SYSTEM_UID, HOME_LAUNCHER, KEYBOARD, THIS_APP }

data class Classification(
    /** What the UI uses: the automatic category, unless the user overrode it. */
    val category: Category,
    val automatic: Category,
    val essentialReason: EssentialReason?,
    val hasIcon: Boolean,
) {
    val isOverridden: Boolean get() = category != automatic
}

/** Everything besides the snapshot that the rules depend on. */
data class ClassificationContext(
    val profile: DeviceProfile,
    /** This app's own package id: it can never be acted on. */
    val ownPackage: String,
    /** The user's manual choices. They can never touch an Essential package. */
    val overrides: Map<String, Category> = emptyMap(),
    /** Packages that have a launcher icon now or had one when last seen: a disabled package loses its icon in the query. */
    val iconPackages: Set<String> = emptySet(),
)

object Classifier {
    private val OVERLAY_MARKERS: List<String> = listOf("auto_generated_rro", ".overlay", "rro", "resoverlay")
    private const val PLAY_STORE: String = "com.android.vending"

    fun classify(snapshot: DeviceSnapshot, context: ClassificationContext): Map<String, Classification> =
        snapshot.packages.associate { record ->
            val hasIcon = record.id in snapshot.launchable || record.id in context.iconPackages
            val essentialReason = essentialReason(record, snapshot.homePackages, snapshot.inputMethodPackage, context)
            val automatic = if (essentialReason != null) Category.ESSENTIAL else automaticCategory(record, snapshot, context, hasIcon)
            // A manual choice never moves a package into or out of Essential: that is a rule, not a preference.
            val chosen = context.overrides[record.id]?.takeIf { essentialReason == null && it != Category.ESSENTIAL }
            record.id to Classification(chosen ?: automatic, automatic, essentialReason, hasIcon)
        }

    /** Why [record] is protected, or null. Public because the guard recomputes it from a live read before every action. */
    fun essentialReason(
        record: PackageRecord,
        homePackages: Set<String>,
        inputMethodPackage: String?,
        context: ClassificationContext,
    ): EssentialReason? =
        when {
            record.id == context.ownPackage -> EssentialReason.THIS_APP
            record.isPersistent -> EssentialReason.PERSISTENT
            record.usesSystemUid -> EssentialReason.SYSTEM_UID
            record.id in context.profile.protectedPackages -> EssentialReason.PROFILE_PROTECTED
            record.id in homePackages -> EssentialReason.HOME_LAUNCHER
            record.id == inputMethodPackage -> EssentialReason.KEYBOARD
            else -> null
        }

    private fun automaticCategory(record: PackageRecord, snapshot: DeviceSnapshot, context: ClassificationContext, hasIcon: Boolean): Category =
        when {
            record.id in snapshot.uninstalledForUser0 -> Category.UNINSTALLED_USER0
            OVERLAY_MARKERS.any { it in record.id } -> Category.OVERLAY
            !record.isSystem -> Category.USER_INSTALLED
            hasIcon || (record.isUpdatedSystemApp && record.installer == PLAY_STORE) -> Category.PREINSTALLED_USER
            context.profile.vendorPrefixes.any { record.id.startsWith(it) } -> Category.VENDOR_SERVICE
            else -> Category.GOOGLE_ANDROID_SERVICE
        }
}
