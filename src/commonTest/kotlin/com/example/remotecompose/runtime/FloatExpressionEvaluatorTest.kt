package com.example.remotecompose.runtime

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Operator semantics as transcribed from `AnimatedFloatExpression.opEval`. */
class FloatExpressionEvaluatorTest {

    private fun op(name: String): Float = FloatExpressionEvaluator.opFloat(OPS.getValue(name))
    private fun eval(vararg items: Any): Float = FloatExpressionEvaluator.eval(
        FloatArray(items.size) { i -> when (val v = items[i]) { is Float -> v; is Int -> v.toFloat(); is String -> op(v); else -> error(v) } },
    )

    @Test
    fun arithmeticIsPostfix() {
        assertEquals(7f, eval(3, 4, "ADD"))
        assertEquals(-1f, eval(3, 4, "SUB"))
        assertEquals(12f, eval(3, 4, "MUL"))
        assertEquals(0.75f, eval(3, 4, "DIV"))
        assertEquals(1f, eval(7, 3, "MOD"))
        assertEquals(8f, eval(2, 3, "POW"))
        assertEquals(5f, eval(3, 4, "HYPOT"))
        assertEquals(25f, eval(3, 4, "SQUARE_SUM"))
    }

    @Test
    fun unaryFunctions() {
        assertEquals(3f, eval(9, "SQRT"))
        assertEquals(2f, eval(-2, "ABS"))
        assertEquals(-1f, eval(-2, "SIGN"))
        assertEquals(2f, eval(2.7f, "FLOOR"))
        assertEquals(3f, eval(2.2f, "CEIL"))
        assertEquals(3f, eval(2.5f, "ROUND"))
        assertEquals(0f, eval(0, "SIN"))
        assertEquals(1f, eval(0, "COS"))
        assertEquals(180f, eval(PI.toFloat(), "DEG"), 0.001f)
        assertEquals(PI.toFloat(), eval(180, "RAD"), 0.001f)
        assertEquals(2f, eval(100, "LOG"))
        assertEquals(3f, eval(8, "LOG2"), 0.0001f)
        assertEquals(0.25f, eval(4, "INV"))
        assertEquals(0.5f, eval(2.5f, "FRACT"))
        assertEquals(-5f, eval(5, "CHANGE_SIGN"))
        assertEquals(2f, eval(8, "CBRT"), 0.0001f)
    }

    @Test
    fun ternaryOperandOrder() {
        // MAD: a b c -> c * b + a
        assertEquals(14f, eval(2, 3, 4, "MAD"))
        // IFELSE: a b cond -> cond > 0 ? b : a
        assertEquals(20f, eval(10, 20, 1, "IFELSE"))
        assertEquals(10f, eval(10, 20, 0, "IFELSE"))
        // CLAMP: value hi lo -> min(max(value, lo), hi)
        assertEquals(5f, eval(5, 10, 0, "CLAMP"))
        assertEquals(10f, eval(15, 10, 0, "CLAMP"))
        assertEquals(0f, eval(-3, 10, 0, "CLAMP"))
        // LERP: a b t
        assertEquals(15f, eval(10, 20, 0.5f, "LERP"))
        // SMOOTH_STEP: x upper lower
        assertEquals(0.5f, eval(5, 10, 0, "SMOOTH_STEP"))
        assertEquals(1f, eval(12, 10, 0, "SMOOTH_STEP"))
    }

    @Test
    fun stackManipulationAndRegisters() {
        assertEquals(9f, eval(3, "DUP", "MUL"))
        assertEquals(1f, eval(3, 4, "SWAP", "SUB"))
        assertEquals(1f, eval(2, 1, "STEP"))
        assertEquals(0f, eval(1, 2, "STEP"))
        assertEquals(9f, eval(3, "STORE_R0", "LOAD_R0", "LOAD_R0", "MUL"))
        assertEquals(7f, eval(3, "NOP", 4, "ADD"))
        assertEquals(1f, eval(3, 2, "PINGPONG"))
        assertEquals(0.5f, eval(1.5f, 1, "PINGPONG"))
    }

    @Test
    fun unsupportedOperatorsYieldNaN() {
        assertTrue(eval(1, "RAND").isNaN())
        assertTrue(eval(1, 2, "A_SUM").isNaN())
    }

    @Test
    fun classification() {
        assertTrue(FloatExpressionEvaluator.isOperator(op("ADD")))
        val variable = Float.fromBits(42 or 0xFF800000.toInt())
        assertTrue(FloatExpressionEvaluator.isVariable(variable))
        assertTrue(!FloatExpressionEvaluator.isOperator(variable))
        assertTrue(!FloatExpressionEvaluator.isVariable(1f))
    }

    @Test
    fun callerVariablesArePushedFromTheirSlots() {
        val vars = floatArrayOf(2f, 3f, 4f)
        fun eval(vararg items: Any) = FloatExpressionEvaluator.eval(
            FloatArray(items.size) { i -> when (val v = items[i]) { is Float -> v; is Int -> v.toFloat(); is String -> op(v); else -> error(v) } },
            vars,
        )
        assertEquals(2f, eval("VAR1"))
        assertEquals(3f, eval("VAR2"))
        assertEquals(4f, eval("VAR3"))
        assertEquals(14f, eval("VAR1", "VAR2", "VAR3", "MUL", "ADD"))
        // Without the caller supplying them the expression has no value at all.
        assertTrue(FloatExpressionEvaluator.eval(floatArrayOf(op("VAR1"))).isNaN())
    }

    @Test
    fun cubicEasesTheTopOfTheStackThroughFourControls() {
        // The linear curve maps t to itself; the ease-in-out curve pulls the middle towards 0.5.
        assertEquals(0.25f, eval(0.25f, 0.25f, 0.75f, 0.75f, 0.25f, "CUBIC"), 0.02f)
        assertEquals(0.5f, eval(0.4f, 0f, 0.6f, 1f, 0.5f, "CUBIC"), 0.02f)
        assertEquals(0f, eval(0.4f, 0f, 0.6f, 1f, 0f, "CUBIC"), 0.001f)
        assertEquals(1f, eval(0.4f, 0f, 0.6f, 1f, 1f, "CUBIC"), 0.001f)
    }

    @Test
    fun theCollectionOperatorsReadAList() {
        // A collection reference is a NaN id with 0x200000 set; it is an operand, not a value.
        val list = Float.fromBits(0x200005 or 0xFF800000.toInt())
        val collections = FloatCollections { id -> if (id == 0x200005) floatArrayOf(2f, 8f, 5f) else null }
        fun eval(vararg items: Any) = FloatExpressionEvaluator.eval(
            FloatArray(items.size) { i ->
                when (val v = items[i]) {
                    is Float -> v; is Int -> v.toFloat(); is String -> op(v); else -> error(v)
                }
            },
            FloatArray(0),
            collections,
        )
        assertEquals(8f, eval(list, 1, "A_DEREF"))
        assertEquals(8f, eval(list, "A_MAX"))
        assertEquals(2f, eval(list, "A_MIN"))
        assertEquals(15f, eval(list, "A_SUM"))
        assertEquals(5f, eval(list, "A_AVG"))
        assertEquals(3f, eval(list, "A_LEN"))
        // The result is an ordinary value, so it goes on with the rest of the expression.
        assertEquals(30f, eval(list, "A_SUM", 2, "MUL"))
    }

    @Test
    fun aMissingOrEmptyCollectionReadsAsZero() {
        val absent = Float.fromBits(0x200009 or 0xFF800000.toInt())
        val none = FloatCollections { null }
        fun eval(vararg items: Any) = FloatExpressionEvaluator.eval(
            FloatArray(items.size) { i ->
                when (val v = items[i]) {
                    is Float -> v; is Int -> v.toFloat(); is String -> op(v); else -> error(v)
                }
            },
            FloatArray(0),
            none,
        )
        assertEquals(0f, eval(absent, "A_MAX"))
        assertEquals(0f, eval(absent, "A_LEN"))
        assertEquals(0f, eval(absent, 0, "A_DEREF"))
    }

    @Test
    fun aCollectionOperatorWithNowhereToReadFromGivesNothing() {
        val list = Float.fromBits(0x200005 or 0xFF800000.toInt())
        assertTrue(FloatExpressionEvaluator.eval(floatArrayOf(list, op("A_SUM"))).isNaN())
    }

    companion object {
        val OPS = mapOf(
            "ADD" to 1, "SUB" to 2, "MUL" to 3, "DIV" to 4, "MOD" to 5, "MIN" to 6, "MAX" to 7, "POW" to 8,
            "SQRT" to 9, "ABS" to 10, "SIGN" to 11, "COPY_SIGN" to 12, "EXP" to 13, "FLOOR" to 14, "LOG" to 15,
            "LN" to 16, "ROUND" to 17, "SIN" to 18, "COS" to 19, "TAN" to 20, "ASIN" to 21, "ACOS" to 22,
            "ATAN" to 23, "ATAN2" to 24, "MAD" to 25, "IFELSE" to 26, "CLAMP" to 27, "CBRT" to 28, "DEG" to 29,
            "RAD" to 30, "CEIL" to 31, "A_DEREF" to 32, "A_MAX" to 33, "A_MIN" to 34, "A_SUM" to 35,
            "A_AVG" to 36, "A_LEN" to 37, "RAND" to 39, "SQUARE_SUM" to 43, "STEP" to 44,
            "SQUARE" to 45, "DUP" to 46, "HYPOT" to 47, "SWAP" to 48, "LERP" to 49, "SMOOTH_STEP" to 50,
            "LOG2" to 51, "INV" to 52, "FRACT" to 53, "PINGPONG" to 54, "NOP" to 55, "STORE_R0" to 56,
            "LOAD_R0" to 60, "VAR1" to 70, "VAR2" to 71, "VAR3" to 72, "CHANGE_SIGN" to 73, "CUBIC" to 74,
        )
    }
}
