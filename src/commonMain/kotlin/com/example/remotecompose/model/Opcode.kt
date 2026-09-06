package com.example.remotecompose.model

/**
 * A single decoded instruction from the `.rc` opcode stream: a transform, a clip, a draw call, or
 * an interaction target.
 *
 * String and bitmap payloads are carried as pool ids (`stringIndex`, `bitmapIndex`) rather than
 * resolved values, since resolution requires the pools on the parsed [RemoteDocument] alongside
 * the opcode list — resolving eagerly here would duplicate that data per opcode.
 */
sealed interface Opcode {

    // --- Matrix & coordinate transforms (§4.1) ---

    /** Pushes the current transform/clip state; paired with [MatrixRestore]. */
    data object MatrixSave : Opcode

    /** Pops back to the state at the most recent unmatched [MatrixSave] *or* [SaveLayerAlpha]. */
    data object MatrixRestore : Opcode

    /**
     * Pushes a new compositing layer with overall opacity [alpha] (`0f`..`1f`), so everything
     * drawn until the matching [MatrixRestore] is blended as one group instead of each draw call
     * fading independently — the real effect `MODIFIER_GRAPHICS_LAYER`'s `ALPHA` attribute has.
     * Uses a generous sentinel layer size rather than this container's real bounds, since this
     * renderer has no measure/layout pass to compute those from.
     */
    data class SaveLayerAlpha(val alpha: Float) : Opcode

    /** Shifts the local coordinate origin by ([dx], [dy]), in DP. */
    data class Translate(val dx: Float, val dy: Float) : Opcode

    /** Scales the coordinate space by ([sx], [sy]) about ([pivotX], [pivotY]). */
    data class Scale(val sx: Float, val sy: Float, val pivotX: Float, val pivotY: Float) : Opcode

    /** Rotates the coordinate space by [degrees] about ([pivotX], [pivotY]). */
    data class Rotate(val degrees: Float, val pivotX: Float, val pivotY: Float) : Opcode

    /**
     * Shears the coordinate space: `x' = x + skewX*y`, `y' = skewY*x + y` — the same convention
     * `android.graphics.Matrix.setSkew(kx, ky)` uses. Unlike [Scale]/[Rotate], the real
     * `MatrixSkew` operation carries no pivot field at all.
     */
    data class Skew(val skewX: Float, val skewY: Float) : Opcode

    /** Intersects the current clip with an axis-aligned rectangle. */
    data class ClipRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : Opcode

    /** Intersects the current clip with an arbitrary path, reconstructed from [commands]. */
    data class ClipPath(val commands: List<PathCommand>) : Opcode

    // --- Draw instructions (§4.2) ---

    /** Draws an axis-aligned rectangle. */
    data class DrawRect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a rectangle with elliptical corners of radius ([radiusX], [radiusY]). */
    data class DrawRoundRect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val radiusX: Float, val radiusY: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a circle centered at ([centerX], [centerY]) with the given [radius]. */
    data class DrawCircle(
        val centerX: Float, val centerY: Float, val radius: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws an arbitrary path reconstructed from [commands]. */
    data class DrawPath(
        val commands: List<PathCommand>,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a straight line segment from `(x1, y1)` to `(x2, y2)`. */
    data class DrawLine(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws an ellipse inscribed in the rect `(left, top, right, bottom)`. */
    data class DrawOval(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val paint: PaintStyle,
    ) : Opcode

    /**
     * Draws an arc of the ellipse inscribed in `(left, top, right, bottom)`, starting at
     * [startAngleDegrees] and sweeping [sweepAngleDegrees] clockwise. When [useCenter] is true the
     * arc is closed back to the ellipse's center (a pie/sector slice); when false it's just the
     * curved stroke/fill between the two arc endpoints.
     */
    data class DrawArc(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val startAngleDegrees: Float, val sweepAngleDegrees: Float,
        val useCenter: Boolean,
        val paint: PaintStyle,
    ) : Opcode

    /**
     * Draws text at ([x], [y]).
     *
     * @property stringIndex Id into [RemoteDocument.strings].
     * @property paint The cumulative paint in effect when the text was issued: color, text size
     *   in document pixels, weight/italic/family, and any gradient shader. Same source of truth
     *   as every shape opcode's paint.
     * @property substringStart When non-null (only `Operations.DRAW_TEXT_RUN` carries these —
     *   plain `Operations.DRAW_TEXT_ANCHORED` always draws the whole pool entry), only the
     *   `[substringStart, substringEnd)` slice of the resolved string is drawn, resolved at
     *   render time so the pool entry itself stays shared rather than duplicated per opcode.
     * @property panX Only `Operations.DRAW_TEXT_ANCHORED` carries a real one (source-confirmed via
     *   javap on the real `DrawTextAnchored.getHorizontalOffset()`): a `-1f..1f` fraction of the
     *   text's own measured width describing which point of it [x] anchors — `-1f` (this class's
     *   default, matching every other `DrawText`-producing opcode's own implicit left-anchor
     *   behavior) means [x] is the left edge, `0f` means [x] is the horizontal center, `1f` means
     *   [x] is the right edge, linearly interpolated in between. The real formula also involves
     *   the text's own left-side bearing (`bounds[0]`), approximated here as `0` (a real, honest
     *   simplification — this renderer has no access to that specific font metric — not a
     *   byte-level guess), same discipline as `DRAW_TEXT_ON_CIRCLE`'s own documented
     *   straight-line approximation elsewhere in this codebase.
     * @property panY Same shape as [panX], but for the vertical axis: `-1f` (this class's default)
     *   means [y] anchors the text's own top edge, `0f` its vertical center, `1f` its bottom edge.
     *   The real `DrawTextAnchored.getVerticalOffset()` computes this from the text's own
     *   baseline-relative ascent/descent (further branching on `ANCHOR_MONOSPACE_MEASURE`/
     *   `BASELINE_RELATIVE` flag bits this renderer doesn't track), which this renderer's
     *   `drawText(topLeft = ...)` API has no equivalent access to. Since that API already treats
     *   [y] as the text's own top-left corner (unlike the real SDK's baseline-relative primitive),
     *   the real *conceptual* top/center/bottom anchoring effect is reproduced here using this
     *   renderer's own measured text height instead of the real font's ascent/descent — the same
     *   "real effect via this renderer's own accurate geometry, not a byte-level port of the real
     *   formula" approximation already used for [panX]'s own `bounds[0]` simplification.
     */
    data class DrawText(
        val stringIndex: Int,
        val x: Float,
        val y: Float,
        val paint: PaintStyle,
        val substringStart: Int? = null,
        val substringEnd: Int? = null,
        val panX: Float = -1f,
        val panY: Float = -1f,
    ) : Opcode

    /**
     * Draws the bitmap at [bitmapIndex], scaled/positioned into the destination rect
     * `(left, top, right, bottom)`. When [srcLeft]/[srcTop]/[srcRight]/[srcBottom] are non-null
     * (only `Operations.DRAW_BITMAP_INT` carries them — plain `Operations.DRAW_BITMAP` always
     * draws the whole source image), only that sub-rectangle of the source bitmap is sampled,
     * still scaled/positioned into the same destination rect.
     */
    data class DrawBitmap(
        val bitmapIndex: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val srcLeft: Float? = null, val srcTop: Float? = null,
        val srcRight: Float? = null, val srcBottom: Float? = null,
    ) : Opcode

    // --- Interaction ---

    /**
     * Marks the rect `(left, top, right, bottom)` as a tap target bound to [actionId]. Resolving
     * [targetUrlStringIndex] against the document's string pool and dispatching a
     * [RemoteAction.Click] on hit-test is the responsibility of the composable integration layer
     * (Phase 3), not the parser.
     */
    data class ActionClick(
        val actionId: Int,
        val targetUrlStringIndex: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    ) : Opcode
}
