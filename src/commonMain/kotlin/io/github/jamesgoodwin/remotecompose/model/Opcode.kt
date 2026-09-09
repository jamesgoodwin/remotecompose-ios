package io.github.jamesgoodwin.remotecompose.model

/**
 * A single decoded instruction from the `.rc` opcode stream: a transform, a clip, a draw call, or
 * an interaction target.
 *
 * String and bitmap payloads are carried as pool ids (`stringIndex`, `bitmapIndex`) rather than
 * resolved values, since resolution requires the pools on the parsed [RemoteDocument] alongside
 * the opcode list — resolving eagerly here would duplicate that data per opcode.
 */
public sealed interface Opcode {

    // --- Matrix & coordinate transforms (§4.1) ---

    /** Pushes the current transform/clip state; paired with [MatrixRestore]. */
    public data object MatrixSave : Opcode

    /** Pops back to the state at the most recent unmatched [MatrixSave] *or* [SaveLayerAlpha]. */
    public data object MatrixRestore : Opcode

    /**
     * Pushes a new compositing layer with overall opacity [alpha] (`0f`..`1f`), so everything
     * drawn until the matching [MatrixRestore] is blended as one group instead of each draw call
     * fading independently — the real effect `MODIFIER_GRAPHICS_LAYER`'s `ALPHA` attribute has.
     * Uses a generous sentinel layer size rather than this container's real bounds, since this
     * renderer has no measure/layout pass to compute those from.
     */
    public data class SaveLayerAlpha(val alpha: Float) : Opcode

    /**
     * Draws everything up to the matching [LayerEffectsEnd] into a layer of its own, and applies
     * the graphics-layer properties that only a layer can carry.
     *
     * `SHADOW_ELEVATION` needs the content to exist as a layer before anything can be cast behind
     * it, and a rotation about X or Y needs one before it can be projected: a canvas transform is
     * affine and cannot foreshorten with distance. Both are properties the official Compose player
     * sets on `Modifier.graphicsLayer`, which is the same Compose mechanism reached another way.
     *
     * Emitted only for a component that asks for elevation or an out-of-plane rotation. A layer
     * that only scales, translates, or turns in the plane stays on the canvas, where it costs no
     * offscreen buffer.
     *
     * @property width the component's width, which with [height] and [cornerRadius] gives the
     *   outline a shadow is cast from.
     * @property cameraDistance `CAMERA_DISTANCE`: how far the viewer is from the plane, which is
     *   what turns a rotation about X or Y into a perspective one.
     */
    public data class LayerEffects(
        val width: Float,
        val height: Float,
        val cornerRadius: Float,
        val elevation: Float,
        val rotationX: Float,
        val rotationY: Float,
        val rotationZ: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val cameraDistance: Float?,
        val pivotX: Float,
        val pivotY: Float,
    ) : Opcode

    /** Closes the most recent unmatched [LayerEffects]. */
    public data object LayerEffectsEnd : Opcode

    /** Shifts the local coordinate origin by ([dx], [dy]), in DP. */
    public data class Translate(val dx: Float, val dy: Float) : Opcode

    /** Scales the coordinate space by ([sx], [sy]) about ([pivotX], [pivotY]). */
    public data class Scale(val sx: Float, val sy: Float, val pivotX: Float, val pivotY: Float) : Opcode

    /** Rotates the coordinate space by [degrees] about ([pivotX], [pivotY]). */
    public data class Rotate(val degrees: Float, val pivotX: Float, val pivotY: Float) : Opcode

    /**
     * Shears the coordinate space: `x' = x + skewX*y`, `y' = skewY*x + y` — the same convention
     * `android.graphics.Matrix.setSkew(kx, ky)` uses. Unlike [Scale]/[Rotate], the real
     * `MatrixSkew` operation carries no pivot field at all.
     */
    public data class Skew(val skewX: Float, val skewY: Float) : Opcode

    /** Intersects the current clip with an axis-aligned rectangle. */
    public data class ClipRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : Opcode

    /** Intersects the current clip with an arbitrary path, reconstructed from [commands]. */
    public data class ClipPath(val commands: List<PathCommand>) : Opcode

    // --- Draw instructions (§4.2) ---

    /** Draws an axis-aligned rectangle. */
    public data class DrawRect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a rectangle with elliptical corners of radius ([radiusX], [radiusY]). */
    public data class DrawRoundRect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val radiusX: Float, val radiusY: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a circle centered at ([centerX], [centerY]) with the given [radius]. */
    public data class DrawCircle(
        val centerX: Float, val centerY: Float, val radius: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws an arbitrary path reconstructed from [commands]. */
    public data class DrawPath(
        val commands: List<PathCommand>,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws a straight line segment from `(x1, y1)` to `(x2, y2)`. */
    public data class DrawLine(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val paint: PaintStyle,
    ) : Opcode

    /** Draws an ellipse inscribed in the rect `(left, top, right, bottom)`. */
    public data class DrawOval(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val paint: PaintStyle,
    ) : Opcode

    /**
     * Draws an arc of the ellipse inscribed in `(left, top, right, bottom)`, starting at
     * [startAngleDegrees] and sweeping [sweepAngleDegrees] clockwise. When [useCenter] is true the
     * arc is closed back to the ellipse's center (a pie/sector slice); when false it's just the
     * curved stroke/fill between the two arc endpoints.
     */
    public data class DrawArc(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val startAngleDegrees: Float, val sweepAngleDegrees: Float,
        val useCenter: Boolean,
        val paint: PaintStyle,
    ) : Opcode

    /**
     * Draws text anchored at ([x], [y]) with Android's `drawTextRun` semantics: [x] is the left
     * edge and [y] the **baseline**. [io.github.jamesgoodwin.remotecompose.text.TextAnchoring] turns that,
     * plus the optional pans, into a top-left corner once the text is measured.
     *
     * @property stringIndex Id into [RemoteDocument.strings].
     * @property paint The cumulative paint in effect when the text was issued: color, text size
     *   in document pixels, weight/italic/family, and any gradient shader.
     * @property substringStart When non-null (`DRAW_TEXT_RUN`), only `[substringStart,
     *   substringEnd)` of the string is drawn.
     * @property panX `DRAW_TEXT_ANCHOR` only: which point of the measured width sits at [x], from
     *   `-1` (left edge) through `0` (center) to `1` (right edge). Null or NaN means the left
     *   edge, as for every other text opcode.
     * @property panY Same for the vertical axis, per `DrawTextAnchored.getVerticalOffset`: `-1`
     *   puts the text's bottom at [y], `0` centers it, `1` puts its top at [y]. Null or NaN means
     *   [y] is the baseline.
     * @property baselineRelative `DrawTextAnchored.BASELINE_RELATIVE` flag: the vertical pan
     *   measures from the box's own center instead of from the baseline.
     */
    public data class DrawText(
        val stringIndex: Int,
        val x: Float,
        val y: Float,
        val paint: PaintStyle,
        val substringStart: Int? = null,
        val substringEnd: Int? = null,
        val panX: Float? = null,
        val panY: Float? = null,
        val baselineRelative: Boolean = false,
    ) : Opcode

    /**
     * Draws the bitmap at [bitmapIndex], scaled/positioned into the destination rect
     * `(left, top, right, bottom)`. When [srcLeft]/[srcTop]/[srcRight]/[srcBottom] are non-null
     * (only `Operations.DRAW_BITMAP_INT` carries them — plain `Operations.DRAW_BITMAP` always
     * draws the whole source image), only that sub-rectangle of the source bitmap is sampled,
     * still scaled/positioned into the same destination rect.
     */
    public data class DrawBitmap(
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
    public data class ActionClick(
        val actionId: Int,
        val targetUrlStringIndex: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    ) : Opcode
}
