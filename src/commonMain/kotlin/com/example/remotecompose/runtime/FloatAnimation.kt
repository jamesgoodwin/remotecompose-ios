package com.example.remotecompose.runtime

/**
 * A cubic Bézier easing on `(0,0) .. (1,1)` with control points `(x1,y1)`, `(x2,y2)`:
 * `CubicEasing` from remote-core, including its preset curves and its bisection lookup.
 */
class CubicEasing(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {

    private fun getX(t: Float): Float {
        val u = 1f - t
        val a = 3f * u * u * t
        val b = 3f * u * t * t
        val c = t * t * t
        return x1 * a + x2 * b + c
    }

    private fun getY(t: Float): Float {
        val u = 1f - t
        val a = 3f * u * u * t
        val b = 3f * u * t * t
        val c = t * t * t
        return y1 * a + y2 * b + c
    }

    /** `CubicEasing.get`: bisect the parameter until `getX` is within 0.01 of [x], then interpolate. */
    fun get(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = 0.5f
        var range = 0.5f
        while (range > 0.01f) {
            val tx = getX(t)
            range *= 0.5f
            t = if (tx < x) t + range else t - range
        }
        val x1 = getX(t - range)
        val x2 = getX(t + range)
        val y1 = getY(t - range)
        val y2 = getY(t + range)
        return (y2 - y1) * (x - x1) / (x2 - x1) + y1
    }

    companion object {
        const val CUBIC_STANDARD = 1
        const val CUBIC_ACCELERATE = 2
        const val CUBIC_DECELERATE = 3
        const val CUBIC_LINEAR = 4
        const val CUBIC_ANTICIPATE = 5
        const val CUBIC_OVERSHOOT = 6
        const val CUBIC_CUSTOM = 11
        const val SPLINE_CUSTOM = 12
        const val EASE_OUT_BOUNCE = 13
        const val EASE_OUT_ELASTIC = 14

        /** Preset control points from `CubicEasing.<clinit>`. */
        fun preset(type: Int): CubicEasing = when (type) {
            CUBIC_ACCELERATE -> CubicEasing(0.4f, 0.05f, 0.8f, 0.7f)
            CUBIC_DECELERATE -> CubicEasing(0f, 0f, 0.2f, 0.95f)
            CUBIC_LINEAR -> CubicEasing(1f, 1f, 0f, 0f)
            CUBIC_ANTICIPATE -> CubicEasing(0.36f, 0f, 0.66f, -0.56f)
            CUBIC_OVERSHOOT -> CubicEasing(0.34f, 1.56f, 0.64f, 1f)
            else -> CubicEasing(0.4f, 0f, 0.2f, 1f) // CUBIC_STANDARD
        }
    }
}

/**
 * `FloatAnimation`: eases a value from [initialValue] to [targetValue] over [duration] seconds.
 *
 * Built from the animation description packed by `FloatAnimation.packToFloatArray`:
 * `[duration]` then, if present, a packed int whose low byte is the easing type, bit 9 says an
 * initial value follows, bit 8 says a wrap value follows, bits 10..11 are the directional snap
 * and bits 16.. the length of the curve spec that follows those values. Bounce, elastic and
 * spline curves are not implemented and fall back to the standard cubic; wrap-around and
 * directional snap are decoded but not applied.
 */
class FloatAnimation(description: FloatArray) {
    val duration: Float
    val type: Int
    var initialValue: Float = Float.NaN
    var targetValue: Float = Float.NaN
    private val easing: CubicEasing

    init {
        duration = if (description.isEmpty()) 1f else description[0]
        var easingType = CubicEasing.CUBIC_STANDARD
        var curve: CubicEasing? = null
        if (description.size > 1) {
            val packed = description[1].toRawBits()
            easingType = packed and 0xFF
            val hasWrap = (packed shr 8) and 1 != 0
            val hasInitial = (packed shr 8) and 2 != 0
            val specLength = (packed shr 16) and 0xFFFF
            var index = 2
            if (hasInitial) initialValue = description[index++]
            if (hasWrap) index++ // wrap value: decoded, not applied
            if (easingType == CubicEasing.CUBIC_CUSTOM && specLength >= 4 && index + 3 < description.size) {
                curve = CubicEasing(description[index], description[index + 1], description[index + 2], description[index + 3])
            }
        }
        type = easingType
        easing = curve ?: CubicEasing.preset(easingType)
    }

    /** `FloatAnimation.get`: the eased value [elapsed] seconds after the animation started. */
    fun get(elapsed: Float): Float {
        if (initialValue.isNaN()) return targetValue
        val fraction = if (duration <= 0f) 1f else elapsed / duration
        return easing.get(fraction) * (targetValue - initialValue) + initialValue
    }
}
