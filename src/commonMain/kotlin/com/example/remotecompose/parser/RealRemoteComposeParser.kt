package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
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

                else -> throw RemoteComposeParseException(
                    "Real opcode $opId is outside the minimal subset this demo parser supports " +
                        "(Header/DataText/RootContentDescription/PaintBundle/DrawRect/DrawCircle/" +
                        "DrawRoundRect/DrawTextAnchored/DrawLine/DrawOval/DrawArc/DrawSector)",
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
            bitmaps = BitmapPool.EMPTY,
            opcodes = opcodes,
        )
    }
}
