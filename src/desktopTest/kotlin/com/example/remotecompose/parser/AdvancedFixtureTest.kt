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
        assertEquals(2, rects.size)
        assertEquals(132f, rects[0].right, 0.001f)
        assertEquals(72f, rects[1].right, 0.001f)
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
