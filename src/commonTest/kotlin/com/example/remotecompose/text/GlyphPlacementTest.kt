package com.example.remotecompose.text

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.geometry.PathGeometry
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathGeometryTest {

    private val square = listOf(
        PathCommand.MoveTo(0f, 0f),
        PathCommand.LineTo(10f, 0f),
        PathCommand.LineTo(10f, 10f),
        PathCommand.Close,
    )

    @Test
    fun lengthSumsSegmentsIncludingTheClosingEdge() {
        // 10 across, 10 down, then the hypotenuse back to the start.
        assertEquals(20f + hypot(10f, 10f), PathGeometry.length(square), 0.001f)
    }

    @Test
    fun pointAtFractionWalksArcLength() {
        val quarter = PathGeometry.pointAtFraction(square, 0.25f)!!
        // A quarter of 34.14 is 8.53, still on the first edge.
        assertEquals(8.535f, quarter.x, 0.01f)
        assertEquals(0f, quarter.y, 0.01f)
        assertTrue(quarter.tangentX > 0f && quarter.tangentY == 0f, "runs left to right there")
    }

    @Test
    fun curvesAreFlattenedIntoManySegments() {
        val curve = listOf(PathCommand.MoveTo(0f, 0f), PathCommand.QuadraticTo(10f, 10f, 20f, 0f))
        assertEquals(16, PathGeometry.flatten(curve).size)
        assertTrue(PathGeometry.length(curve) > 20f, "the arc is longer than the chord")
    }

    @Test
    fun distancePastTheEndClampsToTheTip() {
        val segments = PathGeometry.flatten(listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(10f, 0f)))
        assertEquals(10f, PathGeometry.pointAtDistance(segments, 99f)!!.x, 0.001f)
    }
}

class GlyphPlacementTest {

    private val paint = PaintStyle(Color.Black, PaintStyleKind.FILL, textSize = 10f)
    private val metrics = EstimatedTextMetrics
    private val glyphWidth = 1 * 10f * 0.55f // EstimatedTextMetrics: one character at size 10

    /** Every glyph is a save/translate/rotate/draw/restore group. */
    private fun groups(opcodes: List<Opcode>): List<List<Opcode>> = opcodes.chunked(5)

    @Test
    fun textOnAHorizontalLineAdvancesWithoutRotating() {
        val line = listOf(PathCommand.MoveTo(0f, 50f), PathCommand.LineTo(100f, 50f))
        val out = GlyphPlacement.onPath("abc", stringIndex = 7, commands = line, hOffset = 0f, vOffset = 0f, paint = paint, metrics = metrics)
        val groups = groups(out)
        assertEquals(3, groups.size)
        groups.forEachIndexed { i, group ->
            val translate = group[1] as Opcode.Translate
            val rotate = group[2] as Opcode.Rotate
            val text = group[3] as Opcode.DrawText
            // Glyph i is centered on its own advance: (i + 0.5) advances along the line.
            assertEquals((i + 0.5f) * glyphWidth, translate.dx, 0.01f)
            assertEquals(50f, translate.dy, 0.01f)
            assertEquals(0f, rotate.degrees, 0.01f)
            assertEquals(7, text.stringIndex)
            assertEquals(i, text.substringStart)
            assertEquals(i + 1, text.substringEnd)
            assertEquals(-glyphWidth / 2f, text.x, 0.01f)
        }
    }

    @Test
    fun hOffsetStartsTheRunFurtherAlongAndVOffsetPushesItSideways() {
        val line = listOf(PathCommand.MoveTo(0f, 50f), PathCommand.LineTo(100f, 50f))
        val shifted = groups(
            GlyphPlacement.onPath("a", 0, line, hOffset = 20f, vOffset = 6f, paint = paint, metrics = metrics),
        ).single()
        val translate = shifted[1] as Opcode.Translate
        assertEquals(20f + glyphWidth / 2f, translate.dx, 0.01f)
        // The normal of a left-to-right line points down the screen.
        assertEquals(56f, translate.dy, 0.01f)
    }

    @Test
    fun textOnAVerticalLineRotatesAQuarterTurn() {
        val line = listOf(PathCommand.MoveTo(10f, 0f), PathCommand.LineTo(10f, 100f))
        val group = groups(GlyphPlacement.onPath("a", 0, line, 0f, 0f, paint, metrics)).single()
        assertEquals(90f, (group[2] as Opcode.Rotate).degrees, 0.01f)
    }

    @Test
    fun glyphsRunningOffThePathStopAtItsEnd() {
        val line = listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(4f, 0f))
        val out = GlyphPlacement.onPath("abcdefghij", 0, line, 0f, 0f, paint, metrics)
        // Placement continues but clamps to the tip; nothing is dropped or thrown.
        assertTrue(groups(out).all { (it[1] as Opcode.Translate).dx <= 4.01f })
    }

    @Test
    fun textOnCirclePlacesGlyphsOnTheRadius() {
        val out = GlyphPlacement.onCircle(
            "abcd", stringIndex = 3, centerX = 100f, centerY = 100f, radius = 50f,
            startAngleDegrees = 270f, warpRadiusOffset = 0f, alignment = 1, placement = 0,
            paint = paint, metrics = metrics,
        )
        val groups = groups(out)
        assertEquals(4, groups.size)
        for (group in groups) {
            val t = group[1] as Opcode.Translate
            assertEquals(50f, hypot(t.dx - 100f, t.dy - 100f), 0.01f, "every glyph sits on the radius")
            assertTrue(t.dy < 100f, "270 degrees is the top of the circle")
        }
        // Centered alignment straddles the start angle.
        val first = groups.first()[1] as Opcode.Translate
        val last = groups.last()[1] as Opcode.Translate
        assertTrue(first.dx < 100f && last.dx > 100f)
    }

    @Test
    fun insidePlacementFacesInwardAndRunsTheOtherWay() {
        // Each glyph's own angle on the circle, and the rotation it was given.
        fun placed(placement: Int): List<Pair<Float, Float>> =
            groups(GlyphPlacement.onCircle("ab", 0, 100f, 100f, 50f, 270f, 0f, 1, placement, paint, metrics))
                .map { group ->
                    val t = group[1] as Opcode.Translate
                    val angle = atan2(t.dy - 100f, t.dx - 100f) * 180f / PI.toFloat()
                    angle to (group[2] as Opcode.Rotate).degrees
                }
        // Outside: the baseline runs a quarter turn ahead of the glyph's angle; inside: behind.
        for ((angle, rotation) in placed(0)) assertEquals(0f, angleDifference(rotation, angle + 90f), 1f)
        for ((angle, rotation) in placed(1)) assertEquals(0f, angleDifference(rotation, angle - 90f), 1f)
        // And the run goes the opposite way around the circle.
        val outsideOrder = placed(0).map { it.first }
        val insideOrder = placed(1).map { it.first }
        assertTrue(outsideOrder[1] > outsideOrder[0] && insideOrder[1] < insideOrder[0])
    }

    /** Smallest signed difference between two angles in degrees. */
    private fun angleDifference(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    @Test
    fun warpRadiusOffsetMovesGlyphsOffTheRadius() {
        val out = GlyphPlacement.onCircle("a", 0, 100f, 100f, 50f, 0f, 10f, 0, 0, paint, metrics)
        val t = out[1] as Opcode.Translate
        assertEquals(60f, hypot(t.dx - 100f, t.dy - 100f), 0.01f)
    }

    @Test
    fun emptyTextAndDegeneratePathsProduceNothing() {
        val line = listOf(PathCommand.MoveTo(0f, 0f), PathCommand.LineTo(10f, 0f))
        assertTrue(GlyphPlacement.onPath("", 0, line, 0f, 0f, paint, metrics).isEmpty())
        assertTrue(GlyphPlacement.onPath("a", 0, listOf(PathCommand.MoveTo(0f, 0f)), 0f, 0f, paint, metrics).isEmpty())
        assertTrue(GlyphPlacement.onCircle("a", 0, 0f, 0f, 0f, 0f, 0f, 0, 0, paint, metrics).isEmpty())
    }
}
