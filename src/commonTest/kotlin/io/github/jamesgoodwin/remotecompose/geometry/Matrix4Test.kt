package io.github.jamesgoodwin.remotecompose.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Matrix4Test {

    private fun point(matrix: Matrix4, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        matrix.transformPoint(floatArrayOf(x, y), out)
        return out[0] to out[1]
    }

    @Test
    fun identityLeavesAPointWhereItIs() {
        assertEquals(3f to 4f, point(Matrix4.identity(), 3f, 4f))
    }

    @Test
    fun aQuarterTurnAboutZTakesXToY() {
        val matrix = Matrix4.identity()
        matrix.rotateZ(90f)
        val (x, y) = point(matrix, 1f, 0f)
        assertEquals(0f, x, 0.0001f)
        assertEquals(1f, y, 0.0001f)
    }

    @Test
    fun translationLandsInTheLastColumnAndIsAddedByAPointTransform() {
        val matrix = Matrix4.identity()
        matrix.translate(5f, -2f, 0f)
        assertEquals(5f, matrix[0, 3])
        assertEquals(-2f, matrix[1, 3])
        assertEquals(8f to 1f, point(matrix, 3f, 3f))
    }

    @Test
    fun translateThenRotateTurnsAboutTheTranslatedOrigin() {
        val matrix = Matrix4.identity()
        matrix.translate(100f, 0f, 0f)
        matrix.rotateZ(90f)
        val (x, y) = point(matrix, 10f, 0f)
        assertEquals(100f, x, 0.0001f)
        assertEquals(10f, y, 0.0001f)
    }

    @Test
    fun scaleMultipliesTheDiagonalRatherThanReplacingIt() {
        val matrix = Matrix4.identity()
        matrix.scale(2f, 3f, 1f)
        matrix.scale(2f, 1f, 1f)
        assertEquals(4f, matrix[0, 0])
        assertEquals(3f, matrix[1, 1])
    }

    @Test
    fun rotatingAboutAPivotLeavesThePivotAlone() {
        val matrix = Matrix4.identity()
        matrix.rotateZ(pivotX = 50f, pivotY = 20f, degrees = 37f)
        val (x, y) = point(matrix, 50f, 20f)
        assertEquals(50f, x, 0.001f)
        assertEquals(20f, y, 0.001f)
    }

    @Test
    fun rotatingAboutTheZAxisMatchesTheZRotation() {
        val axis = Matrix4.identity()
        axis.rotateAroundAxis(0f, 0f, 1f, 90f)
        val (x, y) = point(axis, 1f, 0f)
        assertEquals(0f, x, 0.0001f)
        assertEquals(1f, y, 0.0001f)
    }

    @Test
    fun aZeroLengthAxisLeavesTheMatrixAlone() {
        val matrix = Matrix4.identity()
        matrix.rotateAroundAxis(0f, 0f, 0f, 45f)
        assertEquals(1f to 2f, point(matrix, 1f, 2f))
    }

    @Test
    fun aPerspectiveTransformDividesThroughByW() {
        val matrix = Matrix4.identity()
        matrix.projection(90f, 1f, 1f, 100f)
        val near = FloatArray(3)
        val far = FloatArray(3)
        matrix.transformPerspective(floatArrayOf(10f, 0f, -10f), near)
        matrix.transformPerspective(floatArrayOf(10f, 0f, -50f), far)
        // The same x is smaller the further away it sits.
        assertTrue(far[0] < near[0], "${far[0]} is nearer the centre than ${near[0]}")
    }

    @Test
    fun nineValuesAreSpreadTheWayTheLibrarySpreadsThem() {
        val matrix = Matrix4()
        matrix.copyFrom(floatArrayOf(1f, 0f, 7f, 0f, 1f, 8f, 0f, 0f, 1f))
        // The third value of each 3x3 row goes to slots 3, 6 and 10, so the first row's
        // translation reaches the last column but the second row's stops one short. That is what
        // the real copyFrom does, and this test records it rather than correcting it.
        assertEquals(7f, matrix[0, 3])
        assertEquals(8f, matrix[1, 2])
        assertEquals(0f, matrix[1, 3])
        assertEquals(9f to 4f, point(matrix, 2f, 4f))
    }
}

class MatrixExpressionEvaluatorTest {

    private fun op(k: Int) = MatrixExpressionEvaluator.opFloat(k)

    private fun point(matrix: Matrix4?, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        matrix!!.transformPoint(floatArrayOf(x, y), out)
        return out[0] to out[1]
    }

    @Test
    fun anEmptyExpressionIsTheIdentity() {
        assertEquals(1f to 2f, point(MatrixExpressionEvaluator.eval(floatArrayOf()), 1f, 2f))
    }

    @Test
    fun operatorsReadTheOperandsImmediatelyBeforeThem() {
        // translate(150, 40) then turn 90 degrees: (10, 0) lands 10 above the translated origin.
        val matrix = MatrixExpressionEvaluator.eval(floatArrayOf(150f, 40f, op(8), 90f, op(4)))
        val (x, y) = point(matrix, 10f, 0f)
        assertEquals(150f, x, 0.001f)
        assertEquals(50f, y, 0.001f)
    }

    @Test
    fun theSingleAxisTranslationsPickTheirOwnAxis() {
        assertEquals(15f to 2f, point(MatrixExpressionEvaluator.eval(floatArrayOf(5f, op(5))), 10f, 2f))
        assertEquals(10f to 7f, point(MatrixExpressionEvaluator.eval(floatArrayOf(5f, op(6))), 10f, 2f))
    }

    @Test
    fun mulFoldsThePushedMatrixIntoTheOneBelowIt() {
        // Push a second matrix, turn it, then multiply it into the first.
        val product = MatrixExpressionEvaluator.eval(
            floatArrayOf(10f, 20f, op(8), op(1), 90f, op(4), op(15)),
        )
        val (x, y) = point(product, 1f, 0f)
        assertEquals(10f, x, 0.001f)
        assertEquals(21f, y, 0.001f)
    }

    @Test
    fun aMatrixPushedAndNeverMultipliedIsDiscarded() {
        // IDENTITY pushes; the expression's value is still the bottom matrix.
        val matrix = MatrixExpressionEvaluator.eval(floatArrayOf(op(1), 90f, op(4)))
        assertEquals(1f to 2f, point(matrix, 1f, 2f))
    }

    @Test
    fun multiplyingWithNothingPushedIsRejected() {
        assertNull(MatrixExpressionEvaluator.eval(floatArrayOf(op(15))))
    }

    @Test
    fun onlyMatrixOperatorIdsCount() {
        assertTrue(MatrixExpressionEvaluator.isOperator(op(4)))
        assertTrue(!MatrixExpressionEvaluator.isOperator(1f))
        // A float-expression operator is not a matrix operator.
        assertTrue(!MatrixExpressionEvaluator.isOperator(Float.fromBits(3211265 or 0xFF800000.toInt())))
    }
}
