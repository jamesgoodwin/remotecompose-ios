package io.github.jamesgoodwin.remotecompose.model

import io.github.jamesgoodwin.remotecompose.parser.BitmapPool

/**
 * The parsed result of [io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser.parse]: the header,
 * the resource pools draw opcodes reference by id, and the flattened opcode stream.
 *
 * Inert data: no drawing, hit-testing, or variable evaluation happens here. That belongs to
 * [io.github.jamesgoodwin.remotecompose.engine.OpcodeExecutor].
 *
 * @property strings `DATA_TEXT` entries (and every text-producing operation's result) keyed by
 *   the writer's document-wide id. Ids are not contiguous or 0-based.
 */
public data class RemoteDocument internal constructor(
    val header: Header,
    val strings: Map<Int, String>,
    internal val bitmaps: BitmapPool,
    val opcodes: List<Opcode>,
    internal val shaders: Map<Int, ShaderSpec> = emptyMap(),
)
