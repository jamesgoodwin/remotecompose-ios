package com.example.remotecompose.model

import com.example.remotecompose.parser.BitmapPool
import com.example.remotecompose.parser.StringPool
import com.example.remotecompose.parser.VariablePool

/**
 * The fully parsed result of [com.example.remotecompose.parser.RemoteComposeParser.parse]: the
 * document header plus the three asset pools and opcode stream needed to render it.
 *
 * This is intentionally an inert data holder — no drawing, hit-testing, or variable evaluation
 * happens here. That belongs to the execution engine (Phase 2), which consumes a `RemoteDocument`
 * against a `DrawScope`.
 */
data class RemoteDocument(
    val header: Header,
    val strings: StringPool,
    val variables: VariablePool,
    val bitmaps: BitmapPool,
    val opcodes: List<Opcode>,
)
