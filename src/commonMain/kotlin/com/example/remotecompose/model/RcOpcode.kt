package com.example.remotecompose.model

/**
 * Numeric opcode IDs used by the `.rc` opcode stream.
 *
 * These values are placeholders that establish a self-consistent wire format for this renderer;
 * they are **not** yet verified against the canonical `androidx.compose.remote` opcode tables.
 * Every call site references these named constants rather than inline numeric literals, so
 * aligning with the real protocol later is a one-file change.
 *
 * Each record in the opcode stream is framed as a self-describing, skippable unit:
 * `[u8 opcodeId][varint payloadLength][payloadLength bytes of payload]`. This framing (not just
 * the individual opcode IDs) is what lets [com.example.remotecompose.parser.RemoteComposeParser]
 * skip an opcode it doesn't recognize without needing to understand its payload shape — see
 * `Opcode.Unknown`.
 */
object RcOpcode {
    // --- Matrix & coordinate transforms ---
    const val MATRIX_SAVE = 0x01
    const val MATRIX_RESTORE = 0x02
    const val TRANSLATE = 0x03
    const val SCALE = 0x04
    const val ROTATE = 0x05
    const val CLIP_RECT = 0x06
    const val CLIP_PATH = 0x07

    // --- Draw instructions ---
    const val DRAW_RECT = 0x10
    const val DRAW_ROUND_RECT = 0x11
    const val DRAW_CIRCLE = 0x12
    const val DRAW_PATH = 0x13
    const val DRAW_TEXT = 0x14
    const val DRAW_BITMAP = 0x15

    // --- Interaction ---
    const val ACTION_CLICK = 0x20

    /** Fill/stroke kind IDs used inside a paint sub-record (see `RemoteComposeParser.readPaint`). */
    object PaintStyleId {
        const val FILL = 0
        const val STROKE = 1
        const val FILL_AND_STROKE = 2
    }

    /** Segment-type IDs used inside a path sub-record (see `RemoteComposeParser.readPathCommands`). */
    object PathCommandId {
        const val MOVE_TO = 0
        const val LINE_TO = 1
        const val CUBIC_TO = 2
        const val CLOSE = 3
    }
}
