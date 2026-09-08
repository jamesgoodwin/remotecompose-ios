package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import io.github.jamesgoodwin.remotecompose.text.EstimatedTextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ConditionalOperations` gates the block that follows it, on hand-built operation lists so each
 * comparison type can be checked on its own.
 */
class ConditionalOperationsTest {

    private fun nanId(id: Int): Float = Float.fromBits(id or 0xFF800000.toInt())

    /**
     * A document that sets a float to [a] and draws a rect only when `a <type> b` holds. Uses id
     * 100 because ids 1..35 are the reserved system variables (time, size, density).
     */
    private fun drawsRect(type: Int, a: Float, b: Float): Boolean {
        val operations = listOf(
            Operation.FloatConstant(100, a),
            Operation.ConditionalOperations(type.toByte(), nanId(100), b),
            Operation.DrawRect(0f, 0f, 10f, 10f),
            Operation.ContainerEnd,
        )
        val context = RemoteContext()
        val opcodes = RemoteComposeParser.build(operations, context, EstimatedTextMetrics)
        return opcodes.any { it is Opcode.DrawRect }
    }

    @Test
    fun comparisonsGateTheBlock() {
        assertTrue(drawsRect(type = 0, a = 5f, b = 5f), "EQ holds")
        assertTrue(!drawsRect(type = 0, a = 5f, b = 6f), "EQ fails")
        assertTrue(drawsRect(type = 1, a = 5f, b = 6f), "NEQ holds")
        assertTrue(drawsRect(type = 2, a = 5f, b = 6f), "LT holds")
        assertTrue(!drawsRect(type = 2, a = 6f, b = 5f), "LT fails")
        assertTrue(drawsRect(type = 3, a = 5f, b = 5f), "LTE holds on equal")
        assertTrue(drawsRect(type = 4, a = 6f, b = 5f), "GT holds")
        assertTrue(!drawsRect(type = 4, a = 5f, b = 6f), "GT fails")
        assertTrue(drawsRect(type = 5, a = 5f, b = 5f), "GTE holds on equal")
    }

    @Test
    fun changedFiresOnlyAfterAnOperandMoves() {
        val expression = Operation.ConditionalOperations(6, nanId(100), 0f) // TYPE_CHANGED
        val operations = listOf(expression, Operation.DrawRect(0f, 0f, 10f, 10f), Operation.ContainerEnd)
        val context = RemoteContext()
        context.loadFloat(100, 3f)
        // First evaluation has nothing to compare against, so the block does not run.
        assertTrue(RemoteComposeParser.build(operations, context, EstimatedTextMetrics).none { it is Opcode.DrawRect })
        // Same value again: still unchanged.
        assertTrue(RemoteComposeParser.build(operations, context, EstimatedTextMetrics).none { it is Opcode.DrawRect })
        context.loadFloat(100, 4f)
        assertTrue(RemoteComposeParser.build(operations, context, EstimatedTextMetrics).any { it is Opcode.DrawRect })
    }

    @Test
    fun textMeasureStoresTheRequestedComponent() {
        val context = RemoteContext()
        context.texts[9] = "abcd"
        // MEASURE_WIDTH(0) and MEASURE_HEIGHT(1) of "abcd" at the default 12px text size.
        RemoteComposeParser.build(listOf(Operation.TextMeasure(50, 9, 0)), context, EstimatedTextMetrics)
        RemoteComposeParser.build(listOf(Operation.TextMeasure(51, 9, 1)), context, EstimatedTextMetrics)
        assertEquals(4 * 12f * 0.55f, context.floats[50]!!, 0.001f)
        assertEquals(12f * 1.2f, context.floats[51]!!, 0.001f)
    }
}
