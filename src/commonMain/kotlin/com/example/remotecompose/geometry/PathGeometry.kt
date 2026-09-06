package com.example.remotecompose.geometry

import com.example.remotecompose.model.PathCommand
import kotlin.math.sqrt

/**
 * Arc-length geometry over a decoded path, the operations Android's `PathMeasure` provides:
 * flattening curves to a polyline, total length, and the point and tangent at a distance along
 * it. Used by `MATRIX_FROM_PATH`, `DRAW_TWEEN_PATH`'s trim, and text drawn on a path.
 */
object PathGeometry {

    /** Samples per Bézier segment when flattening; the standard trade of accuracy for simplicity. */
    private const val CURVE_SAMPLES = 16

    /** A point on a path with the (un-normalized) direction the path runs there. */
    data class PointOnPath(val x: Float, val y: Float, val tangentX: Float, val tangentY: Float)

    /**
     * The path as one list of `[x1, y1, x2, y2]` line segments, with quadratic and cubic curves
     * sampled into [CURVE_SAMPLES] straight pieces each and `Close` joining back to the subpath
     * start.
     */
    fun flatten(commands: List<PathCommand>): List<FloatArray> {
        val segments = mutableListOf<FloatArray>()
        var curX = 0f
        var curY = 0f
        var subpathStartX = 0f
        var subpathStartY = 0f

        fun quadPoint(t: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
            val u = 1f - t
            return floatArrayOf(
                u * u * x0 + 2f * u * t * x1 + t * t * x2,
                u * u * y0 + 2f * u * t * y1 + t * t * y2,
            )
        }

        fun cubicPoint(
            t: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        ): FloatArray {
            val u = 1f - t
            return floatArrayOf(
                u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3,
            )
        }

        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo -> {
                    curX = command.x; curY = command.y
                    subpathStartX = curX; subpathStartY = curY
                }
                is PathCommand.LineTo -> {
                    segments += floatArrayOf(curX, curY, command.x, command.y)
                    curX = command.x; curY = command.y
                }
                is PathCommand.QuadraticTo -> {
                    var prevX = curX; var prevY = curY
                    for (i in 1..CURVE_SAMPLES) {
                        val p = quadPoint(
                            i / CURVE_SAMPLES.toFloat(), curX, curY,
                            command.x1, command.y1, command.x2, command.y2,
                        )
                        segments += floatArrayOf(prevX, prevY, p[0], p[1])
                        prevX = p[0]; prevY = p[1]
                    }
                    curX = command.x2; curY = command.y2
                }
                is PathCommand.CubicTo -> {
                    var prevX = curX; var prevY = curY
                    for (i in 1..CURVE_SAMPLES) {
                        val p = cubicPoint(
                            i / CURVE_SAMPLES.toFloat(), curX, curY,
                            command.x1, command.y1, command.x2, command.y2, command.x3, command.y3,
                        )
                        segments += floatArrayOf(prevX, prevY, p[0], p[1])
                        prevX = p[0]; prevY = p[1]
                    }
                    curX = command.x3; curY = command.y3
                }
                PathCommand.Close -> {
                    segments += floatArrayOf(curX, curY, subpathStartX, subpathStartY)
                    curX = subpathStartX; curY = subpathStartY
                }
            }
        }
        return segments
    }

    /** Length of each segment of [segments], in the same order. */
    fun segmentLengths(segments: List<FloatArray>): List<Float> =
        segments.map { sqrt((it[2] - it[0]) * (it[2] - it[0]) + (it[3] - it[1]) * (it[3] - it[1])) }

    /** Total arc length of [commands]. */
    fun length(commands: List<PathCommand>): Float = segmentLengths(flatten(commands)).sum()

    /** The point and tangent at [fraction] (0..1) of the path's arc length, or null if degenerate. */
    fun pointAtFraction(commands: List<PathCommand>, fraction: Float): PointOnPath? {
        val segments = flatten(commands)
        val total = segmentLengths(segments).sum()
        if (total <= 0f) return null
        return pointAtDistance(segments, fraction.coerceIn(0f, 1f) * total)
    }

    /**
     * The point and tangent [distance] along the already-flattened [segments]. A distance past
     * either end clamps to that end, so glyphs that overrun a path pile up at its tip rather than
     * disappearing, which is what `Canvas.drawTextOnPath` does.
     */
    fun pointAtDistance(segments: List<FloatArray>, distance: Float): PointOnPath? {
        if (segments.isEmpty()) return null
        val lengths = segmentLengths(segments)
        val total = lengths.sum()
        if (total <= 0f) return null
        val target = distance.coerceIn(0f, total)
        var accumulated = 0f
        for (i in segments.indices) {
            val segmentLength = lengths[i]
            if (accumulated + segmentLength >= target || i == segments.lastIndex) {
                val localT = if (segmentLength > 0f) ((target - accumulated) / segmentLength).coerceIn(0f, 1f) else 0f
                val s = segments[i]
                return PointOnPath(
                    x = s[0] + (s[2] - s[0]) * localT,
                    y = s[1] + (s[3] - s[1]) * localT,
                    tangentX = s[2] - s[0],
                    tangentY = s[3] - s[1],
                )
            }
            accumulated += segmentLength
        }
        return null
    }
}
