package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PathCommand
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the generating operations against `tools/rc-writer/advanced.rc` (see
 * `buildAdvancedSample()` in the writer tool): float functions and path expressions.
 */
class AdvancedFixtureTest {

    private val document by lazy { RemoteComposeParser.parse(File("tools/rc-writer/advanced.rc").readBytes()) }
    private val rects by lazy { document.opcodes.filterIsInstance<Opcode.DrawRect>() }
    private val paths by lazy { document.opcodes.filterIsInstance<Opcode.DrawPath>() }

    private fun points(path: Opcode.DrawPath): List<Pair<Float, Float>> = path.commands.mapNotNull {
        when (it) {
            is PathCommand.MoveTo -> it.x to it.y
            is PathCommand.CubicTo -> it.x3 to it.y3
            else -> null
        }
    }

    @Test
    fun eachCallRunsTheFunctionBodyWithItsOwnArguments() {
        // bar(value, scale) = value * scale, called with (30, 4) then (15, 4); each bar starts
        // at x = 12 and is as wide as the value its own call left behind.
        val bars = rects.filter { it.top == 12f || it.top == 30f }
        assertEquals(2, bars.size)
        assertEquals(132f, bars[0].right, 0.001f)
        assertEquals(72f, bars[1].right, 0.001f)
    }

    @Test
    fun aPathExpressionSamplesItsTwoExpressions() {
        // x = 20 + 25t, y = 130 + 20 sin(2t) over t in [0, 2pi], 16 samples.
        val curve = points(paths[0])
        assertEquals(16, curve.size)
        assertEquals(20f, curve.first().first, 0.001f)
        assertEquals(130f, curve.first().second, 0.001f)
        assertEquals(20f + 25f * 6.2831855f, curve.last().first, 0.01f)
        assertTrue(curve.zipWithNext().all { (a, b) -> b.first > a.first }, "x rises with the sample position")
        assertTrue(curve.any { it.second > 148f } && curve.any { it.second < 112f }, "y swings a full amplitude")
    }

    @Test
    fun aPolarPathExpressionClosesAroundItsCentre() {
        val flower = paths[1]
        assertEquals(PathCommand.Close, flower.commands.last())
        // r = 26 + 8 sin(5t) around (100, 80): every sample sits between the petal radii.
        for ((x, y) in points(flower)) {
            val radius = hypot(x - 100f, y - 80f)
            assertTrue(radius in 17.9f..34.1f, "radius $radius is inside the petal range")
        }
    }

    @Test
    fun aBitmapFontRunPlacesOneBitmapPerGlyph() {
        // "RC C! R" with spacing 1: five drawn glyphs, the two spaces drawing nothing.
        val glyphs = document.opcodes.filterIsInstance<Opcode.DrawBitmap>().filter { it.top == 50f }
        assertEquals(5, glyphs.size)
        assertEquals(listOf(13f, 24f, 47f, 62f, 79f), glyphs.map { it.left })
        // Every glyph is drawn at its own bitmap's size, from the run's top edge down.
        assertTrue(glyphs.all { it.bottom - it.top == 14f })
        assertEquals(10f, glyphs[0].right - glyphs[0].left, "R is 10 wide")
        assertEquals(6f, glyphs[3].right - glyphs[3].left, "! is 6 wide")
    }

    @Test
    fun kerningPullsTheSecondGlyphLeft() {
        val glyphs = document.opcodes.filterIsInstance<Opcode.DrawBitmap>().filter { it.top == 50f }
        // R ends at 23 and both margins are 1, so an unkerned C would start at 25; "RC" is -2.
        assertEquals(24f, glyphs[1].left)
    }

    @Test
    fun theUnderlineIsAsWideAsTheMeasuredRun() {
        val underline = document.opcodes.filterIsInstance<Opcode.DrawRect>().first { it.top == 66f }
        // BITMAP_TEXT_MEASURE width: the advance loop's end, 79, from a left edge of 12.
        assertEquals(91f, underline.right, 0.001f)
    }

    @Test
    fun theSameRunOnAPathIsCentredOnEachPointAndRotated() {
        val opcodes = document.opcodes
        val onPath = opcodes.indices.filter {
            opcodes[it].let { op -> op is Opcode.DrawBitmap && op.left < 0f }
        }
        assertEquals(5, onPath.size)
        for (i in onPath) {
            val bitmap = opcodes[i] as Opcode.DrawBitmap
            // Centred horizontally on the path point, and lifted by the -7 y adjustment.
            assertEquals(0f, bitmap.left + bitmap.right, 0.001f)
            assertEquals(-7f, bitmap.top, 0.001f)
            assertTrue(opcodes[i - 3] is Opcode.MatrixSave)
            assertTrue(opcodes[i - 2] is Opcode.Translate)
            assertTrue(opcodes[i - 1] is Opcode.Rotate)
            assertTrue(opcodes[i + 1] is Opcode.MatrixRestore)
        }
        // The glyphs run left to right along the wave and lean with it.
        val translates = onPath.map { opcodes[it - 2] as Opcode.Translate }
        assertTrue(translates.zipWithNext().all { (a, b) -> b.dx > a.dx })
        assertTrue(onPath.map { (opcodes[it - 1] as Opcode.Rotate).degrees }.distinct().size > 3)
    }

    @Test
    fun everyParticleRunsTheLoopBodyWithItsOwnValues() {
        // 12 particles: x = 16 + 15i, y = 152 + 9 * (i % 3) then 6 lower from the loop's
        // equation, radius = 2 + i % 4.
        val circles = document.opcodes.filterIsInstance<Opcode.DrawCircle>()
        assertEquals(12, circles.size)
        circles.forEachIndexed { i, circle ->
            assertEquals(16f + 15f * i, circle.centerX, 0.001f)
            assertEquals(152f + 9f * (i % 3) + 6f, circle.centerY, 0.001f)
            assertEquals(2f + (i % 4), circle.radius, 0.001f)
        }
    }

    @Test
    fun theComparisonBodyRunsOnlyForTheParticlesThatMatch() {
        // x - 100 is above zero for the six particles from x = 106 on.
        val markers = rects.filter { it.top > 140f }
        assertEquals(6, markers.size)
        assertEquals(listOf(104f, 119f, 134f, 149f, 164f, 179f), markers.map { it.left })
    }

    @Test
    fun aLinearJoinKeepsItsControlPointsOnTheSegments() {
        // The same samples as the spline, joined straight: each cubic's controls are its ends.
        val zigzag = paths[2].commands.filterIsInstance<PathCommand.CubicTo>()
        assertTrue(zigzag.isNotEmpty())
        for (segment in zigzag) {
            assertEquals(segment.x2, segment.x3, 0.001f)
            assertEquals(segment.y2, segment.y3, 0.001f)
        }
        // The spline's controls, by contrast, leave the chord.
        val spline = paths[0].commands.filterIsInstance<PathCommand.CubicTo>()
        assertTrue(spline.any { hypot(it.x2 - it.x3, it.y2 - it.y3) > 1f })
    }
}
