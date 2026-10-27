package com.prototype.tuneupfortv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Background, border and text of one semantic chip, badge, button or symbol. */
data class ToneColors(val background: Color, val border: Color, val content: Color)

/** What a surface says: the same five tones the screens use for badges, banners and result symbols. */
enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

/** The semantic tokens of the app. Screens read these, never raw colors. */
data class TuneupColors(
    val dark: Boolean,
    val screen: Color,
    val screenGlow: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSelected: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val focus: Color,
    val focusHalo: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val protected: Color,
    val disabled: Color,
    val running: Color,
    val primary: ToneColors,
    val secondary: ToneColors,
    val dangerButton: ToneColors,
    val done: ToneColors,
    val neutral: ToneColors,
    val user: ToneColors,
    val runningBadge: ToneColors,
    val protectedBadge: ToneColors,
    val disabledBadge: ToneColors,
    val info: ToneColors,
    val successTone: ToneColors,
    val warningTone: ToneColors,
    val dangerTone: ToneColors,
    val switchOffTrack: Color,
    val switchOffKnob: Color,
    val switchOnTrack: Color,
    val switchOnKnob: Color,
    val track: Color,
) {
    fun tone(tone: Tone): ToneColors =
        when (tone) {
            Tone.NEUTRAL -> neutral
            Tone.INFO -> info
            Tone.SUCCESS -> successTone
            Tone.WARNING -> warningTone
            Tone.DANGER -> dangerTone
        }
}

private fun tone(background: Long, border: Long, content: Long) = ToneColors(Color(background), Color(border), Color(content))

val DarkTuneupColors = TuneupColors(
    dark = true,
    screen = Color(0xFF07090B),
    screenGlow = Color(0x0D8ED8FF),
    surface = Color(0xFF1C2229),
    surfaceRaised = Color(0xFF28313A),
    surfaceSelected = Color(0x128ED8FF),
    line = Color(0xFF2B3138),
    text = Color(0xFFF5F7FA),
    muted = Color(0xFFA9B3BD),
    accent = Color(0xFF8ED8FF),
    onAccent = Color(0xFF071015),
    focus = Color(0xFFFFFFFF),
    focusHalo = Color(0x8C7CD4FF),
    success = Color(0xFF70E1A5),
    warning = Color(0xFFF4C96B),
    danger = Color(0xFFFF8A8A),
    protected = Color(0xFFB8A7FF),
    disabled = Color(0xFFE0A96A),
    running = Color(0xFFBFF2FF),
    primary = tone(0xFF1D4E66, 0x00000000, 0xFFE3F6FF),
    secondary = tone(0xFF28313A, 0x00000000, 0xFFF5F7FA),
    dangerButton = tone(0xFF351C1F, 0xFF65343A, 0xFFFFC6C6),
    done = tone(0xFF1D4E66, 0x00000000, 0xFFE3F6FF),
    neutral = tone(0xFF28313A, 0x00000000, 0xFFB4BEC8),
    user = tone(0xFF10202A, 0xFF1E3D4E, 0xFFBFEAFF),
    runningBadge = tone(0xFF0D2329, 0xFF1D4A56, 0xFFBFF2FF),
    protectedBadge = tone(0xFF1E1930, 0xFF3B315F, 0xFFDED6FF),
    disabledBadge = tone(0xFF2A2015, 0xFF584124, 0xFFFFE0B8),
    info = tone(0xFF10242D, 0xFF245164, 0xFF9BE7FF),
    successTone = tone(0xFF10251B, 0xFF23513A, 0xFF90EFB9),
    warningTone = tone(0xFF2D2311, 0xFF5B461E, 0xFFFFD979),
    dangerTone = tone(0xFF2C1719, 0xFF5E3034, 0xFFFFABAB),
    switchOffTrack = Color(0xFF343B42),
    switchOffKnob = Color(0xFFAEB7BF),
    switchOnTrack = Color(0xFFD8F3FF),
    switchOnKnob = Color(0xFF0B2632),
    track = Color(0xFF1C2228),
)

/** The colors of content drawn on a focused (inverted, near white) container: dark text and deep tones. */
val InvertedTuneupColors = TuneupColors(
    dark = false,
    screen = Color(0xFFEEF2F5),
    screenGlow = Color(0x0E087CAE),
    surface = Color(0xFFE3E9EE),
    surfaceRaised = Color(0xFFD3DBE2),
    surfaceSelected = Color(0xFFD8EAF3),
    line = Color(0xFF9AA7B2),
    text = Color(0xFF0B1218),
    muted = Color(0xFF3F4C57),
    accent = Color(0xFF05638C),
    onAccent = Color(0xFFFFFFFF),
    focus = Color(0xFFFFFFFF),
    focusHalo = Color(0x8C7CD4FF),
    success = Color(0xFF1B6B43),
    warning = Color(0xFF7A5208),
    danger = Color(0xFF8E2C2C),
    protected = Color(0xFF51399F),
    disabled = Color(0xFF7A4A10),
    running = Color(0xFF075A74),
    primary = tone(0xFF0B1218, 0x00000000, 0xFFF5F7FA),
    secondary = tone(0xFFD3DBE2, 0x00000000, 0xFF0B1218),
    dangerButton = tone(0xFF8E2C2C, 0x00000000, 0xFFFFFFFF),
    done = tone(0xFF0B1218, 0x00000000, 0xFFF5F7FA),
    neutral = tone(0xFFD3DBE2, 0x00000000, 0xFF2F3B45),
    user = tone(0xFFCBE4F1, 0x00000000, 0xFF0E4F6E),
    runningBadge = tone(0xFFC9E8EF, 0x00000000, 0xFF075A74),
    protectedBadge = tone(0xFFDDD5F5, 0x00000000, 0xFF45308F),
    disabledBadge = tone(0xFFF3DDBC, 0x00000000, 0xFF6B4010),
    info = tone(0xFFCBE4F1, 0x00000000, 0xFF0E5C7A),
    successTone = tone(0xFFCDEBD9, 0x00000000, 0xFF1B6B43),
    warningTone = tone(0xFFF3E1B0, 0x00000000, 0xFF7A5208),
    dangerTone = tone(0xFFF3D0D0, 0x00000000, 0xFF8E2C2C),
    switchOffTrack = Color(0xFFB4BEC8),
    switchOffKnob = Color(0xFFFFFFFF),
    switchOnTrack = Color(0xFF05638C),
    switchOnKnob = Color(0xFFFFFFFF),
    track = Color(0xFFC3CCD4),
)

val LocalTuneupColors = compositionLocalOf { DarkTuneupColors }

/** Shortcut for the current palette: `TuneupTheme.colors.surface`. */
object TuneupTheme {
    val colors: TuneupColors
        @Composable get() = LocalTuneupColors.current
}

/**
 * The UI is drawn on a 1920x1080 virtual scene. One virtual pixel is this many dp/sp, so every measure is written
 * in virtual pixels (`64.vp`) and the whole UI is calibrated on the BRAVIA by changing this value.
 * 0.5 is the BRAVIA BF1: 1920x1080 px at 320 dpi, so one virtual pixel is exactly one screen pixel.
 */
const val VIRTUAL_PX_TO_DP: Float = 0.5f

val Number.vp: Dp get() = (toFloat() * VIRTUAL_PX_TO_DP).dp

/** The Android TV type scale, in sp: a TV already reports 960x540 dp, so these are the sizes Google recommends for the couch. */
private val TuneupTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
)

/** Tuneup is a TV app: one dark palette. The focused element inverts to [InvertedTuneupColors]. */
@Composable
fun TuneupTheme(content: @Composable () -> Unit) {
    val palette = DarkTuneupColors
    val colors = darkColorScheme(
        background = palette.screen, onBackground = palette.text,
        surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surfaceRaised, onSurfaceVariant = palette.text,
        primary = palette.accent, onPrimary = palette.onAccent,
        error = palette.danger, tertiary = palette.accent,
    )
    CompositionLocalProvider(LocalTuneupColors provides palette) {
        MaterialTheme(colorScheme = colors, typography = TuneupTypography, content = content)
    }
}

/** How much a focused element grows: bigger things grow more. A focused element also inverts to near white, see [Modifier.tvFocus]. */
enum class FocusKind(val scale: Float, val haloPx: Float) {
    CONTROL(1.05f, 8f),
    CARD(1.08f, 8f),
    ROW(1.03f, 8f),
}

private const val FOCUS_ANIMATION_MILLIS: Int = 115

/**
 * Focus for D-pad navigation: a soft halo outside the element and a scale. The element itself paints the inverted
 * container, so focus is never color alone. The halo draws outside the element, so its parent needs padding for it.
 */
@Composable
fun Modifier.tvFocus(
    source: InteractionSource,
    shape: Shape = RoundedCornerShape(20.vp),
    kind: FocusKind = FocusKind.CONTROL,
): Modifier {
    val focused by source.collectIsFocusedAsState()
    val colors = TuneupTheme.colors
    val scale by animateFloatAsState(if (focused) kind.scale else 1f, tween(FOCUS_ANIMATION_MILLIS), label = "focusScale")
    val haloAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_ANIMATION_MILLIS), label = "focusHalo")
    val haloWidth = with(LocalDensity.current) { kind.haloPx.vp.toPx() }
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind {
            if (haloAlpha > 0f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutlineStroke(outline, colors.focusHalo.copy(alpha = colors.focusHalo.alpha * haloAlpha), haloWidth * 2f)
            }
        }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOutlineStroke(
    outline: androidx.compose.ui.graphics.Outline,
    color: Color,
    width: Float,
) {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = width)
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rounded -> drawRoundRect(
            color = color,
            topLeft = Offset(outline.roundRect.left, outline.roundRect.top),
            size = Size(outline.roundRect.width, outline.roundRect.height),
            cornerRadius = CornerRadius(outline.roundRect.topLeftCornerRadius.x, outline.roundRect.topLeftCornerRadius.y),
            style = stroke,
        )
        is androidx.compose.ui.graphics.Outline.Rectangle -> drawRect(color = color, style = stroke)
        is androidx.compose.ui.graphics.Outline.Generic -> drawPath(outline.path, color, style = stroke)
    }
}
