package com.example.remotecompose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.parser.RemoteComposeDocument
import com.example.remotecompose.runtime.currentTimeMillis
import kotlinx.coroutines.isActive
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.example.remotecompose.engine.ComposeTextMetrics
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
 * Gestures are hit-tested in document space against the laid-out components' action lists. An
 * action that writes a document value shows up in the next frame by itself; a `HOST_ACTION`
 * reaches the caller through [onAction]. Overlapping components resolve to the topmost one.
 *
 * @param bytes The raw `.rc` payload as produced by `androidx.compose.remote`'s
 *   `RemoteComposeWriter`. Re-parsed only when this exact [ByteArray] instance changes
 *   (`remember(bytes)`), matching Kotlin's reference-based `ByteArray` equality — pass a new array
 *   instance when the underlying content changes, not the same array mutated in place.
 * @param onAction Invoked when the document runs a `HOST_ACTION`. Defaults to a no-op.
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
    val textMeasurer = rememberTextMeasurer()
    val loaded = remember(bytes, textMeasurer) {
        runCatching { RemoteComposeParser.load(bytes, ComposeTextMetrics(textMeasurer)) }.getOrNull()
    }

    if (loaded == null) {
        fallback?.invoke()
        return
    }

    val document by rememberDocumentFrames(loaded)
    val renderContext = remember(loaded, textMeasurer) { RenderContext(document, textMeasurer) }
    renderContext.document = document
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // HOST_ACTION reaches the caller; every other action writes a document value and shows up in
    // the next frame on its own.
    DisposableEffect(loaded, onAction) {
        loaded.onHostAction = { actionId, metadata ->
            onAction(RemoteAction.Click(actionId = actionId, targetUrl = metadata.ifEmpty { null }, payload = emptyMap()))
        }
        onDispose { loaded.onHostAction = null }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            // Drags before taps: a drag that starts inside a scrolling component moves it, and
            // the tap detector below never sees the gesture. A press that does not move is a tap.
            .pointerInput(loaded, canvasSize) {
                val fit = {
                    fitDocumentToCanvas(
                        canvasWidth = canvasSize.width.toFloat(),
                        canvasHeight = canvasSize.height.toFloat(),
                        header = loaded.header,
                    )
                }
                detectDragGestures(
                    onDragStart = { offset ->
                        val point = fit().toDocumentSpace(offset)
                        loaded.touchDown(point.x, point.y)
                    },
                    onDrag = { change, _ ->
                        val point = fit().toDocumentSpace(change.position)
                        loaded.touchDrag(point.x, point.y)
                    },
                    onDragEnd = { loaded.touchUp(Float.NaN, Float.NaN) },
                    onDragCancel = { loaded.touchCancel(Float.NaN, Float.NaN) },
                )
            }
            .pointerInput(loaded, canvasSize) {
                val fit = {
                    fitDocumentToCanvas(
                        canvasWidth = canvasSize.width.toFloat(),
                        canvasHeight = canvasSize.height.toFloat(),
                        header = loaded.header,
                    )
                }
                detectTapGestures(
                    onPress = { offset ->
                        val point = fit().toDocumentSpace(offset)
                        loaded.touchDown(point.x, point.y)
                        val released = tryAwaitRelease()
                        val end = fit().toDocumentSpace(offset)
                        if (released) loaded.touchUp(end.x, end.y) else loaded.touchCancel(end.x, end.y)
                    },
                    onTap = { offset ->
                        val point = fit().toDocumentSpace(offset)
                        loaded.click(point.x, point.y)
                    },
                )
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
 * Drives a loaded document's frames: evaluates it once immediately, then, for as long as the
 * document reports [RemoteComposeDocument.needsRepaint], re-evaluates it on every display frame
 * with the wall clock. A static document is evaluated once and never again.
 */
@Composable
fun rememberDocumentFrames(loaded: RemoteComposeDocument): State<RemoteDocument> {
    val frame = remember(loaded) { mutableStateOf(loaded.frame(currentTimeMillis())) }
    LaunchedEffect(loaded) {
        while (isActive) {
            withFrameMillis { }
            if (loaded.needsRepaint) frame.value = loaded.frame(currentTimeMillis())
        }
    }
    return frame
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
