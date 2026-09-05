package com.example.remotecompose.model

/**
 * A single decoded instruction from the `.rc` opcode stream: a transform, a clip, a draw call, or
 * an interaction target.
 *
 * String and bitmap payloads are carried as pool indices (`stringIndex`, `bitmapIndex`) rather
 * than resolved values, since resolution requires the [com.example.remotecompose.parser.StringPool]
 * / [com.example.remotecompose.parser.BitmapPool] that live on the parsed
 * [com.example.remotecompose.model.RemoteDocument] alongside the opcode list — resolving eagerly
 * here would duplicate that data per opcode instead of sharing it.
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
     * @property stringIndex Index into the document's [com.example.remotecompose.parser.StringPool].
     * @property fontSize Font size in SP.
     * @property colorArgb Text color, packed ARGB (kept packed rather than a resolved `Color` so
     *   this data class stays a plain value type; the execution engine unpacks it when building
     *   a `TextStyle`).
     */
    data class DrawText(
        val stringIndex: Int,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val colorArgb: Int,
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

    /**
     * An opcode whose ID was not recognized by this version of the parser. Carries just enough
     * information to explain what was skipped and where, without attempting to interpret a
     * payload shape it doesn't know. See [RcOpcode]'s framing note on how skipping stays safe
     * regardless of the unknown opcode's actual payload length.
     */
    data class Unknown(val opcodeId: Int, val payloadLength: Int) : Opcode
}
