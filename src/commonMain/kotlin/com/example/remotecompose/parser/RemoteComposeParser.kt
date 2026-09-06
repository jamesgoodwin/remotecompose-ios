package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.model.RcOpcode
import com.example.remotecompose.model.RemoteDocument

/**
 * Parses a raw `.rc` binary payload into a [RemoteDocument].
 *
 * Section order matches §3.1 of the format: magic header + document metadata, string pool,
 * float/var pool, bitmap pool, then the opcode stream. See [BufferReader] for the byte-order
 * assumptions shared by every section, and [RcOpcode]'s KDoc for the per-opcode framing
 * (`[u8 opcodeId][varint payloadLength][payload]`) that lets the opcode loop below skip anything
 * it can't decode instead of failing the whole parse.
 */
object RemoteComposeParser {

    /**
     * Parses [bytes] end to end.
     *
     * @throws RemoteComposeParseException if the magic header doesn't match, or if any of the
     *   fixed-shape sections (header/string pool/var pool/bitmap pool) run past the end of the
     *   buffer. Individual opcodes that fail to decode do **not** throw — they are recorded as
     *   [Opcode.Unknown] and parsing continues, per the Phase 2 requirement that malformed or
     *   unrecognized opcodes must not break the draw stack.
     */
    fun parse(bytes: ByteArray): RemoteDocument {
        val reader = BufferReader(bytes)

        val header = Header.read(reader)
        val strings = StringPool.read(reader)
        val variables = VariablePool.read(reader)
        val bitmaps = BitmapPool.read(reader)
        val opcodes = readOpcodeStream(reader)

        return RemoteDocument(
            header = header,
            strings = strings,
            variables = variables,
            bitmaps = bitmaps,
            opcodes = opcodes,
        )
    }

    /**
     * Reads opcode records until the buffer is exhausted, resynchronizing to each record's
     * declared end after every attempt so that a decoder which reads too few or too many bytes
     * for its opcode can never desynchronize the ones that follow it.
     */
    private fun readOpcodeStream(reader: BufferReader): List<Opcode> {
        val opcodes = mutableListOf<Opcode>()

        while (reader.hasRemaining()) {
            val opcodeId = reader.readU8()
            val payloadLength = reader.readVarUIntAsInt()
            val declaredEnd = reader.position + payloadLength

            if (declaredEnd > reader.size) {
                // Truncated final record: the stream claims more payload than actually remains.
                // Nothing further in the buffer can be trusted, so stop here rather than guess.
                break
            }

            val opcode = try {
                decodeKnownOpcode(opcodeId, reader) ?: Opcode.Unknown(opcodeId, payloadLength)
            } catch (_: RemoteComposeParseException) {
                Opcode.Unknown(opcodeId, payloadLength)
            }
            opcodes += opcode

            // Always resync to the record's declared boundary, regardless of how many bytes the
            // decoder above actually consumed (including the Unknown/caught-exception cases).
            reader.seek(declaredEnd)
        }

        return opcodes
    }

    /** Returns the decoded opcode for a recognized [opcodeId], or `null` if [opcodeId] is not recognized. */
    private fun decodeKnownOpcode(opcodeId: Int, reader: BufferReader): Opcode? = when (opcodeId) {
        RcOpcode.MATRIX_SAVE -> Opcode.MatrixSave
        RcOpcode.MATRIX_RESTORE -> Opcode.MatrixRestore

        RcOpcode.TRANSLATE -> Opcode.Translate(
            dx = reader.readFloat32(),
            dy = reader.readFloat32(),
        )

        RcOpcode.SCALE -> Opcode.Scale(
            sx = reader.readFloat32(),
            sy = reader.readFloat32(),
            pivotX = reader.readFloat32(),
            pivotY = reader.readFloat32(),
        )

        RcOpcode.ROTATE -> Opcode.Rotate(
            degrees = reader.readFloat32(),
            pivotX = reader.readFloat32(),
            pivotY = reader.readFloat32(),
        )

        RcOpcode.CLIP_RECT -> Opcode.ClipRect(
            left = reader.readFloat32(),
            top = reader.readFloat32(),
            right = reader.readFloat32(),
            bottom = reader.readFloat32(),
        )

        RcOpcode.CLIP_PATH -> Opcode.ClipPath(readPathCommands(reader))

        RcOpcode.DRAW_RECT -> {
            val left = reader.readFloat32()
            val top = reader.readFloat32()
            val right = reader.readFloat32()
            val bottom = reader.readFloat32()
            Opcode.DrawRect(left, top, right, bottom, readPaint(reader))
        }

        RcOpcode.DRAW_ROUND_RECT -> {
            val left = reader.readFloat32()
            val top = reader.readFloat32()
            val right = reader.readFloat32()
            val bottom = reader.readFloat32()
            val radiusX = reader.readFloat32()
            val radiusY = reader.readFloat32()
            Opcode.DrawRoundRect(left, top, right, bottom, radiusX, radiusY, readPaint(reader))
        }

        RcOpcode.DRAW_CIRCLE -> {
            val centerX = reader.readFloat32()
            val centerY = reader.readFloat32()
            val radius = reader.readFloat32()
            Opcode.DrawCircle(centerX, centerY, radius, readPaint(reader))
        }

        RcOpcode.DRAW_PATH -> {
            val commands = readPathCommands(reader)
            Opcode.DrawPath(commands, readPaint(reader))
        }

        RcOpcode.DRAW_TEXT -> {
            val stringIndex = reader.readVarUIntAsInt()
            val x = reader.readFloat32()
            val y = reader.readFloat32()
            val fontSize = reader.readFloat32()
            val colorArgb = reader.readS32()
            Opcode.DrawText(
                stringIndex = stringIndex,
                x = x,
                y = y,
                paint = PaintStyle(Color(colorArgb), PaintStyleKind.FILL, textSize = fontSize),
            )
        }

        RcOpcode.DRAW_BITMAP -> Opcode.DrawBitmap(
            bitmapIndex = reader.readVarUIntAsInt(),
            left = reader.readFloat32(),
            top = reader.readFloat32(),
            right = reader.readFloat32(),
            bottom = reader.readFloat32(),
        )

        RcOpcode.ACTION_CLICK -> Opcode.ActionClick(
            actionId = reader.readVarUIntAsInt(),
            // -1 is the documented sentinel for "no target URL"; zig-zag decoding lets the
            // payload use it without a separate has-URL flag byte.
            targetUrlStringIndex = reader.readVarSInt().toInt(),
            left = reader.readFloat32(),
            top = reader.readFloat32(),
            right = reader.readFloat32(),
            bottom = reader.readFloat32(),
        )

        else -> null
    }

    /** Reads a paint sub-record: packed ARGB color, a style-kind byte, and a stroke width float. */
    private fun readPaint(reader: BufferReader): PaintStyle {
        val color = reader.readColor()
        val style = when (reader.readU8()) {
            RcOpcode.PaintStyleId.FILL -> PaintStyleKind.FILL
            RcOpcode.PaintStyleId.STROKE -> PaintStyleKind.STROKE
            else -> PaintStyleKind.FILL_AND_STROKE
        }
        val strokeWidth = reader.readFloat32()
        return PaintStyle(color, style, strokeWidth)
    }

    /**
     * Reads a path sub-record: an unsigned varint segment count followed by that many
     * `[u8 commandId][floats...]` segments (see [RcOpcode.PathCommandId]).
     */
    private fun readPathCommands(reader: BufferReader): List<PathCommand> {
        val count = reader.readVarUIntAsInt()
        val commands = ArrayList<PathCommand>(count)
        repeat(count) {
            commands += when (val commandId = reader.readU8()) {
                RcOpcode.PathCommandId.MOVE_TO -> PathCommand.MoveTo(
                    x = reader.readFloat32(),
                    y = reader.readFloat32(),
                )
                RcOpcode.PathCommandId.LINE_TO -> PathCommand.LineTo(
                    x = reader.readFloat32(),
                    y = reader.readFloat32(),
                )
                RcOpcode.PathCommandId.CUBIC_TO -> PathCommand.CubicTo(
                    x1 = reader.readFloat32(), y1 = reader.readFloat32(),
                    x2 = reader.readFloat32(), y2 = reader.readFloat32(),
                    x3 = reader.readFloat32(), y3 = reader.readFloat32(),
                )
                RcOpcode.PathCommandId.CLOSE -> PathCommand.Close
                else -> throw RemoteComposeParseException("Unknown path command id $commandId")
            }
        }
        return commands
    }
}
