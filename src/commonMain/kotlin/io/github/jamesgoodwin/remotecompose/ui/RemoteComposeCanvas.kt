package io.github.jamesgoodwin.remotecompose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import io.github.jamesgoodwin.remotecompose.model.RemoteDocument
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeDocument
import io.github.jamesgoodwin.remotecompose.runtime.currentTimeMillis
import kotlinx.coroutines.isActive
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import io.github.jamesgoodwin.remotecompose.engine.ComposeTextMetrics
import io.github.jamesgoodwin.remotecompose.engine.OpcodeExecutor
import io.github.jamesgoodwin.remotecompose.engine.RenderContext
import io.github.jamesgoodwin.remotecompose.model.Header
import io.github.jamesgoodwin.remotecompose.model.RemoteAction
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext

/**
 * Platform-agnostic renderer for Remote Compose binary payloads.
 *
 * Parses [bytes] into a [io.github.jamesgoodwin.remotecompose.model.RemoteDocument] once per distinct
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
 * @param dark Which of the document's palettes to paint; see `THEME` and `COLOR_THEME`. A
 *   document with one palette looks the same either way.
 * @param onAction Invoked when the document runs a `HOST_ACTION`. Defaults to a no-op.
 * @param onDocument Handed the parsed document once, when it is first loaded. A document whose
 *   contents come from the host — one that names its values with `NAMED_VARIABLE` — needs a
 *   handle to be filled in through; this is it.
 * @param fallback Optional composable shown instead of the canvas when [bytes] fails to parse
 *   (a truncated record, or an opcode this parser does not handle). When `null` and parsing
 *   fails, nothing is emitted.
 */
@Composable
public fun RemoteComposeCanvas(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    onAction: (RemoteAction) -> Unit = {},
    onDocument: ((RemoteComposeDocument) -> Unit)? = null,
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

    // A document carries a palette for each mode; this is the host saying which one it is in.
    loaded.paintTheme = if (dark) RemoteContext.THEME_DARK else RemoteContext.THEME_LIGHT

    val document by rememberDocumentFrames(loaded)
    // Shadow and blur are properties of a layer rather than of a draw call, and a layer can only
    // be made from the graphics context the composition provides.
    val graphicsContext = LocalGraphicsContext.current
    val renderContext = remember(loaded, textMeasurer, graphicsContext) {
        RenderContext(document, textMeasurer, graphicsContext)
    }
    renderContext.document = document
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(loaded, onDocument) {
        onDocument?.invoke(loaded)
        onDispose { }
    }

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
            .documentGestures(loaded, canvasSize) {
                fitDocumentToCanvas(
                    canvasWidth = canvasSize.width.toFloat(),
                    canvasHeight = canvasSize.height.toFloat(),
                    header = loaded.header,
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
 * Feeds one pointer gesture at a time to [loaded], mapping screen points into document space with
 * [fit]; [key] restarts the handler when the geometry it captured changes.
 *
 * One handler for the whole gesture rather than a drag detector beside a tap one:
 * `detectTapGestures` consumes the press as soon as it arrives, so whichever of the two was the
 * inner modifier took the gesture and the other never saw it. With a tap detector inside a drag
 * detector that meant `awaitFirstDown` never returned and a scrolling component could not be
 * moved on a device at all, while a document driven directly scrolled perfectly well.
 *
 * The press reaches the document either way. Whether it turns out to be a drag or a tap is only
 * known once the pointer has moved past the touch slop, which is what separates scrolling a list
 * from clicking the row under the finger.
 */
internal fun Modifier.documentGestures(
    loaded: RemoteComposeDocument,
    key: Any?,
    fit: () -> FitTransform,
): Modifier = pointerInput(loaded, key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = fit().toDocumentSpace(down.position)
        loaded.touchDown(start.x, start.y)
        // How fast the finger is going when it leaves is what a scrolling list carries on with.
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var last = down
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            last = change
            if (!change.pressed) break
            velocity.addPosition(change.uptimeMillis, change.position)
            if (!dragging &&
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                dragging = true
            }
            if (dragging) {
                val point = fit().toDocumentSpace(change.position)
                loaded.touchDrag(point.x, point.y)
                // Claim the gesture, so that whatever this canvas sits inside does not take it
                // over halfway through a scroll.
                change.consume()
            }
        }
        val end = fit().toDocumentSpace(last.position)
        // The tracker measures in pixels a second; the document is in its own units.
        val scale = fit().scale
        val pixelsPerSecond = if (dragging) velocity.calculateVelocity() else Velocity.Zero
        loaded.touchUp(end.x, end.y, pixelsPerSecond.x / scale, pixelsPerSecond.y / scale)
        // A press that never moved is a click on whatever is under it.
        if (!dragging) loaded.click(end.x, end.y)
    }
}

/**
 * Drives a loaded document's frames: evaluates it once immediately, then, for as long as the
 * document reports [RemoteComposeDocument.needsRepaint], re-evaluates it on every display frame
 * with the wall clock. A static document is evaluated once and never again.
 */
@Composable
public fun rememberDocumentFrames(loaded: RemoteComposeDocument): State<RemoteDocument> {
    val frame = remember(loaded) { mutableStateOf(loaded.frame(currentTimeMillis())) }
    LaunchedEffect(loaded) {
        var drawnAt = currentTimeMillis()
        while (isActive) {
            withFrameMillis { }
            val now = currentTimeMillis()
            // `getOpsToUpdate`: a document that has changed wants a frame now; one that only
            // asked to be woken wants one when its own delay is up, and nothing in between.
            val delay = loaded.nextRepaintDelayMillis()
            if (delay == 0 || (delay > 0 && now - drawnAt >= delay)) {
                frame.value = loaded.frame(now)
                drawnAt = now
            }
        }
    }
    return frame
}

/**
 * The uniform scale + centering offset that fits a [Header.width] x [Header.height] document into
 * a `canvasWidth` x `canvasHeight` viewport without distortion (equivalent to `ContentScale.Fit`).
 */
internal data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    /** Maps a point in on-screen canvas coordinates back into the document's own coordinate space. */
    fun toDocumentSpace(canvasPoint: Offset): Offset =
        Offset((canvasPoint.x - offsetX) / scale, (canvasPoint.y - offsetY) / scale)
}

internal fun fitDocumentToCanvas(canvasWidth: Float, canvasHeight: Float, header: Header): FitTransform {
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
