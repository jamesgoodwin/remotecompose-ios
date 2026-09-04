package com.example.remotecompose.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
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
 */
@Composable
fun RealPayloadDemoScreen(bytes: ByteArray) {
    val document = remember(bytes) { RealRemoteComposeParser.parse(bytes) }
    val textMeasurer = rememberTextMeasurer()
    val renderContext = remember(document, textMeasurer) { RenderContext(document, textMeasurer) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = Color.White, size = size)
        OpcodeExecutor.render(this, document.opcodes, renderContext)
    }
}
