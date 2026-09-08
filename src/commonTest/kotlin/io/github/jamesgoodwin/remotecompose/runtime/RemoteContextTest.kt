package io.github.jamesgoodwin.remotecompose.runtime

import io.github.jamesgoodwin.remotecompose.parser.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteContextTest {

    private fun nanId(id: Int): Float = Float.fromBits(id or 0xFF800000.toInt())
    private val add = FloatExpressionEvaluator.opFloat(1)
    private val mul = FloatExpressionEvaluator.opFloat(3)

    @Test
    fun animationTimeCountsFromFirstFrame() {
        val ctx = RemoteContext()
        ctx.beginFrame(5_000L)
        assertEquals(0f, ctx.animationTime)
        ctx.beginFrame(6_500L)
        assertEquals(1.5f, ctx.animationTime)
        assertEquals(1.5f, ctx.deltaTime)
        assertEquals(1.5f, ctx.getFloat(RemoteContext.ID_CONTINUOUS_SEC))
        assertTrue(ctx.needsRepaint, "reading a time variable requests another frame")
    }

    @Test
    fun resolveFloatReadsPoolAndSystemVariables() {
        val ctx = RemoteContext()
        ctx.beginFrame(0L)
        ctx.windowWidth = 320f
        ctx.loadFloat(100, 7f)
        assertEquals(7f, ctx.resolveFloat(nanId(100)))
        assertEquals(320f, ctx.resolveFloat(nanId(RemoteContext.ID_WINDOW_WIDTH)))
        assertEquals(2.5f, ctx.resolveFloat(2.5f))
        assertTrue(ctx.resolveFloat(nanId(999)).isNaN())
        assertFalse(ctx.needsRepaint, "no time variable was read")
    }

    @Test
    fun floatExpressionEvaluatesAgainstTime() {
        val ctx = RemoteContext()
        // id 50 = t * 10 + 3
        val op = Operation.FloatExpression(50, floatArrayOf(nanId(RemoteContext.ID_CONTINUOUS_SEC), 10f, mul, 3f, add), null)
        ctx.beginFrame(0L)
        ctx.applyFloatExpression(op)
        assertEquals(3f, ctx.getFloat(50))
        ctx.beginFrame(2_000L)
        ctx.applyFloatExpression(op)
        assertEquals(23f, ctx.getFloat(50))
    }

    @Test
    fun animatedExpressionEasesTowardNewTarget() {
        val ctx = RemoteContext()
        // Expression is a plain pool reference; the pool value jumps and the output eases.
        val description = floatArrayOf(1f, Float.fromBits(CubicEasing.CUBIC_LINEAR)) // 1s, linear
        val op = Operation.FloatExpression(60, floatArrayOf(nanId(70)), description)
        ctx.loadFloat(70, 0f)
        ctx.beginFrame(0L)
        ctx.applyFloatExpression(op)
        assertEquals(0f, ctx.getFloat(60))
        ctx.loadFloat(70, 100f)
        ctx.beginFrame(1_000L) // target changes now
        ctx.applyFloatExpression(op)
        assertEquals(0f, ctx.getFloat(60), 0.01f)
        assertTrue(ctx.needsRepaint)
        ctx.beginFrame(1_500L)
        ctx.applyFloatExpression(op)
        assertEquals(50f, ctx.getFloat(60), 1f)
        ctx.beginFrame(2_500L)
        ctx.applyFloatExpression(op)
        assertEquals(100f, ctx.getFloat(60), 0.01f)
    }

    @Test
    fun cubicPresetsHitTheirEndpoints() {
        for (type in 1..6) {
            val easing = CubicEasing.preset(type)
            assertEquals(0f, easing.get(0f))
            assertEquals(1f, easing.get(1f))
        }
        assertEquals(0.5f, CubicEasing.preset(CubicEasing.CUBIC_LINEAR).get(0.5f), 0.02f)
        assertTrue(CubicEasing.preset(CubicEasing.CUBIC_ACCELERATE).get(0.5f) < 0.5f)
        assertTrue(CubicEasing.preset(CubicEasing.CUBIC_DECELERATE).get(0.5f) > 0.5f)
    }
}
