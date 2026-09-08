package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.jamesgoodwin.remotecompose.model.RemoteDocument

/**
 * Per-render-pass state that [OpcodeExecutor] reads from and writes to while walking a
 * [RemoteDocument]'s opcode stream against a `DrawScope`.
 *
 * A `RenderContext` is created once per [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas]
 * instance (Phase 3) and reused across recompositions/frames for the same parsed document, since
 * [textMeasurer] is expensive to create and [interactiveRegions] only needs to be rebuilt when
 * the opcode stream actually changes.
 *
 * Matrix/clip *state* itself is deliberately not modeled here: [OpcodeExecutor] drives Skia's own
 * canvas save/restore stack directly (via `drawContext.canvas` and `drawContext.transform`)
 * rather than maintaining a parallel CPU-side matrix stack, since on Skia-backed targets — iOS in
 * particular — that would just be redundant matrix math shadowing the one the native canvas
 * already does for us. [OpcodeExecutor] does track the *save depth* as a plain counter, to keep
 * `OP_MATRIX_SAVE`/`OP_MATRIX_RESTORE` balanced even against a malformed document.
 *
 * @property document The parsed document currently being rendered. Supplies [RemoteDocument.strings]
 *   and [RemoteDocument.bitmaps] for resolving text and bitmap pool ids.
 * @property textMeasurer Shared [TextMeasurer] used to lay out every `OP_DRAW_TEXT` opcode. Must
 *   come from `rememberTextMeasurer()` in the composable layer — constructing one directly is
 *   expensive and not itself Composable.
 */
public class RenderContext(
    public var document: RemoteDocument,
    public val textMeasurer: TextMeasurer,
) {
    private val _interactiveRegions = mutableListOf<InteractiveRegion>()

    /**
     * Tap targets collected from `OP_ACTION_CLICK` opcodes during the most recent
     * [OpcodeExecutor.render] pass, in document (pre-scale) coordinates. The composable layer
     * hit-tests pointer input against these to decide which [io.github.jamesgoodwin.remotecompose.model.RemoteAction]
     * to dispatch, so it must apply the same coordinate mapping (e.g. a fit-to-bounds scale) to
     * the pointer position that [OpcodeExecutor] applied to the canvas before rendering.
     *
     * Read-only from outside this package; [OpcodeExecutor] repopulates it on every render pass
     * via [beginFrame] + internal appends, since a document's action targets can move opcode-to-
     * opcode as bound variables change.
     */
    public val interactiveRegions: List<InteractiveRegion> get() = _interactiveRegions

    /** Clears [interactiveRegions] ahead of a new render pass. Called by [OpcodeExecutor.render]. */
    public fun beginFrame() {
        _interactiveRegions.clear()
    }

    /** Appends a hit-testable region. Called by [OpcodeExecutor] while decoding `OP_ACTION_CLICK`. */
    public fun recordInteractiveRegion(region: InteractiveRegion) {
        _interactiveRegions += region
    }

    private val crops = mutableMapOf<CropKey, ImageBitmap>()

    /**
     * The [size] region of [bitmap] at [offset], as an image of its own, kept for as long as this
     * context lives — a document redraws the same crop every frame.
     *
     * Copying it out is what keeps a scaled draw from sampling the pixels around it; the copy
     * itself is done unfiltered at one to one, where there is nothing to interpolate. Returns
     * null for an empty or out-of-bounds region, which draws nothing.
     */
    public fun croppedBitmap(id: Int, bitmap: ImageBitmap, offset: IntOffset, size: IntSize): ImageBitmap? {
        if (size.width <= 0 || size.height <= 0) return null
        if (offset.x < 0 || offset.y < 0) return null
        if (offset.x + size.width > bitmap.width || offset.y + size.height > bitmap.height) return null
        if (offset == IntOffset.Zero && size.width == bitmap.width && size.height == bitmap.height) return bitmap
        return crops.getOrPut(CropKey(id, offset.x, offset.y, size.width, size.height)) {
            val cropped = ImageBitmap(size.width, size.height)
            val paint = Paint().apply { filterQuality = FilterQuality.None }
            Canvas(cropped).drawImageRect(
                image = bitmap,
                srcOffset = offset,
                srcSize = size,
                dstOffset = IntOffset.Zero,
                dstSize = size,
                paint = paint,
            )
            cropped
        }
    }
}

private data class CropKey(val id: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A single tap target produced by an `OP_ACTION_CLICK` opcode.
 *
 * @property bounds Hit rect in document coordinates.
 * @property actionId Document-defined action identifier, forwarded verbatim to
 *   [io.github.jamesgoodwin.remotecompose.model.RemoteAction.Click.actionId].
 * @property targetUrl Resolved string-pool value for the opcode's target URL, or `null` if the
 *   opcode used the "no URL" sentinel.
 */
public data class InteractiveRegion(
    val bounds: Rect,
    val actionId: Int,
    val targetUrl: String?,
)
