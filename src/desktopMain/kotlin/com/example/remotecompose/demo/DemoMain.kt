package com.example.remotecompose.demo

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.remotecompose.engine.OpcodeExecutor
import com.example.remotecompose.engine.RenderContext
import com.example.remotecompose.parser.RealRemoteComposeParser
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Headless proof that a real `androidx.compose.remote` payload — written by the official
 * `remote-creation-jvm` library, not by anything in this codebase — decodes and renders correctly
 * through our own [RealRemoteComposeParser] / [OpcodeExecutor], the exact same execution engine
 * [com.example.remotecompose.ui.RemoteComposeCanvas] uses on iOS. Rendering here goes straight to
 * an off-screen Skia surface so it runs without a display server; the same
 * [OpcodeExecutor.render] call is what backs the on-screen iOS composable.
 */
fun main() {
    val bytes = File("tools/rc-writer/sample.rc").readBytes()
    val document = RealRemoteComposeParser.parse(bytes)
    println("Parsed real payload: ${document.header.width}x${document.header.height}, ${document.opcodes.size} opcode(s): ${document.opcodes}")

    val width = document.header.width
    val height = document.header.height
    val surface = Surface.makeRasterN32Premul(width, height)

    val density = Density(1f)
    val textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
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
    val outFile = File("real-payload-render.png")
    outFile.writeBytes(pngBytes)
    println("Wrote ${pngBytes.size} bytes to ${outFile.absolutePath}")
}
