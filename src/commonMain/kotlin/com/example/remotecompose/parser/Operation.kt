package com.example.remotecompose.parser

import com.example.remotecompose.model.PathCommand

/**
 * One decoded `.rc` record, exactly as it sits on the wire: no pool lookups, no NaN-id
 * resolution, no evaluation. Each class mirrors the `androidx.compose.remote.core.operations`
 * class of the same name; field order is the record's wire order unless a note says otherwise.
 * [OperationReader] produces these; [RemoteComposeParser] evaluates them.
 *
 * A `Float` field may carry a NaN-tagged reference into the float pool (`Utils.asNan(id)`)
 * instead of a literal; that is resolved at evaluation time, as the real player does.
 */
sealed interface Operation {

    // --- Document ---

    data class Header(
        val majorVersion: Int, val minorVersion: Int, val patchVersion: Int,
        val width: Int, val height: Int, val capabilities: Long,
    ) : Operation

    data class RootContentDescription(val textId: Int) : Operation
    data class RootContentBehavior(val scroll: Int, val alignment: Int, val sizing: Int, val mode: Int) : Operation
    data class Theme(val theme: Int) : Operation
    data class HapticFeedback(val hapticId: Int) : Operation
    data class DebugMessage(val textId: Int, val floatValue: Float, val flags: Int) : Operation
    data class Rem(val text: String) : Operation

    /**
     * `SkipOperation`: the reader compares [value] against its own API level per
     * [conditionType] (1 less-than, 2 greater-than, 3 equal, 4 not-equal) and, when the
     * condition holds, has already jumped [skipLength] bytes; [skipped] records that decision.
     */
    data class Skip(val conditionType: Int, val value: Int, val skipLength: Int, val skipped: Boolean) : Operation

    // --- Constants and pools ---

    data class TextData(val id: Int, val text: String) : Operation
    data class FloatConstant(val id: Int, val value: Float) : Operation

    /**
     * `FloatExpression` (opcode `ANIMATED_FLOAT`): `[id][packed: expLen | animLen shl 16]`
     * then `expLen` expression floats and `animLen` animation-description floats. The expression
     * is RPN over literals, NaN-tagged float-pool ids and NaN-tagged operators; see
     * [com.example.remotecompose.runtime.FloatExpressionEvaluator]. Identity-compared so the
     * runtime can keep per-instance animation state.
     */
    class FloatExpression(val id: Int, val expression: FloatArray, val animation: FloatArray?) : Operation {
        override fun toString(): String =
            "FloatExpression(id=$id, expression=${expression.toList()}, animation=${animation?.toList()})"
    }
    data class IntegerConstant(val id: Int, val value: Int) : Operation
    data class BooleanConstant(val id: Int, val value: Boolean) : Operation
    data class LongConstant(val id: Int, val value: Long) : Operation
    data class ColorConstant(val colorId: Int, val colorArgb: Int) : Operation
    data class BitmapData(val bitmapId: Int, val width: Int, val height: Int, val bytes: ByteArray) : Operation
    data class IdList(val id: Int, val ids: List<Int>) : Operation
    data class DataMapIds(val mapId: Int, val entries: List<DataMapEntry>) : Operation
    data class DataMapEntry(val name: String, val type: Int, val valueId: Int)

    /**
     * `DataPath`: the `RemotePathBase` NaN-tagged command array, already decoded by
     * [OperationReader.readPathArray] (which reproduces the encoder's two-word padding after every
     * non-move, non-close tag).
     */
    data class PathData(val pathId: Int, val commands: List<PathCommand>) : Operation
    data class PathCreate(val pathId: Int, val startX: Float, val startY: Float) : Operation
    data class PathAdd(val pathId: Int, val commands: List<PathCommand>) : Operation
    data class PathTween(val outId: Int, val pathId1: Int, val pathId2: Int, val tween: Float) : Operation
    data class PathCombine(val outId: Int, val pathId1: Int, val pathId2: Int, val operation: Int) : Operation

    // --- Derived values ---

    /** `[textId][srcId][start][len]`; `len == -1f` means "to the end of the string". */
    data class TextSubtext(val textId: Int, val srcId: Int, val start: Float, val len: Float) : Operation

    /** [TextSubtext] plus an operation: 1 lowercase, 2 uppercase, 3 trim, 4 capitalize words, 5 capitalize first. */
    data class TextTransform(val textId: Int, val srcId: Int, val start: Float, val len: Float, val operation: Int) : Operation
    data class TextLength(val lengthId: Int, val textId: Int) : Operation
    data class TextLookup(val textId: Int, val dataSetId: Int, val index: Float) : Operation
    data class TextLookupInt(val textId: Int, val dataSetId: Int, val indexRefId: Int) : Operation
    data class TextMerge(val textId: Int, val srcId1: Int, val srcId2: Int) : Operation

    /** `[textId][value][digits: before shl 16 or after][flags]`; `flags and 0x1000` is `FULL_FORMAT`. */
    data class TextFromFloat(val textId: Int, val value: Float, val digits: Int, val flags: Int) : Operation
    data class IdLookup(val intId: Int, val dataSetId: Int, val index: Float) : Operation
    data class DataMapLookup(val id: Int, val dataMapId: Int, val stringId: Int) : Operation

    /**
     * `ColorExpression`: `modeAlpha` packs the mode in its low byte and, for HSV mode 4, alpha in
     * bits 16..23. Modes 0..3 interpolate `word1`/`word2` (each a literal ARGB or, when the
     * matching mode bit is set, a color-pool id) by the float in `word3`.
     */
    data class ColorExpression(val id: Int, val modeAlpha: Int, val word1: Int, val word2: Int, val word3: Int) : Operation

    /**
     * `IntegerExpression`: RPN over [values]; a set bit in [mask] marks an entry as an int-pool
     * id when `< 65536` or an operator (`65536 + opcode`) otherwise.
     */
    data class IntegerExpression(val id: Int, val mask: Int, val values: IntArray) : Operation

    // --- Paint and draw ---

    /** `PaintData`: the raw `PaintBundle` words, applied cumulatively by [PaintBundleDecoder]. */
    data class PaintData(val words: IntArray) : Operation
    data class DrawRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : Operation
    data class DrawRoundRect(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val radiusX: Float, val radiusY: Float,
    ) : Operation
    data class DrawCircle(val centerX: Float, val centerY: Float, val radius: Float) : Operation
    data class DrawLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Operation
    data class DrawOval(val left: Float, val top: Float, val right: Float, val bottom: Float) : Operation
    /** Shared shape of [DrawArc] (open arc) and [DrawSector] (closed pie slice). */
    sealed interface ArcLike : Operation {
        val left: Float; val top: Float; val right: Float; val bottom: Float
        val startAngle: Float; val sweepAngle: Float
    }
    data class DrawArc(
        override val left: Float, override val top: Float, override val right: Float, override val bottom: Float,
        override val startAngle: Float, override val sweepAngle: Float,
    ) : ArcLike
    data class DrawSector(
        override val left: Float, override val top: Float, override val right: Float, override val bottom: Float,
        override val startAngle: Float, override val sweepAngle: Float,
    ) : ArcLike
    data class DrawPath(val pathId: Int) : Operation
    data class DrawTweenPath(val path1Id: Int, val path2Id: Int, val tween: Float, val start: Float, val stop: Float) : Operation
    data class DrawTextRun(
        val textId: Int, val start: Int, val end: Int, val contextStart: Int, val contextEnd: Int,
        val x: Float, val y: Float, val rtl: Boolean,
    ) : Operation

    /** `DrawTextAnchored`: `panX`/`panY` in -1..1 say which point of the text `x`/`y` anchors. */
    data class DrawTextAnchored(val textId: Int, val x: Float, val y: Float, val panX: Float, val panY: Float, val flags: Int) : Operation
    data class DrawTextOnCircle(
        val textId: Int, val centerX: Float, val centerY: Float, val radius: Float,
        val startAngleDegrees: Float, val warpRadiusOffset: Float, val alignment: Int, val placement: Int,
    ) : Operation

    /** Wire order is `vOffset` then `hOffset`, the reverse of `drawTextOnPath`'s argument order. */
    data class DrawTextOnPath(val textId: Int, val pathId: Int, val vOffset: Float, val hOffset: Float) : Operation
    data class DrawBitmap(
        val bitmapId: Int, val left: Float, val top: Float, val right: Float, val bottom: Float,
        val descriptionId: Int,
    ) : Operation
    data class DrawBitmapInt(
        val bitmapId: Int,
        val srcLeft: Int, val srcTop: Int, val srcRight: Int, val srcBottom: Int,
        val dstLeft: Int, val dstTop: Int, val dstRight: Int, val dstBottom: Int,
        val descriptionId: Int,
    ) : Operation
    data class DrawBitmapScaled(
        val bitmapId: Int,
        val srcLeft: Float, val srcTop: Float, val srcRight: Float, val srcBottom: Float,
        val dstLeft: Float, val dstTop: Float, val dstRight: Float, val dstBottom: Float,
        val scaleType: Int, val scaleFactor: Float, val descriptionId: Int,
    ) : Operation
    data class ClickArea(
        val actionId: Int, val contentDescriptionId: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val metadataTextId: Int,
    ) : Operation

    // --- Matrix and clip ---

    data object MatrixSave : Operation
    data object MatrixRestore : Operation
    data class MatrixTranslate(val dx: Float, val dy: Float) : Operation
    data class MatrixScale(val sx: Float, val sy: Float, val pivotX: Float, val pivotY: Float) : Operation
    data class MatrixRotate(val degrees: Float, val pivotX: Float, val pivotY: Float) : Operation
    data class MatrixSkew(val skewX: Float, val skewY: Float) : Operation

    /** `[pathId][fraction][vOffset][flags]`; flag bit 2 aligns the matrix to the path tangent. */
    data class MatrixFromPath(val pathId: Int, val fraction: Float, val vOffset: Float, val flags: Int) : Operation
    data class ClipRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : Operation
    data class ClipPath(val pathId: Int) : Operation

    // --- Layout components (each opened here, closed by a ContainerEnd) ---

    data class LayoutRoot(val componentId: Int) : Operation
    data class LayoutContent(val componentId: Int) : Operation
    data class LayoutCanvasContent(val componentId: Int) : Operation
    data class LayoutCanvas(val componentId: Int, val animationId: Int) : Operation
    data object CanvasOperations : Operation
    /** A component with 2D child alignment: [LayoutBox] and [LayoutFitBox]. */
    sealed interface BoxLike : Operation {
        val componentId: Int; val animationId: Int
        val horizontalPositioning: Int; val verticalPositioning: Int
    }
    data class LayoutBox(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int,
    ) : BoxLike
    data class LayoutFitBox(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int,
    ) : BoxLike

    /** A component that stacks children along one axis with `spacedBy` gaps. */
    sealed interface LinearLike : Operation {
        val componentId: Int; val animationId: Int
        val horizontalPositioning: Int; val verticalPositioning: Int; val spacedBy: Float
    }
    data class LayoutColumn(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int, override val spacedBy: Float,
    ) : LinearLike
    data class LayoutRow(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int, override val spacedBy: Float,
    ) : LinearLike
    data class LayoutCollapsibleColumn(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int, override val spacedBy: Float,
    ) : LinearLike
    data class LayoutCollapsibleRow(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int, override val spacedBy: Float,
    ) : LinearLike
    data class LayoutFlow(
        override val componentId: Int, override val animationId: Int,
        override val horizontalPositioning: Int, override val verticalPositioning: Int, override val spacedBy: Float,
        val maxItemsInMainAxis: Int, val maxLinesInCrossAxis: Int,
    ) : LinearLike
    data class LayoutState(
        val componentId: Int, val animationId: Int, val horizontalPositioning: Int, val verticalPositioning: Int, val stateIndex: Int,
    ) : Operation

    /** `textAlign` is packed; its low 16 bits are the alignment. */
    data class LayoutText(
        val componentId: Int, val animationId: Int, val textId: Int, val color: Int, val fontSize: Float,
        val fontStyle: Int, val fontWeight: Float, val fontFamilyId: Int, val textAlign: Int, val overflow: Int, val maxLines: Int,
    ) : Operation
    data class LayoutImage(val componentId: Int, val animationId: Int, val bitmapId: Int, val scaleType: Int, val alpha: Float) : Operation
    data class LayoutCustom(val componentId: Int, val animationId: Int, val nameTextId: Int, val properties: List<CustomProperty>) : Operation
    data class CustomProperty(val type: Int, val dataType: Int, val value: Int)
    data class LoopStart(val indexVariableId: Int, val from: Float, val step: Float, val until: Float) : Operation
    data object ContainerEnd : Operation
    data class AnimationSpec(
        val animationId: Int, val motionDuration: Float, val motionEasingType: Int,
        val visibilityDuration: Float, val visibilityEasingType: Int, val enterAnimation: Int, val exitAnimation: Int,
    ) : Operation

    // --- Modifiers (attach to the most recently opened component) ---

    /** `mode`: 0 exact, 3 weight, 6 exact dp; other values are fill/wrap strategies. */
    sealed interface DimensionLike : Operation { val mode: Int; val value: Float }
    data class ModifierWidth(override val mode: Int, override val value: Float) : DimensionLike
    data class ModifierHeight(override val mode: Int, override val value: Float) : DimensionLike
    sealed interface RangeLike : Operation { val min: Float; val max: Float }
    data class ModifierWidthIn(override val min: Float, override val max: Float) : RangeLike
    data class ModifierHeightIn(override val min: Float, override val max: Float) : RangeLike

    /** `type`: 0/2 horizontal, 1/3 vertical (2 and 3 are the "required" variants). */
    data class ModifierDimensionConstraints(val type: Int, val min: Float, val max: Float) : Operation
    data class ModifierPadding(val left: Float, val top: Float, val right: Float, val bottom: Float) : Operation
    data class ModifierOffset(val x: Float, val y: Float) : Operation

    /** `shapeType`: 0 rectangle, 1 circle. The leading four ints are the color-by-id path. */
    data class ModifierBackground(
        val colorIdFlag: Int, val colorId: Int, val reserved1: Int, val reserved2: Int,
        val r: Float, val g: Float, val b: Float, val a: Float, val shapeType: Int,
    ) : Operation

    /** `colorRefFlag == 2` means the color comes from the color pool at `colorId`, not `r,g,b,a`. */
    data class ModifierBorder(
        val colorRefFlag: Int, val colorId: Int, val legacyFlag: Int, val reserved: Int,
        val borderWidth: Float, val roundedCorner: Float,
        val r: Float, val g: Float, val b: Float, val a: Float, val shapeType: Int,
    ) : Operation

    /** `Component.Visibility`: 0 gone, 1 visible, 2 invisible. */
    data class ModifierVisibility(val visibility: Int) : Operation
    data object ModifierClipRect : Operation
    data class ModifierRoundedClipRect(val topStart: Float, val topEnd: Float, val bottomStart: Float, val bottomEnd: Float) : Operation
    data class ModifierZIndex(val zIndex: Float) : Operation
    data class ModifierCollapsiblePriority(val orientation: Int, val priority: Float) : Operation
    data class ModifierAlignBy(val line: Float, val flag: Int) : Operation
    data object ModifierRipple : Operation
    data object ModifierDrawContent : Operation
    data class ModifierMarquee(
        val iterations: Int, val animationMode: Int,
        val repeatDelay: Float, val initialDelay: Float, val spacing: Float, val velocity: Float,
    ) : Operation
    data class ModifierScroll(val direction: Int, val positionExpression: Float, val max: Float, val notchMax: Float) : Operation

    /** `[count]` then `count × [tag][rawValue]`; a tag with bit 0x400 set carries float bits. */
    data class ModifierGraphicsLayer(val attributes: List<GraphicsLayerAttribute>) : Operation
    data class GraphicsLayerAttribute(val tag: Int, val rawValue: Int)

    // --- Actions (each opens a nested action list closed by a ContainerEnd) ---

    data object ModifierClick : Operation
    data class ModifierMultiClick(val clickType: Int) : Operation
    data object ModifierTouchDown : Operation
    data object ModifierTouchUp : Operation
    data object ModifierTouchCancel : Operation
    data class HostAction(val actionId: Int) : Operation
    data class ValueIntegerChange(val valueId: Int, val value: Int) : Operation
    data class ValueStringChange(val valueId: Int, val stringId: Int) : Operation
    data class ValueFloatChange(val valueId: Int, val value: Float) : Operation
    data class ValueIntegerExpressionChange(val valueId: Long, val value: Long) : Operation
    data class ValueFloatExpressionChange(val valueId: Int, val value: Int) : Operation
    data class TouchExpression(
        val id: Int, val defValue: Float, val min: Float, val max: Float, val velocity: Float, val flags: Int,
        val srcExp: List<Float>, val tapExpPacked: Int, val tapExp: List<Float>, val tapExpFloats: List<Float>,
    ) : Operation
}
