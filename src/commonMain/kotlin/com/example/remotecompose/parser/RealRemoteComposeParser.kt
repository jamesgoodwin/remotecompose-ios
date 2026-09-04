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

                else -> throw RemoteComposeParseException(
                    "Real opcode $opId is outside the minimal subset this demo parser supports " +
                        "(Header/DataText/RootContentDescription/PaintBundle/DrawRect/DrawCircle/" +
                        "DrawRoundRect/DrawTextAnchored/DrawLine/DrawOval/DrawArc/DrawSector/" +
                        "DataPath/DrawPath/DataBitmap/DrawBitmap/ClickArea)",
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
