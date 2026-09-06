package com.example.remotecompose.model

import com.example.remotecompose.parser.BitmapPool

/**
 * The parsed result of [com.example.remotecompose.parser.RemoteComposeParser.parse]: the header,
 * the resource pools draw opcodes reference by id, and the flattened opcode stream.
 *
 * Inert data: no drawing, hit-testing, or variable evaluation happens here. That belongs to
 * [com.example.remotecompose.engine.OpcodeExecutor].
 *
 * @property strings `DATA_TEXT` entries (and every text-producing operation's result) keyed by
 *   the writer's document-wide id. Ids are not contiguous or 0-based.
 */
data class RemoteDocument(
    val header: Header,
    val strings: Map<Int, String>,
    val bitmaps: BitmapPool,
    val opcodes: List<Opcode>,
)
