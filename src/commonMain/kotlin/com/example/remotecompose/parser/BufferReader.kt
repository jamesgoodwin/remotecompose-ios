package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color

/**
 * Thrown when a `.rc` byte stream cannot be interpreted — either the reader ran past the end of
 * the buffer, or a fixed-format section (e.g. the magic header) did not match what was expected.
 */
class RemoteComposeParseException(message: String) : Exception(message)

/**
 * Sequential, forward-only cursor over a `.rc` payload [ByteArray].
 *
 * All multi-byte primitives are read **big-endian**, and all bytes are treated as unsigned when
 * building larger values. This matches the framing assumed by [com.example.remotecompose.model.Header]
 * and [com.example.remotecompose.parser.RemoteComposeParser]; if a future revision of the wire
 * format specifies a different byte order, this is the only class that needs to change.
 *
 * Every read advances [position]. Reading past the end of the buffer throws
 * [RemoteComposeParseException] rather than an unchecked index exception, so callers (in
 * particular the opcode loop) can catch a single exception type and decide how to recover.
 */
class BufferReader(private val buffer: ByteArray) {

    /** Current absolute offset into [buffer], in bytes. */
    var position: Int = 0
        private set

    /** Total number of bytes in the underlying buffer. */
    val size: Int get() = buffer.size

    /** Bytes left to read before [position] reaches [size]. */
    val remaining: Int get() = size - position

    /** Whether at least one more byte can be read. */
    fun hasRemaining(): Boolean = position < size

    /**
     * Moves the cursor to an absolute [offset]. Used by the opcode loop to resynchronize after a
     * length-prefixed record, so a decoder that under- or over-reads its payload cannot corrupt
     * the stream for subsequent opcodes.
     */
    fun seek(offset: Int) {
        require(offset in 0..size) { "Seek offset $offset out of bounds for buffer of size $size" }
        position = offset
    }

    private fun requireRemaining(count: Int) {
        if (remaining < count) {
            throw RemoteComposeParseException(
                "Attempted to read $count byte(s) at position $position but only $remaining remain"
            )
        }
    }

    /** Reads a single unsigned byte, returned widened to [Int] (range 0..255). */
    fun readU8(): Int {
        requireRemaining(1)
        val value = buffer[position].toInt() and 0xFF
        position += 1
        return value
    }

    /** Reads a single signed byte (range -128..127). */
    fun readS8(): Int {
        requireRemaining(1)
        val value = buffer[position].toInt()
        position += 1
        return value
    }

    /** Reads an unsigned 16-bit big-endian integer. */
    fun readU16(): Int {
        requireRemaining(2)
        val hi = readU8()
        val lo = readU8()
        return (hi shl 8) or lo
    }

    /** Reads a signed 32-bit big-endian integer. */
    fun readS32(): Int {
        requireRemaining(4)
        val b0 = readU8()
        val b1 = readU8()
        val b2 = readU8()
        val b3 = readU8()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Reads a signed 64-bit big-endian integer. */
    fun readS64(): Long {
        requireRemaining(8)
        var result = 0L
        repeat(8) { result = (result shl 8) or readU8().toLong() }
        return result
    }

    /** Reads an IEEE-754 32-bit float, decoded from the same big-endian bit pattern as [readS32]. */
    fun readFloat32(): Float = Float.fromBits(readS32())

    /**
     * Reads an unsigned LEB128 variable-length integer, as used throughout the string pool, the
     * float/var pool, and per-opcode length prefixes.
     *
     * Each byte contributes 7 bits of magnitude; the top bit signals whether another byte
     * follows. Values are capped to fit in a [Long] (up to 10 continuation bytes) to guard
     * against a corrupt/malicious stream spinning forever.
     */
    fun readVarUInt(): Long {
        var result = 0L
        var shift = 0
        var byte: Int
        do {
            if (shift >= 70) {
                throw RemoteComposeParseException("VarInt at position $position is too long (possibly corrupt stream)")
            }
            byte = readU8()
            result = result or ((byte.toLong() and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        return result
    }

    /** Convenience overload of [readVarUInt] for call sites that only need an [Int]. */
    fun readVarUIntAsInt(): Int = readVarUInt().toInt()

    /**
     * Reads a zig-zag encoded signed LEB128 integer (the standard pairing with [readVarUInt] for
     * representing negative values compactly — used by opcodes whose parameters may be negative,
     * e.g. translate deltas expressed as integer device pixels).
     */
    fun readVarSInt(): Long {
        val raw = readVarUInt()
        return (raw ushr 1) xor -(raw and 1L)
    }

    /** Reads exactly [length] raw bytes. */
    fun readBytes(length: Int): ByteArray {
        requireRemaining(length)
        val out = buffer.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** Reads [length] bytes and decodes them as UTF-8. */
    fun readUtf8(length: Int): String = readBytes(length).decodeToString()

    /**
     * Reads a string pool entry: an unsigned varint byte-length prefix followed by that many
     * UTF-8 bytes.
     */
    fun readLengthPrefixedString(): String {
        val length = readVarUIntAsInt()
        return readUtf8(length)
    }

    /**
     * Reads a packed 32-bit ARGB color (0xAARRGGBB, matching [android.graphics.Color]'s packed
     * int layout) and wraps it directly in a Compose [Color].
     */
    fun readColor(): Color = Color(readS32())
}
