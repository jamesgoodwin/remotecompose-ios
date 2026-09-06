package com.example.remotecompose.parser

import com.example.remotecompose.model.Header
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.text.TextMetricsProvider

/**
 * A loaded `.rc` document: its decoded [operations] plus the [context] they evaluate against.
 * The equivalent of `CoreDocument`: one instance lives as long as the document is shown, and
 * each [frame] re-evaluates the time-dependent operations and flattens the result.
 */
class RemoteComposeDocument internal constructor(
    val header: Header,
    val operations: List<Operation>,
    val context: RemoteContext,
    private val textMetrics: TextMetricsProvider,
) {
    /** Decoded lazily after the first frame has collected every `DATA_BITMAP`. */
    private val bitmaps: BitmapPool by lazy { BitmapPool.fromEntries(context.bitmaps) }

    /**
     * True after a [frame] that read a time variable or advanced an animation: the host should
     * schedule another frame. False for a static document.
     */
    val needsRepaint: Boolean get() = context.needsRepaint

    /**
     * Evaluates the document at wall-clock time [nowMillis] and returns its flattened opcodes.
     * The first call fixes the document's load time, so passing `0` first and `t` next yields an
     * animation time of `t` milliseconds.
     */
    fun frame(nowMillis: Long): RemoteDocument {
        context.beginFrame(nowMillis)
        val opcodes = RemoteComposeParser.build(operations, context, textMetrics)
        return RemoteDocument(header, context.texts.toMap(), bitmaps, opcodes)
    }
}
