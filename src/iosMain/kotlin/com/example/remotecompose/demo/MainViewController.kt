package com.example.remotecompose.demo

import androidx.compose.ui.window.ComposeUIViewController
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.UIKit.UIViewController

/**
 * Base64 of `tools/rc-writer/sample.rc` — a real `.rc` payload produced by the official
 * `androidx.compose.remote:remote-creation-jvm:1.0.0-alpha18` writer (one red `drawRect`), not by
 * anything in this codebase. Embedded as a constant rather than bundled as a resource so this
 * proof-of-concept app has zero resource-loading setup to get wrong on first run.
 */
@OptIn(ExperimentalEncodingApi::class)
private val SAMPLE_RC_BYTES: ByteArray by lazy {
    Base64.decode(
        "AAAAAAEAAAABAAAAAAAAAMgAAADIAAAAAAAAAABmAAAAKgAAAARkZW1vZwAAACooAAAAAgAAAAT/5Tk1KkGgAABBoAAAQzQAAEM0AAA="
    )
}

/** Entry point called from Swift (`ComposeApp.swift`) to host the round-trip demo in a UIKit view controller. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    RealPayloadDemoScreen(SAMPLE_RC_BYTES)
}
