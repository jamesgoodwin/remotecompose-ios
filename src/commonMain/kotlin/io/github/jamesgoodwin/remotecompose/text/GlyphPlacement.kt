package io.github.jamesgoodwin.remotecompose.text

import io.github.jamesgoodwin.remotecompose.geometry.PathGeometry
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.model.PathCommand
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lays a string out one glyph at a time along a path or a circle, the way
 * `Canvas.drawTextOnPath` does: each glyph sits at its accumulated advance along the curve, with
 * its baseline on the curve and rotated to the tangent there, and [vOffset] pushes it
 * perpendicular.
 *
 * The renderer has no glyph-run primitive, so each glyph is drawn as the one-character substring
 * of the pooled string inside its own transform. Kerning is lost, since each glyph is measured
 * on its own; the advance used is the glyph's measured width.
 */
internal object GlyphPlacement {

    private const val RADIANS_TO_DEGREES = 180f / PI.toFloat()

    /**
     * `DRAW_TEXT_ON_PATH`: [text] along [commands], starting [hOffset] along the path and
     * [vOffset] perpendicular to it. Empty when the path has no length.
     */
    fun onPath(
        text: String,
        stringIndex: Int,
        commands: List<PathCommand>,
        hOffset: Float,
        vOffset: Float,
        paint: PaintStyle,
        metrics: TextMetricsProvider,
    ): List<Opcode> {
        if (text.isEmpty()) return emptyList()
        val segments = PathGeometry.flatten(commands)
        if (PathGeometry.segmentLengths(segments).sum() <= 0f) return emptyList()
        val out = mutableListOf<Opcode>()
        var distance = hOffset
        for (i in text.indices) {
            val advance = metrics.measure(text.substring(i, i + 1), paint).width
            // The glyph is centered on its own advance along the path, as Android places it.
            val point = PathGeometry.pointAtDistance(segments, distance + advance / 2f) ?: break
            out += glyphAt(
                point.x, point.y, atan2(point.tangentY, point.tangentX),
                advance, vOffset, stringIndex, i, paint,
            )
            distance += advance
        }
        return out
    }

    /**
     * `DRAW_TEXT_ON_CIRCLE`: [text] around the circle at ([centerX], [centerY]) of [radius],
     * starting at [startAngleDegrees], measured clockwise from pointing right.
     *
     * The real `DrawTextOnCircle.paint` throws `UnsupportedOperationException` in remote-core
     * 1.0.0-alpha18, so there is no reference rendering to match; this follows the operation's
     * own fields. [alignment] places the run's start (0), middle (1) or end (2) at the start
     * angle, [placement] puts the glyphs outside (0) or inside (1) the circle, and
     * [warpRadiusOffset] shifts them off the radius.
     */
    fun onCircle(
        text: String,
        stringIndex: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        startAngleDegrees: Float,
        warpRadiusOffset: Float,
        alignment: Int,
        placement: Int,
        paint: PaintStyle,
        metrics: TextMetricsProvider,
    ): List<Opcode> {
        if (text.isEmpty()) return emptyList()
        val effectiveRadius = radius + warpRadiusOffset
        if (effectiveRadius <= 0f) return emptyList()
        val inside = placement == 1
        val advances = FloatArray(text.length) { metrics.measure(text.substring(it, it + 1), paint).width }
        val totalArc = advances.sum() / effectiveRadius // radians the whole run subtends
        val alignmentShift = when (alignment) {
            1 -> -totalArc / 2f
            2 -> -totalArc
            else -> 0f
        }
        // Inside placement runs the other way around so the glyphs stay upright to a reader.
        val direction = if (inside) -1f else 1f
        var angle = startAngleDegrees / RADIANS_TO_DEGREES + alignmentShift * direction
        val out = mutableListOf<Opcode>()
        for (i in text.indices) {
            val halfArc = advances[i] / effectiveRadius / 2f
            val glyphAngle = angle + halfArc * direction
            val quarterTurn = PI.toFloat() / 2f
            out += glyphAt(
                centerX + effectiveRadius * cos(glyphAngle),
                centerY + effectiveRadius * sin(glyphAngle),
                glyphAngle + if (inside) -quarterTurn else quarterTurn,
                advances[i], 0f, stringIndex, i, paint,
            )
            angle += halfArc * 2f * direction
        }
        return out
    }

    /**
     * One glyph centered at ([x], [y]) with its baseline rotated by [rotationRadians] and pushed
     * [vOffset] along the normal. The text opcode draws from its left edge on the baseline, so
     * inside the rotated frame the glyph starts half an advance back.
     */
    private fun glyphAt(
        x: Float,
        y: Float,
        rotationRadians: Float,
        advance: Float,
        vOffset: Float,
        stringIndex: Int,
        glyphIndex: Int,
        paint: PaintStyle,
    ): List<Opcode> {
        val normalX = -sin(rotationRadians)
        val normalY = cos(rotationRadians)
        return listOf(
            Opcode.MatrixSave,
            Opcode.Translate(x + normalX * vOffset, y + normalY * vOffset),
            Opcode.Rotate(rotationRadians * RADIANS_TO_DEGREES, 0f, 0f),
            Opcode.DrawText(
                stringIndex = stringIndex,
                x = -advance / 2f,
                y = 0f,
                paint = paint,
                substringStart = glyphIndex,
                substringEnd = glyphIndex + 1,
            ),
            Opcode.MatrixRestore,
        )
    }
}
