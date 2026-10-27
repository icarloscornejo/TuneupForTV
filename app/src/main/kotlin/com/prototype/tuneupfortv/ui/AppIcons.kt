package com.prototype.tuneupfortv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a package looks like to a person: its real name and its icon. The icon is null for packages that have none.
 * [loaded] is false only while the real ones are being read: the screens draw a skeleton, not the id, until then.
 */
data class AppVisual(val label: String, val icon: ImageBitmap?, val loaded: Boolean = true)

/** Where names and icons come from. Not ADB: the app asks its own TV, like any launcher does. */
interface AppIconSource {
    /** Never throws: a package that cannot be found is shown by its id, without an icon. */
    suspend fun load(packageId: String): AppVisual

    /** What is already known about [packageId], without waiting: a row that scrolls back into view must not flash its id. */
    fun peek(packageId: String): AppVisual? = null
}

/** Reads names and icons from the system package manager, and remembers the last few hundred. */
class PackageManagerIconSource(context: Context) : AppIconSource {
    private val packageManager: PackageManager = context.applicationContext.packageManager
    private val cache: LruCache<String, AppVisual> = LruCache(CACHE_SIZE)

    override fun peek(packageId: String): AppVisual? = cache.get(packageId)

    override suspend fun load(packageId: String): AppVisual {
        cache.get(packageId)?.let { return it }
        // Decoding an icon is real work: never on the main thread.
        val visual = withContext(Dispatchers.IO) { read(packageId) }
        cache.put(packageId, visual)
        return visual
    }

    private fun read(packageId: String): AppVisual =
        try {
            val info = packageManager.getApplicationInfo(packageId, 0)
            val drawable = info.loadIcon(packageManager)
            val bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_PIXELS, ICON_PIXELS)
            drawable.draw(Canvas(bitmap))
            AppVisual(info.loadLabel(packageManager).toString().ifBlank { packageId }, bitmap.asImageBitmap())
        } catch (error: PackageManager.NameNotFoundException) {
            // Uninstalled for the user, or hidden from this app: it is still shown, by its id.
            AppVisual(packageId, null)
        }

    private companion object {
        const val CACHE_SIZE: Int = 500
        const val ICON_PIXELS: Int = 128
    }
}

/** Without a source (previews, tests) every package is shown by its id. */
val LocalAppIcons = compositionLocalOf<AppIconSource> {
    object : AppIconSource {
        override suspend fun load(packageId: String): AppVisual = AppVisual(packageId, null)
    }
}

/** Reads the names and icons of [packageIds] in the background, in order, so the list finds them cached as it scrolls. */
@Composable
fun PrefetchAppVisuals(packageIds: List<String>) {
    val source = LocalAppIcons.current
    androidx.compose.runtime.LaunchedEffect(packageIds, source) {
        packageIds.forEach { source.load(it) }
    }
}

/** The name and icon of [packageId]: its id right away, the real ones as soon as they are read. */
@Composable
fun rememberAppVisual(packageId: String): AppVisual {
    val source = LocalAppIcons.current
    // A cached package is answered at once, so scrolling back never shows the id and the placeholder first.
    val visual by produceState(source.peek(packageId) ?: AppVisual(packageId, null, loaded = false), packageId, source) { value = source.load(packageId) }
    return visual
}

/** The icon of an app at [size]; without one, a rounded square with the app's initial so rows still line up. */
@Composable
fun AppIconImage(visual: AppVisual, size: Dp) {
    if (!visual.loaded) {
        Skeleton(Modifier.size(size), RoundedCornerShape(size * 0.28f))
        return
    }
    val icon = visual.icon
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
        return
    }
    val palette = TuneupTheme.colors
    val shape = RoundedCornerShape(size * 0.28f)
    Box(Modifier.size(size).clip(shape).background(palette.surfaceRaised), contentAlignment = Alignment.Center) {
        Text(
            visual.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·",
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = palette.accent,
        )
    }
}

/** "Real name (package.id)": the name a person knows first, the id for whoever needs it. Just the id when there is no name. */
fun displayName(visual: AppVisual, packageId: String): String =
    if (visual.label == packageId) packageId else "${visual.label} ($packageId)"

/** An icon with the app's name under it, two lines always so every cell has the same height. Used by the action previews. */
@Composable
fun AppTileContent(packageId: String, caption: String?) = AppTileContent(rememberAppVisual(packageId), caption)

@Composable
fun AppTileContent(visual: AppVisual, caption: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.vp)) {
        AppIconImage(visual, 56.vp)
        Text(visual.label, style = MaterialTheme.typography.labelMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = TuneupTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
