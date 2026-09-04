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
     * This parser treats every layout container opcode ([OP_LAYOUT_COLUMN]/[OP_LAYOUT_CONTENT]/
     * [OP_CONTAINER_END]) as a **pass-through scope marker**: it consumes exactly the right bytes
     * to stay aligned, but emits no [Opcode] and does not reposition children. That is correct for
     * a document whose children carry absolute, already-final coordinates (as `tools/rc-writer`'s
     * writer calls do here) — real dynamic arrangement (e.g. children sized/positioned relative to
     * `spacedBy` or `horizontalPositioning`) would need an actual measure/layout pass this flat
     * opcode-list renderer doesn't have, which is real, deliberately out-of-scope future work, not
     * an oversight.
     */
    private const val OP_LAYOUT_COLUMN = 204

    /** `Operations.LAYOUT_ROW` — identical shape to [OP_LAYOUT_COLUMN]'s (source-confirmed: same `apply()` structure). */
    private const val OP_LAYOUT_ROW = 203

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
     * (mode observed as 0 for a fixed-size `width(float)`; other modes presumably exist for
     * wrap-content/fill-parent, unconfirmed). Written immediately after its component's own layout
     * op (e.g. [OP_LAYOUT_BOX]) and before [OP_LAYOUT_CONTENT]. Consumed as a pass-through, same
     * rationale as the layout container opcodes: it would only affect rendering through a real
     * measure/layout pass this renderer doesn't have.
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
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    reader.readFloat32() // spacedBy
                }

                OP_LAYOUT_BOX -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                }

                OP_LAYOUT_CONTENT -> reader.readS32() // componentId

                OP_MODIFIER_WIDTH, OP_MODIFIER_HEIGHT -> {
                    reader.readS32() // mode
                    reader.readFloat32() // value
                }

                OP_CONTAINER_END -> Unit // no payload

                OP_MODIFIER_CLICK -> Unit // no payload — just opens a nested action list

                OP_HOST_ACTION -> reader.readS32() // actionId

                OP_MODIFIER_PADDING -> repeat(4) { reader.readFloat32() }

                OP_MODIFIER_BACKGROUND -> repeat(9) { reader.readFloat32() }

                OP_MODIFIER_VISIBILITY -> reader.readS32()

                OP_MODIFIER_OFFSET -> {
                    reader.readFloat32() // x
                    reader.readFloat32() // y
                }

                OP_MODIFIER_BORDER -> {
                    repeat(4) { reader.readS32() } // colorId-ref flag / colorId / legacy flag / reserved
                    repeat(6) { reader.readFloat32() } // borderWidth, roundedCorner, r, g, b, a
                    reader.readS32() // shapeType
                }

                OP_MODIFIER_CLIP_RECT -> Unit // no payload

                OP_MODIFIER_ROUNDED_CLIP_RECT -> repeat(4) { reader.readFloat32() } // topStart, topEnd, bottomStart, bottomEnd

                OP_MODIFIER_MULTI_CLICK -> reader.readS32() // clickType — just opens a nested action list

                OP_MODIFIER_TOUCH_DOWN, OP_MODIFIER_TOUCH_UP, OP_MODIFIER_TOUCH_CANCEL ->
                    Unit // no payload — just opens a nested action list

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
                    val count = reader.readS32()
                    repeat(count) {
                        reader.readS32() // tag (attribute key, OR'd with 0x400 if float-valued)
                        reader.readS32() // value (int or float bit pattern)
                    }
                }

                else -> throw RemoteComposeParseException(
                    "Real opcode $opId is outside the minimal subset this demo parser supports " +
                        "(Header/DataText/RootContentDescription/PaintBundle/DrawRect/DrawCircle/" +
                        "DrawRoundRect/DrawTextAnchored/DrawLine/DrawOval/DrawArc/DrawSector/" +
                        "DataPath/DrawPath/DataBitmap/DrawBitmap/ClickArea/LayoutColumn/LayoutRow/" +
                        "LayoutBox/LayoutContent/ContainerEnd/ModifierWidth/ModifierHeight/" +
                        "ModifierClick/HostAction/ModifierPadding/ModifierBackground/" +
                        "ModifierVisibility/ModifierOffset/ModifierBorder/ModifierClipRect/" +
                        "ModifierRoundedClipRect/ModifierMultiClick/ModifierTouchDown/" +
                        "ModifierTouchUp/ModifierTouchCancel/ModifierWidthIn/ModifierHeightIn/" +
                        "ModifierCollapsiblePriority/ModifierAlignBy/ModifierZIndex/ModifierRipple/" +
                        "ModifierDrawContent/ModifierMarquee/ModifierGraphicsLayer)",
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
