package io.github.jamesgoodwin.remotecompose.geometry

import io.github.jamesgoodwin.remotecompose.model.PathCommand
import io.github.jamesgoodwin.remotecompose.runtime.FloatExpressionEvaluator
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathGeneratorTest {

    /** The sample position the generator writes into the first caller variable slot. */
    private val var1 = FloatExpressionEvaluator.opFloat(70)
    private val add = FloatExpressionEvaluator.opFloat(1)
    private val mul = FloatExpressionEvaluator.opFloat(3)

    private fun ends(commands: List<PathCommand>): List<Pair<Float, Float>> = commands.mapNotNull {
        when (it) {
            is PathCommand.MoveTo -> it.x to it.y
            is PathCommand.CubicTo -> it.x3 to it.y3
            else -> null
        }
    }

    @Test
    fun linearSamplesTheRangeInclusiveOfBothEnds() {
        val path = PathGenerator.generate(
            expressionX = floatArrayOf(var1),
            expressionY = floatArrayOf(5f),
            min = 0f, max = 3f, count = 4, kind = PathGenerator.Kind.LINEAR, loop = false,
        )
        // count - 1 steps when the path is open, so the last sample lands exactly on max.
        assertEquals(listOf(0f to 5f, 1f to 5f, 2f to 5f, 3f to 5f), ends(path))
        assertTrue(path.none { it == PathCommand.Close })
    }

    @Test
    fun aLinearSegmentIsACubicWithItsControlsOnTheEnds() {
        val path = PathGenerator.generate(
            floatArrayOf(var1), floatArrayOf(0f), 0f, 1f, 2, PathGenerator.Kind.LINEAR, loop = false,
        )
        val segment = path[1] as PathCommand.CubicTo
        assertEquals(0f, segment.x1, 0.001f)
        assertEquals(1f, segment.x2, 0.001f)
        assertEquals(1f, segment.x3, 0.001f)
    }

    @Test
    fun loopingDividesTheRangeByTheSampleCountAndCloses() {
        val path = PathGenerator.generate(
            floatArrayOf(var1), floatArrayOf(0f), 0f, 4f, 4, PathGenerator.Kind.LINEAR, loop = true,
        )
        // Step is range / count, so the samples stop one step short of max and wrap.
        assertEquals(listOf(0f, 1f, 2f, 3f, 0f), ends(path).map { it.first })
        assertEquals(PathCommand.Close, path.last())
    }

    @Test
    fun splineControlPointsLeaveTheStraightLineOnlyWhereThePointsBend() {
        val straight = PathGenerator.generate(
            floatArrayOf(var1), floatArrayOf(var1), 0f, 3f, 4, PathGenerator.Kind.SPLINE, loop = false,
        )
        // Points on y = x: every control point stays on the same line.
        for (command in straight.filterIsInstance<PathCommand.CubicTo>()) {
            assertEquals(command.x1, command.y1, 0.001f)
            assertEquals(command.x2, command.y2, 0.001f)
        }
    }

    @Test
    fun monotonicNeverOvershootsAStepWhereASplineDoes() {
        // A step: three flat samples, then a jump. y = 0, 0, 0, 10, 10, 10.
        val stepX = floatArrayOf(var1)
        val stepY = floatArrayOf(var1, 2.5f, FloatExpressionEvaluator.opFloat(44), 10f, mul) // 10 * step(t, 2.5)
        val spline = PathGenerator.generate(stepX, stepY, 0f, 5f, 6, PathGenerator.Kind.SPLINE, false)
        val monotonic = PathGenerator.generate(stepX, stepY, 0f, 5f, 6, PathGenerator.Kind.MONOTONIC, false)
        fun controlRange(path: List<PathCommand>) =
            path.filterIsInstance<PathCommand.CubicTo>().flatMap { listOf(it.y1, it.y2) }
        assertTrue(controlRange(spline).any { it < -0.001f || it > 10.001f }, "the spline overshoots the step")
        assertTrue(controlRange(monotonic).all { it >= -0.001f && it <= 10.001f }, "the monotonic fit does not")
    }

    @Test
    fun polarSamplesLandOnTheRadiusAroundTheCentre() {
        val circle = PathGenerator.generatePolar(
            expressionRadius = floatArrayOf(10f),
            center = floatArrayOf(50f, 50f),
            min = 0f, max = 6.2831855f, count = 8, kind = PathGenerator.Kind.LINEAR, loop = true,
        )
        val points = ends(circle)
        assertEquals(9, points.size) // 8 samples, and the closing return to the first
        for ((x, y) in points) assertEquals(10f, hypot(x - 50f, y - 50f), 0.001f)
    }

    @Test
    fun polarRadiusVariesWithTheAngle() {
        // r = 10 + t, so the last sample is further out than the first.
        val spiral = PathGenerator.generatePolar(
            floatArrayOf(var1, 10f, add), floatArrayOf(0f, 0f),
            0f, 6f, 7, PathGenerator.Kind.LINEAR, loop = false,
        )
        val points = ends(spiral)
        assertEquals(10f, hypot(points.first().first, points.first().second), 0.001f)
        assertEquals(16f, hypot(points.last().first, points.last().second), 0.001f)
    }

    @Test
    fun anUnsupportedOperatorMakesTheWholePathNonFinite() {
        val random = FloatExpressionEvaluator.opFloat(39) // RAND, which this evaluator does not run
        val path = PathGenerator.generate(
            floatArrayOf(var1, 2f, mul), floatArrayOf(random), 0f, 1f, 3, PathGenerator.Kind.LINEAR, false,
        )
        assertTrue(path.isNotEmpty())
        assertTrue(!PathGenerator.isFinite(path))
    }
}
