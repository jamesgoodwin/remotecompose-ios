package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.jamesgoodwin.remotecompose.engine.ComposeTextMetrics
import io.github.jamesgoodwin.remotecompose.engine.OpcodeExecutor
import io.github.jamesgoodwin.remotecompose.engine.RenderContext
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser
import io.github.jamesgoodwin.remotecompose.ui.rememberDocumentFrames
import androidx.compose.runtime.getValue

/**
 * Demo host for the cross-platform screenshot comparison: parses [bytes] with
 * [RemoteComposeParser] and renders through the same [OpcodeExecutor] that
 * [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas] uses.
 *
 * Draws directly rather than through `RemoteComposeCanvas` because the screenshot pipeline needs
 * the document at exactly 1:1 physical pixels at a known offset, with no fit-scaling.
 *
 * The document's own [Header] canvas is centered on a neutral backdrop, rather than pinned to the
 * top-left of a full-screen canvas, so it doesn't sit under the status bar/notch in screenshots —
 * that's purely a demo-host presentation choice and has no bearing on the parser/renderer.
 */
@Composable
fun RealPayloadDemoScreen(bytes: ByteArray) {
    val textMeasurer = rememberTextMeasurer()
    val loaded = remember(bytes, textMeasurer) {
        RemoteComposeParser.load(bytes, ComposeTextMetrics(textMeasurer))
    }
    val document by rememberDocumentFrames(loaded)
    val renderContext = remember(loaded, textMeasurer) { RenderContext(document, textMeasurer) }
    renderContext.document = document

    // The .rc wire format's coordinates are raw device-independent-ish units matching
    // document.header.width/height 1:1 — every opcode's x/y/left/top/etc is drawn straight into
    // DrawScope, which is a *physical-pixel* space, with no density conversion applied anywhere
    // in OpcodeExecutor. Sizing this Canvas via `.dp` would be wrong: Compose scales a dp value by
    // the device's density to get physical pixels, so on anything but a 1x-density device the
    // Canvas would end up *larger* than document.header.width/height actual pixels, and the
    // (density-unaware) opcode coordinates would only fill roughly `1/density` of it — exactly
    // what happened before this fix (confirmed via pixel measurement on a real device: content
    // consistently rendered shrunk into a corner, not merely "small margins"). Converting through
    // LocalDensity here sizes the Canvas to exactly document.header.width/height *physical*
    // pixels regardless of density, matching the coordinate space OpcodeExecutor actually draws
    // in.
    val density = LocalDensity.current
    val canvasWidth = with(density) { document.header.width.toDp() }
    val canvasHeight = with(density) { document.header.height.toDp() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF37474F)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(canvasWidth, canvasHeight)
                .pointerInput(loaded) {
                    // The canvas is 1:1 with document pixels here, so a tap position is already
                    // in document space. A tap the document does not claim switches the page.
                    detectTapGestures(
                        onPress = { offset ->
                            loaded.touchDown(offset.x, offset.y)
                            val released = tryAwaitRelease()
                            if (released) loaded.touchUp(offset.x, offset.y) else loaded.touchCancel(offset.x, offset.y)
                        },
                        onTap = { offset -> loaded.click(offset.x, offset.y) },
                    )
                },
        ) {
            drawRect(color = Color.White, size = size)
            OpcodeExecutor.render(this, document.opcodes, renderContext)
        }
    }
}
