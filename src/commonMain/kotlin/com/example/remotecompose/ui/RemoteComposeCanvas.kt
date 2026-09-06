package com.example.remotecompose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.example.remotecompose.engine.OpcodeExecutor
import com.example.remotecompose.engine.RenderContext
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.RemoteAction
import com.example.remotecompose.parser.RemoteComposeParser

/**
 * Platform-agnostic renderer for Remote Compose binary payloads.
 *
 * Parses [bytes] into a [com.example.remotecompose.model.RemoteDocument] once per distinct
 * [bytes] reference, then draws it into a [Canvas][androidx.compose.foundation.Canvas] via
 * [OpcodeExecutor] on every frame. The document's intrinsic size ([Header.width] x
 * [Header.height]) is letterboxed to fit the space [modifier] grants this composable — centered,
 * aspect-ratio preserved — rather than stretched, so callers can drop this into any layout size
 * without distorting the content; [modifier] otherwise fully controls how much space that is
 * (this composable does not impose its own size).
 *
 * Tap gestures are hit-tested in document space against the `OP_ACTION_CLICK` regions collected
 * by the most recent draw pass, and surfaced to [onAction] as [RemoteAction.Click]. Overlapping
 * regions resolve to the last one recorded — i.e. the one drawn on top.
 *
 * @param bytes The raw `.rc` payload as produced by `androidx.compose.remote`'s
 *   `RemoteComposeWriter`. Re-parsed only when this exact [ByteArray] instance changes
 *   (`remember(bytes)`), matching Kotlin's reference-based `ByteArray` equality — pass a new array
 *   instance when the underlying content changes, not the same array mutated in place.
 * @param onAction Invoked when a tap lands inside an interactive region. Defaults to a no-op.
 * @param fallback Optional composable shown instead of the canvas when [bytes] fails to parse
 *   (a truncated record, or an opcode this parser does not handle). When `null` and parsing
 *   fails, nothing is emitted.
 */
@Composable
fun RemoteComposeCanvas(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    onAction: (RemoteAction) -> Unit = {},
    fallback: (@Composable () -> Unit)? = null,
) {
    val document = remember(bytes) {
        runCatching { RemoteComposeParser.parse(bytes) }.getOrNull()
    }

    if (document == null) {
        fallback?.invoke()
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val renderContext = remember(document, textMeasurer) { RenderContext(document, textMeasurer) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(document, canvasSize) {
                detectTapGestures { tapOffset ->
                    val fit = fitDocumentToCanvas(
                        canvasWidth = canvasSize.width.toFloat(),
                        canvasHeight = canvasSize.height.toFloat(),
                        header = document.header,
                    )
                    val docPoint = fit.toDocumentSpace(tapOffset)
                    val hit = renderContext.interactiveRegions.lastOrNull { it.bounds.contains(docPoint) }
                        ?: return@detectTapGestures
                    onAction(
                        RemoteAction.Click(
                            actionId = hit.actionId,
                            targetUrl = hit.targetUrl,
                            payload = emptyMap(),
                        )
                    )
                }
            },
    ) {
        val fit = fitDocumentToCanvas(canvasWidth = size.width, canvasHeight = size.height, header = document.header)
        drawContext.canvas.save()
        drawContext.transform.translate(fit.offsetX, fit.offsetY)
        drawContext.transform.scale(fit.scale, fit.scale, pivot = Offset.Zero)
        OpcodeExecutor.render(this, document.opcodes, renderContext)
        drawContext.canvas.restore()
    }
}

/**
 * The uniform scale + centering offset that fits a [Header.width] x [Header.height] document into
 * a `canvasWidth` x `canvasHeight` viewport without distortion (equivalent to `ContentScale.Fit`).
 */
private data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    /** Maps a point in on-screen canvas coordinates back into the document's own coordinate space. */
    fun toDocumentSpace(canvasPoint: Offset): Offset =
        Offset((canvasPoint.x - offsetX) / scale, (canvasPoint.y - offsetY) / scale)
}

private fun fitDocumentToCanvas(canvasWidth: Float, canvasHeight: Float, header: Header): FitTransform {
    val docWidth = header.width.toFloat()
    val docHeight = header.height.toFloat()
    if (docWidth <= 0f || docHeight <= 0f || canvasWidth <= 0f || canvasHeight <= 0f) {
        return FitTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
    }
    val scale = minOf(canvasWidth / docWidth, canvasHeight / docHeight)
    val offsetX = (canvasWidth - docWidth * scale) / 2f
    val offsetY = (canvasHeight - docHeight * scale) / 2f
    return FitTransform(scale, offsetX, offsetY)
}
