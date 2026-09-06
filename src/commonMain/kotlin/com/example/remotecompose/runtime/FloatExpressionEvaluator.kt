package com.example.remotecompose.runtime

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.withSign

/**
 * The RPN float machine of `AnimatedFloatExpression` (remote-core 1.0.0-alpha18).
 *
 * An expression is a float array. A non-NaN entry is pushed. A NaN entry carries an id in its
 * low 23 bits (`AnimatedFloatExpression.fromNaN`): ids in `OFFSET + 1 .. LAST_OP` are operators
 * applied to the stack; ids with `(id & 0x700000) == 0x200000` are collection references; any
 * other id is a float-pool variable, which the caller resolves *before* evaluation (as
 * `FloatExpression.updateVariables` does) so that [eval] only ever sees literals and operators.
 *
 * Operator stack effects are transcribed from `opEval`; `sp` is the index of the stack top.
 * Collection, random, spline, noise and `CMD*` operators are not supported and make the whole
 * expression evaluate to NaN.
 */
object FloatExpressionEvaluator {

    const val OFFSET = 3211264
    const val LAST_OP = 3211343
    private const val ID_MASK = 0x7FFFFF
    private const val COLLECTION_MASK = 0x700000
    private const val COLLECTION_TAG = 0x200000
    private const val RAD_TO_DEG = 57.29578f
    private const val DEG_TO_RAD = 0.017453292f

    /** `AnimatedFloatExpression.asNan(OFFSET + k)`: the operator with index [k] as a wire float. */
    fun opFloat(k: Int): Float = Float.fromBits((OFFSET + k) or 0xFF800000.toInt())

    fun idOf(value: Float): Int = value.toRawBits() and ID_MASK

    /** True for a NaN entry whose id names an operator. */
    fun isOperator(value: Float): Boolean {
        if (!value.isNaN()) return false
        val id = idOf(value)
        return (id and COLLECTION_MASK) != COLLECTION_TAG && id > OFFSET && id <= LAST_OP
    }

    /** True for a NaN entry that references a collection (`NanMap.isDataVariable`). */
    fun isCollectionReference(value: Float): Boolean =
        value.isNaN() && (idOf(value) and COLLECTION_MASK) == COLLECTION_TAG

    /** True for a NaN entry that is neither an operator nor a collection: a float-pool id. */
    fun isVariable(value: Float): Boolean = value.isNaN() && !isOperator(value) && !isCollectionReference(value)

    /**
     * Evaluates [expression], whose variables have already been replaced by their values, and
     * returns the stack top, or NaN if the expression uses an unsupported operator or is
     * malformed.
     */
    fun eval(expression: FloatArray): Float {
        val stack = FloatArray(expression.size + 4)
        val regs = FloatArray(4)
        var sp = -1
        for (v in expression) {
            if (!v.isNaN()) {
                stack[++sp] = v
                continue
            }
            val id = idOf(v)
            if ((id and COLLECTION_MASK) == COLLECTION_TAG) return Float.NaN
            if (id <= OFFSET || id > LAST_OP) {
                // An unresolved variable reached the evaluator; propagate as NaN.
                stack[++sp] = v
                continue
            }
            sp = applyOperator(id - OFFSET, stack, sp, regs)
            // A store leaves the stack legitimately empty (sp == -1); only overflow or an
            // unsupported operator aborts.
            if (sp == UNSUPPORTED || sp >= stack.size) return Float.NaN
        }
        return if (sp >= 0) stack[sp] else Float.NaN
    }

    private const val UNSUPPORTED = Int.MIN_VALUE

    private fun applyOperator(op: Int, s: FloatArray, sp: Int, regs: FloatArray): Int {
        fun binary(f: (Float, Float) -> Float): Int {
            if (sp < 1) return UNSUPPORTED
            s[sp - 1] = f(s[sp - 1], s[sp])
            return sp - 1
        }
        fun unary(f: (Float) -> Float): Int {
            if (sp < 0) return UNSUPPORTED
            s[sp] = f(s[sp])
            return sp
        }
        return when (op) {
            1 -> binary { a, b -> a + b } // ADD
            2 -> binary { a, b -> a - b } // SUB
            3 -> binary { a, b -> a * b } // MUL
            4 -> binary { a, b -> a / b } // DIV
            5 -> binary { a, b -> a % b } // MOD (Java frem)
            6 -> binary { a, b -> min(a, b) } // MIN
            7 -> binary { a, b -> max(a, b) } // MAX
            8 -> binary { a, b -> a.toDouble().pow(b.toDouble()).toFloat() } // POW
            9 -> unary { sqrt(it) } // SQRT
            10 -> unary { abs(it) } // ABS
            11 -> unary { sign(it) } // SIGN
            12 -> binary { a, b -> a.withSign(b) } // COPY_SIGN
            13 -> unary { exp(it) } // EXP
            14 -> unary { floor(it) } // FLOOR
            15 -> unary { log10(it) } // LOG
            16 -> unary { ln(it) } // LN
            17 -> unary { floor(it + 0.5f) } // ROUND: Math.round rounds half up
            18 -> unary { sin(it) } // SIN
            19 -> unary { cos(it) } // COS
            20 -> unary { tan(it) } // TAN
            21 -> unary { asin(it) } // ASIN
            22 -> unary { acos(it) } // ACOS
            23 -> unary { atan(it) } // ATAN
            24 -> binary { a, b -> atan2(a, b) } // ATAN2
            25 -> { // MAD: s[sp-2] = s[sp] * s[sp-1] + s[sp-2]
                if (sp < 2) return UNSUPPORTED
                s[sp - 2] = s[sp] * s[sp - 1] + s[sp - 2]
                sp - 2
            }
            26 -> { // IFELSE: s[sp-2] = s[sp] > 0 ? s[sp-1] : s[sp-2]
                if (sp < 2) return UNSUPPORTED
                s[sp - 2] = if (s[sp] > 0f) s[sp - 1] else s[sp - 2]
                sp - 2
            }
            27 -> { // CLAMP: s[sp-2] = min(max(s[sp-2], s[sp]), s[sp-1])
                if (sp < 2) return UNSUPPORTED
                s[sp - 2] = min(max(s[sp - 2], s[sp]), s[sp - 1])
                sp - 2
            }
            28 -> unary { it.toDouble().pow(1.0 / 3.0).toFloat() } // CBRT
            29 -> unary { it * RAD_TO_DEG } // DEG
            30 -> unary { it * DEG_TO_RAD } // RAD
            31 -> unary { ceil(it) } // CEIL
            43 -> binary { a, b -> a * a + b * b } // SQUARE_SUM
            44 -> binary { a, b -> if (a > b) 1f else 0f } // STEP
            45 -> unary { it * it } // SQUARE
            46 -> { // DUP
                if (sp < 0) return UNSUPPORTED
                s[sp + 1] = s[sp]
                sp + 1
            }
            47 -> binary { a, b -> hypot(a, b) } // HYPOT
            48 -> { // SWAP
                if (sp < 1) return UNSUPPORTED
                val t = s[sp - 1]
                s[sp - 1] = s[sp]
                s[sp] = t
                sp
            }
            49 -> { // LERP: a + (b - a) * t with a = s[sp-2], b = s[sp-1], t = s[sp]
                if (sp < 2) return UNSUPPORTED
                val a = s[sp - 2]; val b = s[sp - 1]; val t = s[sp]
                s[sp - 2] = a + (b - a) * t
                sp - 2
            }
            50 -> { // SMOOTH_STEP: x = s[sp-2], upper = s[sp-1], lower = s[sp]
                if (sp < 2) return UNSUPPORTED
                val x = s[sp - 2]; val upper = s[sp - 1]; val lower = s[sp]
                s[sp - 2] = when {
                    x < lower -> 0f
                    x > upper -> 1f
                    else -> {
                        val t = (x - lower) / (upper - lower)
                        t * t * (3f - 2f * t)
                    }
                }
                sp - 2
            }
            51 -> unary { (ln(it.toDouble()) / ln(2.0)).toFloat() } // LOG2
            52 -> unary { 1f / it } // INV
            53 -> unary { it - it.toInt().toFloat() } // FRACT
            54 -> { // PINGPONG: period = 2 * s[sp]; r = s[sp-1] % period; r < s[sp] ? r : period - r
                if (sp < 1) return UNSUPPORTED
                val period = s[sp] * 2f
                val r = s[sp - 1] % period
                s[sp - 1] = if (r < s[sp]) r else period - r
                sp - 1
            }
            55 -> sp // NOP
            56, 57, 58, 59 -> { // STORE_R0..R3: pop into register
                if (sp < 0) return UNSUPPORTED
                regs[op - 56] = s[sp]
                sp - 1
            }
            60, 61, 62, 63 -> { // LOAD_R0..R3: push register
                s[sp + 1] = regs[op - 60]
                sp + 1
            }
            73 -> unary { -it } // CHANGE_SIGN
            // A_* collection ops (32..38, 75..79), RAND family (39..42), CMD* (64..67),
            // VAR1..3 (70..72, only meaningful with caller-supplied variables) and CUBIC (74).
            else -> UNSUPPORTED
        }
    }

    @Suppress("unused")
    private const val PI_F = PI.toFloat()
}
