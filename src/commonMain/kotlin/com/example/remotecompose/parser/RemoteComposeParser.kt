package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.parser.Operation as Op
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.text.EstimatedTextMetrics
import com.example.remotecompose.text.TextAnchoring
import com.example.remotecompose.text.TextMetricsProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parses the `androidx.compose.remote` wire format (`remote-core` 1.0.0-alpha18) as produced by
 * `RemoteComposeWriter`, into a flat [Opcode] list for
 * [com.example.remotecompose.engine.OpcodeExecutor].
 *
 * Format facts, from `WireBuffer` and each operation's `read()` in the published jar:
 * - Every record starts with one opcode byte (`Operations.<NAME>`) and has a fixed per-opcode
 *   field layout; there is no generic length prefix, so an opcode this parser does not handle
 *   is a hard failure ([RemoteComposeParseException]) rather than a skip.
 * - All integers and floats are big-endian; floats are raw IEEE-754 bits. Lengths and counts are
 *   4-byte ints. There are no variable-length integers.
 * - A float field may be a NaN-tagged reference into the float pool (`Utils.asNan(id)`); see
 *   `resolveFloat` inside [build].
 *
 * Two phases: [OperationReader] decodes the bytes into one [Operation] per record with no
 * evaluation, then [build] walks those operations once, evaluating every value-producing one
 * into pools and flattening layout containers into draw opcodes with a bounding-box heuristic.
 * The second phase is the known architectural gap versus the real player's per-frame
 * `RemoteContext` and measure/layout pass; `docs/PLAN.md` steps 5 and 6 replace it.
 */
object RemoteComposeParser {

    /**
     * `GraphicsLayerModifierOperation.ALPHA` (`= 11`) OR'd with the float-value tag bit
     * (`0x400`) — the tag [Op.ModifierGraphicsLayer] uses to mark an entry as the layer's
     * opacity, the one attribute this parser gives real effect to (see [Opcode.SaveLayerAlpha]).
     */
    private const val GRAPHICS_LAYER_ALPHA_TAG = 11 or 0x400

    // The rest of GraphicsLayerModifierOperation's float-valued attribute tags this parser now
    // also gives real effect to — source-confirmed (javap on the real
    // GraphicsLayerModifierOperation class) as SCALE_X=0, SCALE_Y=1, ROTATION_Z=4,
    // TRANSLATION_X=7, TRANSLATION_Y=8, each OR'd with the same `0x400` float-value tag bit as
    // [GRAPHICS_LAYER_ALPHA_TAG]. Unlike ALPHA (whose effect — a compositing layer — needs no
    // pivot), scale/rotation are applied about this container's own inferred content-bounds
    // center (this renderer has no measure pass to get a real layout box from, the same
    // approximation [Op.ModifierBackground] already makes) — so, unlike every other modifier's
    // immediate emission, they're deferred to this container's own `Op.ContainerEnd`, once that
    // bounding box is known; see [ScopeFrame.glScaleX] etc. and their use in `Op.ContainerEnd`.
    private const val GRAPHICS_LAYER_SCALE_X_TAG = 0 or 0x400
    private const val GRAPHICS_LAYER_SCALE_Y_TAG = 1 or 0x400
    private const val GRAPHICS_LAYER_ROTATION_Z_TAG = 4 or 0x400
    private const val GRAPHICS_LAYER_TRANSLATION_X_TAG = 7 or 0x400
    private const val GRAPHICS_LAYER_TRANSLATION_Y_TAG = 8 or 0x400

    // `GraphicsLayerModifierOperation.TRANSFORM_ORIGIN_X`/`_Y` (`= 5`/`= 6`, source-confirmed via
    // javap), each OR'd with the same `0x400` float-value tag bit. Standard Compose
    // `GraphicsLayerScope.transformOrigin` semantics (not something specific to remote-compose's
    // own wire format): a fraction of this layer's own size along each axis, defaulting to `0.5f`
    // (`TransformOrigin.Center`) when unset — generalizes the scale/rotation pivot [ScopeFrame
    // .glScaleX] etc. already compute from a hardcoded content-bounds *center* into a pivot at
    // `left + originX * width`, `top + originY * height` instead, so an explicit origin away from
    // center (e.g. `(0f, 0f)` for the top-left corner) really moves where a rotation/scale pivots
    // around, while an unset origin keeps the exact center-pivot behavior every existing
    // scale/rotation test already relies on.
    private const val GRAPHICS_LAYER_TRANSFORM_ORIGIN_X_TAG = 5 or 0x400
    private const val GRAPHICS_LAYER_TRANSFORM_ORIGIN_Y_TAG = 6 or 0x400

    // `GraphicsLayerModifierOperation.SHAPE`/`SHAPE_RADIUS` (`= 20`/`= 21`, source-confirmed via
    // javap) — SHAPE is int-valued (no `0x400` float bit: `0`=SHAPE_RECT, `1`=SHAPE_ROUND_RECT,
    // `2`=SHAPE_CIRCLE, same constants javap-confirmed on the same class), SHAPE_RADIUS is
    // float-valued. Standard Compose `GraphicsLayerScope.shape`/`.clip` semantics: a non-rect
    // shape clips this layer's own rendered content to it. This reduced wire API exposes no
    // separate boolean "clip" flag the way real Compose's full API does — SHAPE is the only way
    // this format can express clip intent at all, so a non-RECT value is trusted to mean "clip to
    // this shape" unconditionally. Applied at Op.ContainerEnd as an innermost ClipPath wrap
    // (nested *inside* the SCALE/ROTATION_Z/TRANSLATION transform-wrap above, since real Compose
    // clips a layer's own local content before transforming the whole clipped result) built from
    // the same quadratic-corner [roundedRectPath] approximation [Op.ModifierRoundedClipRect]
    // uses — all 4 corners at `SHAPE_RADIUS` for SHAPE_ROUND_RECT, at half this box's smaller
    // dimension (an approximately-circular rounded rect, `roundedRectPath` already clamps radii to
    // that same maximum) for SHAPE_CIRCLE.
    private const val GRAPHICS_LAYER_SHAPE_TAG = 20
    private const val GRAPHICS_LAYER_SHAPE_RADIUS_TAG = 21 or 0x400

    // `RowLayout`/`ColumnLayout`'s shared positioning-mode ordinals (source-confirmed identical in
    // both classes) — the raw ints `horizontalPositioning`/`verticalPositioning` carry. TOP/BOTTOM
    // are only meaningful on Row's verticalPositioning (cross axis); START/END are only meaningful
    // on Column's horizontalPositioning (cross axis); CENTER and the three SPACE_* modes apply to
    // either axis. See arrangeChildren's KDoc for how each is actually honored.
    private const val POS_START = 1
    private const val POS_CENTER = 2
    private const val POS_END = 3
    private const val POS_TOP = 4
    private const val POS_BOTTOM = 5
    private const val POS_SPACE_BETWEEN = 6
    private const val POS_SPACE_EVENLY = 7
    private const val POS_SPACE_AROUND = 8

    // `DimensionModifierOperation.Type` ordinals for the two modes where MODIFIER_WIDTH/HEIGHT's
    // value is a real target size in this renderer's own coordinate units, rather than a sizing
    // *strategy* (FILL/WRAP/WEIGHT/INTRINSIC_*) this parser has no layout pass to resolve.
    private const val DIMENSION_MODE_EXACT = 0
    private const val DIMENSION_MODE_WEIGHT = 3
    private const val DIMENSION_MODE_EXACT_DP = 6

    /**
     * Fallback font size for a `LAYOUT_TEXT` component whose own size field is an unresolved
     * variable reference. Canvas text (`drawTextAnchored` and friends) takes its size from the
     * cumulative paint instead — see [PaintState].
     */
    private const val DEFAULT_TEXT_SIZE_SP = 16f

    /**
     * Decodes [bytes] into a live [RemoteComposeDocument] whose [RemoteComposeDocument.frame]
     * produces the flattened opcodes for a moment in time.
     *
     * @param textMetrics Measures text for the layout heuristics. Pass
     *   [com.example.remotecompose.engine.ComposeTextMetrics] for real font metrics; the default
     *   is a font-free estimate, adequate for tests and headless use.
     * @throws RemoteComposeParseException on an unhandled opcode or a truncated record.
     */
    fun load(bytes: ByteArray, textMetrics: TextMetricsProvider = EstimatedTextMetrics): RemoteComposeDocument {
        val operations = OperationReader.readAll(bytes)
        val header = operations.filterIsInstance<Op.Header>().firstOrNull()?.let {
            Header(it.majorVersion, it.minorVersion, it.patchVersion, it.width, it.height, it.capabilities)
        } ?: Header(0, 0, 0, 0, 0, 0L)
        return RemoteComposeDocument(header, operations, RemoteContext(), textMetrics)
    }

    /** A static snapshot: [load] followed by the first frame at time zero. */
    fun parse(bytes: ByteArray, textMetrics: TextMetricsProvider = EstimatedTextMetrics): RemoteDocument =
        load(bytes, textMetrics).frame(0L)

    /**
     * Evaluates [operations] against [context] and flattens the result into draw opcodes; see the
     * class KDoc. Constants are applied only the first time (`context.inflated`), so a later
     * change to a pool value persists across frames; everything else is re-applied every frame.
     */
    internal fun build(operations: List<Op>, context: RemoteContext, textMetrics: TextMetricsProvider): List<Opcode> {
        // Cumulative paint, exactly as the real player's PaintContext keeps it: each PAINT_VALUES
        // bundle is a delta applied on top of the previous state, and every draw opcode captures
        // a snapshot of it. Saved on scope push and restored on CONTAINER_END, mirroring
        // Component.paint()'s savePaint()/restorePaint() around each component's own painting.
        val paint = PaintState()
        val textPool = context.texts
        val pathPool = context.paths
        val idListPool = context.idLists
        val bitmapPool = context.bitmaps
        val colorPool = context.colors
        val floatPool = context.floats
        val intPool = context.ints
        val booleanPool = context.booleans
        val longPool = context.longs
        val dataMapPool = context.dataMaps
        val opcodes = mutableListOf<Opcode>()

        /** A wire float that may be a NaN-tagged id, resolved through the context (see [RemoteContext.resolveFloat]). */
        fun resolveFloat(raw: Float): Float = context.resolveFloat(raw)

        // Shared by Op.TextSubtext/Op.TextTransform (real TextSubtext/TextTransform.apply()'s
        // own identical [start, start+len) / [start, end) — when len == -1f — String.substring()
        // logic, source-confirmed via javap on both). null (real byte-consumed-only fallback) when
        // either the source text-pool entry or start/len (already resolveFloat-resolved by the
        // caller) is unresolved.
        fun substringOf(src: String?, start: Float, len: Float): String? {
            if (src == null || start.isNaN() || len.isNaN()) return null
            val startIdx = start.toInt().coerceIn(0, src.length)
            val endIdx = if (len == -1f) src.length else (startIdx + len.toInt()).coerceIn(startIdx, src.length)
            return src.substring(startIdx, endIdx)
        }

        // Every real container/action-list scope (LAYOUT_BOX/COLUMN/ROW/etc's own scope, the
        // LAYOUT_CONTENT children scope, LAYOUT_CANVAS_CONTENT, and MODIFIER_CLICK/MULTI_CLICK/
        // TOUCH_*'s nested action lists) is closed by exactly one generic CONTAINER_END, and these
        // scopes nest strictly LIFO in the real byte stream. This stack lets a modifier that needs
        // real semantic effect (so far: MODIFIER_OFFSET, MODIFIER_VISIBILITY, MODIFIER_BACKGROUND)
        // push cleanup [Opcode]s — or, for MODIFIER_BACKGROUND, a pending fill color — onto its
        // *container's own* scope (always the top of this stack at the point the modifier is
        // parsed, since any nested action-list scope from an earlier modifier in the same list is
        // always fully opened-and-closed before the next modifier is written) so they fire/resolve
        // when that scope's matching CONTAINER_END is reached — without needing to build a real
        // component tree.
        // Regular draw opcodes are appended straight to [opcodes] the moment they're parsed, not
        // buffered per-scope — so a [ScopeFrame] only tracks (a) [startIndex], the [opcodes] size
        // at the moment this scope was pushed (everything from there to the current size when this
        // scope's CONTAINER_END fires is "this container's content", used by MODIFIER_BACKGROUND's
        // bounds inference and MODIFIER_COLUMN/ROW's child-arrangement below), and (b)
        // [cleanupOpcodes], ops queued by attachToTopScope to be appended *after* that content once
        // the scope closes (MODIFIER_OFFSET/VISIBILITY/GRAPHICS_LAYER's MatrixRestore).
        //
        // [parent] is whichever frame was on top of this stack when this frame was pushed — it's
        // how LAYOUT_COLUMN/LAYOUT_ROW discover their *direct* children for real arrangement: a
        // LAYOUT_CONTENT frame's own [layoutAxis]/[spacedBy] are set (from [pendingLayoutAxis]) only
        // when it directly follows a LAYOUT_COLUMN/LAYOUT_ROW open; then every frame whose [parent]
        // is that LAYOUT_CONTENT frame — i.e. every direct child container, since a bare draw call
        // never pushes a frame at all — registers its own finished [startIndex, opcodes.size) range
        // into the LAYOUT_CONTENT frame's [childRanges] as it closes. A child that's itself a
        // container two levels deep (e.g. a Box's own outer scope, whose *inner* LAYOUT_CONTENT
        // frame is what real grandchildren attach to) still registers correctly, because [parent]
        // is captured at push time — the outer Box frame's parent is the Column's LAYOUT_CONTENT
        // frame, while the Box's *inner* content frame's parent is the Box's own outer frame, not
        // the Column's. This naturally recurses: a nested Column's own CONTAINER_END arranges its
        // own children (rewriting their coordinates in place) before its enclosing Box (and in turn
        // that Box's enclosing Column) ever inspects its bounding box.
        class ScopeFrame(val startIndex: Int, val parent: ScopeFrame?, val savedPaint: PaintState) {
            val cleanupOpcodes = mutableListOf<Opcode>()
            var backgroundColor: Color? = null
            // Set by Op.ModifierBackground's real (javap-confirmed) trailing shapeType int:
            // 0=RECTANGLE (default), 1=CIRCLE — same two values and same DrawOval-vs-DrawRect
            // choice Op.ModifierBorder's borderShapeType already gets at Op.ContainerEnd.
            var backgroundShapeType: Int = 0
            var layoutAxis: Char? = null // 'V' (LAYOUT_COLUMN) or 'H' (LAYOUT_ROW); null otherwise
            var spacedBy: Float = 0f
            // RowLayout/ColumnLayout.{START,CENTER,END,TOP,BOTTOM,SPACE_BETWEEN,SPACE_EVENLY,
            // SPACE_AROUND} ordinals (see arrangeChildren's KDoc) — only meaningful when
            // layoutAxis != null; default POS_START matches this parser's original always-packed
            // behavior when a document doesn't set these explicitly.
            var horizontalPositioning: Int = POS_START
            var verticalPositioning: Int = POS_START
            // Set (from pendingFlowMaxItemsPerLine) only for a LAYOUT_FLOW content frame; null for
            // plain LAYOUT_COLUMN/LAYOUT_ROW/LAYOUT_COLLAPSIBLE_* content frames, which always pack
            // onto a single line regardless of child count.
            var flowMaxItemsPerLine: Int? = null
            // Set (from pendingFlowMaxLines) only for a LAYOUT_FLOW content frame; null everywhere
            // else. arrangeChildren hides (Component.Visibility.GONE, via an empty ClipRect — the
            // same mechanism Op.ModifierVisibility uses) every child whose computed row index
            // would reach this line count, matching the real FlowLayout's own measure logic.
            var flowMaxLines: Int? = null
            // Set (from pendingIsCollapsible) only for a LAYOUT_COLLAPSIBLE_COLUMN/ROW content
            // frame; false for plain LAYOUT_COLUMN/LAYOUT_ROW/LAYOUT_FLOW content frames.
            // arrangeChildren hides children by ascending MODIFIER_COLLAPSIBLE_PRIORITY (lowest
            // priority collapses first) once their cumulative main-axis size would exceed this
            // frame's own parent's declared extent — matching CollapsibleRowLayout/
            // CollapsibleColumnLayout's real computeVisibleChildren logic (source-confirmed via
            // javap), only possible when that extent is actually known (an explicit width()/
            // height() on the CollapsibleColumn/Row itself), the same "no measure pass" gate
            // MODIFIER_WIDTH_IN/HEIGHT_IN's real effect already needs.
            var isCollapsible: Boolean = false
            // Set (from pendingIsStateLayout) only for a LAYOUT_STATE content frame; propagated
            // independently of layoutAxis/pendingLayoutAxis, since LAYOUT_STATE needs no position-
            // arrangement handoff at all (unlike Column/Row/Flow/Collapsible, its children keep
            // their own document-authored position — only their *visibility* is real). Real
            // StateLayout (source-confirmed via javap on the real class): `currentLayoutIndex`
            // defaults to `0` and `inflate()` immediately calls `hideLayoutsOtherThan(0)`, so the
            // real, honest default render (before any runtime state-change event this parser has
            // no live state to evaluate) shows only the *first* child, hiding every other one the
            // same way [Component.Visibility.GONE] already does elsewhere.
            var isStateLayout: Boolean = false
            // Set (from pendingIsBoxAlignment) only for a LAYOUT_BOX/LAYOUT_FIT_BOX content frame.
            // arrangeChildren's per-child 2D-alignment pass (real BoxLayout.internalLayoutMeasure
            // behavior, source-confirmed via javap — see Op.LayoutBox's own KDoc) only runs when
            // this is set, so a Custom/Canvas/Root/State content frame (also axis == null, also
            // registers children for MODIFIER_ZINDEX) never gets it by accident.
            var isBoxAlignment: Boolean = false
            // Set (from pendingIsTextLayout et al.) only for a LAYOUT_TEXT content frame — the
            // inner, always-empty frame Op.LayoutContent pushes for it (see Op.LayoutText's own
            // KDoc); consumed at this same frame's own Op.ContainerEnd to synthesize the one real
            // Opcode.DrawText this leaf renders as, since it carries no children opcodes of its own
            // to react to the way every other content frame here does.
            var isTextLayout: Boolean = false
            var textId: Int = 0
            var textColorArgb: Int = 0
            var textFontSize: Float = DEFAULT_TEXT_SIZE_SP
            var textAlign: Int = 1 // TEXT_ALIGN_LEFT
            val childRanges = mutableListOf<IntArray>() // only populated/consumed when layoutAxis != null
            // Parallel to childRanges (same index correspondence) — each entry is the
            // corresponding child's own Op.ModifierZIndex value (default 0f), read by
            // arrangeChildren to reorder sibling paint order after positioning.
            val childZIndices = mutableListOf<Float>()
            // Parallel to childRanges — each entry is the corresponding child's own
            // MODIFIER_COLLAPSIBLE_PRIORITY value already resolved against *this* (the parent
            // container's) axis (Float.MAX_VALUE, meaning "never collapse", when the child either
            // carries no such modifier or one whose own orientation doesn't match this container's
            // axis — see CollapsiblePriority.getPriority's own orientation filter, source-confirmed
            // via javap). Only consulted by arrangeChildren when [isCollapsible].
            val childCollapsiblePriorities = mutableListOf<Float>()
            // Parallel to childRanges — each entry is the corresponding child's own real
            // Modifier.weight() value (null if it has none), already resolved against *this*
            // container's own axis (a widthWeight only applies in a 'H' — Row-axis — parent, a
            // heightWeight only in a 'V' one, mirroring how childCollapsiblePriorities resolves
            // orientation). Consulted by arrangeChildren to distribute this container's own
            // remaining main-axis space (only known when its declared extent is) proportionally,
            // the same real `Modifier.weight()` concept real Compose's own Row/Column give a
            // weighted child.
            val childWeights = mutableListOf<Float?>()
            // Set by Op.ModifierZIndex on this frame itself; read when *this* frame registers
            // into its own parent's childZIndices at Op.ContainerEnd.
            var zIndex: Float = 0f
            // Set by Op.ModifierCollapsiblePriority on this frame itself (its own priority/
            // orientation, not a child's) — read the same way [zIndex] is, when *this* frame
            // registers into its own parent's childCollapsiblePriorities at Op.ContainerEnd.
            var collapsiblePriority: Float = Float.MAX_VALUE
            var collapsiblePriorityOrientation: Int? = null
            // Set by Op.ModifierWidth/Op.ModifierHeight with a WEIGHT mode directly on *this*
            // frame (its own weight, not a child's) — read the same way [zIndex] is, when *this*
            // frame registers into its own parent's childWeights at Op.ContainerEnd.
            var widthWeight: Float? = null
            var heightWeight: Float? = null
            // Captured from a MODIFIER_WIDTH/MODIFIER_HEIGHT with an EXACT(_DP) mode directly on
            // *this* frame (i.e. this container's own declared size, not a child's) — read by
            // arrangeChildren on the LAYOUT_CONTENT frame this one is the parent of, since only a
            // real declared container extent (not just "however much space the children take up")
            // makes CENTER/END/SPACE_* along the main axis mean anything.
            var explicitWidthPx: Float? = null
            var explicitHeightPx: Float? = null
            // Set by Op.ModifierGraphicsLayer when this container's attribute list carries a
            // SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/TRANSLATION_Y entry; consumed at this
            // frame's own Op.ContainerEnd, once contentBounds() can resolve a real pivot for
            // scale/rotation from this container's now-finished children.
            var glScaleX: Float? = null
            var glScaleY: Float? = null
            var glRotationZ: Float? = null
            var glTranslationX: Float? = null
            var glTranslationY: Float? = null
            // Set by Op.ModifierGraphicsLayer's TRANSFORM_ORIGIN_X/_Y — a fraction (0f..1f) of
            // this layer's own size, real Compose's standard `GraphicsLayerScope.transformOrigin`
            // semantics; null (unset) means the default `TransformOrigin.Center` fraction (0.5f),
            // the exact pivot every scale/rotation use before this was hardcoded to.
            var glTransformOriginX: Float? = null
            var glTransformOriginY: Float? = null
            // Set by Op.ModifierGraphicsLayer's SHAPE/SHAPE_RADIUS. null (or SHAPE_RECT/0)
            // means no real clip; SHAPE_ROUND_RECT(1)/SHAPE_CIRCLE(2) clip this layer's own
            // content at Op.ContainerEnd to a roundedRectPath built from this box's own inferred
            // bounds, nested innermost of the SCALE/ROTATION_Z/TRANSLATION transform-wrap.
            var glShapeType: Int? = null
            var glShapeRadius: Float = 0f
            // Set by Op.LayoutImage on the frame it pushes for itself (a leaf, so this frame
            // never gets any content of its own before its own Op.ContainerEnd) — consumed there
            // together with explicitWidthPx/explicitHeightPx from a MODIFIER_WIDTH/HEIGHT on the
            // same modifier, since this opcode carries no position/size fields of its own.
            var imageBitmapId: Int? = null
            var imageAlpha: Float = 1f
            // Set by Op.LayoutImage; SCALE_FIT(4)/SCALE_CROP(5) get a real letterbox/overscan
            // effect at Op.ContainerEnd (see imageScaleDstRect), every other value falls back to
            // the original stretch-to-fill-the-box behavior.
            var imageScaleType: Int = 6 // SCALE_FILL_BOUNDS default: matches this parser's original stretch behavior
            // Set by Op.ModifierPadding; consumed by Op.ContainerEnd's MODIFIER_BACKGROUND
            // handling to expand the inferred background rect back out to cover this container's
            // full (un-padded) box — contentBounds() alone would only ever measure the *inset*
            // children, since their own draw calls already carry the padding's runtime translate.
            var paddingLeft: Float = 0f
            var paddingTop: Float = 0f
            var paddingRight: Float = 0f
            var paddingBottom: Float = 0f
            // Set by Op.ModifierBorder when the color is a literal r/g/b/a (not a color-pool
            // reference this parser doesn't resolve); consumed at Op.ContainerEnd to draw a real
            // stroked outline around this container's own (padding-expanded) inferred bounds.
            var borderColor: Color? = null
            var borderWidth: Float = 0f
            var borderRoundedCorner: Float = 0f
            var borderShapeType: Int = 0
            // Set by Op.ModifierClipRect/Op.ModifierRoundedClipRect — neither carries its own
            // rect bounds on the wire at all (real Compose always clips to this container's own
            // measured box), so a real effect is only possible when this frame also has an
            // explicit MODIFIER_WIDTH/HEIGHT smaller than its natural content — see
            // Op.ContainerEnd's clip handling.
            var hasClipRect: Boolean = false
            // Set by Op.ModifierRoundedClipRect (LTR corner names, source-confirmed via javap
            // on the real RoundedRectShape constructor): non-zero only when this frame's clip
            // came from a RoundedRectShape rather than a plain RectShape, telling
            // Op.ContainerEnd's clip-wrap to build a quadratic-corner rounded rect ClipPath
            // instead of a sharp-cornered ClipRect.
            var cornerTopStart: Float = 0f
            var cornerTopEnd: Float = 0f
            var cornerBottomStart: Float = 0f
            var cornerBottomEnd: Float = 0f
            // Set by Op.ModifierWidthIn/Op.ModifierHeightIn; resolved against this frame's own
            // contentBounds() at Op.ContainerEnd (real Compose constrains to a *measured* size
            // this parser doesn't have) into explicitWidthPx/explicitHeightPx when natural content
            // actually falls outside the range — narrower than [min, max] raises it, wider clips
            // it (widthIn/heightIn imply their own clip, no separate MODIFIER_CLIP_RECT needed).
            var widthInMin: Float? = null
            var widthInMax: Float? = null
            var heightInMin: Float? = null
            var heightInMax: Float? = null
            // Set directly by Op.LoopStart on the frame it pushes for itself (no modifiers ever
            // come between it and its own children — unlike a Component-based container, real
            // LoopOperation isn't a Component/ModifierOperation host at all — so no pending-var
            // handoff to a later LAYOUT_CONTENT is needed the way Op.LayoutBox's isBoxAlignment
            // needs one). Non-null (real, static, non-NaN-tagged) from/step/until only when this
            // loop's own bounds are literal values, not variable references this parser has no
            // expression evaluator to resolve — consumed at this frame's own Op.ContainerEnd to
            // literally repeat its one authored copy of the loop body real Compose's own
            // `RemoteContext`-driven runtime loop would otherwise re-`apply()` N times, the same
            // "real effect via static unrolling, honest fallback otherwise" approach
            // MODIFIER_COLLAPSIBLE_PRIORITY's declared-size gate already established.
            var isLoop: Boolean = false
            var loopFrom: Float? = null
            var loopStep: Float? = null
            var loopUntil: Float? = null
        }
        val scopeStack = mutableListOf<ScopeFrame>()
        fun pushScope() {
            scopeStack.add(ScopeFrame(opcodes.size, scopeStack.lastOrNull(), paint.copy()))
        }
        fun attachToTopScope(op: Opcode) {
            scopeStack.lastOrNull()?.cleanupOpcodes?.add(op)
        }
        // Set by Op.LayoutColumn/Op.LayoutRow, consumed by the very next Op.LayoutContent (the
        // real byte stream always writes a container's own modifiers — none of which open a
        // LAYOUT_CONTENT themselves — between the two, so nothing else can consume this first).
        var pendingLayoutAxis: Char? = null
        var pendingSpacedBy = 0f
        var pendingHorizontalPositioning = POS_START
        var pendingVerticalPositioning = POS_START
        // Set by Op.LayoutFlow alongside pendingLayoutAxis ('H' — FlowLayout extends RowLayout,
        // source-confirmed via javap); non-null tells arrangeChildren to wrap into multiple lines
        // instead of packing every child onto one, capping each line at this many children.
        var pendingFlowMaxItemsPerLine: Int? = null
        // Set by Op.LayoutFlow alongside pendingFlowMaxItemsPerLine; non-null caps how many rows
        // arrangeChildren actually shows, hiding (not just leaving unpositioned) every child past
        // that row count — see ScopeFrame.flowMaxLines's KDoc.
        var pendingFlowMaxLines: Int? = null
        // Set by Op.LayoutCollapsibleColumn/ROW alongside pendingLayoutAxis; tells the very next
        // Op.LayoutContent frame to set ScopeFrame.isCollapsible, same handoff shape as
        // pendingFlowMaxItemsPerLine/pendingFlowMaxLines.
        var pendingIsCollapsible = false
        // Set by Op.LayoutState; tells the very next Op.LayoutContent frame to set
        // ScopeFrame.isStateLayout. Propagated independently of pendingLayoutAxis (LAYOUT_STATE
        // never sets that — see ScopeFrame.isStateLayout's KDoc).
        var pendingIsStateLayout = false
        // Set by Op.LayoutBox/Op.LayoutFitBox; tells the very next Op.LayoutContent frame to
        // set ScopeFrame.isBoxAlignment. Propagated independently of pendingLayoutAxis (Box has no
        // main/cross axis — see Op.LayoutBox's own KDoc) — kept distinct from other axis-less
        // containers (Custom/Canvas/Root/State) so arrangeChildren's Box-alignment pass only
        // applies to a real Box/FitBox, never accidentally to one of those.
        var pendingIsBoxAlignment = false
        // Set by Op.LayoutText on the outer (modifier-carrying) frame it pushes for itself;
        // consumed by the very next Op.LayoutContent, same handoff shape as pendingIsBoxAlignment
        // — this leaf's own content frame (not the outer one) is where Op.ContainerEnd actually
        // synthesizes the real Opcode.DrawText, so these need to reach that inner frame.
        var pendingIsTextLayout = false
        var pendingTextId = 0
        var pendingTextColorArgb = 0
        var pendingTextFontSize = DEFAULT_TEXT_SIZE_SP
        var pendingTextAlign = 1 // TEXT_ALIGN_LEFT

        /**
         * This renderer has no measure/layout pass, so a container's "bounds" for
         * [Op.ModifierBackground] are inferred as the tight bounding box of every draw call
         * inside it — an approximation that ignores padding/insets around the content, but a real
         * visual improvement over not drawing a background at all. Returns null if [ops] contains
         * no boundable draw opcode.
         */
        fun contentBounds(ops: List<Opcode>): FloatArray? {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            fun expand(l: Float, t: Float, r: Float, b: Float) {
                if (l < left) left = l
                if (t < top) top = t
                if (r > right) right = r
                if (b > bottom) bottom = b
            }
            fun expandPoint(x: Float, y: Float) = expand(x, y, x, y)
            // A draw opcode's own fields are always in *local* coordinates — arrangeChildren (and
            // MODIFIER_OFFSET before it) move content by wrapping it in MatrixSave/Translate/.../
            // MatrixRestore rather than rewriting those fields, so a range being measured here can
            // easily contain an already-arranged nested Column/Row whose children only look right
            // once that accumulated translation is added back in. Track it with a plain offset
            // stack — SaveLayerAlpha pushes one same as MatrixSave, since both are popped by a
            // MatrixRestore; Scale/Rotate/ClipRect don't affect a translation-only offset and are
            // deliberately left unhandled (this parser's own arrangement code never emits them).
            var offsetX = 0f
            var offsetY = 0f
            val offsetStack = mutableListOf<FloatArray>()
            for (op in ops) {
                when (op) {
                    Opcode.MatrixSave, is Opcode.SaveLayerAlpha -> offsetStack.add(floatArrayOf(offsetX, offsetY))
                    Opcode.MatrixRestore -> offsetStack.removeLastOrNull()?.let {
                        offsetX = it[0]
                        offsetY = it[1]
                    }
                    is Opcode.Translate -> {
                        offsetX += op.dx
                        offsetY += op.dy
                    }
                    is Opcode.DrawRect -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawRoundRect -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawOval -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawArc -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawBitmap -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawCircle -> expand(
                        op.centerX - op.radius + offsetX, op.centerY - op.radius + offsetY,
                        op.centerX + op.radius + offsetX, op.centerY + op.radius + offsetY,
                    )
                    is Opcode.DrawLine -> expand(
                        minOf(op.x1, op.x2) + offsetX, minOf(op.y1, op.y2) + offsetY,
                        maxOf(op.x1, op.x2) + offsetX, maxOf(op.y1, op.y2) + offsetY,
                    )
                    is Opcode.DrawPath -> for (command in op.commands) when (command) {
                        is PathCommand.MoveTo -> expandPoint(command.x + offsetX, command.y + offsetY)
                        is PathCommand.LineTo -> expandPoint(command.x + offsetX, command.y + offsetY)
                        is PathCommand.QuadraticTo -> {
                            expandPoint(command.x1 + offsetX, command.y1 + offsetY)
                            expandPoint(command.x2 + offsetX, command.y2 + offsetY)
                        }
                        is PathCommand.CubicTo -> {
                            expandPoint(command.x1 + offsetX, command.y1 + offsetY)
                            expandPoint(command.x2 + offsetX, command.y2 + offsetY)
                            expandPoint(command.x3 + offsetX, command.y3 + offsetY)
                        }
                        PathCommand.Close -> Unit
                    }
                    is Opcode.DrawText -> {
                        // No real glyph metrics are available at parse time (text measurement
                        // needs a platform font resolver this parser doesn't have), so width is a
                        // rough average-character-advance estimate — good enough for a child to
                        // participate in real Column/Row arrangement without being ignored
                        // entirely, not a claim of pixel-accurate text bounds.
                        val text = textPool[op.stringIndex] ?: ""
                        val metrics = textMetrics.measure(text, op.paint)
                        val (left, top) = TextAnchoring.topLeft(op, metrics)
                        expand(
                            left + offsetX, top + offsetY,
                            left + metrics.width + offsetX, top + metrics.height + offsetY,
                        )
                    }
                    else -> Unit
                }
            }
            return if (left.isFinite()) floatArrayOf(left, top, right, bottom) else null
        }

        /**
         * Returns a copy of [op] with every absolute coordinate shifted by ([dx], [dy]) — used by
         * [arrangeChildren] as the reliable alternative to wrapping a child's range in a
         * MatrixSave/Translate/MatrixRestore triple when that range contains [Opcode.DrawText] (see
         * that call site's KDoc for why). Path/list-shaped payloads ([Opcode.DrawPath]) are shifted
         * point-by-point; opcodes with no absolute position (transform/clip control)
         * pass through unchanged, since a relative op's own delta stays correct under an outer shift.
         */
        fun shiftOpcode(op: Opcode, dx: Float, dy: Float): Opcode = when (op) {
            is Opcode.DrawRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawRoundRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawOval -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawArc -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawBitmap -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawCircle -> op.copy(centerX = op.centerX + dx, centerY = op.centerY + dy)
            is Opcode.DrawLine -> op.copy(x1 = op.x1 + dx, y1 = op.y1 + dy, x2 = op.x2 + dx, y2 = op.y2 + dy)
            is Opcode.ClipRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.ActionClick -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawText -> op.copy(x = op.x + dx, y = op.y + dy)
            is Opcode.DrawPath -> op.copy(commands = op.commands.map { command ->
                when (command) {
                    is PathCommand.MoveTo -> command.copy(x = command.x + dx, y = command.y + dy)
                    is PathCommand.LineTo -> command.copy(x = command.x + dx, y = command.y + dy)
                    is PathCommand.QuadraticTo -> command.copy(
                        x1 = command.x1 + dx, y1 = command.y1 + dy, x2 = command.x2 + dx, y2 = command.y2 + dy,
                    )
                    is PathCommand.CubicTo -> command.copy(
                        x1 = command.x1 + dx, y1 = command.y1 + dy,
                        x2 = command.x2 + dx, y2 = command.y2 + dy,
                        x3 = command.x3 + dx, y3 = command.y3 + dy,
                    )
                    PathCommand.Close -> command
                }
            })
            else -> op
        }

        /**
         * Returns `(leadingGap, betweenGap)` for [mode] given [extraSpace] (container main-axis
         * extent minus the children's own packed-together size; 0 or negative when the container
         * has no known extent bigger than its content, in which case every mode below correctly
         * degenerates to plain start-packing since there's no slack to distribute) and [count]
         * children. [POS_TOP]/[POS_BOTTOM] have no main-axis meaning and fall back to start/end.
         */
        fun mainAxisGaps(mode: Int, extraSpace: Float, count: Int): FloatArray {
            val extra = extraSpace.coerceAtLeast(0f)
            return when (mode) {
                POS_CENTER -> floatArrayOf(extra / 2f, 0f)
                POS_END, POS_BOTTOM -> floatArrayOf(extra, 0f)
                POS_SPACE_BETWEEN -> floatArrayOf(0f, if (count > 1) extra / (count - 1) else 0f)
                POS_SPACE_EVENLY -> (extra / (count + 1)).let { floatArrayOf(it, it) }
                POS_SPACE_AROUND -> (extra / count).let { floatArrayOf(it / 2f, it) }
                else -> floatArrayOf(0f, 0f) // POS_START/POS_TOP, or unrecognized
            }
        }

        /** Cross-axis offset of a child of [childExtent] within a container of [containerExtent]. */
        fun crossAxisOffset(mode: Int, containerExtent: Float, childExtent: Float): Float = when (mode) {
            POS_CENTER -> (containerExtent - childExtent) / 2f
            POS_END, POS_BOTTOM -> containerExtent - childExtent
            else -> 0f // POS_START/POS_TOP, or unrecognized
        }

        /**
         * Real arrangement for a LAYOUT_COLUMN/LAYOUT_ROW's direct children, run once — from
         * [Op.ContainerEnd] — when [frame] (a LAYOUT_CONTENT frame carrying [ScopeFrame.layoutAxis])
         * closes and every entry in [ScopeFrame.childRanges] is final. Each child's natural size
         * comes from [contentBounds] over its own already-authored (absolute-coordinate) content —
         * there's still no real measure pass computing a size *before* a child is drawn.
         *
         * Both axes are real, within an honest limit: this parser has no notion of the container's
         * own extent unless the document explicitly gave it one via a MODIFIER_WIDTH/HEIGHT with an
         * EXACT(_DP) mode (captured as [ScopeFrame.explicitWidthPx]/[explicitHeightPx] on [frame]'s
         * *parent*, the container's own outer scope — see that field's KDoc). Without one, this
         * falls back to real "wrap content" semantics — the container's extent is exactly what its
         * children need — under which [POS_CENTER]/[POS_END]/the `SPACE_*` modes have no slack to
         * work with and correctly collapse to plain start-packing, same as real Compose would do.
         * Cross-axis alignment always has a meaningful reference even with no declared size: the
         * *tallest* (Row) or *widest* (Column) child, exactly how Compose sizes an unconstrained
         * Row/Column's cross axis by default.
         *
         * A child is moved into place the same way [Op.ModifierOffset] moves content: a
         * MatrixSave/Translate pair spliced immediately before its content, MatrixRestore right
         * after. Splicing is done in *reverse* document order specifically so that an earlier
         * child's still-unprocessed [start, end) range is never shifted by a later child's
         * insertions (every insertion for child K happens at or after K's own start index, which is
         * always ≥ any not-yet-processed, earlier child's end index).
         */
        fun arrangeChildren(frame: ScopeFrame) {
            val axis = frame.layoutAxis
            // Captured before sorting: childZIndices is parallel to frame.childRanges' original
            // (document) order, keyed here by each child's unique start index since sortedBy below
            // produces a new list that no longer corresponds positionally to childZIndices.
            val zIndexByStart = frame.childRanges.indices.associate { i ->
                frame.childRanges[i][0] to frame.childZIndices.getOrElse(i) { 0f }
            }
            val children = frame.childRanges.sortedBy { it[0] }
            if (children.isEmpty()) return
            // Position-arrangement only applies to a real LAYOUT_COLUMN/LAYOUT_ROW content frame
            // (axis != null); a Box's own content frame has no axis, so its children keep their
            // own document-authored position — but MODIFIER_ZINDEX's paint-order reordering below
            // still applies to *any* frame with registered children, Box included.
            if (axis != null) {
                val naturalBounds = children.map { range -> contentBounds(opcodes.subList(range[0], range[1])) }
                val anchorIndex = naturalBounds.indexOfFirst { it != null }
                if (anchorIndex != -1) {
                    val anchor = naturalBounds[anchorIndex]!!
                    val anchorMainStart = if (axis == 'V') anchor[1] else anchor[0] // top (Column) or left (Row)
                    val crossAnchor = if (axis == 'V') anchor[0] else anchor[1] // left (Column) or top (Row)

                    val mainMode = if (axis == 'V') frame.verticalPositioning else frame.horizontalPositioning
                    val crossMode = if (axis == 'V') frame.horizontalPositioning else frame.verticalPositioning

                    val mainSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[3] - it[1] else it[2] - it[0] } ?: 0f }.toMutableList()
                    val crossSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[2] - it[0] else it[3] - it[1] } ?: 0f }
                    val declaredMainExtent = if (axis == 'V') frame.parent?.explicitHeightPx else frame.parent?.explicitWidthPx
                    val declaredCrossExtent = if (axis == 'V') frame.parent?.explicitWidthPx else frame.parent?.explicitHeightPx

                    // Real Modifier.weight() effect: only possible once this container's own
                    // declared main-axis extent is known (same "no measure pass" gate every other
                    // real-but-approximate effect here needs) and at least one child actually
                    // carries a weight. Real Compose distributes the *remaining* space (this
                    // extent minus every non-weighted child's own natural size and every
                    // in-between spacedBy gap) proportionally among the weighted children — mirrors
                    // real Row/Column's own weight semantics, not a from-scratch approximation.
                    // weightScaleFactors is consumed at the very end, after position-shifting, to
                    // stretch a weighted child's own already-shifted content from its natural size
                    // up (or down) to its real weighted share via a Scale wrap pivoted at its own
                    // now-final leading edge — the same MatrixSave/Scale/MatrixRestore mechanism
                    // MODIFIER_GRAPHICS_LAYER's SCALE_X/Y already uses, just per-child here instead
                    // of around a whole container's content.
                    val weightScaleFactors = arrayOfNulls<Float>(children.size)
                    if (declaredMainExtent != null) {
                        val weights = children.indices.map { i -> frame.childWeights.getOrElse(i) { null } }
                        val totalWeight = weights.filterNotNull().sum()
                        if (totalWeight > 0f) {
                            val fixedSize = children.indices.filter { weights[it] == null }.sumOf { mainSizes[it].toDouble() }.toFloat()
                            val totalGaps = frame.spacedBy * (children.size - 1).coerceAtLeast(0)
                            val remaining = (declaredMainExtent - fixedSize - totalGaps).coerceAtLeast(0f)
                            for (i in children.indices) {
                                val w = weights[i] ?: continue
                                val weightedSize = remaining * (w / totalWeight)
                                val naturalSize = mainSizes[i]
                                if (naturalSize > 0f) weightScaleFactors[i] = weightedSize / naturalSize
                                mainSizes[i] = weightedSize
                            }
                        }
                    }

                    // LAYOUT_FLOW (flowMaxItemsPerLine != null) wraps into multiple "lines" of at
                    // most that many children each, each line packed/aligned exactly the way a
                    // plain Row's single line already was, then stacked along the cross axis with
                    // spacedBy between them. Every other container is always exactly one line
                    // (perLineCap == children.size), so this loop runs its body once with the
                    // *same* packedMainSize/crossExtent the original single-line code computed —
                    // behavior-preserving for Column/Row/CollapsibleColumn/Row.
                    val perLineCap = frame.flowMaxItemsPerLine ?: children.size
                    val deltas = arrayOfNulls<FloatArray>(children.size)
                    // LAYOUT_FLOW's maxLinesInCrossAxis (flowMaxLines != null) or
                    // isCollapsible's real priority-based collapsing: every index added here is
                    // skipped from packing/positioning below and wrapped in an empty ClipRect at
                    // the very end, matching real Compose's Component.Visibility.GONE (no space
                    // reserved) for both mechanisms.
                    val hiddenChildIndices = mutableSetOf<Int>()
                    // LAYOUT_COLLAPSIBLE_COLUMN/ROW's real collapsing (source-confirmed via javap
                    // on CollapsibleRowLayout/CollapsibleColumnLayout's computeVisibleChildren):
                    // only possible once this frame's own parent's declared main-axis extent is
                    // known — real Compose's own available-space constraint this parser otherwise
                    // has no measure pass to provide. Children are visited highest-priority-first
                    // (childCollapsiblePriorities' Float.MAX_VALUE default sorts first, meaning
                    // "no modifier" never collapses); each is kept only if its own size still fits
                    // the *remaining* budget — real Compose's own per-child comparison ignores
                    // spacedBy entirely here (confirmed via javap: it compares running-total plus
                    // this child's raw width against the available extent, with no gap term), so
                    // this mirrors that exactly rather than a more "correct"-looking accounting.
                    if (frame.isCollapsible && declaredMainExtent != null) {
                        val priorities = children.indices.map { i -> frame.childCollapsiblePriorities.getOrElse(i) { Float.MAX_VALUE } }
                        val priorityOrder = children.indices.sortedByDescending { priorities[it] }
                        var used = 0f
                        for (i in priorityOrder) {
                            val size = mainSizes[i]
                            if (used + size > declaredMainExtent) {
                                hiddenChildIndices.add(i)
                            } else {
                                used += size
                            }
                        }
                    }
                    var lineCrossCursor = crossAnchor
                    var lineStart = 0
                    var lineIndex = 0
                    while (lineStart < children.size) {
                        val lineEnd = (lineStart + perLineCap).coerceAtMost(children.size)
                        val lineIndices = (lineStart until lineEnd).filter { it !in hiddenChildIndices }
                        val lineCount = lineIndices.size
                        val maxLines = frame.flowMaxLines
                        if (maxLines != null && lineIndex >= maxLines) hiddenChildIndices.addAll(lineIndices)
                        val lineMainSizes = lineIndices.map { mainSizes[it] }
                        val lineCrossSizes = lineIndices.map { crossSizes[it] }
                        val packedMainSize = lineMainSizes.sum() + frame.spacedBy * (lineCount - 1).coerceAtLeast(0)
                        val mainExtent = declaredMainExtent ?: packedMainSize
                        val crossExtent = declaredCrossExtent ?: (lineCrossSizes.maxOrNull() ?: 0f)

                        val (leadingGap, betweenGap) = mainAxisGaps(mainMode, mainExtent - packedMainSize, lineCount)
                        var cursorMain = anchorMainStart + leadingGap
                        for (i in lineIndices) {
                            val bounds = naturalBounds[i] ?: continue
                            val crossOffset = crossAxisOffset(crossMode, crossExtent, crossSizes[i])
                            val dx: Float
                            val dy: Float
                            if (axis == 'V') {
                                dx = (lineCrossCursor + crossOffset) - bounds[0]
                                dy = cursorMain - bounds[1]
                            } else {
                                dx = cursorMain - bounds[0]
                                dy = (lineCrossCursor + crossOffset) - bounds[1]
                            }
                            deltas[i] = floatArrayOf(dx, dy)
                            cursorMain += mainSizes[i] + frame.spacedBy + betweenGap
                        }
                        lineCrossCursor += (lineCrossSizes.maxOrNull() ?: 0f) + frame.spacedBy
                        lineStart = lineEnd
                        lineIndex++
                    }
                    for (i in children.indices.reversed()) {
                        val range = children[i]
                        if (i in hiddenChildIndices) {
                            // Same empty-ClipRect GONE mechanism Op.ModifierVisibility uses —
                            // this child's own position doesn't matter once it renders nothing.
                            opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                            opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.ClipRect(0f, 0f, 0f, 0f)))
                            continue
                        }
                        val delta = deltas[i]
                        if (delta != null && (delta[0] != 0f || delta[1] != 0f)) {
                            // Rewrite every opcode's own coordinates directly instead of wrapping the range in
                            // MatrixSave/Translate/MatrixRestore. A real on-device Compose Canvas target was
                            // confirmed (via a minimal, isolated repro) to corrupt DrawText positioning — even
                            // *unwrapped* DrawText elsewhere in the same render — after two or more repeated
                            // canvas.save()/translate()/restore() cycles from sibling arranged children, the
                            // exact shape every stat card's icon-then-value-then-label triplet has. Since a
                            // shape's own position is just as easy to rewrite directly as text's, arrangeChildren
                            // never emits real Matrix ops at all — sidestepping the bug at its root rather than
                            // only where it was first noticed. Nested Translate/MatrixSave/MatrixRestore inside
                            // this range (from an inner, already-arranged nested Column/Row) pass through
                            // shiftOpcode unchanged, since a relative delta stays correct under an outer shift.
                            for (j in range[0] until range[1]) {
                                opcodes[j] = shiftOpcode(opcodes[j], delta[0], delta[1])
                            }
                        }
                        // Real Modifier.weight(): stretch this now-repositioned child's content
                        // from its natural main-axis size up (or down) to its real weighted share,
                        // via a Scale wrap pivoted at its own now-final leading edge (so that edge
                        // stays put and only the trailing edge moves) — cross-axis scale stays 1
                        // (unaffected), matching real Compose's weight only ever redistributing
                        // the *main*-axis extent.
                        val scaleFactor = weightScaleFactors[i]
                        if (scaleFactor != null && delta != null) {
                            val bounds = naturalBounds[i]!!
                            val pivotX = bounds[0] + delta[0]
                            val pivotY = bounds[1] + delta[1]
                            val sx = if (axis == 'V') 1f else scaleFactor
                            val sy = if (axis == 'V') scaleFactor else 1f
                            opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                            opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.Scale(sx, sy, pivotX, pivotY)))
                        }
                    }
                }
            }

            // LAYOUT_BOX/LAYOUT_FIT_BOX's real per-child 2D alignment (source-confirmed via javap
            // on BoxLayout.internalLayoutMeasure — see Op.LayoutBox's own KDoc): unlike Column/
            // Row's sequential main-axis stacking, every child is independently positioned within
            // the box's own bounds (the union of every child's own natural bounds, or this box's
            // own declared width/height when explicit — same anchor-at-natural-bounds,
            // extent-from-declared-size split Column/Row's own arrangement already uses) via the
            // same crossAxisOffset helper, reused verbatim for *both* axes here since Box's
            // horizontalPositioning/verticalPositioning share Column/Row's own ordinals exactly.
            if (frame.isBoxAlignment) {
                contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { boxBounds ->
                    val boxWidth = frame.parent?.explicitWidthPx ?: (boxBounds[2] - boxBounds[0])
                    val boxHeight = frame.parent?.explicitHeightPx ?: (boxBounds[3] - boxBounds[1])
                    for (i in children.indices.reversed()) {
                        val range = children[i]
                        val childBounds = contentBounds(opcodes.subList(range[0], range[1])) ?: continue
                        val childWidth = childBounds[2] - childBounds[0]
                        val childHeight = childBounds[3] - childBounds[1]
                        val dx = (boxBounds[0] + crossAxisOffset(frame.horizontalPositioning, boxWidth, childWidth)) - childBounds[0]
                        val dy = (boxBounds[1] + crossAxisOffset(frame.verticalPositioning, boxHeight, childHeight)) - childBounds[1]
                        if (dx == 0f && dy == 0f) continue
                        for (j in range[0] until range[1]) {
                            opcodes[j] = shiftOpcode(opcodes[j], dx, dy)
                        }
                    }
                }
            }

            // LAYOUT_STATE's real default: only the first child (index 0, matching the real
            // StateLayout's own currentLayoutIndex default) stays visible; every other child is
            // hidden via the same empty-ClipRect GONE mechanism used above, in place at its own
            // document-authored position (StateLayout has no axis, so no repositioning applies).
            if (frame.isStateLayout) {
                for (i in children.indices.reversed()) {
                    if (i == 0) continue
                    val range = children[i]
                    opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                    opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.ClipRect(0f, 0f, 0f, 0f)))
                }
            }

            // MODIFIER_ZINDEX: reorder sibling *paint* order (not position, already fixed above)
            // by z-index — a stable sort, so same-z-index siblings keep their original relative
            // (document) order, matching real Compose's tie-breaking. Only safe to do as a blind
            // remove-and-reinsert over [overallStart, overallEnd) when every child range *exactly
            // tiles* that span with no gaps — guards against a bare, unwrapped draw call between
            // children (which pushes no scope, so never registers in childRanges) silently
            // getting dropped; skips the reorder entirely rather than risk losing content.
            val zIndices = children.map { zIndexByStart[it[0]] ?: 0f }
            if (zIndices.any { it != 0f }) {
                val overallStart = children.minOf { it[0] }
                val overallEnd = children.maxOf { it[1] }
                val totalChildLength = children.sumOf { it[1] - it[0] }
                if (totalChildLength == overallEnd - overallStart) {
                    val paintOrder = children.indices.sortedBy { zIndices[it] }
                    val blocks = children.map { range -> opcodes.subList(range[0], range[1]).toList() }
                    val reordered = paintOrder.flatMap { blocks[it] }
                    for (j in overallEnd - 1 downTo overallStart) opcodes.removeAt(j)
                    opcodes.addAll(overallStart, reordered)
                }
            }
        }

        for (op in operations) {
            if (context.inflated && op.isConstant()) continue
            when (op) {
                // Decoded by OperationReader so the stream stays aligned, but with no effect in
                // this evaluator: their semantics are runtime state, actions or animation, which
                // need the per-frame context of docs/PLAN.md step 5.
                is Op.Skip, is Op.Rem, is Op.RootContentDescription, is Op.DebugMessage,
                is Op.AnimationSpec, is Op.HapticFeedback, is Op.Theme, is Op.RootContentBehavior,
                is Op.HostAction, is Op.ModifierAlignBy, is Op.ModifierMarquee, is Op.ModifierScroll,
                is Op.ModifierRipple, is Op.ModifierDrawContent, is Op.TouchExpression,
                is Op.ValueIntegerChange, is Op.ValueStringChange, is Op.ValueFloatChange,
                is Op.ValueIntegerExpressionChange, is Op.ValueFloatExpressionChange -> Unit
                is Op.Header -> Unit // captured by load()

                is Op.FloatExpression -> context.applyFloatExpression(op)

                is Op.TextData -> {
                    val id = op.id
                    textPool[id] = op.text
                }

                is Op.TextSubtext -> {
                    val textId = op.textId
                    val srcId = op.srcId
                    val start = resolveFloat(op.start)
                    val len = resolveFloat(op.len)
                    substringOf(textPool[srcId], start, len)?.let { textPool[textId] = it }
                }

                is Op.TextTransform -> {
                    val textId = op.textId
                    val srcId = op.srcId
                    val start = resolveFloat(op.start)
                    val len = resolveFloat(op.len)
                    val operation = op.operation
                    substringOf(textPool[srcId], start, len)?.let { sub ->
                        textPool[textId] = when (operation) {
                            1 -> sub.lowercase()
                            2 -> sub.uppercase()
                            3 -> sub.trim()
                            // capitalizeWords: title-cases the first char of every word, copying
                            // every other char through unchanged — ported char-for-char from the
                            // real capitalizeWords() bytecode's own atStartOfWord-flag loop.
                            4 -> buildString {
                                var atStartOfWord = true
                                for (c in sub) {
                                    when {
                                        c.isWhitespace() -> { atStartOfWord = true; append(c) }
                                        atStartOfWord -> { append(c.uppercaseChar()); atStartOfWord = false }
                                        else -> append(c)
                                    }
                                }
                            }
                            // capitalizeFirstWord: title-cases only the first non-whitespace char
                            // of the whole string — ported from the real capitalizeFirstWord()
                            // bytecode.
                            5 -> {
                                val i = sub.indexOfFirst { !it.isWhitespace() }
                                if (i == -1) sub else sub.substring(0, i) + sub[i].uppercaseChar() + sub.substring(i + 1)
                            }
                            else -> sub
                        }
                    }
                }

                is Op.PaintData -> {
                    val words = op.words
                    PaintBundleDecoder.apply(
                        words = words,
                        state = paint,
                        resolveFloat = ::resolveFloat,
                        colorById = { colorPool[it] },
                        textById = { textPool[it] },
                    )
                }

                is Op.DrawRect -> {
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    opcodes += Opcode.DrawRect(
                        left, top, right, bottom,
                        paint.snapshot(),
                    )
                }

                is Op.DrawCircle -> {
                    val centerX = resolveFloat(op.centerX)
                    val centerY = resolveFloat(op.centerY)
                    val radius = resolveFloat(op.radius)
                    opcodes += Opcode.DrawCircle(
                        centerX, centerY, radius,
                        paint.snapshot(),
                    )
                }

                is Op.DrawRoundRect -> {
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    val radiusX = resolveFloat(op.radiusX)
                    val radiusY = resolveFloat(op.radiusY)
                    opcodes += Opcode.DrawRoundRect(
                        left, top, right, bottom, radiusX, radiusY,
                        paint.snapshot(),
                    )
                }

                is Op.DrawTextRun -> {
                    val textId = op.textId
                    val start = op.start
                    val end = op.end
                    val x = resolveFloat(op.x)
                    val y = resolveFloat(op.y)
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = x,
                        y = y,
                        paint = paint.snapshot(),
                        substringStart = start,
                        substringEnd = end,
                    )
                }

                is Op.DrawTextAnchored -> {
                    val textId = op.textId
                    val x = resolveFloat(op.x)
                    val y = resolveFloat(op.y)
                    val panX = resolveFloat(op.panX)
                    val panY = resolveFloat(op.panY)
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = x,
                        y = y,
                        paint = paint.snapshot(),
                        panX = panX,
                        panY = panY,
                        baselineRelative = op.flags and 8 != 0, // DrawTextAnchored.BASELINE_RELATIVE
                    )
                }

                is Op.DrawTextOnCircle -> {
                    val textId = op.textId
                    val centerX = resolveFloat(op.centerX)
                    val centerY = resolveFloat(op.centerY)
                    val radius = resolveFloat(op.radius)
                    val startAngleDegrees = resolveFloat(op.startAngleDegrees)
                    val startAngleRadians = startAngleDegrees * (PI.toFloat() / 180f)
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = centerX + radius * cos(startAngleRadians),
                        y = centerY + radius * sin(startAngleRadians),
                        paint = paint.snapshot(),
                    )
                }

                is Op.DrawTextOnPath -> {
                    val textId = op.textId
                    val pathId = op.pathId
                    val vOffset = op.vOffset // written before hOffset — see KDoc above
                    val hOffset = op.hOffset
                    val anchor = pathPool[pathId]?.filterIsInstance<PathCommand.MoveTo>()?.firstOrNull()
                    if (anchor != null) {
                        opcodes += Opcode.DrawText(
                            stringIndex = textId,
                            x = anchor.x + hOffset,
                            y = anchor.y + vOffset,
                            paint = paint.snapshot(),
                        )
                    }
                }

                is Op.DrawLine -> {
                    val x1 = resolveFloat(op.x1)
                    val y1 = resolveFloat(op.y1)
                    val x2 = resolveFloat(op.x2)
                    val y2 = resolveFloat(op.y2)
                    opcodes += Opcode.DrawLine(
                        x1, y1, x2, y2,
                        paint.snapshot().copy(style = PaintStyleKind.STROKE),
                    )
                }

                is Op.DrawOval -> {
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    opcodes += Opcode.DrawOval(
                        left, top, right, bottom,
                        paint.snapshot(),
                    )
                }

                is Op.DrawArc, is Op.DrawSector -> {
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    val startAngle = resolveFloat(op.startAngle)
                    val sweepAngle = resolveFloat(op.sweepAngle)
                    opcodes += Opcode.DrawArc(
                        left, top, right, bottom, startAngle, sweepAngle,
                        useCenter = op is Op.DrawSector,
                        paint = paint.snapshot(),
                    )
                }

                is Op.PathData -> {
                    val pathId = op.pathId
                    pathPool[pathId] = op.commands
                }

                is Op.PathCreate -> {
                    val pathId = op.pathId
                    val startX = resolveFloat(op.startX)
                    val startY = resolveFloat(op.startY)
                    pathPool[pathId] = listOf(PathCommand.MoveTo(startX, startY))
                }

                is Op.PathAdd -> {
                    val pathId = op.pathId
                    val appended = op.commands
                    pathPool[pathId] = (pathPool[pathId] ?: emptyList()) + appended
                }

                is Op.PathTween -> {
                    val outId = op.outId
                    val pathId1 = op.pathId1
                    val pathId2 = op.pathId2
                    val tween = resolveFloat(op.tween)
                    lerpPath(pathPool[pathId1], pathPool[pathId2], tween)?.let { pathPool[outId] = it }
                }

                is Op.MatrixFromPath -> {
                    val pathId = op.pathId
                    val fraction = resolveFloat(op.fraction)
                    val vOffset = resolveFloat(op.vOffset)
                    val flags = op.flags
                    val posTan = pathPool[pathId]?.let { pointAndTangentAlongPath(it, fraction) }
                    if (posTan != null) {
                        val (px, py, tx, ty) = posTan
                        val len = sqrt(tx * tx + ty * ty).takeIf { it > 0f } ?: 1f
                        val perpX = -ty / len * vOffset
                        val perpY = tx / len * vOffset
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.Translate(px + perpX, py + perpY)
                        if (flags and 2 != 0) { // TANGENT_MATRIX_FLAG
                            opcodes += Opcode.Rotate(atan2(ty, tx) * 180f / PI.toFloat(), 0f, 0f)
                        }
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                is Op.DrawTweenPath -> {
                    val path1Id = op.path1Id
                    val path2Id = op.path2Id
                    val tween = resolveFloat(op.tween)
                    val start = resolveFloat(op.start)
                    val stop = resolveFloat(op.stop)
                    val lerped = lerpPath(pathPool[path1Id], pathPool[path2Id], tween)
                    if (lerped != null) {
                        val trimmed = trimPath(lerped, start, stop)
                        if (trimmed.isNotEmpty()) {
                            opcodes += Opcode.DrawPath(trimmed, paint.snapshot())
                        }
                    }
                }

                is Op.PathCombine -> {
                    val outId = op.outId
                    val pathId1 = op.pathId1
                    val pathId2 = op.pathId2
                    val operation = op.operation
                    val path1 = pathPool[pathId1]
                    val path2 = pathPool[pathId2]
                    if (operation == 1 && path1 != null && path2 != null) { // OP_INTERSECT only
                        val subject = flattenPathSegments(path1).map { floatArrayOf(it[0], it[1]) }
                        val clip = flattenPathSegments(path2).map { floatArrayOf(it[0], it[1]) }
                        val result = sutherlandHodgmanIntersect(subject, clip)
                        if (result.size >= 3) {
                            pathPool[outId] = listOf(PathCommand.MoveTo(result[0][0], result[0][1])) +
                                result.drop(1).map { PathCommand.LineTo(it[0], it[1]) } +
                                listOf(PathCommand.Close)
                        }
                    }
                }

                is Op.DrawPath -> {
                    val pathId = op.pathId
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "DrawPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.DrawPath(commands, paint.snapshot())
                }

                is Op.ClipPath -> {
                    val pathId = op.pathId
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "ClipPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.ClipPath(commands)
                }

                is Op.BitmapData -> {
                    val bitmapId = op.bitmapId
                    bitmapPool[bitmapId] = op.bytes
                }

                is Op.DrawBitmap -> {
                    val bitmapId = op.bitmapId
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    opcodes += Opcode.DrawBitmap(bitmapId, left, top, right, bottom)
                }

                is Op.DrawBitmapInt -> {
                    val bitmapId = op.bitmapId
                    val srcLeft = op.srcLeft.toFloat()
                    val srcTop = op.srcTop.toFloat()
                    val srcRight = op.srcRight.toFloat()
                    val srcBottom = op.srcBottom.toFloat()
                    val dstLeft = op.dstLeft.toFloat()
                    val dstTop = op.dstTop.toFloat()
                    val dstRight = op.dstRight.toFloat()
                    val dstBottom = op.dstBottom.toFloat()
                    opcodes += Opcode.DrawBitmap(
                        bitmapId, dstLeft, dstTop, dstRight, dstBottom,
                        srcLeft, srcTop, srcRight, srcBottom,
                    )
                }

                is Op.DrawBitmapScaled -> {
                    val bitmapId = op.bitmapId
                    val srcLeft = resolveFloat(op.srcLeft)
                    val srcTop = resolveFloat(op.srcTop)
                    val srcRight = resolveFloat(op.srcRight)
                    val srcBottom = resolveFloat(op.srcBottom)
                    val dstLeft = resolveFloat(op.dstLeft)
                    val dstTop = resolveFloat(op.dstTop)
                    val dstRight = resolveFloat(op.dstRight)
                    val dstBottom = resolveFloat(op.dstBottom)
                    val scaleType = op.scaleType
                    val srcWidth = (srcRight - srcLeft).toInt()
                    val srcHeight = (srcBottom - srcTop).toInt()
                    val realScaleTypes = scaleType == 0 || scaleType == 1 || scaleType == 4 || scaleType == 5
                    val dst = if (realScaleTypes) {
                        imageScaleDstRect(scaleType, srcWidth, srcHeight, dstLeft, dstTop, dstRight, dstBottom)
                    } else {
                        floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
                    }
                    opcodes += Opcode.MatrixSave
                    opcodes += Opcode.ClipRect(dstLeft, dstTop, dstRight, dstBottom)
                    opcodes += Opcode.DrawBitmap(
                        bitmapId, dst[0], dst[1], dst[2], dst[3],
                        srcLeft, srcTop, srcRight, srcBottom,
                    )
                    opcodes += Opcode.MatrixRestore
                }

                is Op.ClickArea -> {
                    val actionId = op.actionId
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    val metadataTextId = op.metadataTextId
                    opcodes += Opcode.ActionClick(actionId, metadataTextId, left, top, right, bottom)
                }

                is Op.LayoutColumn, is Op.LayoutRow -> {
                    val horizontalPositioning = op.horizontalPositioning
                    val verticalPositioning = op.verticalPositioning
                    val spacedBy = op.spacedBy
                    pushScope() // this container's own scope — closed by its outermost CONTAINER_END
                    // Consumed by this container's own LAYOUT_CONTENT next, so its content frame
                    // (where the real children live) knows to arrange them — see arrangeChildren.
                    pendingLayoutAxis = if (op is Op.LayoutColumn) 'V' else 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                }

                is Op.LayoutCollapsibleColumn, is Op.LayoutCollapsibleRow -> {
                    val horizontalPositioning = op.horizontalPositioning
                    val verticalPositioning = op.verticalPositioning
                    val spacedBy = op.spacedBy
                    pushScope() // this container's own scope — closed by its outermost CONTAINER_END
                    // Identical shape to Op.LayoutColumn/Op.LayoutRow's own fields, and — since
                    // Op.LayoutContent is a single generic children-marker shared by every
                    // container type, not a Column/Row-specific one — the exact same
                    // pendingLayoutAxis handoff gives these real arrangement too, with no changes
                    // needed to arrangeChildren or Op.LayoutContent itself.
                    pendingLayoutAxis = if (op is Op.LayoutCollapsibleColumn) 'V' else 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingIsCollapsible = true
                }

                is Op.LayoutFlow -> {
                    val horizontalPositioning = op.horizontalPositioning
                    val verticalPositioning = op.verticalPositioning
                    val spacedBy = op.spacedBy
                    val maxItemsInMainAxis = op.maxItemsInMainAxis
                    val maxLinesInCrossAxis = op.maxLinesInCrossAxis
                    pushScope()
                    // FlowLayout extends RowLayout (source-confirmed via javap): the main axis is
                    // always horizontal, wrapping to a new line after maxItemsInMainAxis children —
                    // see arrangeChildren's flowMaxItemsPerLine handling for the actual wrapping.
                    pendingLayoutAxis = 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingFlowMaxItemsPerLine = maxItemsInMainAxis.takeIf { it > 0 && it < Int.MAX_VALUE }
                    // Real effect (javap-confirmed on FlowLayout's own measure logic): once a
                    // child's row index reaches maxLinesInCrossAxis, real Compose marks it
                    // Component.Visibility.GONE (no space reserved) rather than adding another
                    // row — see arrangeChildren's flowMaxLines handling for the hide mechanism.
                    pendingFlowMaxLines = maxLinesInCrossAxis.takeIf { it > 0 && it < Int.MAX_VALUE }
                }

                is Op.LayoutBox, is Op.LayoutFitBox -> {
                    val horizontalPositioning = op.horizontalPositioning
                    val verticalPositioning = op.verticalPositioning
                    pushScope()
                    // Consumed by this container's own LAYOUT_CONTENT next, same handoff shape as
                    // LAYOUT_COLUMN/LAYOUT_ROW's own positioning — but Box has no main/cross axis
                    // (pendingLayoutAxis is deliberately left null), so arrangeChildren's *separate*
                    // per-child 2D-alignment pass applies these instead of the sequential-stacking
                    // packing loop Column/Row use — see its own KDoc for BoxLayout's real algorithm
                    // (source-confirmed via javap).
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingIsBoxAlignment = true
                }

                is Op.LayoutText -> {
                    val textId = op.textId
                    val color = op.color
                    val fontSize = resolveFloat(op.fontSize)
                    val textAlign = op.textAlign and 0xFFFF // packed; see Op.LayoutText's KDoc
                    pushScope()
                    pendingIsTextLayout = true
                    pendingTextId = textId
                    pendingTextColorArgb = color
                    pendingTextFontSize = fontSize
                    pendingTextAlign = textAlign
                }

                is Op.LayoutRoot -> {
                    pushScope() // closed by this container's single CONTAINER_END
                }

                is Op.CanvasOperations -> pushScope() // no payload — closed by a single CONTAINER_END

                is Op.TextLength -> {
                    val lengthId = op.lengthId
                    val textId = op.textId
                    floatPool[lengthId] = (textPool[textId]?.length ?: 0).toFloat()
                }

                is Op.IdList -> {
                    val id = op.id
                    idListPool[id] = op.ids
                }

                is Op.TextLookup -> {
                    val textId = op.textId
                    val dataSetId = op.dataSetId
                    val index = resolveFloat(op.index)
                    if (!index.isNaN()) {
                        idListPool[dataSetId]?.getOrNull(index.toInt())?.let { srcId ->
                            textPool[srcId]?.let { textPool[textId] = it }
                        }
                    }
                }

                is Op.TextLookupInt -> {
                    val textId = op.textId
                    val dataSetId = op.dataSetId
                    val indexRefId = op.indexRefId
                    intPool[indexRefId]?.let { index ->
                        idListPool[dataSetId]?.getOrNull(index)?.let { srcId ->
                            textPool[srcId]?.let { textPool[textId] = it }
                        }
                    }
                }

                is Op.TextMerge -> {
                    val textId = op.textId
                    val srcId1 = op.srcId1
                    val srcId2 = op.srcId2
                    val left = textPool[srcId1] ?: ""
                    val right = textPool[srcId2] ?: ""
                    textPool[textId] = left + right
                }

                is Op.ColorExpression -> {
                    val id = op.id
                    val modeAlpha = op.modeAlpha
                    val mode = modeAlpha and 0xFF
                    val alpha = (modeAlpha ushr 16) and 0xFF
                    val word1 = op.word1
                    val word2 = op.word2
                    val word3 = op.word3
                    val computed: Int? = when (mode) {
                        0, 1, 2, 3 -> {
                            val c1 = if (mode and 1 != 0) colorPool[word1]?.toArgb() else word1
                            val c2 = if (mode and 2 != 0) colorPool[word2]?.toArgb() else word2
                            val tween = resolveFloat(Float.fromBits(word3))
                            if (c1 != null && c2 != null && !tween.isNaN()) {
                                interpolateColorArgb(c1, c2, tween)
                            } else {
                                null
                            }
                        }

                        4 -> {
                            val hue = resolveFloat(Float.fromBits(word1))
                            val sat = resolveFloat(Float.fromBits(word2))
                            val value = resolveFloat(Float.fromBits(word3))
                            if (!hue.isNaN() && !sat.isNaN() && !value.isNaN()) {
                                (alpha shl 24) or (hsvToRgbArgb(hue, sat, value) and 0xFFFFFF)
                            } else {
                                null
                            }
                        }

                        else -> null // ARGB_MODE(5)/IDARGB_MODE(6) — left unresolved
                    }
                    if (computed != null) colorPool[id] = Color(computed)
                }

                is Op.IdLookup -> {
                    val intId = op.intId
                    val dataSetId = op.dataSetId
                    val index = resolveFloat(op.index)
                    if (!index.isNaN()) {
                        idListPool[dataSetId]?.getOrNull(index.toInt())?.let { intPool[intId] = it }
                    }
                }

                is Op.IntegerExpression -> {
                    val id = op.id
                    val mask = op.mask
                    val values = op.values
                    val count = values.size
                    val resolved = IntArray(count)
                    val isOperator = BooleanArray(count)
                    var allResolved = true
                    for (i in 0 until count) {
                        val bitSet = (mask ushr i) and 1 != 0
                        val v = values[i]
                        if (bitSet && v < 65536) {
                            val iv = intPool[v]
                            if (iv != null) resolved[i] = iv else allResolved = false
                        } else {
                            resolved[i] = v
                            isOperator[i] = bitSet && v >= 65536
                        }
                    }
                    if (allResolved) {
                        val stack = IntArray(count)
                        var sp = -1
                        var valid = true
                        for (i in 0 until count) {
                            if (isOperator[i]) {
                                val newSp = evalIntegerOp(stack, sp, resolved[i])
                                if (newSp == null) {
                                    valid = false
                                    break
                                }
                                sp = newSp
                            } else {
                                sp++
                                stack[sp] = resolved[i]
                            }
                        }
                        if (valid && sp >= 0) intPool[id] = stack[sp]
                    }
                }

                is Op.TextFromFloat -> {
                    val textId = op.textId
                    val value = resolveFloat(op.value)
                    val flags = op.flags
                    if (!value.isNaN() && flags and 0x1000 != 0) {
                        textPool[textId] = value.toString()
                    }
                }

                is Op.DataMapIds -> {
                    val mapId = op.mapId
                    val entries = op.entries
                    dataMapPool[mapId] = entries
                }

                is Op.DataMapLookup -> {
                    val id = op.id
                    val dataMapId = op.dataMapId
                    val stringId = op.stringId
                    val key = textPool[stringId]
                    val entry = dataMapPool[dataMapId]?.firstOrNull { it.name == key }
                    if (entry != null) {
                        val (_, type, valueId) = entry
                        when (type) {
                            0 -> textPool[valueId]?.let { textPool[id] = it }
                            1 -> intPool[valueId]?.let { intPool[id] = it }
                            2 -> floatPool[valueId]?.let { floatPool[id] = it }
                            3 -> longPool[valueId]?.let { intPool[id] = it.toInt() }
                            4 -> booleanPool[valueId]?.let { intPool[id] = if (it) 1 else 0 }
                        }
                    }
                }

                is Op.LoopStart -> {
                    val from = resolveFloat(op.from)
                    val step = resolveFloat(op.step)
                    val until = resolveFloat(op.until)
                    pushScope() // no LAYOUT_CONTENT marker — closed by a single CONTAINER_END
                    scopeStack.last().isLoop = true
                    scopeStack.last().loopFrom = from.takeUnless { it.isNaN() }
                    scopeStack.last().loopStep = step.takeUnless { it.isNaN() }
                    scopeStack.last().loopUntil = until.takeUnless { it.isNaN() }
                }

                is Op.LayoutState -> {
                    // changes this parser has no live state to evaluate; the real default render
                    // (currentLayoutIndex=0, see ScopeFrame.isStateLayout's KDoc) needs none.
                    pushScope()
                    pendingIsStateLayout = true
                }

                is Op.LayoutContent, is Op.LayoutCanvasContent -> {
                    pushScope() // the children scope itself — this is where real children attach
                    // Assigned unconditionally (not gated on axis != null): a Box's own
                    // horizontalPositioning/verticalPositioning (from Op.LayoutBox) need to reach
                    // this frame too, for arrangeChildren's per-child 2D-alignment pass — see
                    // Op.LayoutBox's own KDoc.
                    scopeStack.last().horizontalPositioning = pendingHorizontalPositioning
                    scopeStack.last().verticalPositioning = pendingVerticalPositioning
                    scopeStack.last().isBoxAlignment = pendingIsBoxAlignment
                    pendingHorizontalPositioning = POS_START
                    pendingVerticalPositioning = POS_START
                    pendingIsBoxAlignment = false
                    scopeStack.last().isTextLayout = pendingIsTextLayout
                    scopeStack.last().textId = pendingTextId
                    scopeStack.last().textColorArgb = pendingTextColorArgb
                    scopeStack.last().textFontSize = pendingTextFontSize
                    scopeStack.last().textAlign = pendingTextAlign
                    pendingIsTextLayout = false
                    val axis = pendingLayoutAxis
                    if (axis != null) {
                        scopeStack.last().layoutAxis = axis
                        scopeStack.last().spacedBy = pendingSpacedBy
                        scopeStack.last().flowMaxItemsPerLine = pendingFlowMaxItemsPerLine
                        scopeStack.last().flowMaxLines = pendingFlowMaxLines
                        scopeStack.last().isCollapsible = pendingIsCollapsible
                        pendingLayoutAxis = null
                        pendingFlowMaxItemsPerLine = null
                        pendingFlowMaxLines = null
                        pendingIsCollapsible = false
                    }
                    scopeStack.last().isStateLayout = pendingIsStateLayout
                    pendingIsStateLayout = false
                }

                is Op.LayoutCanvas -> {
                    pushScope()
                }

                is Op.LayoutCustom -> {
                    pushScope()
                }

                is Op.LayoutImage -> {
                    val bitmapId = op.bitmapId
                    val scaleType = op.scaleType
                    val alpha = op.alpha
                    pushScope() // a leaf — no LAYOUT_CONTENT, just its own single CONTAINER_END
                    scopeStack.last().imageBitmapId = bitmapId
                    scopeStack.last().imageAlpha = alpha
                    scopeStack.last().imageScaleType = scaleType
                }

                is Op.ModifierWidth, is Op.ModifierHeight -> {
                    val mode = op.mode
                    val value = resolveFloat(op.value)
                    val frame = scopeStack.lastOrNull()
                    // Only a real target size (not a sizing *strategy* like FILL/WRAP this parser
                    // has no layout pass to resolve) is useful to arrangeChildren's main-axis
                    // CENTER/END/SPACE_* modes — see ScopeFrame.explicitWidthPx's KDoc.
                    if (mode == DIMENSION_MODE_EXACT || mode == DIMENSION_MODE_EXACT_DP) {
                        if (op is Op.ModifierWidth) frame?.explicitWidthPx = value
                        else frame?.explicitHeightPx = value
                    } else if (mode == DIMENSION_MODE_WEIGHT) {
                        // A real weight value (not a sizing strategy) — see ScopeFrame.widthWeight
                        // /heightWeight's KDoc and arrangeChildren's real effect for it.
                        if (op is Op.ModifierWidth) frame?.widthWeight = value
                        else frame?.heightWeight = value
                    }
                }

                is Op.ContainerEnd -> {
                    // Close this scope: if it carried a MODIFIER_BACKGROUND, insert an inferred
                    // background DrawRect *before* this container's own content (everything
                    // appended to [opcodes] since [ScopeFrame.startIndex]); if it's a LAYOUT_COLUMN/
                    // LAYOUT_ROW content frame, arrange its now-final children in place; then append
                    // this scope's deferred cleanup (e.g. a MatrixRestore queued by MODIFIER_OFFSET/
                    // MODIFIER_VISIBILITY/MODIFIER_GRAPHICS_LAYER) right after that content, closing
                    // whatever that modifier opened around it; and finally, if this scope's own
                    // parent is itself a LAYOUT_COLUMN/LAYOUT_ROW content frame, register this now-
                    // finished [startIndex, opcodes.size) range as one of *its* children.
                    val frame = scopeStack.removeLastOrNull()
                    if (frame != null) {
                        paint.restoreFrom(frame.savedPaint)
                        val bg = frame.backgroundColor
                        if (bg != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val left = bounds[0] - frame.paddingLeft
                                val top = bounds[1] - frame.paddingTop
                                val right = bounds[2] + frame.paddingRight
                                val bottom = bounds[3] + frame.paddingBottom
                                val paint = PaintStyle(bg, PaintStyleKind.FILL)
                                opcodes.add(
                                    frame.startIndex,
                                    // shapeType 1 (CIRCLE, javap-confirmed on the real
                                    // BackgroundModifierOperation.paint()) draws a filled oval
                                    // inscribed in the box instead of a rect — the same
                                    // shapeType-gated shape choice Op.ModifierBorder's stroke
                                    // already makes below.
                                    if (frame.backgroundShapeType == 1) {
                                        Opcode.DrawOval(left, top, right, bottom, paint)
                                    } else {
                                        Opcode.DrawRect(left, top, right, bottom, paint)
                                    },
                                )
                            }
                        }
                        // Always called (not gated on frame.layoutAxis): a Box's own inner
                        // content frame has no layoutAxis, so position-arrangement inside it is a
                        // no-op, but its registered children still need MODIFIER_ZINDEX's
                        // paint-order reordering — see arrangeChildren's own gating on axis.
                        arrangeChildren(frame)
                        // MODIFIER_BORDER: a real stroked-outline effect, drawn *on top of* this
                        // container's now-finished content (appended, not inserted at
                        // frame.startIndex like the background fill above) around the same
                        // contentBounds()-inferred, padding-expanded box the background uses.
                        // shapeType 1 (CIRCLE) draws a stroked oval inscribed in that box instead
                        // of a rect/round-rect.
                        val border = frame.borderColor
                        if (border != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val left = bounds[0] - frame.paddingLeft
                                val top = bounds[1] - frame.paddingTop
                                val right = bounds[2] + frame.paddingRight
                                val bottom = bounds[3] + frame.paddingBottom
                                val paint = PaintStyle(border, PaintStyleKind.STROKE, frame.borderWidth)
                                opcodes += if (frame.borderShapeType == 1) {
                                    Opcode.DrawOval(left, top, right, bottom, paint)
                                } else if (frame.borderRoundedCorner > 0f) {
                                    Opcode.DrawRoundRect(
                                        left, top, right, bottom,
                                        frame.borderRoundedCorner, frame.borderRoundedCorner,
                                        paint,
                                    )
                                } else {
                                    Opcode.DrawRect(left, top, right, bottom, paint)
                                }
                            }
                        }
                        // LAYOUT_IMAGE carries no position/size of its own, so real rendering only
                        // happens when an explicit MODIFIER_WIDTH/HEIGHT on the same modifier gave
                        // this frame a real box to draw into — the same real-vs-byte-consumed-only
                        // gate MODIFIER_GRAPHICS_LAYER's scale/rotation use below, for the same
                        // "no measure pass" reason. Drawn at this frame's own local origin (0,0),
                        // same as every other leaf here that has no absolute position of its own
                        // to inherit from anywhere but an enclosing MODIFIER_OFFSET/Translate.
                        val imageBitmapId = frame.imageBitmapId
                        val imageWidth = frame.explicitWidthPx
                        val imageHeight = frame.explicitHeightPx
                        if (imageBitmapId != null && imageWidth != null && imageHeight != null) {
                            val wrapAlpha = frame.imageAlpha < 1f
                            if (wrapAlpha) opcodes += Opcode.SaveLayerAlpha(frame.imageAlpha)
                            val naturalSize = bitmapPool[imageBitmapId]?.let { pngNaturalSize(it) }
                            var clipWrappedImage = false
                            val realScaleTypes = frame.imageScaleType == 0 || frame.imageScaleType == 1 ||
                                frame.imageScaleType == 4 || frame.imageScaleType == 5
                            if (naturalSize != null && realScaleTypes) {
                                val dst = imageScaleDstRect(
                                    frame.imageScaleType, naturalSize[0], naturalSize[1],
                                    0f, 0f, imageWidth, imageHeight,
                                )
                                if (frame.imageScaleType == 0 || frame.imageScaleType == 5) {
                                    // SCALE_NONE (an oversized natural bitmap) / SCALE_CROP (one
                                    // axis always) can overflow this box — real Compose's own
                                    // Image/Modifier.paint clips automatically whenever a
                                    // mismatched contentScale overflows the layout box. Harmless
                                    // (a no-op clip) when SCALE_NONE's natural size already fits.
                                    opcodes += Opcode.MatrixSave
                                    opcodes += Opcode.ClipRect(0f, 0f, imageWidth, imageHeight)
                                    clipWrappedImage = true
                                }
                                opcodes += Opcode.DrawBitmap(imageBitmapId, dst[0], dst[1], dst[2], dst[3])
                            } else {
                                opcodes += Opcode.DrawBitmap(imageBitmapId, 0f, 0f, imageWidth, imageHeight)
                            }
                            if (clipWrappedImage) opcodes += Opcode.MatrixRestore
                            if (wrapAlpha) opcodes += Opcode.MatrixRestore
                        }
                        // LAYOUT_TEXT: this leaf's own content frame is always empty (see
                        // Op.LayoutText's own KDoc), so the one real Opcode.DrawText it renders as
                        // is synthesized here rather than reacting to already-emitted children.
                        // textAlign only gets a real horizontal-alignment effect when this leaf's
                        // *outer* frame (frame.parent — the one Op.LayoutText itself pushed, which
                        // any MODIFIER_WIDTH/MODIFIER_DIMENSION_CONSTRAINTS on this same component
                        // sets explicitWidthPx on, same as Op.LayoutBox's own boxWidth lookup)
                        // actually declares a width to align within; otherwise it's real byte-
                        // coverage only, same honest gate MODIFIER_WIDTH_IN's own effect needs.
                        if (frame.isTextLayout) {
                            val text = textPool[frame.textId] ?: ""
                            val textPaint = PaintStyle(
                                Color(frame.textColorArgb),
                                PaintStyleKind.FILL,
                                textSize = frame.textFontSize,
                            )
                            val estimatedWidth = textMetrics.measure(text, textPaint).width
                            val boxWidth = frame.parent?.explicitWidthPx
                            val alignOffsetX = if (boxWidth != null) {
                                val posMode = when (frame.textAlign) {
                                    3 -> POS_CENTER // TEXT_ALIGN_CENTER
                                    2, 6 -> POS_END // TEXT_ALIGN_RIGHT/END
                                    else -> POS_START // LEFT/START/JUSTIFY (no wrap algorithm here)
                                }
                                crossAxisOffset(posMode, boxWidth, estimatedWidth)
                            } else {
                                0f
                            }
                            // Real TextLayout draws with its baseline at -bounds[1], i.e. the
                            // text's top at the component origin: panY = 1 says "top at y".
                            opcodes += Opcode.DrawText(
                                stringIndex = frame.textId,
                                x = alignOffsetX,
                                y = 0f,
                                paint = textPaint,
                                panY = 1f,
                            )
                        }
                        // MODIFIER_GRAPHICS_LAYER's SHAPE/SHAPE_RADIUS: a real clip, nested
                        // *innermost* (inserted at frame.startIndex first, so the transform-wrap
                        // below — inserted at the same index afterward — ends up outside it),
                        // matching real Compose's own order: a layer clips its own local content
                        // before the whole clipped result is scaled/rotated/translated as a unit.
                        var shapeClipWrapped = false
                        val shapeType = frame.glShapeType
                        if (shapeType != null && shapeType != 0) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val radius = if (shapeType == 2) {
                                    minOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2f
                                } else {
                                    frame.glShapeRadius
                                }
                                opcodes.addAll(
                                    frame.startIndex,
                                    listOf(
                                        Opcode.MatrixSave,
                                        Opcode.ClipPath(
                                            roundedRectPath(
                                                bounds[0], bounds[1], bounds[2], bounds[3],
                                                radius, radius, radius, radius,
                                            ),
                                        ),
                                    ),
                                )
                                shapeClipWrapped = true
                            }
                        }
                        if (shapeClipWrapped) opcodes += Opcode.MatrixRestore
                        // MODIFIER_GRAPHICS_LAYER's SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/
                        // TRANSLATION_Y: wrap this frame's now-finished content (background
                        // included, since a real graphicsLayer transform applies to the whole
                        // composable box) in MatrixSave/Translate/Rotate/Scale/MatrixRestore,
                        // pivoting scale/rotation at a fraction (TRANSFORM_ORIGIN_X/_Y, default
                        // 0.5f — real Compose's own TransformOrigin.Center) of the inferred
                        // content-bounds box — the same bounds approximation MODIFIER_BACKGROUND
                        // uses, since this renderer has no measure pass to get a real layout box
                        // from instead. The MatrixRestore is appended *after* [frame.cleanupOpcodes]
                        // below (not here) so it closes outermost, keeping this the outermost
                        // save/restore pair around any MODIFIER_OFFSET/VISIBILITY/
                        // GRAPHICS_LAYER-ALPHA opened earlier inside this same frame.
                        var transformWrapped = false
                        val sx = frame.glScaleX
                        val sy = frame.glScaleY
                        val rz = frame.glRotationZ
                        val tx = frame.glTranslationX
                        val ty = frame.glTranslationY
                        if (sx != null || sy != null || rz != null || tx != null || ty != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val originXFraction = frame.glTransformOriginX ?: 0.5f
                                val originYFraction = frame.glTransformOriginY ?: 0.5f
                                val pivotX = bounds[0] + originXFraction * (bounds[2] - bounds[0])
                                val pivotY = bounds[1] + originYFraction * (bounds[3] - bounds[1])
                                val wrap = mutableListOf<Opcode>(Opcode.MatrixSave)
                                if (tx != null || ty != null) wrap += Opcode.Translate(tx ?: 0f, ty ?: 0f)
                                if (rz != null) wrap += Opcode.Rotate(rz, pivotX, pivotY)
                                if (sx != null || sy != null) wrap += Opcode.Scale(sx ?: 1f, sy ?: 1f, pivotX, pivotY)
                                opcodes.addAll(frame.startIndex, wrap)
                                transformWrapped = true
                            }
                        }
                        opcodes.addAll(frame.cleanupOpcodes)
                        if (transformWrapped) opcodes += Opcode.MatrixRestore
                        // MODIFIER_WIDTH_IN/MODIFIER_HEIGHT_IN: resolve against this frame's own
                        // inferred content bounds now that its content is finished — narrower than
                        // min raises the effective declared size (visible to a parent Row/Column's
                        // arrangement below, the same as an explicit width()/height() would be);
                        // wider than max clips it, the same real "cut off the overflow" effect
                        // MODIFIER_CLIP_RECT gets, without a document needing a separate clip(...)
                        // call — real Compose's widthIn/heightIn imply their own clip. Only applied
                        // when no exact width()/height() already set explicitWidthPx/HeightPx,
                        // since that's a stronger, unambiguous declaration.
                        var clipImpliedByRangeConstraint = false
                        val widthInMin = frame.widthInMin
                        val widthInMax = frame.widthInMax
                        if ((widthInMin != null || widthInMax != null) && frame.explicitWidthPx == null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val naturalWidth = bounds[2] - bounds[0]
                                val clamped = naturalWidth.coerceIn(widthInMin ?: 0f, widthInMax ?: Float.MAX_VALUE)
                                if (clamped != naturalWidth) {
                                    frame.explicitWidthPx = clamped
                                    if (widthInMax != null && naturalWidth > widthInMax) clipImpliedByRangeConstraint = true
                                }
                            }
                        }
                        val heightInMin = frame.heightInMin
                        val heightInMax = frame.heightInMax
                        if ((heightInMin != null || heightInMax != null) && frame.explicitHeightPx == null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val naturalHeight = bounds[3] - bounds[1]
                                val clamped = naturalHeight.coerceIn(heightInMin ?: 0f, heightInMax ?: Float.MAX_VALUE)
                                if (clamped != naturalHeight) {
                                    frame.explicitHeightPx = clamped
                                    if (heightInMax != null && naturalHeight > heightInMax) clipImpliedByRangeConstraint = true
                                }
                            }
                        }
                        // MODIFIER_CLIP_RECT/MODIFIER_ROUNDED_CLIP_RECT: real only when this frame
                        // also has an explicit width/height smaller than its natural content —
                        // clipping to the *inferred* (natural) bounds alone would be a no-op, since
                        // by construction nothing in the content extends past its own bounding box.
                        // The outermost wrap (inserted after the graphics-layer transform above,
                        // so it applies in this container's own *un-transformed* layout space, the
                        // same space its declared width/height is measured in) — a real "cut off
                        // the overflow" effect, not just a byte-skip.
                        var clipWrapped = false
                        if ((frame.hasClipRect || clipImpliedByRangeConstraint) &&
                            (frame.explicitWidthPx != null || frame.explicitHeightPx != null)
                        ) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val clipRight = frame.explicitWidthPx?.let { bounds[0] + it } ?: bounds[2]
                                val clipBottom = frame.explicitHeightPx?.let { bounds[1] + it } ?: bounds[3]
                                val clipShape = if (
                                    frame.cornerTopStart > 0f || frame.cornerTopEnd > 0f ||
                                    frame.cornerBottomStart > 0f || frame.cornerBottomEnd > 0f
                                ) {
                                    Opcode.ClipPath(
                                        roundedRectPath(
                                            bounds[0], bounds[1], clipRight, clipBottom,
                                            frame.cornerTopStart, frame.cornerTopEnd,
                                            frame.cornerBottomStart, frame.cornerBottomEnd,
                                        ),
                                    )
                                } else {
                                    Opcode.ClipRect(bounds[0], bounds[1], clipRight, clipBottom)
                                }
                                opcodes.addAll(
                                    frame.startIndex,
                                    listOf(Opcode.MatrixSave, clipShape),
                                )
                                clipWrapped = true
                            }
                        }
                        if (clipWrapped) opcodes += Opcode.MatrixRestore
                        val parent = frame.parent
                        if (parent != null) {
                            // Real LOOP_START unrolling (see its own KDoc): a static, non-empty
                            // range clones this frame's one authored body that many times, each
                            // clone registered as its own independent sibling below — real
                            // Compose's own per-iteration re-apply(), just unrolled at parse time
                            // instead of re-walked live. Every other frame (loopFrom == null) — and
                            // a variable-driven loop, honestly falling back to rendering once —
                            // takes repeatCount == 1, reducing to plain single registration, the
                            // exact behavior every non-loop frame already had.
                            val loopFrom = frame.loopFrom
                            val loopStep = frame.loopStep
                            val loopUntil = frame.loopUntil
                            val repeatCount = if (
                                frame.isLoop && loopFrom != null && loopStep != null && loopUntil != null &&
                                loopStep > 0f && loopFrom < loopUntil
                            ) {
                                ceil((loopUntil - loopFrom) / loopStep).toInt().coerceIn(0, 64)
                            } else {
                                1
                            }
                            val bodyStart = frame.startIndex
                            val bodyTemplate = opcodes.subList(bodyStart, opcodes.size).toList()
                            if (repeatCount <= 0) {
                                // A real "loop never runs" range (from >= until): this frame's one
                                // authored body copy was never actually meant to render at all.
                                while (opcodes.size > bodyStart) opcodes.removeAt(opcodes.size - 1)
                            } else {
                                val expectedOrientation = if (parent.layoutAxis == 'V') 1 else 0
                                fun registerChild(range: IntArray) {
                                    // Registered regardless of parent.layoutAxis: a Box's children
                                    // need this too, just for MODIFIER_ZINDEX's paint-order
                                    // reordering below rather than arrangeChildren's position-
                                    // arrangement (Box already paints children at their own
                                    // document-authored position).
                                    parent.childRanges.add(range)
                                    parent.childZIndices.add(frame.zIndex)
                                    // Resolved against *this* registration's own parent axis now,
                                    // while both this frame's own collapsiblePriorityOrientation
                                    // and the parent's layoutAxis are known —
                                    // CollapsiblePriority.getPriority's real orientation filter
                                    // (javap-confirmed): a priority whose own orientation doesn't
                                    // match is treated as Float.MAX_VALUE (never collapse), same
                                    // as no modifier at all.
                                    parent.childCollapsiblePriorities.add(
                                        if (frame.collapsiblePriorityOrientation == expectedOrientation) {
                                            frame.collapsiblePriority
                                        } else {
                                            Float.MAX_VALUE
                                        },
                                    )
                                    // A widthWeight only applies in an 'H' (Row-axis) parent, a
                                    // heightWeight only in a 'V' one — same axis-matching rationale
                                    // as collapsiblePriority's own orientation filter above.
                                    parent.childWeights.add(
                                        if (parent.layoutAxis == 'V') frame.heightWeight else frame.widthWeight,
                                    )
                                }
                                registerChild(intArrayOf(bodyStart, opcodes.size))
                                repeat(repeatCount - 1) {
                                    val cloneStart = opcodes.size
                                    opcodes.addAll(bodyTemplate)
                                    registerChild(intArrayOf(cloneStart, opcodes.size))
                                }
                            }
                        }
                    }
                }

                is Op.ModifierClick -> pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                is Op.ModifierPadding -> {
                    // A real semantic effect (not just a byte-skip): translates every subsequent
                    // draw belonging to this container's children inward, undone at this
                    // container's own closing CONTAINER_END — the same mechanism MODIFIER_OFFSET
                    // uses. right/bottom are stashed too (not applied as a translate themselves —
                    // padding only insets from the top-left, the same as MODIFIER_OFFSET's own
                    // two-float shape has no separate "how much smaller" concept) so
                    // Op.ContainerEnd's MODIFIER_BACKGROUND handling can expand the inferred
                    // background rect back out to cover the full un-padded box, matching the
                    // classic "colored margin around padded content" look real Compose gives
                    // `Modifier.background(color).padding(...)`.
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    val frame = scopeStack.lastOrNull()
                    frame?.paddingLeft = left
                    frame?.paddingTop = top
                    frame?.paddingRight = right
                    frame?.paddingBottom = bottom
                    if (left != 0f || top != 0f) {
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.Translate(left, top)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                is Op.ModifierBackground -> {
                    val r = op.r
                    val g = op.g
                    val b = op.b
                    val a = op.a
                    val shapeType = op.shapeType // 0=RECTANGLE, 1=CIRCLE
                    val frame = scopeStack.lastOrNull()
                    frame?.backgroundColor = Color(r, g, b, a)
                    frame?.backgroundShapeType = shapeType
                }

                is Op.ModifierVisibility -> {
                    // Component.Visibility: GONE=0, VISIBLE=1, INVISIBLE=2. Anything but VISIBLE
                    // is rendered here as an empty clip around this container's children (the
                    // executor already implements Op.ClipRect via Skia's real clip stack, and an
                    // empty rect makes every subsequent draw inside it a no-op) — a real semantic
                    // effect, not just a byte-skip, though it doesn't distinguish GONE (no space
                    // reserved) from INVISIBLE (space reserved) since this renderer has no layout
                    // pass to reserve space with.
                    val visibility = op.visibility
                    if (visibility != 1) {
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.ClipRect(0f, 0f, 0f, 0f)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                is Op.ModifierOffset -> {
                    // A real semantic effect (not just a byte-skip): translates every subsequent
                    // draw belonging to this container's children, undone at this container's own
                    // closing CONTAINER_END via the scope stack above.
                    val x = resolveFloat(op.x)
                    val y = resolveFloat(op.y)
                    opcodes += Opcode.MatrixSave
                    opcodes += Opcode.Translate(x, y)
                    attachToTopScope(Opcode.MatrixRestore)
                }

                is Op.ModifierBorder -> {
                    val colorRefFlag = op.colorRefFlag
                    val colorId = op.colorId // only meaningful when colorRefFlag == 2
                    val borderWidth = op.borderWidth
                    val roundedCorner = op.roundedCorner
                    val r = op.r
                    val g = op.g
                    val b = op.b
                    val a = op.a
                    val shapeType = op.shapeType
                    val resolvedColor = if (colorRefFlag == 2) colorPool[colorId] else Color(r, g, b, a)
                    if (resolvedColor != null) {
                        val frame = scopeStack.lastOrNull()
                        frame?.borderColor = resolvedColor
                        frame?.borderWidth = borderWidth
                        frame?.borderRoundedCorner = roundedCorner
                        frame?.borderShapeType = shapeType
                    }
                }

                is Op.FloatConstant -> {
                    val id = op.id
                    val value = op.value
                    floatPool[id] = value
                }

                is Op.IntegerConstant -> {
                    val id = op.id
                    val value = op.value
                    intPool[id] = value
                }

                is Op.BooleanConstant -> {
                    val id = op.id
                    val value = op.value
                    booleanPool[id] = value
                }

                is Op.LongConstant -> {
                    val id = op.id
                    val value = op.value
                    longPool[id] = value
                }

                is Op.ColorConstant -> {
                    val colorId = op.colorId
                    val colorArgb = op.colorArgb
                    colorPool[colorId] = Color(colorArgb)
                }

                is Op.ModifierClipRect -> scopeStack.lastOrNull()?.hasClipRect = true

                is Op.ModifierRoundedClipRect -> {
                    val topStart = resolveFloat(op.topStart)
                    val topEnd = resolveFloat(op.topEnd)
                    val bottomStart = resolveFloat(op.bottomStart)
                    val bottomEnd = resolveFloat(op.bottomEnd)
                    val frame = scopeStack.lastOrNull()
                    frame?.hasClipRect = true
                    frame?.cornerTopStart = topStart
                    frame?.cornerTopEnd = topEnd
                    frame?.cornerBottomStart = bottomStart
                    frame?.cornerBottomEnd = bottomEnd
                }

                is Op.ModifierMultiClick -> {
                    pushScope() // opens a nested action list, closed by its own CONTAINER_END
                }

                is Op.ModifierTouchDown, is Op.ModifierTouchUp, is Op.ModifierTouchCancel ->
                    pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                is Op.ModifierWidthIn, is Op.ModifierHeightIn -> {
                    val min = resolveFloat(op.min)
                    val max = resolveFloat(op.max)
                    val frame = scopeStack.lastOrNull()
                    if (op is Op.ModifierWidthIn) {
                        frame?.widthInMin = min
                        frame?.widthInMax = max
                    } else {
                        frame?.heightInMin = min
                        frame?.heightInMax = max
                    }
                }

                is Op.ModifierCollapsiblePriority -> {
                    val orientation = op.orientation
                    val priority = resolveFloat(op.priority)
                    val frame = scopeStack.lastOrNull()
                    frame?.collapsiblePriority = priority
                    frame?.collapsiblePriorityOrientation = orientation
                }

                is Op.ModifierZIndex -> {
                    val zIndex = resolveFloat(op.zIndex)
                    scopeStack.lastOrNull()?.zIndex = zIndex
                }

                is Op.ModifierGraphicsLayer -> {
                    // Real semantic effect for ALPHA (opens a real compositing layer around this
                    // container's children, closed by a MatrixRestore queued on this container's
                    // own scope — the same mechanism MODIFIER_OFFSET/MODIFIER_VISIBILITY use),
                    // SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/TRANSLATION_Y, TRANSFORM_ORIGIN_X/_Y,
                    // and SHAPE/SHAPE_RADIUS (all stashed on this container's own [ScopeFrame],
                    // applied at its Op.ContainerEnd once real bounds/a real pivot can be inferred
                    // — see the frame's `glScaleX`/`glTransformOriginX`/`glShapeType` etc. KDoc).
                    // Every other attribute (shadow/blur/camera distance/etc.) is still just
                    // byte-consumed, since those have no equivalent among this renderer's Opcodes.
                    var alpha: Float? = null
                    for (attr in op.attributes) {
                        val tag = attr.tag // attribute key, OR'd with 0x400 if float-valued
                        val rawValue = attr.rawValue // int or float bit pattern
                        when (tag) {
                            GRAPHICS_LAYER_ALPHA_TAG -> alpha = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SCALE_X_TAG -> scopeStack.lastOrNull()?.glScaleX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SCALE_Y_TAG -> scopeStack.lastOrNull()?.glScaleY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_ROTATION_Z_TAG -> scopeStack.lastOrNull()?.glRotationZ = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSLATION_X_TAG -> scopeStack.lastOrNull()?.glTranslationX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSLATION_Y_TAG -> scopeStack.lastOrNull()?.glTranslationY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSFORM_ORIGIN_X_TAG -> scopeStack.lastOrNull()?.glTransformOriginX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSFORM_ORIGIN_Y_TAG -> scopeStack.lastOrNull()?.glTransformOriginY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SHAPE_TAG -> scopeStack.lastOrNull()?.glShapeType = rawValue // int, not bit-reinterpreted
                            GRAPHICS_LAYER_SHAPE_RADIUS_TAG -> scopeStack.lastOrNull()?.glShapeRadius = Float.fromBits(rawValue)
                        }
                    }
                    alpha?.let {
                        opcodes += Opcode.SaveLayerAlpha(it)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                is Op.ModifierDimensionConstraints -> {
                    val type = op.type
                    val min = resolveFloat(op.min)
                    val max = resolveFloat(op.max)
                    val frame = scopeStack.lastOrNull()
                    when (type) {
                        // HORIZONTAL_CONSTRAINTS(0)/REQUIRED_HORIZONTAL_CONSTRAINTS(2): same real
                        // effect Op.ModifierWidthIn already gets — see ScopeFrame.widthInMin's
                        // KDoc and Op.ContainerEnd's clamp/clip handling for it.
                        0, 2 -> {
                            frame?.widthInMin = min
                            frame?.widthInMax = max
                        }
                        // VERTICAL_CONSTRAINTS(1)/REQUIRED_VERTICAL_CONSTRAINTS(3): same real
                        // effect Op.ModifierHeightIn already gets.
                        1, 3 -> {
                            frame?.heightInMin = min
                            frame?.heightInMax = max
                        }
                    }
                }

                is Op.MatrixSave -> opcodes += Opcode.MatrixSave

                is Op.MatrixRestore -> opcodes += Opcode.MatrixRestore

                is Op.MatrixTranslate -> {
                    val dx = resolveFloat(op.dx)
                    val dy = resolveFloat(op.dy)
                    opcodes += Opcode.Translate(dx, dy)
                }

                is Op.MatrixScale -> {
                    val sx = resolveFloat(op.sx)
                    val sy = resolveFloat(op.sy)
                    val pivotX = resolveFloat(op.pivotX)
                    val pivotY = resolveFloat(op.pivotY)
                    opcodes += Opcode.Scale(sx, sy, pivotX, pivotY)
                }

                is Op.MatrixRotate -> {
                    val degrees = resolveFloat(op.degrees)
                    val pivotX = resolveFloat(op.pivotX)
                    val pivotY = resolveFloat(op.pivotY)
                    opcodes += Opcode.Rotate(degrees, pivotX, pivotY)
                }

                is Op.MatrixSkew -> {
                    val skewX = resolveFloat(op.skewX)
                    val skewY = resolveFloat(op.skewY)
                    opcodes += Opcode.Skew(skewX, skewY)
                }

                is Op.ClipRect -> {
                    val left = resolveFloat(op.left)
                    val top = resolveFloat(op.top)
                    val right = resolveFloat(op.right)
                    val bottom = resolveFloat(op.bottom)
                    opcodes += Opcode.ClipRect(left, top, right, bottom)
                }

            }
        }

        context.inflated = true
        return opcodes
    }

    /**
     * Operations whose only effect is to define a pool value once. They are skipped after the
     * first pass so that later writes to the same id (an animation, an action) are not undone
     * every frame; `PathCreate`/`PathAdd` are included because re-applying them would append
     * segments again.
     */
    private fun Op.isConstant(): Boolean = when (this) {
        is Op.TextData, is Op.FloatConstant, is Op.IntegerConstant, is Op.BooleanConstant,
        is Op.LongConstant, is Op.ColorConstant, is Op.BitmapData, is Op.PathData,
        is Op.PathCreate, is Op.PathAdd, is Op.IdList, is Op.DataMapIds -> true
        else -> false
    }

    /**
     * A quadratic-corner approximation of a rounded rect, for [Op.ModifierRoundedClipRect]'s
     * real clip effect: this parser has no dedicated round-rect clip primitive (unlike
     * [Opcode.DrawRoundRect], which real Compose's own Skia backend draws natively), so each
     * corner is instead approximated by a quadratic Bézier whose control point sits at the
     * corner's own sharp vertex — visually close to, but not bit-identical with, a true circular
     * arc, the same kind of documented approximation [Op.DrawTextOnCircle]'s straight-line
     * fallback already makes elsewhere in this parser. Each radius is independently clamped to
     * half this rect's smaller dimension so opposite corners can never overlap. Corner naming
     * (`topStart`/`topEnd`/`bottomStart`/`bottomEnd`) assumes LTR, matching every other
     * `Component.Positioning`-based measurement in this parser (which has no bidi/RTL support).
     */
    private fun roundedRectPath(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topStart: Float,
        topEnd: Float,
        bottomStart: Float,
        bottomEnd: Float,
    ): List<PathCommand> {
        val maxRadius = minOf(right - left, bottom - top) / 2f
        val rTopStart = topStart.coerceIn(0f, maxRadius)
        val rTopEnd = topEnd.coerceIn(0f, maxRadius)
        val rBottomStart = bottomStart.coerceIn(0f, maxRadius)
        val rBottomEnd = bottomEnd.coerceIn(0f, maxRadius)
        return listOf(
            PathCommand.MoveTo(left + rTopStart, top),
            PathCommand.LineTo(right - rTopEnd, top),
            PathCommand.QuadraticTo(right, top, right, top + rTopEnd),
            PathCommand.LineTo(right, bottom - rBottomEnd),
            PathCommand.QuadraticTo(right, bottom, right - rBottomEnd, bottom),
            PathCommand.LineTo(left + rBottomStart, bottom),
            PathCommand.QuadraticTo(left, bottom, left, bottom - rBottomStart),
            PathCommand.LineTo(left, top + rTopStart),
            PathCommand.QuadraticTo(left, top, left + rTopStart, top),
            PathCommand.Close,
        )
    }

    /**
     * A PNG's natural pixel size, read directly from its `IHDR` chunk (always the first chunk,
     * immediately after the 8-byte signature: 4-byte length, 4-byte `"IHDR"` tag, then a 4-byte
     * big-endian width and a 4-byte big-endian height) — exact for any real PNG, so no platform
     * image-decoding is needed just to answer "how big is this bitmap really", which this
     * common-code parser has no access to anyway. [bitmapPool]'s entries are always real PNG bytes
     * (the real writer's own `storeBitmap`/`addBitmap` always encodes one), so this never needs a
     * fallback. Returns `null` if `bytes` is too short to hold an `IHDR` chunk at all.
     */
    private fun pngNaturalSize(bytes: ByteArray): IntArray? {
        if (bytes.size < 24) return null
        fun beInt(offset: Int) =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        return intArrayOf(beInt(16), beInt(20))
    }

    /**
     * `Operations.LAYOUT_IMAGE`'s real `scaleType` effect for `SCALE_FIT`(`4`)/`SCALE_CROP`(`5`) —
     * a faithful port of the real `ImageScaling.adjustDrawToType()`'s own integer arithmetic
     * (javap-confirmed), not a from-scratch reimplementation, so it matches real Compose's pixel
     * rounding too. Both scale types compare `srcW*dstH` against `dstW*srcH` to decide which axis
     * needs adjusting, but with the comparison flipped between them: `FIT` shrinks whichever axis
     * would otherwise overflow (letterboxing — the result always fits *inside* [dstLeft, dstTop,
     * dstRight, dstBottom]); `CROP` grows whichever axis would otherwise leave a gap (the result
     * can extend *outside* those bounds on one axis, so the caller must clip to them — real
     * Compose's own `Image`/`Modifier.paint` does exactly that whenever a mismatched
     * `contentScale` overflows the layout box). Every other `scaleType` returns the box unchanged
     * (this parser's original stretch-to-fill behavior, identical to what `SCALE_FILL_BOUNDS`
     * itself really means).
     */
    private fun imageScaleDstRect(
        scaleType: Int,
        naturalWidth: Int,
        naturalHeight: Int,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
    ): FloatArray {
        if (naturalWidth <= 0 || naturalHeight <= 0 ||
            (scaleType != 0 && scaleType != 1 && scaleType != 4 && scaleType != 5)
        ) {
            return floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
        }
        val dstW = (dstRight - dstLeft).toInt()
        val dstH = (dstBottom - dstTop).toInt()
        var leftOffset = 0
        var rightOffset = dstW
        var topOffset = 0
        var bottomOffset = dstH
        // SCALE_NONE: natural size, centered — no scaling at all (javap-confirmed: real
        // ImageScaling.adjustDrawToType()'s case 0 uses srcW/srcH directly, unadjusted).
        fun centerAtNaturalSize() {
            leftOffset = (dstW - naturalWidth) / 2
            rightOffset = naturalWidth + leftOffset
            topOffset = (dstH - naturalHeight) / 2
            bottomOffset = naturalHeight + topOffset
        }
        // Shared FIT/CROP/SCALE_INSIDE's-shrink-branch math: compare srcW*dstH against dstW*srcH
        // to decide which axis to adjust, with the branch flipped between FIT (shrink whichever
        // axis would overflow) and CROP (grow whichever axis would leave a gap) — see
        // imageScaleDstRect's own original KDoc above [Op.LayoutImage] for the full rationale.
        fun shrinkOrGrowToFit(shrinkHeightWhenSrcWider: Boolean) {
            val srcWiderThanDst = naturalWidth * dstH > dstW * naturalHeight
            val shrinkHeight = if (shrinkHeightWhenSrcWider) srcWiderThanDst else !srcWiderThanDst
            if (shrinkHeight) {
                val adjustedHeight = dstW * naturalHeight / naturalWidth
                topOffset = (dstH - adjustedHeight) / 2
                bottomOffset = adjustedHeight + topOffset
            } else {
                val adjustedWidth = dstH * naturalWidth / naturalHeight
                leftOffset = (dstW - adjustedWidth) / 2
                rightOffset = adjustedWidth + leftOffset
            }
        }
        when (scaleType) {
            0 -> centerAtNaturalSize()
            // SCALE_INSIDE: keep natural size (centered, never scaled up) when it already fits
            // both dimensions of the box, otherwise shrink exactly like SCALE_FIT (javap-confirmed
            // on the real case 1: an early-exit when dst is at least as large as src in both
            // dimensions, else it falls through to the identical shrink-to-fit branch case 4 uses).
            1 -> if (dstW >= naturalWidth && dstH >= naturalHeight) centerAtNaturalSize() else shrinkOrGrowToFit(true)
            4 -> shrinkOrGrowToFit(true) // SCALE_FIT
            5 -> shrinkOrGrowToFit(false) // SCALE_CROP
        }
        return floatArrayOf(dstLeft + leftOffset, dstTop + topOffset, dstLeft + rightOffset, dstTop + bottomOffset)
    }

    // Op.PathTween's real semantic (matching real android.graphics.Path.interpolate()'s own
    // contract): both paths need the identical command sequence — same count, same kind at every
    // index — to linearly interpolate; null (this parser's own honest "leave unresolved" fallback)
    // otherwise, the same way real Path.canInterpolate() refuses a structural mismatch.
    private fun lerpPath(a: List<PathCommand>?, b: List<PathCommand>?, t: Float): List<PathCommand>? {
        if (a == null || b == null || a.size != b.size) return null
        fun lerp(x: Float, y: Float) = x + (y - x) * t
        return a.zip(b).map { (ca, cb) ->
            when {
                ca is PathCommand.MoveTo && cb is PathCommand.MoveTo ->
                    PathCommand.MoveTo(lerp(ca.x, cb.x), lerp(ca.y, cb.y))
                ca is PathCommand.LineTo && cb is PathCommand.LineTo ->
                    PathCommand.LineTo(lerp(ca.x, cb.x), lerp(ca.y, cb.y))
                ca is PathCommand.QuadraticTo && cb is PathCommand.QuadraticTo ->
                    PathCommand.QuadraticTo(
                        lerp(ca.x1, cb.x1), lerp(ca.y1, cb.y1),
                        lerp(ca.x2, cb.x2), lerp(ca.y2, cb.y2),
                    )
                ca is PathCommand.CubicTo && cb is PathCommand.CubicTo ->
                    PathCommand.CubicTo(
                        lerp(ca.x1, cb.x1), lerp(ca.y1, cb.y1),
                        lerp(ca.x2, cb.x2), lerp(ca.y2, cb.y2),
                        lerp(ca.x3, cb.x3), lerp(ca.y3, cb.y3),
                    )
                ca is PathCommand.Close && cb is PathCommand.Close -> PathCommand.Close
                else -> return null
            }
        }
    }

    // Op.ColorExpression's real gamma-2.2-corrected color interpolation (source-confirmed via
    // javap on the real Utils.interpolateColor()) — NOT a naive linear RGB lerp: each channel is
    // decoded to linear light (channel/255)^2.2, lerped there, then re-encoded ^(1/2.2) before
    // clamping back to a byte. `tween` of exactly 0f/NaN or 1f short-circuits to the input color
    // unchanged (matching the real method's own guard).
    private fun interpolateColorArgb(color1: Int, color2: Int, tween: Float): Int {
        if (tween.isNaN() || tween == 0f) return color1
        if (tween == 1f) return color2
        fun channel(c: Int, shift: Int): Float = ((c ushr shift) and 0xFF) / 255f
        fun gamma(v: Float): Float = v.toDouble().pow(2.2).toFloat()
        val a1 = channel(color1, 24); val r1 = gamma(channel(color1, 16))
        val g1 = gamma(channel(color1, 8)); val b1 = gamma(channel(color1, 0))
        val a2 = channel(color2, 24); val r2 = gamma(channel(color2, 16))
        val g2 = gamma(channel(color2, 8)); val b2 = gamma(channel(color2, 0))
        val aOut = (a1 + tween * (a2 - a1))
        val rOut = (r1 + tween * (r2 - r1)).toDouble().pow(1.0 / 2.2).toFloat()
        val gOut = (g1 + tween * (g2 - g1)).toDouble().pow(1.0 / 2.2).toFloat()
        val bOut = (b1 + tween * (b2 - b1)).toDouble().pow(1.0 / 2.2).toFloat()
        fun toByte(v: Float): Int = (v * 255f).toInt().coerceIn(0, 255)
        return (toByte(aOut) shl 24) or (toByte(rOut) shl 16) or (toByte(gOut) shl 8) or toByte(bOut)
    }

    // Op.ColorExpression's real HSV-to-RGB conversion (source-confirmed via javap on the real
    // Utils.hsvToRgb()) — `hue` a 0f..1f wheel fraction (not degrees). Returns a fully opaque
    // (0xFF alpha) ARGB int; the caller overwrites the alpha byte with the real op's own alpha
    // field. Matches the real method's own edge case: `hue` of exactly 1f (segment index 6, one
    // past the last hexagon wedge) returns plain transparent-black `0`, not a wrapped-around
    // segment 0.
    private fun hsvToRgbArgb(hue: Float, sat: Float, value: Float): Int {
        val hh = hue * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = (0.5f + 255f * value * (1f - sat)).toInt()
        val q = (0.5f + 255f * value * (1f - f * sat)).toInt()
        val t = (0.5f + 255f * value * (1f - (1f - f) * sat)).toInt()
        val v = (0.5f + 255f * value).toInt()
        return when (i) {
            0 -> -0x1000000 or (v shl 16) or (t shl 8) or p
            1 -> -0x1000000 or (q shl 16) or (v shl 8) or p
            2 -> -0x1000000 or (p shl 16) or (v shl 8) or t
            3 -> -0x1000000 or (p shl 16) or (q shl 8) or v
            4 -> -0x1000000 or (t shl 16) or (p shl 8) or v
            5 -> -0x1000000 or (v shl 16) or (p shl 8) or q
            else -> 0
        }
    }

    // Op.IntegerExpression's real RPN stack-machine step (source-confirmed via javap on the real
    // IntegerExpressionEvaluator.opEval()): binary ops pop stack[sp-1]/stack[sp], push 1 result at
    // sp-1 (returns sp-1); unary ops rewrite stack[sp] in place (returns sp unchanged);
    // CLAMP/IFELSE/MAD pop 3 (stack[sp-2..sp]), push 1 result at sp-2 (returns sp-2). Returns null
    // for an out-of-bounds pop (malformed expression) or VAR1/VAR2/VAR3 (only meaningful inside a
    // loop/foreach evaluation context this parser doesn't implement).
    private fun evalIntegerOp(stack: IntArray, sp: Int, op: Int): Int? {
        fun binary(f: (Int, Int) -> Int): Int? {
            if (sp < 1) return null
            stack[sp - 1] = f(stack[sp - 1], stack[sp])
            return sp - 1
        }

        fun unary(f: (Int) -> Int): Int? {
            if (sp < 0) return null
            stack[sp] = f(stack[sp])
            return sp
        }
        return when (op) {
            65537 -> binary { a, b -> a + b } // I_ADD
            65538 -> binary { a, b -> a - b } // I_SUB
            65539 -> binary { a, b -> a * b } // I_MUL
            65540 -> binary { a, b -> if (b == 0) 0 else a / b } // I_DIV
            65541 -> binary { a, b -> if (b == 0) 0 else a % b } // I_MOD
            65542 -> binary { a, b -> a shl b } // I_SHL
            65543 -> binary { a, b -> a shr b } // I_SHR
            65544 -> binary { a, b -> a ushr b } // I_USHR
            65545 -> binary { a, b -> a or b } // I_OR
            65546 -> binary { a, b -> a and b } // I_AND
            65547 -> binary { a, b -> a xor b } // I_XOR
            65548 -> binary { a, b -> (a xor (b shr 31)) - (b shr 31) } // I_COPY_SIGN
            65549 -> binary { a, b -> min(a, b) } // I_MIN
            65550 -> binary { a, b -> max(a, b) } // I_MAX
            65551 -> unary { a -> -a } // I_NEG
            65552 -> unary { a -> abs(a) } // I_ABS
            65553 -> unary { a -> a + 1 } // I_INCR
            65554 -> unary { a -> a - 1 } // I_DECR
            65555 -> unary { a -> a.inv() } // I_NOT
            65556 -> unary { a -> (a shr 31) or (-a ushr 31) } // I_SIGN
            65557 -> { // I_CLAMP: min(max(value, lo), hi)
                if (sp < 2) null else {
                    stack[sp - 2] = min(max(stack[sp - 2], stack[sp - 1]), stack[sp])
                    sp - 2
                }
            }

            65558 -> { // I_IFELSE: condition > 0 ? thenVal : elseVal
                if (sp < 2) null else {
                    stack[sp - 2] = if (stack[sp - 2] > 0) stack[sp - 1] else stack[sp]
                    sp - 2
                }
            }

            65559 -> { // I_MAD: a*b + c (a=top, b=second, c=third)
                if (sp < 2) null else {
                    stack[sp - 2] = stack[sp] * stack[sp - 1] + stack[sp - 2]
                    sp - 2
                }
            }

            else -> null // I_VAR1/I_VAR2/I_VAR3 (only meaningful inside a loop context) or unknown
        }
    }

    // Op.MatrixFromPath's real position-along-path semantic (matching Android's own
    // PathMeasure.getPosTan()): flattens Quadratic/CubicTo into short line segments (a standard,
    // real curve-length technique) to build one continuous polyline, walks it by cumulative arc
    // length to the target fraction, and returns [x, y, tangentDx, tangentDy] at that point — the
    // tangent an un-normalized direction vector (only its angle matters to the caller). null for
    // an empty/degenerate (zero-length) path.
    // Shared by [pointAndTangentAlongPath]/[trimPath]: flattens Quadratic/CubicTo segments into
    // 16 short line samples each (a standard, real curve-length technique — see
    // Op.MatrixFromPath's own KDoc) into one continuous list of [x1, y1, x2, y2] line segments.
    private fun flattenPathSegments(commands: List<PathCommand>): List<FloatArray> {
        val segments = mutableListOf<FloatArray>()
        var curX = 0f; var curY = 0f
        var subpathStartX = 0f; var subpathStartY = 0f
        val curveSamples = 16
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
                    for (i in 1..curveSamples) {
                        val p = quadPoint(
                            i / curveSamples.toFloat(), curX, curY,
                            command.x1, command.y1, command.x2, command.y2,
                        )
                        segments += floatArrayOf(prevX, prevY, p[0], p[1])
                        prevX = p[0]; prevY = p[1]
                    }
                    curX = command.x2; curY = command.y2
                }
                is PathCommand.CubicTo -> {
                    var prevX = curX; var prevY = curY
                    for (i in 1..curveSamples) {
                        val p = cubicPoint(
                            i / curveSamples.toFloat(), curX, curY,
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

    private fun pointAndTangentAlongPath(commands: List<PathCommand>, fraction: Float): FloatArray? {
        val segments = flattenPathSegments(commands)
        val lengths = segments.map { sqrt((it[2] - it[0]) * (it[2] - it[0]) + (it[3] - it[1]) * (it[3] - it[1])) }
        val totalLength = lengths.sum()
        if (totalLength <= 0f) return null
        val targetDist = fraction.coerceIn(0f, 1f) * totalLength
        var accumulated = 0f
        for (i in segments.indices) {
            val segLen = lengths[i]
            if (accumulated + segLen >= targetDist || i == segments.lastIndex) {
                val localT = if (segLen > 0f) ((targetDist - accumulated) / segLen).coerceIn(0f, 1f) else 0f
                val seg = segments[i]
                return floatArrayOf(
                    seg[0] + (seg[2] - seg[0]) * localT,
                    seg[1] + (seg[3] - seg[1]) * localT,
                    seg[2] - seg[0],
                    seg[3] - seg[1],
                )
            }
            accumulated += segLen
        }
        return null
    }

    // Op.DrawTweenPath's real start/stop trim (matching Android's own well-documented
    // PathMeasure.getSegment()): keeps only the [start, stop) fraction of the path's own total
    // arc length, rebuilt as a polyline (MoveTo + LineTo per flattened vertex, the same
    // curve-flattening [flattenPathSegments] already performs elsewhere) — start == 0f && stop ==
    // 1f (no real trim) returns the original commands unchanged, so untrimmed callers keep their
    // own exact Quadratic/CubicTo curves instead of an unnecessarily-flattened approximation.
    private fun trimPath(commands: List<PathCommand>, start: Float, stop: Float): List<PathCommand> {
        if (start <= 0f && stop >= 1f) return commands
        val segments = flattenPathSegments(commands)
        val lengths = segments.map { sqrt((it[2] - it[0]) * (it[2] - it[0]) + (it[3] - it[1]) * (it[3] - it[1])) }
        val totalLength = lengths.sum()
        if (totalLength <= 0f) return commands
        val startDist = start.coerceIn(0f, 1f) * totalLength
        val stopDist = stop.coerceIn(0f, 1f) * totalLength
        val vertices = mutableListOf<FloatArray>()
        var accumulated = 0f
        for (i in segments.indices) {
            val seg = segments[i]
            val segStart = accumulated
            val segEnd = accumulated + lengths[i]
            // segStart < stopDist (strict): a segment that starts exactly at the stop boundary
            // contributes zero real length inside [start, stop) and must be excluded, or its own
            // tEnd == 0 vertex would duplicate the previous segment's own already-added endpoint.
            if (segEnd >= startDist && segStart < stopDist) {
                val tStart = if (lengths[i] > 0f) ((startDist - segStart) / lengths[i]).coerceIn(0f, 1f) else 0f
                val tEnd = if (lengths[i] > 0f) ((stopDist - segStart) / lengths[i]).coerceIn(0f, 1f) else 1f
                if (vertices.isEmpty()) {
                    vertices += floatArrayOf(seg[0] + (seg[2] - seg[0]) * tStart, seg[1] + (seg[3] - seg[1]) * tStart)
                }
                vertices += floatArrayOf(seg[0] + (seg[2] - seg[0]) * tEnd, seg[1] + (seg[3] - seg[1]) * tEnd)
            }
            accumulated = segEnd
        }
        if (vertices.size < 2) return emptyList()
        return listOf(PathCommand.MoveTo(vertices[0][0], vertices[0][1])) +
            vertices.drop(1).map { PathCommand.LineTo(it[0], it[1]) }
    }

    // Op.PathCombine's OP_INTERSECT: the textbook Sutherland-Hodgman polygon-clipping algorithm
    // (real, well-known computational geometry — not a guess), correct for any simple subject
    // polygon clipped against a *convex* clip polygon. [ensureCcw] normalizes winding first since
    // the algorithm's own "inside" test assumes a consistent (counter-clockwise) orientation —
    // real callers may author either winding.
    private fun ensureCcw(poly: List<FloatArray>): List<FloatArray> {
        var area = 0f
        for (i in poly.indices) {
            val p1 = poly[i]
            val p2 = poly[(i + 1) % poly.size]
            area += p1[0] * p2[1] - p2[0] * p1[1]
        }
        return if (area < 0f) poly.reversed() else poly
    }

    private fun sutherlandHodgmanIntersect(subject: List<FloatArray>, clip: List<FloatArray>): List<FloatArray> {
        if (subject.size < 3 || clip.size < 3) return emptyList()
        fun isInside(p: FloatArray, a: FloatArray, b: FloatArray) =
            (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0f
        fun intersection(p1: FloatArray, p2: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
            val denom = (p1[0] - p2[0]) * (a[1] - b[1]) - (p1[1] - p2[1]) * (a[0] - b[0])
            if (denom == 0f) return p2
            val t = ((p1[0] - a[0]) * (a[1] - b[1]) - (p1[1] - a[1]) * (a[0] - b[0])) / denom
            return floatArrayOf(p1[0] + t * (p2[0] - p1[0]), p1[1] + t * (p2[1] - p1[1]))
        }
        val clipCcw = ensureCcw(clip)
        var output = ensureCcw(subject)
        for (i in clipCcw.indices) {
            if (output.isEmpty()) break
            val a = clipCcw[i]
            val b = clipCcw[(i + 1) % clipCcw.size]
            val input = output
            val next = mutableListOf<FloatArray>()
            for (j in input.indices) {
                val current = input[j]
                val prev = input[(j - 1 + input.size) % input.size]
                val currentInside = isInside(current, a, b)
                val prevInside = isInside(prev, a, b)
                if (currentInside) {
                    if (!prevInside) next += intersection(prev, current, a, b)
                    next += current
                } else if (prevInside) {
                    next += intersection(prev, current, a, b)
                }
            }
            output = next
        }
        return output
    }
}
