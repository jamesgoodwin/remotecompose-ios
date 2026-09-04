package com.example.remotecompose.parser

/**
 * Table of dynamic float values (layout metrics, animation progress, bound variables, etc.)
 * referenced by index from opcodes, mirroring how [StringPool] backs `OP_DRAW_TEXT`.
 *
 * This phase only decodes the pool into a flat, mutable `FloatArray` snapshot. Wiring these
 * slots up to a live, re-evaluated expression/animation system is the responsibility of the
 * execution engine ([com.example.remotecompose.engine], added in Phase 2), which can call
 * [set] as bound values change and re-trigger a draw pass.
 */
class VariablePool private constructor(private val values: FloatArray) {

    /** Number of variable slots in the pool. */
    val size: Int get() = values.size

    /** Returns the current value of variable [index], or `0f` if [index] is out of range. */
    operator fun get(index: Int): Float = if (index in values.indices) values[index] else 0f

    /** Overwrites variable [index] in place, if it exists. Out-of-range indices are ignored. */
    fun set(index: Int, value: Float) {
        if (index in values.indices) values[index] = value
    }

    companion object {
        /** An empty pool, useful as a default/placeholder before parsing occurs. */
        val EMPTY = VariablePool(FloatArray(0))

        /**
         * Reads the float/var pool section: an unsigned varint entry count followed by that many
         * IEEE-754 32-bit floats.
         */
        fun read(reader: BufferReader): VariablePool {
            val count = reader.readVarUIntAsInt()
            val values = FloatArray(count) { reader.readFloat32() }
            return VariablePool(values)
        }
    }
}
