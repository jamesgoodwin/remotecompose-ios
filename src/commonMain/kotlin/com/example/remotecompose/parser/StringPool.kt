package com.example.remotecompose.parser

/**
 * Table of UTF-8 strings referenced by index/id from elsewhere in the document (most notably
 * `OP_DRAW_TEXT`). Keeping strings out of the opcode stream lets the same label be reused across
 * many draw calls without repeating its bytes.
 *
 * Backed by a [Map] rather than a dense [List] because the real `androidx.compose.remote` wire
 * format ([RealRemoteComposeParser]) assigns text ids that are not necessarily 0-based or
 * contiguous — they're just whatever counter value the writer's document-wide id allocator was at
 * when a given text was created. [RemoteComposeParser]'s placeholder format happens to use dense
 * 0-based indices, which [read] models as a map with keys `0 until count`.
 */
class StringPool private constructor(private val values: Map<Int, String>) {

    /** Number of strings in the pool. */
    val size: Int get() = values.size

    /**
     * Returns the string at [index], or an empty string if [index] is not present. A missing
     * string should never abort rendering of an otherwise-valid document, so this deliberately
     * does not throw.
     */
    operator fun get(index: Int): String = values[index] ?: ""

    companion object {
        /** An empty pool, useful as a default/placeholder before parsing occurs. */
        val EMPTY = StringPool(emptyMap())

        /**
         * Reads the string pool section: an unsigned varint entry count followed by that many
         * length-prefixed UTF-8 strings, indexed densely from 0.
         */
        fun read(reader: BufferReader): StringPool {
            val count = reader.readVarUIntAsInt()
            val values = LinkedHashMap<Int, String>(count)
            repeat(count) { index ->
                values[index] = reader.readLengthPrefixedString()
            }
            return StringPool(values)
        }

        /** Wraps an id-to-string map collected while parsing, e.g. real `DATA_TEXT` entries. */
        fun fromEntries(entries: Map<Int, String>): StringPool = StringPool(entries.toMap())
    }
}
