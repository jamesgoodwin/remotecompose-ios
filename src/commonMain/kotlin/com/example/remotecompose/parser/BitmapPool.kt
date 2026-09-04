package com.example.remotecompose.parser

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Table of raw, still-encoded image assets (PNG/JPEG/WEBP bytes) referenced by index from
 * `OP_DRAW_BITMAP`. Decoding to an [ImageBitmap] is deferred until first access via [get] and
 * memoized, since a document may declare more images than a given draw pass actually uses.
 */
class BitmapPool private constructor(private val rawEntries: List<ByteArray>) {

    private val decodedCache = arrayOfNulls<ImageBitmap?>(rawEntries.size)

    /** Number of bitmap entries in the pool (decoded or not). */
    val size: Int get() = rawEntries.size

    /**
     * Returns the decoded bitmap at [index], decoding and caching it on first access.
     *
     * Returns `null` — rather than throwing — if [index] is out of range or the entry's bytes
     * fail to decode as a supported image format, so a single corrupt asset degrades that one
     * draw call instead of aborting the whole render.
     */
    fun get(index: Int): ImageBitmap? {
        if (index !in rawEntries.indices) return null
        decodedCache[index]?.let { return it }
        return try {
            decodeImageBitmap(rawEntries[index]).also { decodedCache[index] = it }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** An empty pool, useful as a default/placeholder before parsing occurs. */
        val EMPTY = BitmapPool(emptyList())

        /**
         * Reads the bitmap pool section: an unsigned varint entry count followed by that many
         * length-prefixed raw (still-encoded) image byte blobs.
         */
        fun read(reader: BufferReader): BitmapPool {
            val count = reader.readVarUIntAsInt()
            val entries = ArrayList<ByteArray>(count)
            repeat(count) {
                val length = reader.readVarUIntAsInt()
                entries += reader.readBytes(length)
            }
            return BitmapPool(entries)
        }
    }
}
