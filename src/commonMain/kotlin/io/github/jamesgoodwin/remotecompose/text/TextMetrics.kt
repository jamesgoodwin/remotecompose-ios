package io.github.jamesgoodwin.remotecompose.text

import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.model.PaintStyle

/**
 * The measurements text anchoring needs, in document pixels: the advance [width], and the
 * [ascent] above and [descent] below the baseline. Stands in for Android's
 * `Paint.getTextBounds` result `[left, top, right, bottom]` as `[0, -ascent, width, descent]`.
 */
public data class TextMetrics(val width: Float, val ascent: Float, val descent: Float) {
    val height: Float get() = ascent + descent
}

/** Measures a string as the given paint would draw it. */
public fun interface TextMetricsProvider {
    public fun measure(text: String, paint: PaintStyle): TextMetrics
}

/**
 * A font-free approximation for callers with no text engine at hand (tests, the parser when
 * used headlessly): average glyph advance of 0.55em, ascent 0.9em, descent 0.3em.
 */
public object EstimatedTextMetrics : TextMetricsProvider {
    override fun measure(text: String, paint: PaintStyle): TextMetrics {
        val size = paint.textSize
        return TextMetrics(width = text.length * size * 0.55f, ascent = size * 0.9f, descent = size * 0.3f)
    }
}

/**
 * Where a [Opcode.DrawText] lands given its metrics, mirroring `DrawTextAnchored.paint()` and
 * `PaintContext.drawTextRun`: `y` is a baseline, and the optional pans move the anchor across
 * the measured box. Formulas are those of `DrawTextAnchored.getHorizontalOffset()` and
 * `getVerticalOffset(baselineRelative)` with the bounds substitution documented on [TextMetrics].
 */
internal object TextAnchoring {

    /** Top-left corner of the text's box, as `(x, y)`. */
    public fun topLeft(op: Opcode.DrawText, metrics: TextMetrics): Pair<Float, Float> {
        val panX = op.panX
        val panY = op.panY
        // getHorizontalOffset: -(width * (1 + panX) / 2) - bounds[0], with bounds[0] = 0.
        val left = if (panX == null || panX.isNaN()) op.x else op.x - metrics.width * (1f + panX) / 2f
        // getVerticalOffset: -(height * (1 - panY) / 2) + (baselineRelative ? height / 2 : -top),
        // added to y to give the baseline; the box top is one ascent above that.
        val baseline = if (panY == null || panY.isNaN()) {
            op.y
        } else {
            val h = metrics.height
            op.y - h * (1f - panY) / 2f + (if (op.baselineRelative) h / 2f else metrics.ascent)
        }
        return left to (baseline - metrics.ascent)
    }
}
