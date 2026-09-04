package com.example.remotecompose.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.remotecompose.engine.OpcodeExecutor
import com.example.remotecompose.engine.RenderContext
import com.example.remotecompose.parser.RealRemoteComposeParser

/**
 * Proof-of-concept screen for the real-`androidx.compose.remote`-payload round trip: parses
 * [bytes] with [RealRemoteComposeParser] (the real wire format, not the placeholder one
 * [com.example.remotecompose.ui.RemoteComposeCanvas] is built against) and renders the result
 * through the exact same [OpcodeExecutor] the real composable uses.
 *
 * Deliberately skips [com.example.remotecompose.ui.RemoteComposeCanvas] itself here — that
 * composable is hardwired to [com.example.remotecompose.parser.RemoteComposeParser] per its Phase
 * 3 contract, and swapping its parser is a real API decision for later, not something to fold
 * into a one-off demo. This screen exists only to prove the real payload renders correctly on
 * iOS; it skips fit-scaling and tap dispatch since the demo document has neither multiple sizes
 * nor interactive regions to exercise.
 *
 * The document's own [Header] canvas is centered on a neutral backdrop, rather than pinned to the
 * top-left of a full-screen canvas, so it doesn't sit under the status bar/notch in screenshots —
 * that's purely a demo-host presentation choice and has no bearing on the parser/renderer.
 */
@Composable
fun RealPayloadDemoScreen(bytes: ByteArray) {
    val document = remember(bytes) { RealRemoteComposeParser.parse(bytes) }
    val textMeasurer = rememberTextMeasurer()
    val renderContext = remember(document, textMeasurer) { RenderContext(document, textMeasurer) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF37474F)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(document.header.width.dp, document.header.height.dp)) {
            drawRect(color = Color.White, size = size)
            OpcodeExecutor.render(this, document.opcodes, renderContext)
        }
    }
}
