package com.example.remotecompose.parser

/**
 * Deduplicated table of UTF-8 strings referenced by index from elsewhere in the document (most
 * notably `OP_DRAW_TEXT`). Keeping strings out of the opcode stream lets the same label be reused
 * across many draw calls without repeating its bytes.
 */
class StringPool private constructor(private val values: List<String>) {

    /** Number of strings in the pool. */
    val size: Int get() = values.size

    /**
     * Returns the string at [index], or an empty string if [index] is out of range. A missing
     * string should never abort rendering of an otherwise-valid document, so this deliberately
     * does not throw.
     */
    operator fun get(index: Int): String = values.getOrElse(index) { "" }

    companion object {
        /** An empty pool, useful as a default/placeholder before parsing occurs. */
        val EMPTY = StringPool(emptyList())

        /**
         * Reads the string pool section: an unsigned varint entry count followed by that many
         * length-prefixed UTF-8 strings.
         */
        fun read(reader: BufferReader): StringPool {
            val count = reader.readVarUIntAsInt()
            val values = ArrayList<String>(count)
            repeat(count) {
                values += reader.readLengthPrefixedString()
            }
            return StringPool(values)
        }
    }
}
