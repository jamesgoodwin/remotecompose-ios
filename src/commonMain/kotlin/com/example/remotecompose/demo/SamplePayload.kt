package com.example.remotecompose.demo

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 of `tools/rc-writer/sample.rc` — a real `.rc` payload produced by the official
 * `androidx.compose.remote:remote-creation-jvm:1.0.0-alpha18` writer (a red `drawRect`, a blue
 * `drawCircle`, a green `drawRoundRect`, and a `drawTextAnchored`), not by anything in this
 * codebase. Shared by every platform demo entry point (iOS, Android) so they render byte-identical
 * input — the point of the cross-platform comparison is to catch *rendering* differences, not to
 * accidentally compare two different payloads.
 */
@OptIn(ExperimentalEncodingApi::class)
val SAMPLE_RC_BYTES: ByteArray by lazy {
    Base64.decode(
        "AAAAAAEAAAABAAAAAAAAAMgAAADIAAAAAAAAAABmAAAAKgAAAARkZW1vZwAAACooAAAAAgAAAAT/5Tk1KkGgAABBoAAAQzQAAEM0AAAo" +
            "AAAAAgAAAAT/HojlLkJwAABDDAAAQfAAACgAAAACAAAABP9DoEczQtwAAELcAABDPgAAQz4AAEFAAABBQAAAKAAAAAIAAAAE/wAA" +
            "AGYAAAArAAAAAkhphQAAACtCyAAAQaAAAAAAAAAAAAAAAAAAAA=="
    )
}
