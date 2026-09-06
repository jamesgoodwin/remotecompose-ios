package com.example.remotecompose.parser

/**
 * Thrown when a `.rc` byte stream cannot be interpreted: the reader ran past the end of the
 * buffer, or a record referenced a resource that was never defined.
 */
class RemoteComposeParseException(message: String) : Exception(message)

/**
 * Sequential, forward-only cursor over a `.rc` payload, matching `WireBuffer` in `remote-core`:
 * every multi-byte primitive is big-endian, floats are raw IEEE-754 bits, and there are no
 * variable-length integers anywhere in the format.
 *
 * Reading past the end of the buffer throws [RemoteComposeParseException] rather than an
 * unchecked index exception.
 */
class BufferReader(private val buffer: ByteArray) {

    /** Current absolute offset into the buffer, in bytes. */
    var position: Int = 0
        private set

    val size: Int get() = buffer.size

    val remaining: Int get() = size - position

    fun hasRemaining(): Boolean = position < size

    /** Moves the cursor to an absolute [offset]; used by `SKIP` records to jump over their body. */
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

    /**
     * Reads a 16-bit big-endian value as the signed short the caller stores it in: `readShort`
     * returns it widened and unsigned, and every field it feeds is declared `short`.
     */
    fun readS16(): Int = readU16().toShort().toInt()

    /** Reads a signed 32-bit big-endian integer (`WireBuffer.readInt`). */
    fun readS32(): Int {
        requireRemaining(4)
        val b0 = readU8()
        val b1 = readU8()
        val b2 = readU8()
        val b3 = readU8()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Reads a signed 64-bit big-endian integer (`WireBuffer.readLong`). */
    fun readS64(): Long {
        requireRemaining(8)
        var result = 0L
        repeat(8) { result = (result shl 8) or readU8().toLong() }
        return result
    }

    /** Reads an IEEE-754 32-bit float from the same big-endian bits as [readS32] (`WireBuffer.readFloat`). */
    fun readFloat32(): Float = Float.fromBits(readS32())

    /** Reads exactly [length] raw bytes. */
    fun readBytes(length: Int): ByteArray {
        requireRemaining(length)
        val out = buffer.copyOfRange(position, position + length)
        position += length
        return out
    }

    /** Reads [length] bytes and decodes them as UTF-8. */
    fun readUtf8(length: Int): String = readBytes(length).decodeToString()
}
