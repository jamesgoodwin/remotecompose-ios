package io.github.jamesgoodwin.remotecompose.harness

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SkiaGraphicsContext
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamesgoodwin.remotecompose.engine.ComposeTextMetrics
import io.github.jamesgoodwin.remotecompose.engine.OpcodeExecutor
import io.github.jamesgoodwin.remotecompose.engine.RenderContext
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeParser
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Headless proof that a real `androidx.compose.remote` payload — written by the official
 * `remote-creation-jvm` library, not by anything in this codebase — decodes and renders correctly
 * through our own [RemoteComposeParser] / [OpcodeExecutor], the exact same execution engine
 * [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas] uses on iOS. Rendering here goes straight to
 * an off-screen Skia surface so it runs without a display server; the same
 * [OpcodeExecutor.render] call is what backs the on-screen iOS composable.
 */
/**
 * @param args optional `[inputRcPath] [outputPngPath] [timeSeconds] [taps]`, defaulting to the
 *   opcode-coverage sample used by the cross-platform demo apps — passing an alternate `.rc`
 *   (e.g. a hand-built showcase document) renders it the exact same way without touching that
 *   shared coverage fixture. `taps` is a comma-separated list of `x:y` points in document space,
 *   clicked in order before the frame is rendered, which is how an interactive document's later
 *   states get screenshotted.
 */
public fun main(args: Array<String>) {
    val inputPath = args.getOrElse(0) { "tools/rc-writer/sample.rc" }
    val outputPath = args.getOrElse(1) { "real-payload-render.png" }
    val bytes = File(inputPath).readBytes()
    val density = Density(1f)
    val textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    // Optional third argument: the animation time in seconds at which to evaluate the document.
    // Parsed as a Double: a Float cannot hold an epoch-scale number of seconds and a fraction of
    // one at the same time, and a document read off the wall clock is rendered at exactly those.
    val timeMillis = (args.getOrNull(2)?.toDoubleOrNull() ?: 0.0).let { (it * 1000.0).toLong() }
    val loaded = RemoteComposeParser.load(bytes, ComposeTextMetrics(textMeasurer))
    // Optional fifth argument: "dark" to paint the document's dark palette.
    if (args.getOrNull(4) == "dark") loaded.paintTheme = RemoteContext.THEME_DARK
    loaded.frame(0L)
    // Optional fourth argument: taps to deliver before rendering, as "x:y,x:y".
    args.getOrNull(3)?.split(',')?.filter { it.isNotBlank() }?.forEach { gesture ->
        val points = gesture.split('>')
        val (x, y) = points.first().split(':').map { it.trim().toFloat() }
        if (points.size == 1) {
            // A press as well as the click, so that anything reacting to the press — a ripple —
            // is under way in the frame that follows.
            loaded.touchDown(x, y)
            println("Tap at $x, $y ${if (loaded.click(x, y)) "handled" else "ignored"}")
            loaded.touchUp(x, y)
            // A frame at time zero, so that anything the tap set in motion starts there and the
            // frame rendered at `timeSeconds` is that far into it.
            loaded.frame(0L)
        } else {
            // "x:y>x:y" is a drag, which is how a scrolling component is moved.
            val (toX, toY) = points[1].split(':').map { it.trim().toFloat() }
            // A frame between each step, since a touch expression only follows the pointer while
            // the document is being evaluated — which on a device is what the next frame does.
            loaded.touchDown(x, y)
            loaded.frame(timeMillis)
            loaded.touchDrag(toX, toY)
            loaded.frame(timeMillis)
            loaded.touchUp(toX, toY)
            println("Drag from $x, $y to $toX, $toY")
        }
    }
    // Optional sixth argument: "flight" pushes a feed of named values before rendering, which is
    // how a document whose contents come from the host is screenshotted at all.
    if (args.getOrNull(5) == "flight") {
        loaded.setNamedString("flight", "BA 2490")
        loaded.setNamedString("route", "Bristol to Palma")
        loaded.setNamedString("gate", "B12")
        loaded.setNamedString("status", "Boarding")
        loaded.setNamedColor("statusColor", 0xFF2E7D32.toInt())
        loaded.setNamedFloat("minutes", 23f)
        loaded.setNamedFloat("boarded", 0.4f)
        if (args.getOrNull(6) == "delayed") {
            loaded.setNamedFloat("delayed", 1f)
            loaded.setNamedString("delayNote", "Delayed 25 min · new gate B31")
            loaded.setNamedString("status", "Delayed")
            loaded.setNamedColor("statusColor", 0xFFB3261E.toInt())
        }
        loaded.frame(0L)
    }
    // A document that animates has to have been running to be caught part way through one: a
    // single frame at a distant time leaves every animated value at the start of its transition,
    // since that is the frame that first saw the change. Stepping up to the moment puts each of
    // them where it would actually be.
    if (timeMillis > 0L) {
        var step = timeMillis - 1000L
        while (step < timeMillis) {
            loaded.frame(step)
            step += 20L
        }
    }
    val document = loaded.frame(timeMillis)
    println("Parsed real payload: ${document.header.width}x${document.header.height}, ${document.opcodes.size} opcode(s): ${document.opcodes}")

    val width = document.header.width
    val height = document.header.height
    val surface = Surface.makeRasterN32Premul(width, height)
    // Skia's own graphics context, so that a headless render can make the layers a shadow or a
    // blur needs. There is no composition here to take one from, and without it those two
    // attributes would be silently missing from the reference the devices are compared against.
    // It is marked internal to compose-ui and may move between versions; that is a risk worth
    // taking in a development harness and would not be in the library.
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    val renderContext = RenderContext(document, textMeasurer, SkiaGraphicsContext())

    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = surface.canvas.asComposeCanvas(),
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        drawRect(color = Color.White, size = size)
        OpcodeExecutor.render(this, document.opcodes, renderContext)
    }

    val pngBytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    val outFile = File(outputPath)
    outFile.writeBytes(pngBytes)
    println("Wrote ${pngBytes.size} bytes to ${outFile.absolutePath}")
}
