package io.github.jamesgoodwin.remotecompose.runtime

import kotlin.math.hypot

/**
 * `MonotonicCurveFit`: a cubic Hermite spline through the given points whose tangents are
 * limited so it never overshoots between them — the Fritsch–Carlson construction.
 *
 * Each segment is the standard Hermite basis, and the tangents start as the average of the
 * neighbouring slopes. Where a tangent would be steep enough relative to its segment's slope for
 * the curve to leave the interval its ends bracket, the pair is scaled back: `hypot(a, b) > 9`
 * with `a` and `b` the tangents over the slope, scaled by `3 / hypot`.
 *
 * The real class fits any number of dimensions at once; the easing path is the only caller here
 * and always fits one, so this carries a single value per point.
 */
class MonotonicSpline(private val t: DoubleArray, private val y: DoubleArray) {

    private val tangent = DoubleArray(t.size)

    init {
        val n = t.size
        val slope = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val dt = t[i + 1] - t[i]
            slope[i] = (y[i + 1] - y[i]) / dt
            tangent[i] = if (i == 0) slope[0] else (slope[i - 1] + slope[i]) * 0.5
        }
        tangent[n - 1] = slope[n - 2]
        for (i in 0 until n - 1) {
            if (slope[i] == 0.0) {
                // A flat segment has to stay flat, or the curve would bulge off it.
                tangent[i] = 0.0
                tangent[i + 1] = 0.0
            } else {
                val a = tangent[i] / slope[i]
                val b = tangent[i + 1] / slope[i]
                val h = hypot(a, b)
                if (h > 9.0) {
                    val scale = 3.0 / h
                    tangent[i] = scale * a * slope[i]
                    tangent[i + 1] = scale * b * slope[i]
                }
            }
        }
    }

    /**
     * The value at [x]. Past either end the curve is continued along its end tangent, which is
     * what `mExtrapolate` selects and the constructor always sets.
     */
    fun position(x: Double): Double {
        val n = t.size
        if (x <= t[0]) return y[0] + (x - t[0]) * slope(t[0])
        if (x >= t[n - 1]) return y[n - 1] + (x - t[n - 1]) * slope(t[n - 1])
        for (i in 0 until n - 1) {
            if (x == t[i]) return y[i]
            if (x < t[i + 1]) {
                val h = t[i + 1] - t[i]
                val fraction = (x - t[i]) / h
                return interpolate(h, fraction, y[i], y[i + 1], tangent[i], tangent[i + 1])
            }
        }
        return 0.0
    }

    /** `getSlope`: the same, differentiated, with [x] held inside the ends rather than continued. */
    fun slope(xIn: Double): Double {
        val n = t.size
        var x = xIn
        if (x < t[0]) x = t[0] else if (x >= t[n - 1]) x = t[n - 1]
        for (i in 0 until n - 1) {
            if (x <= t[i + 1]) {
                val h = t[i + 1] - t[i]
                val fraction = (x - t[i]) / h
                return diff(h, fraction, y[i], y[i + 1], tangent[i], tangent[i + 1])
            }
        }
        return 0.0
    }

    private companion object {
        /** The cubic Hermite basis over one segment, `x` being the fraction along it. */
        fun interpolate(h: Double, x: Double, y1: Double, y2: Double, m1: Double, m2: Double): Double {
            val x2 = x * x
            val x3 = x2 * x
            return (-2.0 * x3 * y2 + 3.0 * x2 * y2 + 2.0 * x3 * y1 - 3.0 * x2 * y1 + y1 +
                h * m2 * x3 + h * m1 * x3 - h * m2 * x2 - 2.0 * h * m1 * x2 + h * m1 * x)
        }

        fun diff(h: Double, x: Double, y1: Double, y2: Double, m1: Double, m2: Double): Double {
            val x2 = x * x
            return (-6.0 * x2 * y2 + 6.0 * x * y2 + 6.0 * x2 * y1 - 6.0 * x * y1 +
                3.0 * h * m2 * x2 + 3.0 * h * m1 * x2 - 2.0 * h * m2 * x - 4.0 * h * m1 * x + h * m1)
        }
    }
}
