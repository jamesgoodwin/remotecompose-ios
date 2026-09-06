package com.example.remotecompose.geometry

/**
 * The matrix machine of `androidx.compose.remote.core.operations.utilities.MatrixOperations`
 * (remote-core 1.0.0-alpha18).
 *
 * An expression is a float array read left to right. A plain float is an operand, waiting to be
 * picked up; a NaN entry whose id lands in `OFFSET + 1 .. LAST_OP` is an operator that reads the
 * operands immediately before it and applies itself to the matrix on top of a small matrix
 * stack. `IDENTITY` pushes a new matrix, `MUL` multiplies the top two into one, and the
 * expression's value is the bottom matrix.
 */
object MatrixExpressionEvaluator {

    const val OFFSET = 3276800
    const val LAST_OP = 3276854
    private const val ID_MASK = 0x7FFFFF
    private const val COLLECTION_MASK = 0x700000
    private const val COLLECTION_TAG = 0x200000
    private const val STACK_DEPTH = 8

    /** `MatrixOperations.asNan(OFFSET + k)`: the operator with index [k] as a wire float. */
    fun opFloat(k: Int): Float = Float.fromBits((OFFSET + k) or 0xFF800000.toInt())

    /** `isOperator`: a NaN entry naming one of the matrix operators. */
    fun isOperator(value: Float): Boolean {
        if (!value.isNaN()) return false
        val id = value.toRawBits() and ID_MASK
        if ((id and COLLECTION_MASK) == COLLECTION_TAG) return false
        return id > OFFSET && id <= LAST_OP
    }

    /**
     * Evaluates [expression], whose pool variables the caller has already resolved, and returns
     * the resulting matrix, or null if the expression over- or underflows the matrix stack.
     */
    fun eval(expression: FloatArray): Matrix4? {
        val matrices = Array(STACK_DEPTH) { Matrix4() }
        var top = 0
        matrices[0].setIdentity()
        for (i in expression.indices) {
            val value = expression[i]
            if (!value.isNaN()) continue
            val id = value.toRawBits() and ID_MASK
            if (id <= OFFSET || id > LAST_OP) continue
            top = apply(id - OFFSET, i, expression, matrices, top) ?: return null
        }
        return matrices[0]
    }

    /** One operator: `opEval`. Returns the new stack top, or null on an over- or underflow. */
    private fun apply(op: Int, at: Int, stack: FloatArray, matrices: Array<Matrix4>, top: Int): Int? {
        fun operand(back: Int): Float = stack.getOrElse(at - back) { Float.NaN }
        val matrix = matrices[top]
        when (op) {
            1 -> { // IDENTITY: push a new matrix
                if (top + 1 >= matrices.size) return null
                matrices[top + 1].setIdentity()
                return top + 1
            }
            2 -> matrix.rotateX(operand(1))
            3 -> matrix.rotateY(operand(1))
            4 -> matrix.rotateZ(operand(1))
            5 -> matrix.translate(operand(1), 0f, 0f)
            6 -> matrix.translate(0f, operand(1), 0f)
            7 -> matrix.translate(0f, 0f, operand(1))
            8 -> matrix.translate(operand(2), operand(1), 0f)
            9 -> matrix.translate(operand(3), operand(2), operand(1))
            10 -> matrix.scale(operand(1), 1f, 1f)
            11 -> matrix.scale(1f, operand(1), 1f)
            12 -> matrix.scale(1f, 1f, operand(1))
            // As in the real operation, the two-argument scale zeroes the z axis.
            13 -> matrix.scale(operand(2), operand(1), 0f)
            14 -> matrix.scale(operand(3), operand(2), operand(1))
            15 -> { // MUL: the matrix below the top becomes their product
                if (top < 1) return null
                val product = Matrix4()
                matrices[top - 1].multiplyInto(matrices[top], product)
                matrices[top - 1].copyFrom(product)
                return top - 1
            }
            16 -> matrix.rotateZ(operand(2), operand(1), operand(3))
            17 -> matrix.rotateAroundAxis(operand(3), operand(2), operand(1), operand(4))
            18 -> matrix.projection(operand(4), operand(3), operand(2), operand(1))
        }
        return top
    }
}
