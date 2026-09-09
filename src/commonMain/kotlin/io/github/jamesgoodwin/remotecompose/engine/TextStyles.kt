package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.jamesgoodwin.remotecompose.model.FontFamilyKind
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.text.TextMetrics
import io.github.jamesgoodwin.remotecompose.text.TextMetricsProvider

/**
 * The Compose [TextStyle] for a paint, at an explicit [fontSize]; callers pick the unit that
 * makes the result exactly `paint.textSize` document pixels in their own density.
 */
internal fun PaintStyle.toTextStyle(fontSize: TextUnit, brush: Brush? = null): TextStyle {
    // An embedded DATA_FONT wins over the named family, the way it replaces the paint's typeface
    // outright in the real player; a font this platform could not read falls back to the family.
    val embedded = font?.family
    // Weight and slant describe an embedded font file rather than restyle it: the real player
    // hands the typeface straight to the paint, and its `Font.Builder.setWeight`/`setSlant` are
    // metadata for matching within a family of one. Asking Compose for them as well would have
    // Skia synthesise a bold or an oblique that Android would not draw — and `setTypeface(int)`
    // sets the italic bit on every embedded font, since the reader reads the "this is an id"
    // flag as part of it.
    val weight = if (embedded != null) FontWeight.Normal else FontWeight(fontWeight.coerceIn(1, 1000))
    val fontStyle = if (fontItalic && embedded == null) FontStyle.Italic else FontStyle.Normal
    val family = embedded ?: when (fontFamily) {
        FontFamilyKind.DEFAULT -> FontFamily.Default
        FontFamilyKind.SANS_SERIF -> FontFamily.SansSerif
        FontFamilyKind.SERIF -> FontFamily.Serif
        FontFamilyKind.MONOSPACE -> FontFamily.Monospace
    }
    return if (brush != null) {
        TextStyle(brush = brush, fontSize = fontSize, fontWeight = weight, fontStyle = fontStyle, fontFamily = family)
    } else {
        TextStyle(color = color, fontSize = fontSize, fontWeight = weight, fontStyle = fontStyle, fontFamily = family)
    }
}

/**
 * Real font metrics through a Compose [TextMeasurer]. Measures at a 1:1 density so `sp` equals
 * document pixels, which is the space every opcode coordinate lives in.
 */
public class ComposeTextMetrics(private val textMeasurer: TextMeasurer) : TextMetricsProvider {
    private val unitDensity = Density(density = 1f, fontScale = 1f)

    override fun measure(text: String, paint: PaintStyle): TextMetrics {
        val layout = textMeasurer.measure(text, paint.toTextStyle(paint.textSize.sp), density = unitDensity)
        val ascent = layout.firstBaseline
        return TextMetrics(
            width = layout.size.width.toFloat(),
            ascent = ascent,
            descent = layout.size.height - ascent,
        )
    }
}
