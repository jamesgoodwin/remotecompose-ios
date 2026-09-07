package com.example.remotecompose.parser

import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.text.BitmapGlyph

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
    /**
     * `Theme`: which mode the operations after it belong to — `LIGHT`(-3), `DARK`(-2),
     * `SYSTEM`(0) or `UNSPECIFIED`(-1). Everything between a mode and the next `UNSPECIFIED`
     * runs only when painting in that mode, which is how a document carries both palettes.
     */
    data class Theme(val theme: Int) : Operation

    /**
     * `ColorAttribute`: stores one component of the colour [colorId] as the float [id] — its
     * hue, saturation or brightness, or one of its channels. [type] is `HUE`(0),
     * `SATURATION`(1), `BRIGHTNESS`(2), `RED`(3), `GREEN`(4), `BLUE`(5) or `ALPHA`(6). Every
     * value comes out between 0 and 1, hue included.
     */
    data class ColorAttribute(val id: Int, val colorId: Int, val type: Int) : Operation

    /**
     * `ColorTheme`: one colour with a value for each mode. [lightMode] and [darkMode] are ARGB;
     * [colorGroupId] and the two indices name a palette entry, which the real operation's own
     * `apply` does not read either.
     */
    data class ColorTheme(
        val id: Int,
        val colorGroupId: Int,
        val lightModeIndex: Int,
        val darkModeIndex: Int,
        val lightMode: Int,
        val darkMode: Int,
    ) : Operation
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

    /**
     * `TextMeasure`: measures the text at [textId] with the current paint and stores one
     * component of the result under [id]. [type] is `MEASURE_WIDTH`(0), `HEIGHT`(1),
     * `LEFT`(2), `RIGHT`(3), `TOP`(4) or `BOTTOM`(5), optionally OR'd with
     * `MEASURE_MONOSPACE_FLAG`(256) or `MEASURE_MAX_HEIGHT_FLAG`(512).
     */
    data class TextMeasure(val id: Int, val textId: Int, val type: Int) : Operation
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
    /**
     * `ConditionalOperations`: the block that follows runs only when [varA] and [varB] compare
     * as [type] says — `TYPE_EQ`(0), `NEQ`(1), `LT`(2), `LTE`(3), `GT`(4), `GTE`(5), or
     * `CHANGED`(6), which fires when either operand differs from the previous evaluation.
     * Closed by a `ContainerEnd`.
     */
    data class ConditionalOperations(val type: Byte, val varA: Float, val varB: Float) : Operation

    data class LoopStart(val indexVariableId: Int, val from: Float, val step: Float, val until: Float) : Operation

    /**
     * `DataListFloat`: a fixed list of floats under [id], readable by the expression evaluator's
     * collection operators.
     *
     * An entry may be written as a NaN-tagged id, and is left that way: nothing in the library
     * resolves list entries — `getFloats` hands back the array as it was read — so a list whose
     * entries are references reads back as those references. A list that follows other values is
     * built with [DynamicFloatList] and [UpdateDynamicFloatList] instead.
     */
    data class FloatListData(val id: Int, val values: FloatArray) : Operation {
        override fun equals(other: Any?): Boolean =
            other is FloatListData && id == other.id && values.contentEquals(other.values)

        override fun hashCode(): Int = id * 31 + values.size

        override fun toString(): String = "FloatListData(id=$id, values=${values.toList()})"
    }

    /**
     * `DataDynamicListFloat`: a list of [length] zeros under [id], where [length] may be a
     * NaN-tagged id and so may change. Its entries are written by [UpdateDynamicFloatList].
     */
    data class DynamicFloatList(val id: Int, val length: Float) : Operation

    /** `UpdateDynamicFloatList`: writes [value] into entry [index] of the list [arrayId]. */
    data class UpdateDynamicFloatList(val arrayId: Int, val index: Float, val value: Float) : Operation

    /**
     * `ReferencedOperations`: a block of operations kept under [id] rather than run where it is
     * written, closed by a `ContainerEnd`.
     *
     * `CoreDocument` collects every one of these into a map as it loads, before anything is
     * expanded, so an [IncludeReferencedOperations] may name one written after it. Unlike a
     * pattern it takes no parameters and supplies no blocks: it is the same operations again.
     */
    data class ReferencedOperations(val id: Int) : Operation

    /**
     * `IncludeReferencedOperations`: puts the block [id] names here.
     *
     * `materialize` re-reads the block's bytes through a forked remap context marked as inside a
     * macro, so each inclusion declares its own ids rather than sharing one set — which is what
     * lets the same block be included twice and hold two different things.
     */
    data class IncludeReferencedOperations(val id: Int) : Operation

    /**
     * `PatternDefine`: a named block of operations with parameters, the format's component.
     *
     * [paramIds] are the ids a call binds its arguments to, so the [body] is written in terms of
     * them. Where the body should hold something the caller supplies instead of something it
     * draws itself, it carries a [PatternArgument]. Unlike every other container, the body is a
     * length-prefixed blob rather than operations in the stream, so it is decoded on its own; a
     * `ContainerEnd` follows the blob.
     */
    data class PatternDefine(val id: Int, val paramIds: List<Int>, val body: List<Operation>) : Operation

    /**
     * `PatternInflation`: runs the pattern [id] with [argIds] bound to its parameters. The block
     * arguments it supplies are the [PatternBlock]s between it and its `ContainerEnd`.
     */
    data class PatternCall(val id: Int, val argIds: List<Int>) : Operation

    /**
     * `PatternArgument`: inside a pattern's body, where the caller's block [paramIndex] goes.
     * Nothing is drawn if the call supplied no such block.
     */
    data class PatternArgument(val paramIndex: Int) : Operation

    /**
     * `PatternBlock`: inside a call, the operations to put where the pattern's body asks for
     * block [paramIndex]. Closed by a `ContainerEnd`.
     */
    data class PatternBlock(val paramIndex: Int) : Operation

    /**
     * `PatternForEach`: runs the block that follows once per entry of the id list [collectionId],
     * with [localItemId] standing for that entry. Closed by a `ContainerEnd`.
     *
     * The real operation is a macro rather than a loop: it serializes its body once and inflates
     * a fresh copy per entry with [localItemId] remapped to the entry's own id, so the expanded
     * document holds one copy of the body per item. What reaches the screen is the same either
     * way, and this evaluator walks the body per entry instead of copying it.
     *
     * Only an id list can be iterated. `ArrayAccess.getId` — what the real operation binds
     * [localItemId] to — is defined on `DataListIds` alone; the float lists inherit its default
     * of -1, so a float list has nothing to bind.
     */
    data class PatternForEach(val collectionId: Int, val localItemId: Int) : Operation

    /**
     * `ShaderData`: an AGSL shader — its source is the text at [shaderTextId] — and the uniforms
     * to bind when it is used. Decoded so the stream stays aligned and the document can be
     * inspected; painting one needs a runtime shader compiler this renderer does not have, so
     * `PaintBundle`'s `SHADER` attribute ignores it (see `docs/OPCODES.md`).
     */
    data class ShaderData(
        val id: Int,
        val shaderTextId: Int,
        val floatUniforms: Map<String, FloatArray>,
        val intUniforms: Map<String, IntArray>,
        val bitmapUniforms: Map<String, Int>,
    ) : Operation {
        override fun equals(other: Any?): Boolean =
            other is ShaderData && id == other.id && shaderTextId == other.shaderTextId &&
                bitmapUniforms == other.bitmapUniforms &&
                floatUniforms.keys == other.floatUniforms.keys &&
                floatUniforms.all { (k, v) -> v.contentEquals(other.floatUniforms[k]) } &&
                intUniforms.keys == other.intUniforms.keys &&
                intUniforms.all { (k, v) -> v.contentEquals(other.intUniforms[k]) }

        override fun hashCode(): Int = id * 31 + shaderTextId

        override fun toString(): String =
            "ShaderData(id=$id, shaderTextId=$shaderTextId, " +
                "floats=${floatUniforms.mapValues { it.value.toList() }}, " +
                "ints=${intUniforms.mapValues { it.value.toList() }}, bitmaps=$bitmapUniforms)"
    }

    /**
     * `MatrixConstant`: stores [values] — 16 for a full matrix, 9 for a 3x3 affine one — as the
     * matrix [id]. Each value may be a NaN-tagged float id.
     */
    data class MatrixConstant(val id: Int, val type: Int, val values: FloatArray) : Operation {
        override fun equals(other: Any?): Boolean =
            other is MatrixConstant && id == other.id && type == other.type && values.contentEquals(other.values)

        override fun hashCode(): Int = id * 31 + type

        override fun toString(): String = "MatrixConstant(id=$id, type=$type, values=${values.toList()})"
    }

    /** `MatrixExpression`: builds the matrix [id] by running [expression] on the matrix machine. */
    data class MatrixExpression(val id: Int, val type: Int, val expression: FloatArray) : Operation {
        override fun equals(other: Any?): Boolean =
            other is MatrixExpression && id == other.id && type == other.type &&
                expression.contentEquals(other.expression)

        override fun hashCode(): Int = id * 31 + type

        override fun toString(): String = "MatrixExpression(id=$id, type=$type, expression=${expression.toList()})"
    }

    /**
     * `MatrixVectorMath`: transforms [inputs] by the matrix [matrixId] and stores the components
     * of the result under [outputs]. [type] 0 transforms a point, anything else divides through
     * by w for a perspective projection.
     */
    data class MatrixVectorMath(
        val type: Int, val outputs: List<Int>, val matrixId: Int, val inputs: FloatArray,
    ) : Operation {
        override fun equals(other: Any?): Boolean =
            other is MatrixVectorMath && type == other.type && outputs == other.outputs &&
                matrixId == other.matrixId && inputs.contentEquals(other.inputs)

        override fun hashCode(): Int = matrixId * 31 + type

        override fun toString(): String =
            "MatrixVectorMath(type=$type, outputs=$outputs, matrixId=$matrixId, inputs=${inputs.toList()})"
    }

    /**
     * `ParticlesCreate`: [particleCount] particles, each with one value per entry of [varIds].
     * A particle's starting values come from [equations], which see the particle's index in the
     * first caller variable slot.
     */
    data class ParticlesCreate(
        val id: Int, val varIds: List<Int>, val equations: List<FloatArray>, val particleCount: Int,
    ) : Operation

    /**
     * `ParticlesLoop`: runs the block that follows once per particle of the system [id], having
     * first advanced that particle by [equations] — one per variable. A particle whose [restart]
     * expression comes out above zero is created again from scratch. Closed by a `ContainerEnd`.
     */
    data class ParticlesLoop(
        val id: Int, val restart: FloatArray, val equations: List<FloatArray>,
    ) : Operation

    /**
     * `ParticlesCompare`: the same, for the particles between [min] and [max] whose [expression]
     * comes out above zero; those particles are advanced by [equations1]. When [equations2] is
     * also present the real operation switches to its two-body form, comparing particles pairwise.
     * Closed by a `ContainerEnd`.
     */
    data class ParticlesCompare(
        val id: Int, val flags: Int, val min: Float, val max: Float, val expression: FloatArray,
        val equations1: List<FloatArray>, val equations2: List<FloatArray>,
    ) : Operation

    /**
     * `BitmapFontData`: a font whose glyphs are bitmaps. [version] 1 and later carry the
     * [kerning] table, keyed by the two glyph strings either side of a join.
     */
    data class BitmapFontData(
        val id: Int, val version: Int, val glyphs: List<BitmapGlyph>, val kerning: Map<String, Int>,
    ) : Operation

    /**
     * `DrawBitmapFontText`: draws `[start, end)` of the text at [textId] in the bitmap font at
     * [fontId], with the run's origin at ([x], [y]) and [glyphSpacing] added after every glyph.
     * An [end] of -1 (or 0 with a non-zero [start]) runs to the end of the string.
     */
    data class DrawBitmapFontText(
        val textId: Int, val fontId: Int, val start: Int, val end: Int,
        val x: Float, val y: Float, val glyphSpacing: Float,
    ) : Operation

    /**
     * `DrawBitmapFontTextOnPath`: the same run laid along the path at [pathId], each glyph
     * centred on its own point and rotated to the tangent, [yAdj] from the path.
     */
    data class DrawBitmapFontTextOnPath(
        val textId: Int, val fontId: Int, val pathId: Int, val start: Int, val end: Int,
        val yAdj: Float, val glyphSpacing: Float,
    ) : Operation

    /**
     * `BitmapTextMeasure`: measures the text at [textId] in the bitmap font at [fontId] and
     * stores one component under [id]. [type] takes the same values as [TextMeasure]'s.
     */
    data class BitmapTextMeasure(
        val id: Int, val textId: Int, val fontId: Int, val type: Int, val glyphSpacing: Float,
    ) : Operation

    /**
     * `PathExpression`: builds the path [id] by sampling [expressionX] and [expressionY] at
     * [count] points between [min] and [max], with the sample position in the expression's first
     * caller variable slot. [flags] carries `LOOP`(1), the join kind in bits 1-2 —
     * `MONOTONIC`(2), `LINEAR`(4), spline when neither — `POLAR`(8), and the winding rule in
     * bits 24-25.
     */
    data class PathExpression(
        val id: Int, val flags: Int, val min: Float, val max: Float, val count: Float,
        val expressionX: FloatArray, val expressionY: FloatArray,
    ) : Operation {
        override fun equals(other: Any?): Boolean =
            other is PathExpression && id == other.id && flags == other.flags && min == other.min &&
                max == other.max && count == other.count &&
                expressionX.contentEquals(other.expressionX) && expressionY.contentEquals(other.expressionY)

        override fun hashCode(): Int = id * 31 + flags

        override fun toString(): String =
            "PathExpression(id=$id, flags=$flags, min=$min, max=$max, count=$count, " +
                "x=${expressionX.toList()}, y=${expressionY.toList()})"
    }

    /**
     * `FloatFunctionDefine`: names the block that follows as a reusable body. [argIds] are the
     * float-pool ids a call binds its arguments to before running the body, which is closed by a
     * `ContainerEnd`. Defining does not run anything.
     */
    data class FloatFunctionDefine(val id: Int, val argIds: List<Int>) : Operation

    /**
     * `FloatFunctionCall`: writes [args] (each a value or a NaN-tagged float id) into the
     * argument ids of the function [id], then runs its body's value operations.
     */
    data class FloatFunctionCall(val id: Int, val args: List<Float>) : Operation
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
    /**
     * `NamedVariable` (opcode `NAMED_VARIABLE`): `[id][type][length][utf8 name]`. Gives a pool
     * value a name the host can find it by; [type] is `STRING_TYPE` 0, `FLOAT_TYPE` 1,
     * `COLOR_TYPE` 2, `IMAGE_TYPE` 3, `INT_TYPE` 4, `LONG_TYPE` 5, and 6 for both
     * `FLOAT_ARRAY_TYPE` and `PATH_TYPE`, which the real class gives the same number.
     */
    /**
     * `TextAttribute` (opcode `ATTRIBUTE_TEXT`): `[id][textId][short type][short discarded]`.
     * Stores one measurement of the text as the float [id]. Types are `MEASURE_WIDTH` 0,
     * `MEASURE_HEIGHT` 1, the four bounds edges `LEFT` 2 / `RIGHT` 3 / `TOP` 4 / `BOTTOM` 5, and
     * `TEXT_LENGTH` 6; the high byte carries the monospace and max-height measuring flags.
     */
    /**
     * `HostNamedActionOperation` (opcode `HOST_NAMED_ACTION`): `[textId][type][valueId]`. Runs a
     * host action named by the text at [textId], carrying the value at [valueId] read as [type]:
     * `FLOAT_TYPE` 0, `INT_TYPE` 1, `STRING_TYPE` 2, `FLOAT_ARRAY_TYPE` 3, `NONE_TYPE` -1.
     */
    /**
     * `TimeAttribute` (opcode `ATTRIBUTE_TIME`): `[id][timeId][short type][short argCount]` then
     * [args] ids. Stores one part of a moment as the float [id]. The moment is the long at
     * [timeId] when there is one and now otherwise; types 3..5 measure from the long at `args[0]`
     * instead of from now.
     */
    /**
     * `DrawBitmapTextAnchored` (opcode `DRAW_BITMAP_TEXT_ANCHORED`): the bitmap-font run
     * `[start, end)` positioned by [panX]/[panY] about `(x, y)` rather than starting there, the
     * way `DRAW_TEXT_ANCHOR` positions ordinary text. The text id carries the glyph-spacing flag
     * in its top bit as the rest of the bitmap-font family does.
     */
    /**
     * `FontData` (opcode `DATA_FONT`): `[fontId][type][length][bytes]`, an embedded font file.
     * `apply` is `loadFont(fontId, bytes)`.
     *
     * Kept rather than drawn with: nothing in remote-core or its creation library points a paint
     * at a loaded font's id. `TYPEFACE` and `TextStyle`'s `P_FONT_FAMILY` both name a family by
     * string, and the concrete `loadFont` that would register these bytes under such a name is
     * not in the extracted jars. See `docs/OPCODES.md`.
     */
    /**
     * The parameters of a `TEXT_STYLE` or `CORE_TEXT`, by their `TextStyle.P_*` key.
     *
     * Both records are a run of `CommandParameters` entries: a key byte, then a value whose type
     * comes from `TextStyle.PARAMETERS` rather than the wire — `CoreText` reads against that same
     * table. Values are `Int`, `Float`, `Boolean`, `IntArray`, `FloatArray` or `String`.
     */
    data class StyleParameters(val values: Map<Int, Any>) {
        fun int(key: Int): Int? = values[key] as? Int
        fun float(key: Int): Float? = values[key] as? Float
        fun boolean(key: Int): Boolean? = values[key] as? Boolean

        companion object {
            const val P_ID = 1
            const val P_ANIMATION_ID = 2
            const val P_COLOR = 3
            const val P_COLOR_ID = 4
            const val P_FONT_SIZE = 5
            const val P_FONT_STYLE = 6
            const val P_FONT_WEIGHT = 7
            const val P_FONT_FAMILY = 8
            const val P_TEXT_ALIGN = 9
            const val P_OVERFLOW = 10
            const val P_MAX_LINES = 11
            const val P_LETTER_SPACING = 12
            const val P_LINE_HEIGHT_ADD = 13
            const val P_LINE_HEIGHT_MULTIPLIER = 14
            const val P_BREAK_STRATEGY = 15
            const val P_HYPHENATION_FREQUENCY = 16
            const val P_JUSTIFICATION_MODE = 17
            const val P_UNDERLINE = 18
            const val P_STRIKETHROUGH = 19
            const val P_FONT_AXIS = 20
            const val P_FONT_AXIS_VALUES = 21
            const val P_AUTOSIZE = 22
            const val P_FLAGS = 23
            const val P_PARENT_ID = 24
            const val P_MIN_FONT_SIZE = 25
            const val P_MAX_FONT_SIZE = 26
        }
    }

    /** `TextStyle` (opcode `TEXT_STYLE`): a named bundle of text parameters, `[short count]` then them. */
    data class TextStyleData(val parameters: StyleParameters) : Operation

    /**
     * `CoreText` (opcode `CORE_TEXT`): the richer text component, `[textId][short count]` then
     * the parameters. `RemoteComposeBuffer.addTextComponentStart` writes this one or `TextLayout`
     * depending on which overload the caller used.
     *
     * The component's own id is the `P_ID` parameter rather than the leading int, which is the
     * text — checked against the bytes the writer produces.
     */
    data class CoreText(val textId: Int, val parameters: StyleParameters) : Operation

    data class FontData(val fontId: Int, val type: Int, val bytes: ByteArray) : Operation {
        override fun toString(): String = "FontData(fontId=$fontId, type=$type, bytes=${bytes.size})"
    }

    data class DrawBitmapTextAnchored(
        val textId: Int, val fontId: Int, val start: Float, val end: Float,
        val x: Float, val y: Float, val panX: Float, val panY: Float, val glyphSpacing: Float,
    ) : Operation

    data class TimeAttribute(val id: Int, val timeId: Int, val type: Int, val args: List<Int>) : Operation

    data class HostNamedAction(val textId: Int, val type: Int, val valueId: Int) : Operation

    /**
     * `HostActionMetadataOperation` (opcode `HOST_METADATA_ACTION`): `[actionId][metadataId]`,
     * `runAction(actionId, getText(metadataId))` — the plain host action with a string beside it.
     */
    data class HostMetadataAction(val actionId: Int, val metadataId: Int) : Operation

    data class TextAttribute(val id: Int, val textId: Int, val type: Int) : Operation

    /**
     * `ImageAttribute` (opcode `ATTRIBUTE_IMAGE`): `[id][imageId][short type][short argCount]`
     * then [args] ids. Stores `IMAGE_WIDTH` 0 or `IMAGE_HEIGHT` 1 of the bitmap as the float [id].
     */
    data class ImageAttribute(val id: Int, val imageId: Int, val type: Int, val args: List<Int>) : Operation

    data class NamedVariable(val id: Int, val type: Int, val name: String) : Operation

    data class TouchExpression(
        val id: Int, val defValue: Float, val min: Float, val max: Float, val velocity: Float, val flags: Int,
        val srcExp: List<Float>, val tapExpPacked: Int, val tapExp: List<Float>, val tapExpFloats: List<Float>,
    ) : Operation
}
