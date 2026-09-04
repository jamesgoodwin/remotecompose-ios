package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.model.RemoteDocument

/**
 * Parses the **real** `androidx.compose.remote` wire format (v1.0.0-alpha18), as produced by the
 * official `androidx.compose.remote:remote-creation-jvm` writer — not the placeholder format
 * [RemoteComposeParser] was built against.
 *
 * This is deliberately narrow: it understands exactly the real opcodes a minimal
 * `RemoteComposeWriter(width, height, contentDescription, platform)` document produces when built
 * from `getRcPaint().setColor(...).commit()` plus `drawRect`/`drawCircle`/`drawRoundRect`/
 * `drawTextAnchored` calls, reverse-engineered by hex-dumping actual output from the official
 * writer (see the opcode constants below and `tools/rc-writer`, which generates the payloads this
 * was verified against). Real documents in general use 100+ opcodes across versioned "profiles"
 * (`androidx.compose.remote.core.Operations`), each with its own fixed field layout dispatched by
 * a per-opcode `CompanionOperation` — there is no generic length-prefix that would let a reader
 * skip an opcode it doesn't recognize (unlike the skippable framing [RemoteComposeParser] assumes).
 * An opcode outside this subset is therefore a hard parse failure here, not a graceful skip.
 *
 * Confirmed against real output from `androidx.compose.remote:remote-core` /
 * `remote-creation-jvm:1.0.0-alpha18`:
 * - All integers/floats are big-endian, IEEE-754 raw bits for floats — this part matches what
 *   [BufferReader] already assumed for the placeholder format, so its primitive readers are reused
 *   as-is.
 * - There is **no LEB128 varint** anywhere; lengths and counts are fixed 4-byte big-endian ints
 *   (`WireBuffer.writeInt`), unlike [RemoteComposeParser]'s varint-based framing.
 * - Every operation record starts with a single opcode byte (`Operations.<NAME>`), with **no**
 *   generic length prefix — the reader must know each opcode's exact shape.
 *
 * Once resolved into an [Opcode] list, rendering is identical to the placeholder path — this
 * parser feeds the exact same [com.example.remotecompose.engine.OpcodeExecutor] /
 * [com.example.remotecompose.ui.RemoteComposeCanvas], since those only depend on the abstract
 * [Opcode] model, not on which wire format produced it.
 */
object RealRemoteComposeParser {

    /** `Operations.HEADER` — document metadata, written in "flat" (non-map) form by this writer version. */
    private const val OP_HEADER = 0

    /** `Operations.DATA_TEXT` — a `(id, UTF-8 string)` entry in the document's text pool. */
    private const val OP_DATA_TEXT = 102

    /** `Operations.ROOT_CONTENT_DESCRIPTION` — a single int reference into the text pool. */
    private const val OP_ROOT_CONTENT_DESCRIPTION = 103

    /**
     * A paint-property bundle (observed opcode id 40; the real symbolic `Operations` name wasn't
     * confirmed against source, only its wire shape). Framed as
     * `[wordCount:i32][wordCount × i32]`, i.e. self-describing by word count rather than a fixed
     * shape — the one real opcode here that *is* generically skippable. Observed as
     * `(tag=4, argbColor)` for a single `setColor(...).commit()` call; this parser only extracts
     * the last word as a color, which holds for that single-property case.
     */
    private const val OP_PAINT_BUNDLE = 40

    /** `Operations.DRAW_RECT` — `[left,top,right,bottom]` as four raw floats, no length prefix. */
    private const val OP_DRAW_RECT = 42

    /** `Operations.DRAW_CIRCLE` — `[centerX,centerY,radius]` as three raw floats. */
    private const val OP_DRAW_CIRCLE = 46

    /** `Operations.DRAW_ROUND_RECT` — `[left,top,right,bottom,radiusX,radiusY]` as six raw floats. */
    private const val OP_DRAW_ROUND_RECT = 51

    /**
     * The real op `drawTextAnchored(text, x, y, panX, panY, flags)` writes: a `DATA_TEXT` entry
     * for the string, then this opcode (observed id 133) referencing it. Payload:
     * `[textId:i32][x:f32][y:f32][panX:f32][panY:f32][flags:i32]` — panX/panY/flags are read to
     * stay aligned with the stream but not modeled by [Opcode.DrawText], which has no anchor or
     * flags concept.
     */
    private const val OP_DRAW_TEXT_ANCHORED = 133

    /** `Operations.DRAW_LINE` — `[x1,y1,x2,y2]` as four raw floats. */
    private const val OP_DRAW_LINE = 47

    /** `Operations.DRAW_OVAL` — `[left,top,right,bottom]` as four raw floats (bounds of the ellipse). */
    private const val OP_DRAW_OVAL = 56

    /**
     * `Operations.DRAW_ARC` — `[left,top,right,bottom,startAngle,sweepAngle]` as six raw floats,
     * an open arc (no line back to center — `Opcode.DrawArc.useCenter = false`).
     */
    private const val OP_DRAW_ARC = 152

    /**
     * `Operations.DRAW_SECTOR` — same six-float shape as [OP_DRAW_ARC], but a closed pie slice
     * (`Opcode.DrawArc.useCenter = true`). The real format distinguishes arc-vs-sector by opcode
     * id, not by a flag in the payload.
     */
    private const val OP_DRAW_SECTOR = 52

    /**
     * `Operations.DATA_PATH` — defines a reusable path resource: `[pathId:i32][floatCount:i32]`
     * followed by `floatCount` raw i32 words forming `RemotePathBase`'s flat, NaN-tagged command
     * array (see `androidx.compose.remote.core.RemotePathBase` — [Utils.asNan]-style sentinels,
     * not plain floats, mark where each command starts).
     *
     * Confirmed against real output from `moveTo`/`lineTo`/`close()`, then separately
     * `moveTo`/`quadTo`/`cubicTo`/`close()`, paths: the encoder has a documented bug
     * (`RemotePathBase.add(int,float,float)`: "THIS IS FLAW in the encoding TODO FIX ON
     * VERSIONING") that advances the write cursor 2 slots too many before writing a command's
     * real coordinates, leaving 2 zeroed/garbage floats between a command's tag and its actual
     * arguments for every non-[PATH_CMD_MOVE]/[PATH_CMD_CLOSE] command — this reader has to
     * reproduce that exact padding to stay aligned, not just skip it as a curiosity. Per-command
     * stride (tag + padding + real args) is verified for [PATH_CMD_MOVE]/[PATH_CMD_LINE]/
     * [PATH_CMD_QUADRATIC]/[PATH_CMD_CUBIC]/[PATH_CMD_CLOSE]; only [PATH_CMD_CONIC] remains
     * unverified — its 2-word padding is the same bug applied to a same-shaped `add()` overload,
     * but that specific overload hasn't been exercised against real bytes, so it's still a hard
     * parse failure here rather than an unverified extrapolation.
     */
    private const val OP_DATA_PATH = 123

    /** `Operations.DRAW_PATH` — `[pathId:i32]`, referencing a [OP_DATA_PATH] resource. */
    private const val OP_DRAW_PATH = 124

    /**
     * `Operations.CLIP_PATH` — `RemoteComposeBuffer.addClipPath(pathId)` writes just `[pathId:i32]`
     * (5 bytes total, confirmed against real output: nothing but the opcode byte and one int
     * before the next opcode) — the real op's `ClipPath.apply(WireBuffer, Int)` only ever takes
     * the id; a region-op (replace/intersect/union/etc, packed into the *read*-side int's high
     * byte) is never written by this simple `addClipPath(int)` call path, so it always decodes as
     * 0/REPLACE here — modeled as an intersect via the same [Opcode.ClipPath] the executor already
     * implements (Compose's own `DrawTransform.clipPath` has no separate replace mode to honor
     * anyway). References an id already registered by a prior [OP_DATA_PATH].
     */
    private const val OP_CLIP_PATH = 38

    // RemotePathBase command tags (source-confirmed values), NaN-encoded via Utils.asNan(tag) —
    // i.e. an IEEE-754 float bit pattern with sign=1, exponent=0xFF, mantissa=tag.
    private const val PATH_CMD_MOVE = 10
    private const val PATH_CMD_LINE = 11
    private const val PATH_CMD_QUADRATIC = 12
    private const val PATH_CMD_CONIC = 13
    private const val PATH_CMD_CUBIC = 14
    private const val PATH_CMD_CLOSE = 15

    /** Mask isolating sign+exponent; a path-array word is a command tag iff these bits are all set. */
    private const val NAN_TAG_MASK = -0x800000 // 0xFF800000 as a 32-bit Int

    /**
     * `Operations.DATA_BITMAP` — defines a reusable image resource:
     * `[bitmapId:i32][width:i32][height:i32][pngByteLength:i32][pngBytes...]`. The image bytes
     * are a real, directly-decodable encoded image (confirmed via magic bytes `\x89PNG\r\n\x1a\n`)
     * — not a raw pixel dump — so they can be handed straight to [BitmapPool] unchanged, the same
     * as [RemoteComposeParser]'s placeholder bitmap pool.
     */
    private const val OP_DATA_BITMAP = 101

    /**
     * `Operations.DRAW_BITMAP` — `[bitmapId:i32][left:f32][top:f32][right:f32][bottom:f32]
     * [contentDescriptionTextId:i32]`. The trailing text-pool reference (the `drawBitmap(...,
     * contentDescription)` string) is read to stay aligned but not used, the same as
     * [OP_ROOT_CONTENT_DESCRIPTION]'s.
     */
    private const val OP_DRAW_BITMAP = 44

    /**
     * `Operations.CLICK_AREA` — `addClickArea(actionId, contentDescription, left, top, right,
     * bottom, metadata)` writes `[actionId:i32][contentDescriptionTextId:i32][left:f32][top:f32]
     * [right:f32][bottom:f32][metadataTextId:i32]`. `metadata` is exactly the target-URL string
     * [Opcode.ActionClick.targetUrlStringIndex] expects; `contentDescription` is read to stay
     * aligned but not modeled, same pattern as [OP_DRAW_BITMAP]'s.
     */
    private const val OP_CLICK_AREA = 64

    /**
     * `Operations.LAYOUT_COLUMN` — `startColumn(modifier, horizontalPositioning,
     * verticalPositioning)` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]`.
     *
     * Most layout container opcodes ([OP_LAYOUT_BOX]/[OP_LAYOUT_FLOW]/etc, [OP_LAYOUT_CONTENT]/
     * [OP_CONTAINER_END]) are still treated as **pass-through scope markers**: exactly the right
     * bytes are consumed to stay aligned, but no repositioning happens — correct only for a
     * document whose children already carry final, non-overlapping absolute coordinates.
     * [OP_LAYOUT_COLUMN] and [OP_LAYOUT_ROW] are the exception: this renderer still has no real
     * measure pass (nothing here computes a child's size before it's drawn), but once a child's
     * own content has been parsed, its *drawn* extent is known after the fact — enough to really
     * stack children in document order along the container's axis, `spacedBy` apart, rather than
     * trusting the document's own absolute placement for them. See `arrangeChildren` (in
     * [RealRemoteComposeParser.parse]) for the mechanism and its honest limits (no real alignment
     * modes, no reserved-but-empty space, first child's own position is the anchor).
     */
    private const val OP_LAYOUT_COLUMN = 204

    /** `Operations.LAYOUT_ROW` — identical shape to [OP_LAYOUT_COLUMN]'s (source-confirmed: same `apply()` structure). */
    private const val OP_LAYOUT_ROW = 203

    /**
     * `Operations.LAYOUT_COLLAPSIBLE_COLUMN`/`LAYOUT_COLLAPSIBLE_ROW` — `startCollapsibleColumn`/
     * `startCollapsibleRow` write the exact same `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]` shape as
     * [OP_LAYOUT_COLUMN]/[OP_LAYOUT_ROW] (confirmed byte-for-byte against real output), just under
     * different opcode numbers, and are closed the same way (a `LAYOUT_CONTENT` children marker,
     * then two [OP_CONTAINER_END]s).
     */
    private const val OP_LAYOUT_COLLAPSIBLE_COLUMN = 233
    private const val OP_LAYOUT_COLLAPSIBLE_ROW = 230

    /**
     * `Operations.LAYOUT_FLOW` — `startFlow` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]
     * [maxItemsInMainAxis:i32][maxLinesInCrossAxis:i32]` — [OP_LAYOUT_COLUMN]'s shape plus two
     * trailing ints, confirmed via real output (`maxItemsInMainAxis`/`maxLinesInCrossAxis` default
     * to `Int.MAX_VALUE` when unset). Closed the same way as the other layout containers (a
     * `LAYOUT_CONTENT` children marker, then two [OP_CONTAINER_END]s).
     */
    private const val OP_LAYOUT_FLOW = 240

    /**
     * `Operations.LAYOUT_FIT_BOX` — `startFitBox` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32]`, the exact same 4-int shape as
     * [OP_LAYOUT_BOX] (no `spacedBy`, confirmed against real output), closed the same way as the
     * other layout containers.
     */
    private const val OP_LAYOUT_FIT_BOX = 176

    /**
     * `Operations.LAYOUT_ROOT` — `startRoot()`/`endRoot()` write only `[componentId:i32]` (no
     * `LAYOUT_CONTENT` children marker, unlike every other container here — children follow
     * directly), closed by a single [OP_CONTAINER_END] (not two, confirmed against real output —
     * `endRoot()` calls `addContainerEnd()` exactly once).
     */
    private const val OP_LAYOUT_ROOT = 200

    /**
     * `Operations.LAYOUT_STATE` — `startStateLayout` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][stateIndex:i32]` (5 ints, confirmed
     * against real output), closed the same way as most other layout containers (a
     * `LAYOUT_CONTENT` children marker, then two [OP_CONTAINER_END]s).
     */
    private const val OP_LAYOUT_STATE = 217

    /**
     * `Operations.LAYOUT_CANVAS` — `startCanvas` writes only `[componentId:i32][animationId:i32]`
     * (2 ints, no positioning/spacing — confirmed against real output), followed by a
     * `LAYOUT_CONTENT` marker, then `Operations.LAYOUT_CANVAS_CONTENT` (opcode 207,
     * [OP_LAYOUT_CANVAS_CONTENT] below) wrapping a single auto-assigned `[componentId:i32]`.
     * `endCanvas()` closes with **three** [OP_CONTAINER_END]s (one per nested scope), not the
     * usual two.
     */
    private const val OP_LAYOUT_CANVAS = 205
    private const val OP_LAYOUT_CANVAS_CONTENT = 207

    /**
     * `Operations.LAYOUT_CUSTOM` — `startCustom(modifier, name, properties)` first registers
     * `name` via a normal `DATA_TEXT` op, then writes `[componentId:i32][animationId:i32]
     * [nameTextId:i32][propertyCount:i32]` followed by `propertyCount` entries of `[type:i16]
     * [dataType:i16][value:4 bytes]` (int or float depending on `dataType`). Confirmed via
     * `startCustom(RecordingModifier(), "myCustom", emptyList())`: componentId decodes as literal
     * `-1` (not auto-numbered like other containers — `Custom.apply()` uses the modifier's raw
     * `getComponentId()` directly, bypassing the usual auto-assign-next-negative-id helper).
     */
    private const val OP_LAYOUT_CUSTOM = 93

    /**
     * `Operations.LAYOUT_IMAGE` — `writer.image(modifier, scaleType, bitmapId, alpha)` writes
     * `[componentId:i32][animationId:i32][scaleType:i32][bitmapId:i32][alpha:f32]` (confirmed via
     * `image(modifier, 3, 1, 0.75f)` decoding to exactly `[3, 1, 0.75]`). A leaf component — no
     * `LAYOUT_CONTENT` children marker — closed by a single [OP_CONTAINER_END].
     */
    private const val OP_LAYOUT_IMAGE = 234

    /**
     * `Operations.HAPTIC_FEEDBACK`/`THEME`/`ROOT_CONTENT_BEHAVIOR` — top-level document metadata
     * ops (not nested in any container). `performHaptic(id)` writes `[id:i32]`; `setTheme(theme)`
     * writes `[theme:i32]`; `setRootContentBehavior(a, b, c, d)` writes `[a:i32][b:i32][c:i32]
     * [d:i32]`. Confirmed via real output for all three.
     */
    private const val OP_HAPTIC_FEEDBACK = 177
    private const val OP_THEME = 63
    private const val OP_ROOT_CONTENT_BEHAVIOR = 65

    /**
     * `Operations.ANIMATION_SPEC` — `RecordingModifier.animationSpec(animationId)` writes
     * `[animationId:i32][motionDuration:f32][motionEasingType:i32][visibilityDuration:f32]
     * [visibilityEasingType:i32][enterAnimation:i32][exitAnimation:i32]` (7 fields, the last two
     * being `AnimationSpec.ANIMATION` enum ordinals). Confirmed via `animationSpec(3)`'s
     * hardcoded defaults decoding to exactly `[3, 300.0, 1, 300.0, 1, 0, 1]`.
     */
    private const val OP_ANIMATION_SPEC = 14

    /**
     * `Operations.LAYOUT_BOX` — `[componentId:i32][animationId:i32][horizontalPositioning:i32]
     * [verticalPositioning:i32]`, i.e. [OP_LAYOUT_COLUMN]'s shape minus the trailing `spacedBy`
     * float (source-confirmed: `BoxLayout.apply()` has no spacing concept, boxes stack children
     * rather than distributing them along an axis).
     */
    private const val OP_LAYOUT_BOX = 202

    /**
     * `Operations.LAYOUT_CONTENT` — `[componentId:i32]`, marking the start of a container's
     * children (a `LAYOUT_COLUMN`/`LAYOUT_ROW`/`LAYOUT_BOX`'s body). See [OP_LAYOUT_COLUMN]'s KDoc
     * for why this parser only consumes it rather than modeling it.
     */
    private const val OP_LAYOUT_CONTENT = 201

    /**
     * `Operations.CONTAINER_END` — no payload. `startColumn`/`endColumn` emits this **twice**
     * (closing [OP_LAYOUT_CONTENT]'s children block, then [OP_LAYOUT_COLUMN]'s own scope) —
     * confirmed against real output, not assumed from the single `endColumn()` call site.
     */
    private const val OP_CONTAINER_END = 214

    /**
     * `Operations.MODIFIER_WIDTH` — `RecordingModifier.width(float)` writes `[mode:i32][value:f32]`
     * (mode observed as 0/[DIMENSION_MODE_EXACT] for a fixed-size `width(float)`; the other
     * [DIMENSION_MODE_EXACT] siblings are sizing *strategies* — FILL/WRAP/WEIGHT/INTRINSIC_* —
     * this renderer has no layout pass to resolve). Written immediately after its component's own
     * layout op (e.g. [OP_LAYOUT_BOX]) and before [OP_LAYOUT_CONTENT]. When the mode is a real
     * target size, the value is captured onto the current container's [ScopeFrame] as
     * [ScopeFrame.explicitWidthPx] — used by `arrangeChildren` (a LAYOUT_COLUMN/LAYOUT_ROW's real
     * child arrangement) as the container's known main/cross-axis extent; otherwise ignored, same
     * rationale as most other layout container opcodes.
     */
    private const val OP_MODIFIER_WIDTH = 16

    /** `Operations.MODIFIER_HEIGHT` — same `[mode:i32][value:f32]` shape as [OP_MODIFIER_WIDTH]. */
    private const val OP_MODIFIER_HEIGHT = 67

    /**
     * `Operations.MODIFIER_CLICK` — `RecordingModifier.onClick(vararg Action)` writes **no
     * payload of its own**; it's purely a marker opening a nested action list (one or more
     * [OP_HOST_ACTION]-shaped ops, or other action kinds this parser doesn't decode) that runs
     * when the component is tapped, closed by a generic [OP_CONTAINER_END] — the same closing
     * opcode the layout containers use, reused here for a third kind of scope.
     *
     * A real component-level click handler like this is architecturally the same problem as
     * [OP_LAYOUT_COLUMN]'s dynamic arrangement: knowing *which pixels* trigger it requires the
     * measure/layout pass this renderer doesn't have, unlike [OP_CLICK_AREA]'s explicit rect. So
     * this is consumed as a pass-through, not wired into [com.example.remotecompose.model.Opcode.ActionClick].
     */
    private const val OP_MODIFIER_CLICK = 59

    /** `Operations.HOST_ACTION` — `HostAction(actionId)` writes `[actionId:i32]`. */
    private const val OP_HOST_ACTION = 209

    /**
     * `Operations.MODIFIER_PADDING` — `RecordingModifier.padding(float)` writes four raw floats
     * (all equal to the single value passed, for the one-arg overload — presumably
     * top/bottom/left/right independently for the four-arg overload, unconfirmed order).
     */
    private const val OP_MODIFIER_PADDING = 58

    /**
     * `Operations.MODIFIER_BACKGROUND` — `RecordingModifier.background(Int)` writes nine raw
     * floats: four leading values (observed all `0.0` here — presumably per-corner radii,
     * unconfirmed since this call used no rounding), then the color as four **normalized `0f..1f`
     * channel floats** (not a packed ARGB int — confirmed: `0xFF7B1FA2` decoded here as
     * `[0.4824, 0.1216, 0.6353, 1.0]`, exactly `R/255, G/255, B/255, A/255`), then one trailing
     * `0.0` whose role isn't confirmed.
     */
    private const val OP_MODIFIER_BACKGROUND = 55

    /** `Operations.MODIFIER_VISIBILITY` — `RecordingModifier.visibility(int)` writes a single raw int. */
    private const val OP_MODIFIER_VISIBILITY = 211

    /** `Operations.MODIFIER_OFFSET` — `RecordingModifier.offset(x, y)` writes `[x:f32][y:f32]`. */
    private const val OP_MODIFIER_OFFSET = 221

    /**
     * `Operations.MODIFIER_BORDER` — `RecordingModifier.border(width, roundedCorner, color,
     * shapeType)` writes 4 raw ints then 6 raw floats then 1 raw int (44 bytes), confirmed via
     * `border(2f, 4f, 0xFF000000, 0)`: `[0, 0, 0, 0][2.0, 4.0, 0.0, 0.0, 0.0, 1.0][0]` — a
     * colorId-ref flag/id/legacy-flag/reserved int quad, then borderWidth, roundedCorner, and the
     * color as normalized r/g/b/a floats (same normalized-channel convention as
     * [OP_MODIFIER_BACKGROUND]), then a trailing shapeType int.
     */
    private const val OP_MODIFIER_BORDER = 107

    /**
     * `Operations.MODIFIER_CLIP_RECT` — `RecordingModifier.clip(RectShape(...))` writes only the
     * opcode tag, no payload — confirmed: the very next byte is the following opcode
     * (`LAYOUT_CONTENT`'s `0xc9`), with nothing in between.
     */
    private const val OP_MODIFIER_CLIP_RECT = 108

    /**
     * `Operations.MODIFIER_ROUNDED_CLIP_RECT` — `RecordingModifier.clip(RoundedRectShape(topStart,
     * topEnd, bottomStart, bottomEnd))` writes those 4 raw floats, confirmed via
     * `RoundedRectShape(4f, 4f, 4f, 4f)` decoding to exactly `[4.0, 4.0, 4.0, 4.0]`.
     */
    private const val OP_MODIFIER_ROUNDED_CLIP_RECT = 54

    /**
     * `Operations.MODIFIER_MULTI_CLICK` — `RecordingModifier.onLongClick`/`onDoubleClick` write a
     * single raw int (the click-type discriminant: long=1, double=2 — confirmed via
     * `onLongClick(HostAction(9))` decoding to `[1]`) before opening the same nested
     * action-list-closed-by-[OP_CONTAINER_END] shape as [OP_MODIFIER_CLICK].
     */
    private const val OP_MODIFIER_MULTI_CLICK = 83

    /**
     * `Operations.MODIFIER_TOUCH_DOWN`/`_UP`/`_CANCEL` — `RecordingModifier.onTouchDown`/
     * `onTouchUp`/`onTouchCancel` each write only the opcode tag (no payload) before opening the
     * same nested action-list-closed-by-[OP_CONTAINER_END] shape as [OP_MODIFIER_CLICK], confirmed
     * via `onTouchDown/Up/Cancel(HostAction(9))` each decoding to the opcode immediately followed
     * by [OP_HOST_ACTION]'s `[9]` then [OP_CONTAINER_END].
     */
    private const val OP_MODIFIER_TOUCH_DOWN = 219
    private const val OP_MODIFIER_TOUCH_UP = 220
    private const val OP_MODIFIER_TOUCH_CANCEL = 225

    /**
     * `Operations.MODIFIER_WIDTH_IN`/`MODIFIER_HEIGHT_IN` — `RecordingModifier.widthIn(min, max)`/
     * `heightIn(min, max)` write `[min:f32][max:f32]`, the same two-float shape as
     * [OP_MODIFIER_WIDTH]/[OP_MODIFIER_HEIGHT] (which instead carry a leading mode int), confirmed
     * via `widthIn(10f, 20f)` decoding to exactly `[10.0, 20.0]`.
     */
    private const val OP_MODIFIER_WIDTH_IN = 231
    private const val OP_MODIFIER_HEIGHT_IN = 232

    /**
     * `Operations.MODIFIER_COLLAPSIBLE_PRIORITY` — `RecordingModifier.collapsiblePriority(orientation,
     * priority)` writes `[orientation:i32][priority:f32]`, confirmed via `collapsiblePriority(0, 2f)`
     * decoding to exactly `[0, 2.0]`.
     */
    private const val OP_MODIFIER_COLLAPSIBLE_PRIORITY = 235

    /**
     * `Operations.MODIFIER_ALIGN_BY` — `RecordingModifier.alignByBaseline()` writes
     * `[line:f32][flag:i32]` (line is a NaN-tagged baseline-kind constant, not a plain coordinate;
     * flag observed `0`), confirmed via `alignByBaseline()` decoding to a NaN-payload float
     * followed by `[0]`.
     */
    private const val OP_MODIFIER_ALIGN_BY = 237

    /**
     * `Operations.MODIFIER_ZINDEX` — the (not directly exposed on `RecordingModifier`, reached via
     * `.then(ZIndexModifier(value))`) z-index modifier writes a single raw float, confirmed via
     * `ZIndexModifier(3f)` decoding to exactly `[3.0]`.
     */
    private const val OP_MODIFIER_ZINDEX = 223

    /**
     * `Operations.MODIFIER_RIPPLE` — reached via `.then(RippleModifier())` (also not a direct
     * `RecordingModifier` method) — writes only the opcode tag, no payload, confirmed: the very
     * next byte is [OP_LAYOUT_CONTENT]'s `0xc9`.
     */
    private const val OP_MODIFIER_RIPPLE = 229

    /**
     * `Operations.MODIFIER_DRAW_CONTENT` — `RecordingModifier.drawContent()`/`drawWithContent()`
     * write only the opcode tag, no payload, confirmed: the very next byte is
     * [OP_LAYOUT_CONTENT]'s `0xc9`.
     */
    private const val OP_MODIFIER_DRAW_CONTENT = 174

    /**
     * `Operations.MODIFIER_MARQUEE` — reached via `.then(MarqueeModifier(iterations, animationMode,
     * repeatDelayMillis, initialDelayMillis, spacing, velocity))` (not a direct method either) —
     * writes `[iterations:i32][animationMode:i32][repeatDelay:f32][initialDelay:f32][spacing:f32]
     * [velocity:f32]`, confirmed via `MarqueeModifier(1, 0, 1000f, 500f, 8f, 30f)` decoding to
     * exactly `[1, 0, 1000.0, 500.0, 8.0, 30.0]`.
     */
    private const val OP_MODIFIER_MARQUEE = 228

    /**
     * `Operations.MODIFIER_GRAPHICS_LAYER` — reached via `.then(GraphicsLayerModifier().apply {
     * setFloatAttribute(key, value) })` (a `HashMap<Int, Any>` of attributes, not a direct method)
     * — writes `[count:i32]` then `count` entries of `[tag:i32][value:4 bytes]`, where `tag` is the
     * attribute key OR'd with `0x400` for a float value (else a plain int). Confirmed via
     * `setFloatAttribute(11 /* ALPHA */, 0.5f)` decoding to `[1, [0x40B, 0.5]]`.
     */
    private const val OP_MODIFIER_GRAPHICS_LAYER = 224

    /**
     * `GraphicsLayerModifierOperation.ALPHA` (`= 11`) OR'd with the float-value tag bit
     * (`0x400`) — the tag [OP_MODIFIER_GRAPHICS_LAYER] uses to mark an entry as the layer's
     * opacity, the one attribute this parser gives real effect to (see [Opcode.SaveLayerAlpha]).
     */
    private const val GRAPHICS_LAYER_ALPHA_TAG = 11 or 0x400

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
    private const val DIMENSION_MODE_EXACT_DP = 6

    /**
     * `Operations.MODIFIER_DIMENSION_CONSTRAINTS` — reached via `.then(WidthInModifier(type, min,
     * max))`'s 3-arg constructor (the public 2-arg `widthIn(min, max)` always takes the
     * [OP_MODIFIER_WIDTH_IN] path instead) — writes `[type:byte][min:f32][max:f32]`, a single raw
     * **byte** rather than the usual `i32`, confirmed via `WidthInModifier(1, 5f, 40f)` decoding to
     * exactly `[1, 5.0, 40.0]` in a 9-byte payload (1+4+4, not 1+4+4+3 padding).
     */
    private const val OP_MODIFIER_DIMENSION_CONSTRAINTS = 243

    /**
     * `Operations.VALUE_INTEGER_CHANGE_ACTION` — `ValueIntegerChange(valueId, value)`, an `Action`
     * subtype usable anywhere [OP_HOST_ACTION] is (inside `onClick`/`onLongClick`/etc's nested
     * action list) — writes `[valueId:i32][value:i32]`, confirmed via `ValueIntegerChange(3, 7)`
     * decoding to exactly `[3, 7]`.
     */
    private const val OP_VALUE_INTEGER_CHANGE = 212

    /**
     * `Operations.VALUE_STRING_CHANGE_ACTION` — `ValueStringChange(valueId, string)` first
     * registers the string via a normal [OP_DATA_TEXT] op (the same text pool `DrawTextAnchored`
     * uses), then writes `[valueId:i32][stringId:i32]` referencing it. Confirmed via
     * `ValueStringChange(5, "hi")` decoding to a `DATA_TEXT` registration followed by `[5, <that
     * id>]`.
     */
    private const val OP_VALUE_STRING_CHANGE = 213

    /**
     * `Operations.VALUE_FLOAT_CHANGE_ACTION` — `ValueFloatChange(valueId, value)` writes
     * `[valueId:i32][value:f32]`, confirmed via `ValueFloatChange(4, 2.5f)` decoding to exactly
     * `[4, 2.5]`.
     */
    private const val OP_VALUE_FLOAT_CHANGE = 222

    /**
     * `Operations.VALUE_INTEGER_EXPRESSION_CHANGE_ACTION` — `ValueIntegerExpressionChange(valueId,
     * value)` writes `[valueId:i64][value:i64]` — the first action using 64-bit fields instead of
     * 32-bit. Confirmed via `ValueIntegerExpressionChange(6L, 42L)` decoding to exactly `[6, 42]`.
     */
    private const val OP_VALUE_INTEGER_EXPRESSION_CHANGE = 218

    /**
     * `Operations.VALUE_FLOAT_EXPRESSION_CHANGE_ACTION` — `ValueFloatExpressionChange(valueId,
     * value)` writes `[valueId:i32][value:i32]` (both plain ints, despite the name — `value` here
     * is an expression/id reference, not a float bit pattern). Confirmed via
     * `ValueFloatExpressionChange(7, 9)` decoding to exactly `[7, 9]`.
     */
    private const val OP_VALUE_FLOAT_EXPRESSION_CHANGE = 227

    /**
     * `Operations.MATRIX_SAVE` / `MATRIX_RESTORE` — bare opcode bytes, no payload (confirmed via
     * `writer.save()`/`writer.restore()` decoding to a single byte each, `82`/`83` hex). Unlike
     * [OP_MODIFIER_OFFSET]'s scope-attached synthetic save/restore pair, these are top-level draw
     * ops the document author placed directly in the stream, so they map straight onto the
     * existing [Opcode.MatrixSave]/[Opcode.MatrixRestore] with no scope-stack bookkeeping needed.
     */
    private const val OP_MATRIX_SAVE = 130
    private const val OP_MATRIX_RESTORE = 131

    /** `Operations.MATRIX_TRANSLATE` — `[dx:f32][dy:f32]`, confirmed via `writer.translate(130f,41f)`. */
    private const val OP_MATRIX_TRANSLATE = 127

    /**
     * `Operations.MATRIX_SCALE` — `[scaleX:f32][scaleY:f32][pivotX:f32][pivotY:f32]`, confirmed
     * via `writer.scale(2f,2f,145f,46f)` decoding to exactly those four floats in that order.
     */
    private const val OP_MATRIX_SCALE = 126

    /**
     * `Operations.MATRIX_ROTATE` — `[degrees:f32][pivotX:f32][pivotY:f32]`, confirmed via
     * `writer.rotate(45f,175f,46f)` decoding to exactly those three floats in that order.
     */
    private const val OP_MATRIX_ROTATE = 129

    /**
     * `Operations.CLIP_RECT` — the top-level draw-context clip (distinct from
     * [OP_MODIFIER_CLIP_RECT]'s modifier-attached one): `[left:f32][top:f32][right:f32][bottom:f32]`,
     * confirmed via `writer.clipRect(190f,41f,205f,51f)` decoding to exactly those four floats.
     */
    private const val OP_CLIP_RECT = 39

    /**
     * `drawTextAnchored` carries no font-size parameter — real font sizing comes from a text style
     * this minimal parser doesn't yet decode — so text is drawn at a fixed, reasonable default.
     */
    private const val DEFAULT_TEXT_SIZE_SP = 16f

    /**
     * Parses [bytes] as a real `.rc` document containing only the opcode subset documented above.
     *
     * @throws RemoteComposeParseException if an opcode outside that subset is encountered, or if
     *   the buffer runs out mid-record.
     */
    fun parse(bytes: ByteArray): RemoteDocument {
        val reader = BufferReader(bytes)

        var width = 0
        var height = 0
        var currentColor = Color.Black
        val textPool = mutableMapOf<Int, String>()
        val pathPool = mutableMapOf<Int, List<PathCommand>>()
        val bitmapPool = mutableMapOf<Int, ByteArray>()
        val opcodes = mutableListOf<Opcode>()

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
        class ScopeFrame(val startIndex: Int, val parent: ScopeFrame?) {
            val cleanupOpcodes = mutableListOf<Opcode>()
            var backgroundColor: Color? = null
            var layoutAxis: Char? = null // 'V' (LAYOUT_COLUMN) or 'H' (LAYOUT_ROW); null otherwise
            var spacedBy: Float = 0f
            // RowLayout/ColumnLayout.{START,CENTER,END,TOP,BOTTOM,SPACE_BETWEEN,SPACE_EVENLY,
            // SPACE_AROUND} ordinals (see arrangeChildren's KDoc) — only meaningful when
            // layoutAxis != null; default POS_START matches this parser's original always-packed
            // behavior when a document doesn't set these explicitly.
            var horizontalPositioning: Int = POS_START
            var verticalPositioning: Int = POS_START
            val childRanges = mutableListOf<IntArray>() // only populated/consumed when layoutAxis != null
            // Captured from a MODIFIER_WIDTH/MODIFIER_HEIGHT with an EXACT(_DP) mode directly on
            // *this* frame (i.e. this container's own declared size, not a child's) — read by
            // arrangeChildren on the LAYOUT_CONTENT frame this one is the parent of, since only a
            // real declared container extent (not just "however much space the children take up")
            // makes CENTER/END/SPACE_* along the main axis mean anything.
            var explicitWidthPx: Float? = null
            var explicitHeightPx: Float? = null
        }
        val scopeStack = mutableListOf<ScopeFrame>()
        fun pushScope() {
            scopeStack.add(ScopeFrame(opcodes.size, scopeStack.lastOrNull()))
        }
        fun attachToTopScope(op: Opcode) {
            scopeStack.lastOrNull()?.cleanupOpcodes?.add(op)
        }
        // Set by OP_LAYOUT_COLUMN/OP_LAYOUT_ROW, consumed by the very next OP_LAYOUT_CONTENT (the
        // real byte stream always writes a container's own modifiers — none of which open a
        // LAYOUT_CONTENT themselves — between the two, so nothing else can consume this first).
        var pendingLayoutAxis: Char? = null
        var pendingSpacedBy = 0f
        var pendingHorizontalPositioning = POS_START
        var pendingVerticalPositioning = POS_START

        /**
         * This renderer has no measure/layout pass, so a container's "bounds" for
         * [OP_MODIFIER_BACKGROUND] are inferred as the tight bounding box of every draw call
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
                        val estimatedWidth = text.length * op.fontSize * 0.55f
                        val estimatedHeight = op.fontSize * 1.2f
                        expand(
                            op.x + offsetX, op.y + offsetY,
                            op.x + estimatedWidth + offsetX, op.y + estimatedHeight + offsetY,
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
         * point-by-point; opcodes with no absolute position (transform/clip control, [Opcode.Unknown])
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
         * [OP_CONTAINER_END] — when [frame] (a LAYOUT_CONTENT frame carrying [ScopeFrame.layoutAxis])
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
         * A child is moved into place the same way [OP_MODIFIER_OFFSET] moves content: a
         * MatrixSave/Translate pair spliced immediately before its content, MatrixRestore right
         * after. Splicing is done in *reverse* document order specifically so that an earlier
         * child's still-unprocessed [start, end) range is never shifted by a later child's
         * insertions (every insertion for child K happens at or after K's own start index, which is
         * always ≥ any not-yet-processed, earlier child's end index).
         */
        fun arrangeChildren(frame: ScopeFrame) {
            val axis = frame.layoutAxis ?: return
            val children = frame.childRanges.sortedBy { it[0] }
            if (children.isEmpty()) return
            val naturalBounds = children.map { range -> contentBounds(opcodes.subList(range[0], range[1])) }
            val anchorIndex = naturalBounds.indexOfFirst { it != null }
            if (anchorIndex == -1) return
            val anchor = naturalBounds[anchorIndex]!!
            val anchorMainStart = if (axis == 'V') anchor[1] else anchor[0] // top (Column) or left (Row)
            val crossAnchor = if (axis == 'V') anchor[0] else anchor[1] // left (Column) or top (Row)

            val mainMode = if (axis == 'V') frame.verticalPositioning else frame.horizontalPositioning
            val crossMode = if (axis == 'V') frame.horizontalPositioning else frame.verticalPositioning

            val mainSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[3] - it[1] else it[2] - it[0] } ?: 0f }
            val crossSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[2] - it[0] else it[3] - it[1] } ?: 0f }
            val packedMainSize = mainSizes.sum() + frame.spacedBy * (children.size - 1).coerceAtLeast(0)
            val declaredMainExtent = if (axis == 'V') frame.parent?.explicitHeightPx else frame.parent?.explicitWidthPx
            val declaredCrossExtent = if (axis == 'V') frame.parent?.explicitWidthPx else frame.parent?.explicitHeightPx
            val mainExtent = declaredMainExtent ?: packedMainSize
            val crossExtent = declaredCrossExtent ?: (crossSizes.maxOrNull() ?: 0f)

            val (leadingGap, betweenGap) = mainAxisGaps(mainMode, mainExtent - packedMainSize, children.size)
            var cursorMain = anchorMainStart + leadingGap
            val deltas = arrayOfNulls<FloatArray>(children.size)
            for (i in children.indices) {
                val bounds = naturalBounds[i] ?: continue
                val crossOffset = crossAxisOffset(crossMode, crossExtent, crossSizes[i])
                val dx: Float
                val dy: Float
                if (axis == 'V') {
                    dx = (crossAnchor + crossOffset) - bounds[0]
                    dy = cursorMain - bounds[1]
                } else {
                    dx = cursorMain - bounds[0]
                    dy = (crossAnchor + crossOffset) - bounds[1]
                }
                deltas[i] = floatArrayOf(dx, dy)
                cursorMain += mainSizes[i] + frame.spacedBy + betweenGap
            }
            for (i in children.indices.reversed()) {
                val delta = deltas[i] ?: continue
                if (delta[0] == 0f && delta[1] == 0f) continue
                val range = children[i]
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
        }

        while (reader.hasRemaining()) {
            when (val opId = reader.readU8()) {
                OP_HEADER -> {
                    reader.readS32() // major version
                    reader.readS32() // minor version
                    reader.readS32() // patch version
                    width = reader.readS32()
                    height = reader.readS32()
                    reader.readS64() // capabilities bitmask — unused by this minimal renderer
                }

                OP_DATA_TEXT -> {
                    val id = reader.readS32()
                    val length = reader.readS32()
                    textPool[id] = reader.readUtf8(length)
                }

                OP_ROOT_CONTENT_DESCRIPTION -> {
                    reader.readS32() // text-pool id reference — not needed for drawing
                }

                OP_PAINT_BUNDLE -> {
                    val wordCount = reader.readS32()
                    var lastWord = 0
                    repeat(wordCount) { lastWord = reader.readS32() }
                    if (wordCount >= 2) currentColor = Color(lastWord)
                }

                OP_DRAW_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.DrawRect(
                        left, top, right, bottom,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_CIRCLE -> {
                    val centerX = reader.readFloat32()
                    val centerY = reader.readFloat32()
                    val radius = reader.readFloat32()
                    opcodes += Opcode.DrawCircle(
                        centerX, centerY, radius,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_ROUND_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val radiusX = reader.readFloat32()
                    val radiusY = reader.readFloat32()
                    opcodes += Opcode.DrawRoundRect(
                        left, top, right, bottom, radiusX, radiusY,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_TEXT_ANCHORED -> {
                    val textId = reader.readS32()
                    val x = reader.readFloat32()
                    val y = reader.readFloat32()
                    reader.readFloat32() // panX — no anchor concept in Opcode.DrawText
                    reader.readFloat32() // panY
                    reader.readS32() // flags
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = x,
                        y = y,
                        fontSize = DEFAULT_TEXT_SIZE_SP,
                        colorArgb = currentColor.toArgb(),
                    )
                }

                OP_DRAW_LINE -> {
                    val x1 = reader.readFloat32()
                    val y1 = reader.readFloat32()
                    val x2 = reader.readFloat32()
                    val y2 = reader.readFloat32()
                    opcodes += Opcode.DrawLine(
                        x1, y1, x2, y2,
                        PaintStyle(currentColor, PaintStyleKind.STROKE),
                    )
                }

                OP_DRAW_OVAL -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.DrawOval(
                        left, top, right, bottom,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_ARC, OP_DRAW_SECTOR -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val startAngle = reader.readFloat32()
                    val sweepAngle = reader.readFloat32()
                    opcodes += Opcode.DrawArc(
                        left, top, right, bottom, startAngle, sweepAngle,
                        useCenter = opId == OP_DRAW_SECTOR,
                        paint = PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DATA_PATH -> {
                    val pathId = reader.readS32()
                    val floatCount = reader.readS32()
                    pathPool[pathId] = decodePathArray(reader, floatCount)
                }

                OP_DRAW_PATH -> {
                    val pathId = reader.readS32()
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "DrawPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.DrawPath(commands, PaintStyle(currentColor, PaintStyleKind.FILL))
                }

                OP_CLIP_PATH -> {
                    val pathId = reader.readS32()
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "ClipPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.ClipPath(commands)
                }

                OP_DATA_BITMAP -> {
                    val bitmapId = reader.readS32()
                    reader.readS32() // width — BitmapPool/decodeImageBitmap reads it back out of the PNG itself
                    reader.readS32() // height
                    val pngLength = reader.readS32()
                    bitmapPool[bitmapId] = reader.readBytes(pngLength)
                }

                OP_DRAW_BITMAP -> {
                    val bitmapId = reader.readS32()
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    reader.readS32() // content-description text-pool id — not needed for drawing
                    opcodes += Opcode.DrawBitmap(bitmapId, left, top, right, bottom)
                }

                OP_CLICK_AREA -> {
                    val actionId = reader.readS32()
                    reader.readS32() // content-description text-pool id — not needed for hit-testing
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val metadataTextId = reader.readS32()
                    opcodes += Opcode.ActionClick(actionId, metadataTextId, left, top, right, bottom)
                }

                OP_LAYOUT_COLUMN, OP_LAYOUT_ROW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val horizontalPositioning = reader.readS32()
                    val verticalPositioning = reader.readS32()
                    val spacedBy = reader.readFloat32()
                    pushScope() // this container's own scope — closed by its outermost CONTAINER_END
                    // Consumed by this container's own LAYOUT_CONTENT next, so its content frame
                    // (where the real children live) knows to arrange them — see arrangeChildren.
                    pendingLayoutAxis = if (opId == OP_LAYOUT_COLUMN) 'V' else 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                }

                OP_LAYOUT_COLLAPSIBLE_COLUMN, OP_LAYOUT_COLLAPSIBLE_ROW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    reader.readFloat32() // spacedBy
                    // Real arrangement isn't wired up for the collapsible variants yet (unlike plain
                    // LAYOUT_COLUMN/LAYOUT_ROW below) — deliberately deferred, not an oversight.
                    pushScope()
                }

                OP_LAYOUT_FLOW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    reader.readFloat32() // spacedBy
                    reader.readS32() // maxItemsInMainAxis
                    reader.readS32() // maxLinesInCrossAxis
                    pushScope()
                }

                OP_LAYOUT_BOX, OP_LAYOUT_FIT_BOX -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    pushScope()
                }

                OP_LAYOUT_ROOT -> {
                    reader.readS32() // componentId — no LAYOUT_CONTENT marker follows
                    pushScope() // closed by this container's single CONTAINER_END
                }

                OP_LAYOUT_STATE -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    reader.readS32() // stateIndex
                    pushScope()
                }

                OP_LAYOUT_CONTENT, OP_LAYOUT_CANVAS_CONTENT -> {
                    reader.readS32() // componentId
                    pushScope() // the children scope itself — this is where real children attach
                    val axis = pendingLayoutAxis
                    if (axis != null) {
                        scopeStack.last().layoutAxis = axis
                        scopeStack.last().spacedBy = pendingSpacedBy
                        scopeStack.last().horizontalPositioning = pendingHorizontalPositioning
                        scopeStack.last().verticalPositioning = pendingVerticalPositioning
                        pendingLayoutAxis = null
                    }
                }

                OP_LAYOUT_CANVAS -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    pushScope()
                }

                OP_LAYOUT_CUSTOM -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // nameTextId
                    val propertyCount = reader.readS32()
                    repeat(propertyCount) {
                        reader.readU16() // type
                        reader.readU16() // dataType
                        reader.readS32() // value (int or float bit pattern)
                    }
                    pushScope()
                }

                OP_LAYOUT_IMAGE -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // scaleType
                    reader.readS32() // bitmapId
                    reader.readFloat32() // alpha
                    pushScope() // a leaf — no LAYOUT_CONTENT, just its own single CONTAINER_END
                }

                OP_HAPTIC_FEEDBACK -> reader.readS32() // hapticId

                OP_THEME -> reader.readS32() // theme

                OP_ROOT_CONTENT_BEHAVIOR -> repeat(4) { reader.readS32() }

                OP_ANIMATION_SPEC -> {
                    reader.readS32() // animationId
                    reader.readFloat32() // motionDuration
                    reader.readS32() // motionEasingType
                    reader.readFloat32() // visibilityDuration
                    reader.readS32() // visibilityEasingType
                    reader.readS32() // enterAnimation
                    reader.readS32() // exitAnimation
                }

                OP_MODIFIER_WIDTH, OP_MODIFIER_HEIGHT -> {
                    val mode = reader.readS32()
                    val value = reader.readFloat32()
                    // Only a real target size (not a sizing *strategy* like FILL/WRAP/WEIGHT this
                    // parser has no layout pass to resolve) is useful to arrangeChildren's
                    // main-axis CENTER/END/SPACE_* modes — see ScopeFrame.explicitWidthPx's KDoc.
                    if (mode == DIMENSION_MODE_EXACT || mode == DIMENSION_MODE_EXACT_DP) {
                        val frame = scopeStack.lastOrNull()
                        if (opId == OP_MODIFIER_WIDTH) frame?.explicitWidthPx = value
                        else frame?.explicitHeightPx = value
                    }
                }

                OP_CONTAINER_END -> {
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
                        val bg = frame.backgroundColor
                        if (bg != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                opcodes.add(
                                    frame.startIndex,
                                    Opcode.DrawRect(
                                        bounds[0], bounds[1], bounds[2], bounds[3],
                                        PaintStyle(bg, PaintStyleKind.FILL),
                                    ),
                                )
                            }
                        }
                        if (frame.layoutAxis != null) {
                            arrangeChildren(frame)
                        }
                        opcodes.addAll(frame.cleanupOpcodes)
                        val parent = frame.parent
                        if (parent != null && parent.layoutAxis != null) {
                            parent.childRanges.add(intArrayOf(frame.startIndex, opcodes.size))
                        }
                    }
                }

                OP_MODIFIER_CLICK -> pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                OP_HOST_ACTION -> reader.readS32() // actionId

                OP_MODIFIER_PADDING -> repeat(4) { reader.readFloat32() }

                OP_MODIFIER_BACKGROUND -> {
                    repeat(4) { reader.readFloat32() } // corner radii — not modeled (no rounded-fill Opcode variant used here)
                    val r = reader.readFloat32()
                    val g = reader.readFloat32()
                    val b = reader.readFloat32()
                    val a = reader.readFloat32()
                    reader.readFloat32() // trailing float — role unconfirmed
                    scopeStack.lastOrNull()?.backgroundColor = Color(r, g, b, a)
                }

                OP_MODIFIER_VISIBILITY -> {
                    // Component.Visibility: GONE=0, VISIBLE=1, INVISIBLE=2. Anything but VISIBLE
                    // is rendered here as an empty clip around this container's children (the
                    // executor already implements OP_CLIP_RECT via Skia's real clip stack, and an
                    // empty rect makes every subsequent draw inside it a no-op) — a real semantic
                    // effect, not just a byte-skip, though it doesn't distinguish GONE (no space
                    // reserved) from INVISIBLE (space reserved) since this renderer has no layout
                    // pass to reserve space with.
                    val visibility = reader.readS32()
                    if (visibility != 1) {
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.ClipRect(0f, 0f, 0f, 0f)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_MODIFIER_OFFSET -> {
                    // A real semantic effect (not just a byte-skip): translates every subsequent
                    // draw belonging to this container's children, undone at this container's own
                    // closing CONTAINER_END via the scope stack above.
                    val x = reader.readFloat32()
                    val y = reader.readFloat32()
                    opcodes += Opcode.MatrixSave
                    opcodes += Opcode.Translate(x, y)
                    attachToTopScope(Opcode.MatrixRestore)
                }

                OP_MODIFIER_BORDER -> {
                    repeat(4) { reader.readS32() } // colorId-ref flag / colorId / legacy flag / reserved
                    repeat(6) { reader.readFloat32() } // borderWidth, roundedCorner, r, g, b, a
                    reader.readS32() // shapeType
                }

                OP_MODIFIER_CLIP_RECT -> Unit // no payload

                OP_MODIFIER_ROUNDED_CLIP_RECT -> repeat(4) { reader.readFloat32() } // topStart, topEnd, bottomStart, bottomEnd

                OP_MODIFIER_MULTI_CLICK -> {
                    reader.readS32() // clickType
                    pushScope() // opens a nested action list, closed by its own CONTAINER_END
                }

                OP_MODIFIER_TOUCH_DOWN, OP_MODIFIER_TOUCH_UP, OP_MODIFIER_TOUCH_CANCEL ->
                    pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                OP_MODIFIER_WIDTH_IN, OP_MODIFIER_HEIGHT_IN -> {
                    reader.readFloat32() // min
                    reader.readFloat32() // max
                }

                OP_MODIFIER_COLLAPSIBLE_PRIORITY -> {
                    reader.readS32() // orientation
                    reader.readFloat32() // priority
                }

                OP_MODIFIER_ALIGN_BY -> {
                    reader.readFloat32() // line (NaN-tagged baseline-kind constant)
                    reader.readS32() // flag
                }

                OP_MODIFIER_ZINDEX -> reader.readFloat32() // z-index value

                OP_MODIFIER_RIPPLE -> Unit // no payload

                OP_MODIFIER_DRAW_CONTENT -> Unit // no payload

                OP_MODIFIER_MARQUEE -> {
                    reader.readS32() // iterations
                    reader.readS32() // animationMode
                    repeat(4) { reader.readFloat32() } // repeatDelay, initialDelay, spacing, velocity
                }

                OP_MODIFIER_GRAPHICS_LAYER -> {
                    // Real semantic effect for the ALPHA attribute specifically (tag ==
                    // GRAPHICS_LAYER_ALPHA_TAG): opens a real compositing layer around this
                    // container's children, closed by a MatrixRestore queued on this container's
                    // own scope — the same mechanism MODIFIER_OFFSET/MODIFIER_VISIBILITY use.
                    // Every other attribute (scale/rotation/shadow/blur/etc.) is still just
                    // byte-consumed, since those need real box bounds this renderer doesn't have.
                    val count = reader.readS32()
                    var alpha: Float? = null
                    repeat(count) {
                        val tag = reader.readS32() // attribute key, OR'd with 0x400 if float-valued
                        val rawValue = reader.readS32() // int or float bit pattern
                        if (tag == GRAPHICS_LAYER_ALPHA_TAG) alpha = Float.fromBits(rawValue)
                    }
                    alpha?.let {
                        opcodes += Opcode.SaveLayerAlpha(it)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_MODIFIER_DIMENSION_CONSTRAINTS -> {
                    reader.readS8() // type
                    reader.readFloat32() // min
                    reader.readFloat32() // max
                }

                OP_VALUE_INTEGER_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // value
                }

                OP_VALUE_STRING_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // stringId (text-pool reference)
                }

                OP_VALUE_FLOAT_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readFloat32() // value
                }

                OP_VALUE_INTEGER_EXPRESSION_CHANGE -> {
                    reader.readS64() // valueId
                    reader.readS64() // value
                }

                OP_VALUE_FLOAT_EXPRESSION_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // value (expression/id reference)
                }

                OP_MATRIX_SAVE -> opcodes += Opcode.MatrixSave

                OP_MATRIX_RESTORE -> opcodes += Opcode.MatrixRestore

                OP_MATRIX_TRANSLATE -> {
                    val dx = reader.readFloat32()
                    val dy = reader.readFloat32()
                    opcodes += Opcode.Translate(dx, dy)
                }

                OP_MATRIX_SCALE -> {
                    val sx = reader.readFloat32()
                    val sy = reader.readFloat32()
                    val pivotX = reader.readFloat32()
                    val pivotY = reader.readFloat32()
                    opcodes += Opcode.Scale(sx, sy, pivotX, pivotY)
                }

                OP_MATRIX_ROTATE -> {
                    val degrees = reader.readFloat32()
                    val pivotX = reader.readFloat32()
                    val pivotY = reader.readFloat32()
                    opcodes += Opcode.Rotate(degrees, pivotX, pivotY)
                }

                OP_CLIP_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.ClipRect(left, top, right, bottom)
                }

                else -> throw RemoteComposeParseException(
                    "Real opcode $opId is outside the minimal subset this demo parser supports " +
                        "(Header/DataText/RootContentDescription/PaintBundle/DrawRect/DrawCircle/" +
                        "DrawRoundRect/DrawTextAnchored/DrawLine/DrawOval/DrawArc/DrawSector/" +
                        "DataPath/DrawPath/DataBitmap/DrawBitmap/ClickArea/LayoutColumn/LayoutRow/" +
                        "LayoutCollapsibleColumn/LayoutCollapsibleRow/LayoutFlow/LayoutFitBox/" +
                        "LayoutRoot/LayoutState/LayoutCanvas/LayoutCanvasContent/LayoutCustom/" +
                        "LayoutImage/HapticFeedback/Theme/RootContentBehavior/AnimationSpec/" +
                        "LayoutBox/LayoutContent/ContainerEnd/ModifierWidth/ModifierHeight/" +
                        "ModifierClick/HostAction/ModifierPadding/ModifierBackground/" +
                        "ModifierVisibility/ModifierOffset/ModifierBorder/ModifierClipRect/" +
                        "ModifierRoundedClipRect/ModifierMultiClick/ModifierTouchDown/" +
                        "ModifierTouchUp/ModifierTouchCancel/ModifierWidthIn/ModifierHeightIn/" +
                        "ModifierCollapsiblePriority/ModifierAlignBy/ModifierZIndex/ModifierRipple/" +
                        "ModifierDrawContent/ModifierMarquee/ModifierGraphicsLayer/" +
                        "ModifierDimensionConstraints/ValueIntegerChange/ValueStringChange/" +
                        "ValueFloatChange/ValueIntegerExpressionChange/ValueFloatExpressionChange/" +
                        "MatrixSave/MatrixRestore/MatrixTranslate/MatrixScale/MatrixRotate/ClipRect/" +
                        "ClipPath)",
                )
            }
        }

        return RemoteDocument(
            header = Header(
                versionMajor = 1,
                versionMinor = 0,
                width = width,
                height = height,
                backgroundColor = Color.Transparent,
                capabilities = 0L,
            ),
            strings = StringPool.fromEntries(textPool),
            variables = VariablePool.EMPTY,
            bitmaps = BitmapPool.fromEntries(bitmapPool),
            opcodes = opcodes,
        )
    }

    /**
     * Decodes a `RemotePathBase` flat command array of [floatCount] raw i32 words (read directly
     * as bits, not as [BufferReader.readFloat32], since a command tag is a specific NaN bit
     * pattern that must be tested before deciding whether a word is a tag or real float data).
     *
     * @throws RemoteComposeParseException on [PATH_CMD_CONIC] — declared from source but not yet
     *   byte-verified (see [OP_DATA_PATH]).
     */
    private fun decodePathArray(reader: BufferReader, floatCount: Int): List<PathCommand> {
        val words = IntArray(floatCount) { reader.readS32() }
        val commands = mutableListOf<PathCommand>()
        var i = 0
        while (i < words.size) {
            val tagWord = words[i]
            require((tagWord and NAN_TAG_MASK) == NAN_TAG_MASK) {
                "Expected a path command tag at float index $i, got a non-tag word"
            }
            when (val tag = tagWord and 0x7FFFFF) {
                PATH_CMD_MOVE -> {
                    commands += PathCommand.MoveTo(Float.fromBits(words[i + 1]), Float.fromBits(words[i + 2]))
                    i += 3
                }
                PATH_CMD_LINE -> {
                    // Real encoder bug: 2 garbage words between the tag and the real (x, y) — see
                    // OP_DATA_PATH's KDoc.
                    commands += PathCommand.LineTo(Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]))
                    i += 5
                }
                PATH_CMD_QUADRATIC -> {
                    // Same 2-word padding bug as LINE, ahead of 4 real floats (x1, y1, x2, y2).
                    commands += PathCommand.QuadraticTo(
                        Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]),
                        Float.fromBits(words[i + 5]), Float.fromBits(words[i + 6]),
                    )
                    i += 7
                }
                PATH_CMD_CUBIC -> {
                    // Same 2-word padding bug, ahead of 6 real floats (x1, y1, x2, y2, x3, y3).
                    commands += PathCommand.CubicTo(
                        Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]),
                        Float.fromBits(words[i + 5]), Float.fromBits(words[i + 6]),
                        Float.fromBits(words[i + 7]), Float.fromBits(words[i + 8]),
                    )
                    i += 9
                }
                PATH_CMD_CLOSE -> {
                    commands += PathCommand.Close
                    i += 1
                }
                else -> throw RemoteComposeParseException(
                    "Real path command tag $tag at float index $i is not yet supported " +
                        "(MOVE/LINE/QUADRATIC/CUBIC/CLOSE verified against real output; CONIC is not)",
                )
            }
        }
        return commands
    }
}
