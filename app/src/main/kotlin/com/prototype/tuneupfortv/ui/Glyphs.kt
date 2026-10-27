package com.prototype.tuneupfortv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The line icons of the app (24x24 viewBox, stroked), tinted by whoever draws them. */
object Glyphs {
    val Back: ImageVector by lazy { glyph("Back", 2.3f, "M15.5 5 8.5 12l7 7") }
    val Chevron: ImageVector by lazy { glyph("Chevron", 2.3f, "M8.5 5l7 7-7 7") }
    val Brand: ImageVector by lazy { glyph("Brand", 2.2f, "M5 7.5h14M7.5 12h9M10 16.5h4") }
    val Packages: ImageVector by lazy {
        glyph("Packages", 1.9f, roundedRect(3.5f, 4f, 7f, 7f), roundedRect(13.5f, 4f, 7f, 7f), roundedRect(3.5f, 14f, 7f, 6f), roundedRect(13.5f, 14f, 7f, 6f))
    }
    val Processes: ImageVector by lazy { glyph("Processes", 2f, "M4 7.5h16M4 12h10M4 16.5h7", circle(18f, 15.5f, 3f)) }
    val Health: ImageVector by lazy { glyph("Health", 2f, "M3.5 13h4l2-5 4 9 2.2-4H20.5") }
    val Settings: ImageVector by lazy {
        glyph("Settings", 1.9f, circle(12f, 12f, 3.5f), "M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6 18 18M18 6l-1.4 1.4M7.4 16.6 6 18")
    }
    val Refresh: ImageVector by lazy {
        glyph("Refresh", 2f, "M20 11a8 8 0 0 0-14.8-3.9L3 9.3M4 5v4.3h4.3M4 13a8 8 0 0 0 14.8 3.9l2.2-2.2M20 19v-4.3h-4.3")
    }
    val Debloat: ImageVector by lazy { glyph("Debloat", 2f, "M9 4h6M4.5 7h15M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5") }
    val Plug: ImageVector by lazy { glyph("Plug", 2f, "M10 7V4m4 3V4M8 7h8v5a4 4 0 0 1-8 0V7Zm4 9v4m-3 0h6") }
    val Search: ImageVector by lazy { glyph("Search", 2f, circle(10.5f, 10.5f, 6.2f), "m15.3 15.3 5 5") }
    val Check: ImageVector by lazy { glyph("Check", 2.6f, "M5 12.5l4.5 4.5L19 7.5") }
    val ViewList: ImageVector by lazy { glyph("ViewList", 2.2f, "M4 7h16M4 12h16M4 17h16") }
    val ViewGrid: ImageVector by lazy {
        glyph("ViewGrid", 1.9f, roundedRect(4f, 4f, 6.5f, 6.5f), roundedRect(13.5f, 4f, 6.5f, 6.5f), roundedRect(4f, 13.5f, 6.5f, 6.5f), roundedRect(13.5f, 13.5f, 6.5f, 6.5f))
    }

    private fun glyph(name: String, strokeWidth: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** SVG `rect` with rx = 2, as path data (the parser has no shapes). */
    private fun roundedRect(x: Float, y: Float, width: Float, height: Float): String {
        val radius = 2f
        val innerWidth = width - 2 * radius
        val innerHeight = height - 2 * radius
        return "M${x + radius} ${y}h$innerWidth" +
            "a$radius $radius 0 0 1 $radius ${radius}v$innerHeight" +
            "a$radius $radius 0 0 1 ${-radius} ${radius}h${-innerWidth}" +
            "a$radius $radius 0 0 1 ${-radius} ${-radius}v${-innerHeight}" +
            "a$radius $radius 0 0 1 $radius ${-radius}z"
    }

    /** SVG `circle` as two half arcs. */
    private fun circle(centerX: Float, centerY: Float, radius: Float): String =
        "M${centerX - radius} ${centerY}a$radius $radius 0 1 0 ${2 * radius} 0a$radius $radius 0 1 0 ${-2 * radius} 0z"
}
