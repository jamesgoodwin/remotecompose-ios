package com.example.remotecompose.parser

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Table of raw, still-encoded image assets (PNG/JPEG/WEBP bytes) referenced by id from
 * `OP_DRAW_BITMAP`. Decoding to an [ImageBitmap] is deferred until first access via [get] and
 * memoized, since a document may declare more images than a given draw pass actually uses.
 *
 * Backed by a [Map] rather than a dense [List] for the same reason as [StringPool]: the real
 * `androidx.compose.remote` wire format ([RealRemoteComposeParser]) assigns resource ids from one
 * shared, document-wide counter across text/path/bitmap resources alike, so a bitmap id is not
 * necessarily 0-based or contiguous. [RemoteComposeParser]'s placeholder format's dense 0-based
 * indices are modeled as a map with keys `0 until count` in [read].
 */
class BitmapPool private constructor(private val rawEntries: Map<Int, ByteArray>) {

    private val decodedCache = mutableMapOf<Int, ImageBitmap?>()

    /** Number of bitmap entries in the pool (decoded or not). */
    val size: Int get() = rawEntries.size

    /**
     * Returns the decoded bitmap at [id], decoding and caching it on first access.
     *
     * Returns `null` — rather than throwing — if [id] is not present or the entry's bytes fail to
     * decode as a supported image format, so a single corrupt asset degrades that one draw call
     * instead of aborting the whole render.
     */
    fun get(id: Int): ImageBitmap? {
        decodedCache[id]?.let { return it }
        val raw = rawEntries[id] ?: return null
        return try {
            decodeImageBitmap(raw).also { decodedCache[id] = it }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** An empty pool, useful as a default/placeholder before parsing occurs. */
        val EMPTY = BitmapPool(emptyMap())

        /**
         * Reads the bitmap pool section: an unsigned varint entry count followed by that many
         * length-prefixed raw (still-encoded) image byte blobs, indexed densely from 0.
         */
        fun read(reader: BufferReader): BitmapPool {
            val count = reader.readVarUIntAsInt()
            val entries = LinkedHashMap<Int, ByteArray>(count)
            repeat(count) { index ->
                val length = reader.readVarUIntAsInt()
                entries[index] = reader.readBytes(length)
            }
            return BitmapPool(entries)
        }

        /** Wraps an id-to-bytes map collected while parsing, e.g. real `DATA_BITMAP` entries. */
        fun fromEntries(entries: Map<Int, ByteArray>): BitmapPool = BitmapPool(entries.toMap())
    }
}
