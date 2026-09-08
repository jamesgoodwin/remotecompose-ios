package io.github.jamesgoodwin.remotecompose.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 4x4 matrix in row-major order, a transcription of
 * `androidx.compose.remote.core.operations.utilities.Matrix` (remote-core 1.0.0-alpha18) at the
 * only size the matrix operations use.
 *
 * The builders are not all of one kind, and the difference matters: [rotateX], [rotateY],
 * [rotateZ], [translate] and [projection] multiply this matrix by the new one on the right,
 * [rotateZ] about a pivot and [rotateAroundAxis] multiply on the left, and [scale] multiplies
 * the diagonal in place.
 */
class Matrix4 {

    val values = FloatArray(16)

    operator fun get(row: Int, column: Int): Float = values[row * 4 + column]

    operator fun set(row: Int, column: Int, value: Float) {
        values[row * 4 + column] = value
    }

    fun setIdentity() {
        values.fill(0f)
        for (i in 0 until 4) this[i, i] = 1f
    }

    fun copyFrom(other: Matrix4) = other.values.copyInto(values)

    /**
     * `copyFrom(float[])`: 16 values as they are, or 9 spread over the top three rows. The nine
     * land in slots 0, 1, 3, 4, 5, 6, 8, 9, 10 — so the first row's third value reaches the last
     * column and the second row's does not. Transcribed as the library writes it.
     */
    fun copyFrom(source: FloatArray) {
        when (source.size) {
            16 -> source.copyInto(values)
            9 -> {
                values[0] = source[0]; values[1] = source[1]; values[3] = source[2]
                values[4] = source[3]; values[5] = source[4]; values[6] = source[5]
                values[8] = source[6]; values[9] = source[7]; values[10] = source[8]
                values[11] = 0f
            }
        }
    }

    /** `putValues`: copies this matrix out, as far as [destination] has room. */
    fun putValues(destination: FloatArray) {
        for (i in destination.indices) if (i < values.size) destination[i] = values[i]
    }

    /** `Matrix.multiply(a, b, out)`: `out = this * right`. */
    internal fun multiplyInto(right: Matrix4, out: Matrix4) {
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += this[i, k] * right[k, j]
                out[i, j] = sum
            }
        }
    }

    /** `this = this * right`. */
    private fun postMultiply(right: Matrix4) {
        val out = Matrix4()
        multiplyInto(right, out)
        copyFrom(out)
    }

    /** `this = left * this`, the order the pivot rotations use. */
    private fun preMultiply(left: Matrix4) {
        val out = Matrix4()
        left.multiplyInto(this, out)
        copyFrom(out)
    }

    fun rotateX(degrees: Float) {
        val radians = degrees * DEG_TO_RAD
        val c = cos(radians)
        val s = sin(radians)
        val r = Matrix4()
        r.setIdentity()
        r[1, 1] = c; r[1, 2] = -s
        r[2, 1] = s; r[2, 2] = c
        postMultiply(r)
    }

    fun rotateY(degrees: Float) {
        val radians = degrees * DEG_TO_RAD
        val c = cos(radians)
        val s = sin(radians)
        val r = Matrix4()
        r.setIdentity()
        r[0, 0] = c; r[0, 2] = s
        r[2, 0] = -s; r[2, 2] = c
        postMultiply(r)
    }

    fun rotateZ(degrees: Float) {
        val radians = degrees * DEG_TO_RAD
        val c = cos(radians)
        val s = sin(radians)
        val r = Matrix4()
        r.setIdentity()
        r[0, 0] = c; r[0, 1] = -s
        r[1, 0] = s; r[1, 1] = c
        postMultiply(r)
    }

    fun translate(x: Float, y: Float, z: Float) {
        val t = Matrix4()
        t.setIdentity()
        t[0, 3] = x; t[1, 3] = y; t[2, 3] = z
        postMultiply(t)
    }

    /** `setScale`: multiplies the diagonal, leaving the rest of the matrix alone. */
    fun scale(x: Float, y: Float, z: Float) {
        this[0, 0] = this[0, 0] * x
        this[1, 1] = this[1, 1] * y
        this[2, 2] = this[2, 2] * z
    }

    /** `rotateZ(px, py, degrees)`: a turn about the point ([pivotX], [pivotY]) in the z plane. */
    fun rotateZ(pivotX: Float, pivotY: Float, degrees: Float) {
        val radians = degrees * DEG_TO_RAD
        val c = cos(radians)
        val s = sin(radians)
        val t = 1f - c
        val r = Matrix4()
        r[0, 0] = c; r[0, 1] = -s; r[0, 3] = pivotX * t + pivotY * s
        r[1, 0] = s; r[1, 1] = c; r[1, 3] = pivotY * t - pivotX * s
        r[2, 2] = 1f
        r[3, 3] = 1f
        preMultiply(r)
    }

    /**
     * `rotateAroundAxis`: a turn of [degrees] about the axis ([x], [y], [z]). A zero-length axis
     * leaves the matrix alone.
     */
    fun rotateAroundAxis(x: Float, y: Float, z: Float, degrees: Float) {
        val radians = degrees * DEG_TO_RAD
        val lengthSquared = x * x + y * y + z * z
        if (lengthSquared == 0f) return
        val length = sqrt(lengthSquared)
        val nx = x / length
        val ny = y / length
        val nz = z / length
        val c = cos(radians)
        val s = sin(radians)
        val t = 1f - c
        val r = Matrix4()
        r[0, 0] = c + nx * nx * t
        r[0, 1] = nx * ny * t - nz * s
        r[0, 2] = nx * nz * t + ny * s
        r[1, 0] = ny * nx * t + nz * s
        r[1, 1] = c + ny * ny * t
        r[1, 2] = ny * nz * t - nx * s
        r[2, 0] = nz * nx * t - ny * s
        r[2, 1] = nz * ny * t + nx * s
        r[2, 2] = c + nz * nz * t
        r[3, 3] = 1f
        preMultiply(r)
    }

    /** `projection`: the usual perspective matrix, multiplied onto this one. */
    fun projection(fieldOfViewDegrees: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fieldOfViewDegrees * DEG_TO_RAD / 2f)
        val rangeInverse = 1f / (near - far)
        val p = Matrix4()
        p.values[0] = f / aspect
        p.values[5] = f
        p.values[10] = (far + near) * rangeInverse
        p.values[11] = -1f
        p.values[14] = 2f * far * near * rangeInverse
        postMultiply(p)
    }

    /**
     * `multiply(float[], float[])`: transforms [input] as a point, adding the translation column,
     * and writes as many components as [output] has room for.
     */
    fun transformPoint(input: FloatArray, output: FloatArray) {
        for (j in output.indices) {
            var sum = 0f
            for (k in input.indices) sum += this[j, k] * input[k]
            output[j] = sum + this[j, 3]
        }
    }

    /**
     * `evalPerspective`: transforms [input] as a homogeneous point — a missing fourth component
     * is 1 — and divides the result through by w.
     */
    fun transformPerspective(input: FloatArray, output: FloatArray) {
        val vector = FloatArray(4) { if (it < input.size) input[it] else if (it == 3) 1f else 0f }
        val transformed = FloatArray(4)
        for (j in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += this[j, k] * vector[k]
            transformed[j] = sum
        }
        val w = transformed[3]
        for (j in output.indices) output[j] = if (j < 4) transformed[j] / w else 0f
    }

    override fun toString(): String = (0 until 4).joinToString("\n") { row ->
        (0 until 4).joinToString(" ") { column -> this[row, column].toString() }
    }

    companion object {
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()

        fun identity(): Matrix4 = Matrix4().also { it.setIdentity() }
    }
}
