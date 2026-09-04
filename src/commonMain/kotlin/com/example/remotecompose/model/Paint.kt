package com.example.remotecompose.model

import androidx.compose.ui.graphics.Color

/**
 * Fill/stroke styling for a draw opcode, decoded from the opcode's payload.
 *
 * @property color Paint color, packed ARGB.
 * @property style Whether the shape is filled, stroked, or both.
 * @property strokeWidth Stroke width in DP; meaningless when [style] is [PaintStyleKind.FILL].
 */
data class PaintStyle(
    val color: Color,
    val style: PaintStyleKind,
    val strokeWidth: Float = 0f,
)

/** Which of fill/stroke (or both) a [PaintStyle] applies. */
enum class PaintStyleKind {
    FILL,
    STROKE,
    FILL_AND_STROKE,
}

/**
 * One segment of a reconstructed path, as emitted by `OP_DRAW_PATH` / `OP_CLIP_PATH`. Mirrors the
 * subset of `androidx.compose.ui.graphics.Path` construction calls the renderer needs to support:
 * move, line, cubic Bézier, and close.
 */
sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) : PathCommand
    data object Close : PathCommand
}
