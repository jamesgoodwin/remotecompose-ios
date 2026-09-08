package io.github.jamesgoodwin.remotecompose.text

import androidx.compose.ui.graphics.Color
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.model.PaintStyleKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks [TextAnchoring] against the formulas in `DrawTextAnchored.getHorizontalOffset()` /
 * `getVerticalOffset(boolean)` with bounds `[0, -ascent, width, descent]`.
 */
class TextAnchoringTest {

    private val metrics = TextMetrics(width = 40f, ascent = 9f, descent = 3f)
    private val paint = PaintStyle(Color.Black, PaintStyleKind.FILL)

    private fun text(panX: Float? = null, panY: Float? = null, baselineRelative: Boolean = false) =
        Opcode.DrawText(stringIndex = 0, x = 100f, y = 50f, paint = paint, panX = panX, panY = panY, baselineRelative = baselineRelative)

    @Test
    fun noPanMeansLeftEdgeAndBaseline() {
        assertEquals(100f to 41f, TextAnchoring.topLeft(text(), metrics))
    }

    @Test
    fun horizontalPanMovesAnchorAcrossWidth() {
        assertEquals(100f, TextAnchoring.topLeft(text(panX = -1f), metrics).first)
        assertEquals(80f, TextAnchoring.topLeft(text(panX = 0f), metrics).first)
        assertEquals(60f, TextAnchoring.topLeft(text(panX = 1f), metrics).first)
    }

    @Test
    fun verticalPanMinusOnePutsBottomAtY() {
        // baseline = y - h + ascent = 50 - 12 + 9 = 47; top = 38; bottom = 50.
        assertEquals(38f, TextAnchoring.topLeft(text(panY = -1f), metrics).second)
    }

    @Test
    fun verticalPanOnePutsTopAtY() {
        assertEquals(50f, TextAnchoring.topLeft(text(panY = 1f), metrics).second)
    }

    @Test
    fun verticalPanZeroCentersBox() {
        assertEquals(44f, TextAnchoring.topLeft(text(panY = 0f), metrics).second)
    }

    @Test
    fun baselineRelativeMeasuresFromBoxCenter() {
        // baseline = y - h*(1-panY)/2 + h/2 = 50 - 6 + 6 = 50 for panY = 0; top = 41.
        assertEquals(41f, TextAnchoring.topLeft(text(panY = 0f, baselineRelative = true), metrics).second)
    }

    @Test
    fun nanPanFallsBackToDefaultAnchor() {
        assertEquals(100f to 41f, TextAnchoring.topLeft(text(panX = Float.NaN, panY = Float.NaN), metrics))
    }

    @Test
    fun estimatedMetricsScaleWithTextSize() {
        val m = EstimatedTextMetrics.measure("abcd", paint.copy(textSize = 10f))
        assertEquals(TextMetrics(width = 22f, ascent = 9f, descent = 3f), m)
    }
}
