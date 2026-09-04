package com.example.remotecompose.engine

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import kotlin.math.roundToInt

/**
 * Executes a decoded `.rc` opcode stream against a Compose Multiplatform [DrawScope].
 *
 * This is the only place in the renderer that touches `drawContext` directly. It drives Skia's
 * own canvas save/restore and matrix/clip stack imperatively — via `drawContext.canvas` and
 * `drawContext.transform`, *not* the lambda-scoped `DrawScope.translate { }` / `withTransform { }`
 * helpers — because `OP_MATRIX_SAVE` / `OP_MATRIX_RESTORE` are independent, non-nested-lambda
 * opcodes in the stream; see [RenderContext]'s KDoc for why no separate matrix stack is kept on
 * the Kotlin side.
 *
 * Every opcode is dispatched inside its own `try/catch`, so one malformed or out-of-range opcode
 * (e.g. a bitmap index with no matching pool entry, or a degenerate/negative rect) degrades only
 * that single draw call instead of aborting the rest of the stream — the executor never lets an
 * exception propagate out of [render].
 */
object OpcodeExecutor {

    /**
     * Renders [opcodes] into [drawScope], resolving pool references (strings, bitmaps, variables)
     * against [context]'s document and recording `OP_ACTION_CLICK` targets into
     * [RenderContext.interactiveRegions] for the composable layer to hit-test later.
     */
    fun render(drawScope: DrawScope, opcodes: List<Opcode>, context: RenderContext) {
        context.beginFrame()

        val canvas = drawScope.drawContext.canvas
        val transform = drawScope.drawContext.transform
        var saveDepth = 0

        for (opcode in opcodes) {
            try {
                when (opcode) {
                    Opcode.MatrixSave -> {
                        canvas.save()
                        saveDepth++
                    }

                    Opcode.MatrixRestore -> {
                        // Guard against a document with more restores than saves: restoring an
                        // empty native canvas stack is undefined behavior we'd rather not risk.
                        if (saveDepth > 0) {
                            canvas.restore()
                            saveDepth--
                        }
                    }

                    is Opcode.Translate -> transform.translate(opcode.dx, opcode.dy)

                    is Opcode.Scale -> transform.scale(
                        scaleX = opcode.sx,
                        scaleY = opcode.sy,
                        pivot = Offset(opcode.pivotX, opcode.pivotY),
                    )

                    is Opcode.Rotate -> transform.rotate(
                        degrees = opcode.degrees,
                        pivot = Offset(opcode.pivotX, opcode.pivotY),
                    )

                    is Opcode.ClipRect -> transform.clipRect(
                        left = opcode.left,
                        top = opcode.top,
                        right = opcode.right,
                        bottom = opcode.bottom,
                    )

                    is Opcode.ClipPath -> transform.clipPath(buildPath(opcode.commands))

                    is Opcode.DrawRect -> withPaintStyles(opcode.paint) { style ->
                        drawScope.drawRect(
                            color = opcode.paint.color,
                            topLeft = Offset(opcode.left, opcode.top),
                            size = rectSize(opcode.left, opcode.top, opcode.right, opcode.bottom),
                            style = style,
                        )
                    }

                    is Opcode.DrawRoundRect -> withPaintStyles(opcode.paint) { style ->
                        drawScope.drawRoundRect(
                            color = opcode.paint.color,
                            topLeft = Offset(opcode.left, opcode.top),
                            size = rectSize(opcode.left, opcode.top, opcode.right, opcode.bottom),
                            cornerRadius = CornerRadius(opcode.radiusX, opcode.radiusY),
                            style = style,
                        )
                    }

                    is Opcode.DrawCircle -> withPaintStyles(opcode.paint) { style ->
                        drawScope.drawCircle(
                            color = opcode.paint.color,
                            radius = opcode.radius.coerceAtLeast(0f),
                            center = Offset(opcode.centerX, opcode.centerY),
                            style = style,
                        )
                    }

                    is Opcode.DrawPath -> {
                        val path = buildPath(opcode.commands)
                        withPaintStyles(opcode.paint) { style ->
                            drawScope.drawPath(path = path, color = opcode.paint.color, style = style)
                        }
                    }

                    is Opcode.DrawText -> drawScope.drawText(
                        textMeasurer = context.textMeasurer,
                        text = context.document.strings[opcode.stringIndex],
                        topLeft = Offset(opcode.x, opcode.y),
                        style = TextStyle(fontSize = opcode.fontSize.sp, color = Color(opcode.colorArgb)),
                    )

                    is Opcode.DrawBitmap -> {
                        val bitmap = context.document.bitmaps.get(opcode.bitmapIndex)
                        if (bitmap != null) {
                            drawScope.drawImage(
                                image = bitmap,
                                dstOffset = IntOffset(opcode.left.roundToInt(), opcode.top.roundToInt()),
                                dstSize = IntSize(
                                    (opcode.right - opcode.left).roundToInt().coerceAtLeast(0),
                                    (opcode.bottom - opcode.top).roundToInt().coerceAtLeast(0),
                                ),
                            )
                        }
                    }

                    is Opcode.ActionClick -> context.recordInteractiveRegion(
                        InteractiveRegion(
                            bounds = Rect(opcode.left, opcode.top, opcode.right, opcode.bottom),
                            actionId = opcode.actionId,
                            targetUrl = opcode.targetUrlStringIndex.takeIf { it >= 0 }
                                ?.let { context.document.strings[it] },
                        )
                    )

                    is Opcode.Unknown -> Unit // Deliberately not rendered; see Opcode.Unknown KDoc.
                }
            } catch (_: Exception) {
                // A single malformed/unsupported opcode must never break the rest of the draw
                // stack. Fatal errors (e.g. OutOfMemoryError) are intentionally not caught here.
            }
        }

        // Balance any OP_MATRIX_SAVE left un-restored by a malformed document, so this render
        // pass never leaks transform/clip state onto whatever draws next in the same DrawScope.
        while (saveDepth > 0) {
            canvas.restore()
            saveDepth--
        }
    }

    /** Builds a non-negative [Size] for a possibly-degenerate `(left, top, right, bottom)` rect. */
    private fun rectSize(left: Float, top: Float, right: Float, bottom: Float): Size =
        Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))

    /**
     * Invokes [block] once per [DrawStyle] implied by [paint]'s [PaintStyleKind] — twice, for
     * both [Fill] and [Stroke], when the paint is [PaintStyleKind.FILL_AND_STROKE] — since a
     * Compose `DrawScope` draw call takes a single [DrawStyle] per invocation, unlike Android's
     * combined `Paint.Style.FILL_AND_STROKE`.
     */
    private inline fun withPaintStyles(paint: PaintStyle, block: (DrawStyle) -> Unit) {
        when (paint.style) {
            PaintStyleKind.FILL -> block(Fill)
            PaintStyleKind.STROKE -> block(Stroke(width = paint.strokeWidth))
            PaintStyleKind.FILL_AND_STROKE -> {
                block(Fill)
                block(Stroke(width = paint.strokeWidth))
            }
        }
    }

    /** Reconstructs a Compose [Path] from a decoded `OP_DRAW_PATH` / `OP_CLIP_PATH` command list. */
    private fun buildPath(commands: List<PathCommand>): Path {
        val path = Path()
        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo -> path.moveTo(command.x, command.y)
                is PathCommand.LineTo -> path.lineTo(command.x, command.y)
                is PathCommand.CubicTo -> path.cubicTo(
                    command.x1, command.y1,
                    command.x2, command.y2,
                    command.x3, command.y3,
                )
                PathCommand.Close -> path.close()
            }
        }
        return path
    }
}
