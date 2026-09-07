package com.example.remotecompose.harness

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
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.parser.RemoteComposeParser
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Compares what a device drew against what this renderer draws for the same document.
 *
 * The reference is rendered here, headless, by the same [OpcodeExecutor] the apps use. A second
 * render with the text opcodes removed marks which pixels are glyphs, and those are the only ones
 * allowed to differ — two font rasterizers never agree pixel for pixel, but the same geometry
 * through the same renderer should land in exactly the same place, so a moved shape is a failure
 * rather than a tolerance to widen.
 *
 * Usage: `pixelHarness <document.rc> <label>=<screenshot.png> [...] [--out <dir>]
 * [--text-fraction <0..1>] [--channel-tolerance <0..255>] [--search-radius <px>]
 * [--mask-radius <px>] [--allow-shape-pixels <n>]`
 *
 * `--allow-shape-pixels` is for a difference that is real and understood — a document whose
 * geometry comes from text measurement is genuinely a different shape on a platform whose fonts
 * are different — and the caller passing it is expected to say why.
 *
 * A screenshot may be the whole device screen: the document is found inside it by
 * [ImageDiff.locateDocument], which relies on the demo host drawing it opaque and centred on the
 * flat backdrop that host uses. Exits non-zero if any comparison fails.
 */
fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    var outDir = File("build/pixel-harness")
    var textFraction = ImageDiff.DEFAULT_TEXT_FRACTION
    var channelTolerance = ImageDiff.DEFAULT_CHANNEL_TOLERANCE
    var searchRadius = ImageDiff.DEFAULT_SEARCH_RADIUS
    var maskRadius = -1
    var allowedShapePixels = 0
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> outDir = File(args[++i])
            "--text-fraction" -> textFraction = args[++i].toFloat()
            "--channel-tolerance" -> channelTolerance = args[++i].toInt()
            "--search-radius" -> searchRadius = args[++i].toInt()
            "--mask-radius" -> maskRadius = args[++i].toInt()
            "--allow-shape-pixels" -> allowedShapePixels = args[++i].toInt()
            else -> positional += args[i]
        }
        i++
    }
    if (positional.size < 2) {
        println("usage: pixelHarness <document.rc> <label>=<screenshot.png> [...]")
        exitProcess(2)
    }

    val documentFile = File(positional[0])
    val document = renderDocument(documentFile)
    outDir.mkdirs()

    val reference = Raster.of(document.full.toImage())
    val radius = if (maskRadius >= 0) maskRadius else ImageDiff.maskRadiusFor(document.largestTextSize)
    val mask = ImageDiff.textMask(reference, Raster.of(document.withoutText.toImage()), radius)
    writeImage(document.full, File(outDir, "${documentFile.nameWithoutExtension}-reference.png"))

    println(
        "${documentFile.name}: ${reference.width}x${reference.height}, ${mask.count { it }} text pixels " +
            "(mask radius $radius for ${document.largestTextSize}px text)" +
            if (allowedShapePixels > 0) ", $allowedShapePixels shape pixels allowed" else "",
    )
    var failures = 0
    for (entry in positional.drop(1)) {
        val label = entry.substringBefore('=', "candidate")
        val path = File(entry.substringAfter('='))
        if (!path.isFile) {
            println("  $label: no screenshot at $path")
            failures++
            continue
        }
        val screen = Raster.of(ImageIO.read(path))
        val candidate = when {
            screen.width == reference.width && screen.height == reference.height -> screen
            else -> {
                val at = ImageDiff.locateDocument(screen, reference.width, reference.height, DEMO_BACKDROP)
                if (at == null) {
                    println("  $label: no ${reference.width}x${reference.height} document found in $path")
                    failures++
                    continue
                }
                println("  $label: document found at ${at.first},${at.second}")
                screen.crop(at.first, at.second, reference.width, reference.height)
            }
        }
        val diff = IntArray(reference.pixels.size)
        val report = ImageDiff.compare(reference, candidate, mask, channelTolerance, searchRadius, diff)
        val passed = report.passed(textFraction, allowedShapePixels)
        if (!passed) failures++
        println("  $label: ${if (passed) "pass" else "FAIL"} — ${report.describe(textFraction)}")
        writeImage(candidate, File(outDir, "${documentFile.nameWithoutExtension}-$label.png"))
        writeImage(
            Raster(reference.width, reference.height, diff),
            File(outDir, "${documentFile.nameWithoutExtension}-$label-diff.png"),
        )
    }
    if (failures > 0) {
        println("$failures comparison(s) failed")
        exitProcess(1)
    }
}

/** The slate the demo host paints behind a document; see `RealPayloadDemoScreen`. */
private const val DEMO_BACKDROP = 0xFF37474F.toInt()

private class RenderedDocument(val full: Raster, val withoutText: Raster, val largestTextSize: Float)

/** Renders [file] twice: as it is, and with every text draw dropped. */
private fun renderDocument(file: File): RenderedDocument {
    val density = Density(1f)
    val textMeasurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    val loaded = RemoteComposeParser.load(file.readBytes(), ComposeTextMetrics(textMeasurer))
    loaded.frame(0L)
    val document = loaded.frame(0L)
    val texts = document.opcodes.filterIsInstance<Opcode.DrawText>()
    return RenderedDocument(
        full = render(document, document.opcodes, textMeasurer, density),
        withoutText = render(document, document.opcodes.filter { it !is Opcode.DrawText }, textMeasurer, density),
        largestTextSize = texts.maxOfOrNull { it.paint.textSize } ?: 0f,
    )
}

private fun render(
    document: RemoteDocument,
    opcodes: List<Opcode>,
    textMeasurer: TextMeasurer,
    density: Density,
): Raster {
    val width = document.header.width
    val height = document.header.height
    val surface = Surface.makeRasterN32Premul(width, height)
    val context = RenderContext(document, textMeasurer)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = surface.canvas.asComposeCanvas(),
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        drawRect(color = Color.White, size = size)
        OpcodeExecutor.render(this, opcodes, context)
    }
    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    return Raster.of(ImageIO.read(png.inputStream()))
}

private fun writeImage(raster: Raster, file: File) {
    ImageIO.write(raster.toImage(), "png", file)
}
