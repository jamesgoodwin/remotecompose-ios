package com.example.remotecompose.demo

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.remotecompose.engine.ComposeTextMetrics
import com.example.remotecompose.engine.OpcodeExecutor
import com.example.remotecompose.engine.RenderContext
import com.example.remotecompose.parser.RemoteComposeParser
import com.example.remotecompose.runtime.RemoteContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Headless proof that a real `androidx.compose.remote` payload — written by the official
 * `remote-creation-jvm` library, not by anything in this codebase — decodes and renders correctly
 * through our own [RemoteComposeParser] / [OpcodeExecutor], the exact same execution engine
 * [com.example.remotecompose.ui.RemoteComposeCanvas] uses on iOS. Rendering here goes straight to
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
fun main(args: Array<String>) {
    val inputPath = args.getOrElse(0) { "tools/rc-writer/sample.rc" }
    val outputPath = args.getOrElse(1) { "real-payload-render.png" }
    val bytes = File(inputPath).readBytes()
    val density = Density(1f)
    val textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    // Optional third argument: the animation time in seconds at which to evaluate the document.
    val timeMillis = (args.getOrNull(2)?.toFloatOrNull() ?: 0f).let { (it * 1000f).toLong() }
    val loaded = RemoteComposeParser.load(bytes, ComposeTextMetrics(textMeasurer))
    // Optional fifth argument: "dark" to paint the document's dark palette.
    if (args.getOrNull(4) == "dark") loaded.paintTheme = RemoteContext.THEME_DARK
    loaded.frame(0L)
    // Optional fourth argument: taps to deliver before rendering, as "x:y,x:y".
    args.getOrNull(3)?.split(',')?.filter { it.isNotBlank() }?.forEach { gesture ->
        val points = gesture.split('>')
        val (x, y) = points.first().split(':').map { it.trim().toFloat() }
        if (points.size == 1) {
            println("Tap at $x, $y ${if (loaded.click(x, y)) "handled" else "ignored"}")
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
    val document = loaded.frame(timeMillis)
    println("Parsed real payload: ${document.header.width}x${document.header.height}, ${document.opcodes.size} opcode(s): ${document.opcodes}")

    val width = document.header.width
    val height = document.header.height
    val surface = Surface.makeRasterN32Premul(width, height)
    val renderContext = RenderContext(document, textMeasurer)

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
