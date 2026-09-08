package io.github.jamesgoodwin.remotecompose.parser

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encoded image assets from `DATA_BITMAP` records, keyed by the writer's document-wide id.
 * Decoding to an [ImageBitmap] is deferred until first access via [get] and memoized, since a
 * document may declare more images than a given draw pass actually uses.
 */
internal class BitmapPool private constructor(private val rawEntries: Map<Int, ByteArray>) {

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
        val EMPTY = BitmapPool(emptyMap())

        /** Wraps the id-to-bytes map collected from `DATA_BITMAP` records while parsing. */
        fun fromEntries(entries: Map<Int, ByteArray>): BitmapPool = BitmapPool(entries.toMap())
    }
}
