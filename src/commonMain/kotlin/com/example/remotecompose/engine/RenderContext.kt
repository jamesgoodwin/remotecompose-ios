package com.example.remotecompose.engine

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import com.example.remotecompose.model.RemoteDocument

/**
 * Per-render-pass state that [OpcodeExecutor] reads from and writes to while walking a
 * [RemoteDocument]'s opcode stream against a `DrawScope`.
 *
 * A `RenderContext` is created once per [com.example.remotecompose.ui.RemoteComposeCanvas]
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
 *   and [RemoteDocument.bitmaps] for resolving `OP_DRAW_TEXT` / `OP_DRAW_BITMAP` pool indices, and
 *   [RemoteDocument.variables] for future expression-driven opcodes.
 * @property textMeasurer Shared [TextMeasurer] used to lay out every `OP_DRAW_TEXT` opcode. Must
 *   come from `rememberTextMeasurer()` in the composable layer — constructing one directly is
 *   expensive and not itself Composable.
 */
class RenderContext(
    var document: RemoteDocument,
    val textMeasurer: TextMeasurer,
) {
    private val _interactiveRegions = mutableListOf<InteractiveRegion>()

    /**
     * Tap targets collected from `OP_ACTION_CLICK` opcodes during the most recent
     * [OpcodeExecutor.render] pass, in document (pre-scale) coordinates. The composable layer
     * hit-tests pointer input against these to decide which [com.example.remotecompose.model.RemoteAction]
     * to dispatch, so it must apply the same coordinate mapping (e.g. a fit-to-bounds scale) to
     * the pointer position that [OpcodeExecutor] applied to the canvas before rendering.
     *
     * Read-only from outside this package; [OpcodeExecutor] repopulates it on every render pass
     * via [beginFrame] + internal appends, since a document's action targets can move opcode-to-
     * opcode as bound variables change.
     */
    val interactiveRegions: List<InteractiveRegion> get() = _interactiveRegions

    /** Clears [interactiveRegions] ahead of a new render pass. Called by [OpcodeExecutor.render]. */
    fun beginFrame() {
        _interactiveRegions.clear()
    }

    /** Appends a hit-testable region. Called by [OpcodeExecutor] while decoding `OP_ACTION_CLICK`. */
    fun recordInteractiveRegion(region: InteractiveRegion) {
        _interactiveRegions += region
    }
}

/**
 * A single tap target produced by an `OP_ACTION_CLICK` opcode.
 *
 * @property bounds Hit rect in document coordinates.
 * @property actionId Document-defined action identifier, forwarded verbatim to
 *   [com.example.remotecompose.model.RemoteAction.Click.actionId].
 * @property targetUrl Resolved string-pool value for the opcode's target URL, or `null` if the
 *   opcode used the "no URL" sentinel.
 */
data class InteractiveRegion(
    val bounds: Rect,
    val actionId: Int,
    val targetUrl: String?,
)
