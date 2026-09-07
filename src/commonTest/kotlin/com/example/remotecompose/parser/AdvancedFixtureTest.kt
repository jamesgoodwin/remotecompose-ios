package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PathCommand
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the generating operations against `tools/rc-writer/advanced.rc` (see
 * `buildAdvancedSample()` in the writer tool): float functions and path expressions.
 */
class AdvancedFixtureTest {

    private val bytes = fixture("advanced")
    private val document by lazy { RemoteComposeParser.parse(bytes) }
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
    fun theMatrixExpressionMovesThePointsItIsGiven() {
        // Four corners of a 44x28 rectangle, translated to (150, 40) and turned 20 degrees.
        val lines = document.opcodes.filterIsInstance<Opcode.DrawLine>()
        assertEquals(4, lines.size)
        val corners = lines.map { it.x1 to it.y1 }
        for ((x, y) in corners) {
            assertEquals(hypot(22f, 14f), hypot(x - 150f, y - 40f), 0.01f, "a turn keeps every corner at its distance")
        }
        // Turned, so no edge is axis-aligned any more, and the sides keep their lengths.
        assertTrue(lines.none { it.y1 == it.y2 || it.x1 == it.x2 })
        assertEquals(44f, hypot(lines[0].x2 - lines[0].x1, lines[0].y2 - lines[0].y1), 0.01f)
        assertEquals(28f, hypot(lines[1].x2 - lines[1].x1, lines[1].y2 - lines[1].y1), 0.01f)
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

    /**
     * `DRAW_BITMAP_TEXT_ANCHORED`: the same run drawn three times about one point, differing
     * only in which part of itself lands there.
     *
     * `getHorizontalOffset` is `-width * (1 + panX) / 2 - left`, so -1 puts the run's left edge
     * on the point, 0 its centre and 1 its right edge.
     */
    @Test
    fun anAnchoredBitmapRunIsPlacedByItsPan() {
        val anchored = OperationReader.readAll(bytes).filterIsInstance<Operation.DrawBitmapTextAnchored>()
        assertEquals(listOf(-1f, 0f, 1f), anchored.map { it.panX })
        assertTrue(anchored.all { it.x == 150f }, "all three about the same point")

        // The glyphs each run drew, grouped by the row they landed in.
        val rows = document.opcodes.filterIsInstance<Opcode.DrawBitmap>()
            .filter { it.top > 70f && it.top < 140f }
            .groupBy { (it.top / 20f).toInt() }
            .entries.sortedBy { it.key }
        assertEquals(3, rows.size, "one row per anchoring")

        val spans = rows.map { row -> row.value.minOf { it.left } to row.value.maxOf { it.right } }
        val width = spans[0].second - spans[0].first
        assertTrue(width > 0f, "the run has a width: $width")
        assertTrue(spans.all { it.second - it.first == width }, "the same run each time: $spans")

        assertEquals(150f, spans[0].first, 0.01f, "panX -1 puts its left edge on the point")
        assertEquals(150f, (spans[1].first + spans[1].second) / 2f, 0.01f, "panX 0 its centre")
        assertEquals(150f, spans[2].second, 0.01f, "panX 1 its right edge")
    }
}
