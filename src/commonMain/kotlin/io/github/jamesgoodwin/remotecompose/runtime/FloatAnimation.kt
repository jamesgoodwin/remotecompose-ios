package io.github.jamesgoodwin.remotecompose.runtime

import kotlin.math.pow
import kotlin.math.sin

/** `Easing`: a curve from 0 to 1, which is all any of its subclasses offer the callers here. */
interface Easing {
    fun get(fraction: Float): Float
}

/**
 * `BounceCurve`: the value drops in and settles over four shortening bounces.
 */
class BounceCurve : Easing {
    override fun get(fraction: Float): Float {
        var t = fraction
        if (t < 0f) return 0f
        if (t < 0.36363637f) return 0.73333335f * (7.5625f * t * t + t)
        if (t < 0.72727275f) {
            t -= 0.54545456f
            return 7.5625f * t * t + 0.75f
        }
        if (t < 0.90909090909f) {
            t -= 0.8181818f
            return 7.5625f * t * t + 0.9375f
        }
        if (t <= 1f) {
            t -= 0.95454544f
            return 7.5625f * t * t + 0.984375f
        }
        return 1f
    }
}

/**
 * `ElasticOutCurve`: overshoots and oscillates into place, the oscillation decaying by `2^-10t`.
 * `2.0943952` is the `C4` the real class names, which is two thirds of pi.
 */
class ElasticOutCurve : Easing {
    override fun get(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val decay = 2.0.pow((-10f * fraction).toDouble())
        return (decay * sin(((fraction * 10f - 0.75f) * 2.0943952f).toDouble()) + 1.0).toFloat()
    }
}

/**
 * `StepCurve`: a curve through evenly spaced values rather than one named by control points.
 *
 * `genSpline` lays the values out at `i / (length - 1)` and then repeats them a period below and
 * a period above, each copy offset by one in value, so that the spline running through the middle
 * copy meets its neighbours smoothly instead of flattening at the ends.
 */
class StepCurve(values: FloatArray, offset: Int, length: Int) : Easing {

    private val spline: MonotonicSpline

    init {
        val points = length * 3 - 2
        val count = length - 1
        val step = 1.0 / count
        val y = DoubleArray(points)
        val t = DoubleArray(points)
        for (i in 0 until length) {
            val v = values[i + offset].toDouble()
            y[i + count] = v
            t[i + count] = i * step
            if (i > 0) {
                y[i + count * 2] = v + 1.0
                t[i + count * 2] = i * step + 1.0
                y[i - 1] = v - 1.0
                t[i - 1] = i * step - 1.0 - step
            }
        }
        spline = MonotonicSpline(t, y)
    }

    override fun get(fraction: Float): Float {
        if (fraction < 0f) return 0f
        if (fraction > 1f) return 1f
        return spline.position(fraction.toDouble()).toFloat()
    }
}

/**
 * A cubic Bézier easing on `(0,0) .. (1,1)` with control points `(x1,y1)`, `(x2,y2)`:
 * `CubicEasing` from remote-core, including its preset curves and its bisection lookup.
 */
class CubicEasing(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) : Easing {

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
    override fun get(x: Float): Float {
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

        /**
         * The curve a type names, as `FloatAnimation` chooses it: 1..6 the cubic presets, 13 the
         * bounce, 14 the elastic. 12 is a spline over a supplied spec, which needs the spec and
         * so is built in [FloatAnimation] rather than here; anything else is the standard cubic.
         */
        fun preset(type: Int): Easing = when (type) {
            EASE_OUT_BOUNCE -> BounceCurve()
            EASE_OUT_ELASTIC -> ElasticOutCurve()
            else -> cubicPreset(type)
        }

        /** Preset control points from `CubicEasing.<clinit>`. */
        fun cubicPreset(type: Int): CubicEasing = when (type) {
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
 * A wrap value makes the target live on a circle: the animation then goes the short way round,
 * which is what stops a clock hand swinging backwards through the dial at the end of a minute.
 * The directional snap is decoded but not applied.
 */
class FloatAnimation(description: FloatArray) {
    val duration: Float
    val type: Int
    var initialValue: Float = Float.NaN

    /**
     * `setTargetValue`: where the value is heading. With a [wrap] the two ends are first brought
     * into `0..wrap`, and a target that lies backwards by the short way round is pushed a whole
     * turn forward instead — which is how a clock hand goes from 354 degrees to 360 rather than
     * all the way back through zero.
     */
    var targetValue: Float = Float.NaN
        set(value) {
            field = value
            val period = wrap
            if (period.isNaN()) return
            initialValue = wrapInto(period, initialValue)
            field = wrapInto(period, field)
            if (initialValue.isNaN()) initialValue = field
            val distance = wrapDistance(period, initialValue, field)
            if (distance > 0f && field < initialValue) field += period
        }

    /** The period the value lives on, from the description; `NaN` when it does not wrap. */
    var wrap: Float = Float.NaN
        private set

    private val easing: Easing

    init {
        duration = if (description.isEmpty()) 1f else description[0]
        var easingType = CubicEasing.CUBIC_STANDARD
        var curve: Easing? = null
        if (description.size > 1) {
            val packed = description[1].toRawBits()
            easingType = packed and 0xFF
            val hasWrap = (packed shr 8) and 1 != 0
            val hasInitial = (packed shr 8) and 2 != 0
            val specLength = (packed shr 16) and 0xFFFF
            // `create(mType, spec, 2, specLength)`: the curve spec sits at index 2, and the
            // initial and wrap values follow it rather than coming first.
            val spec = 2
            var index = 2 + specLength
            if (hasInitial && index < description.size) initialValue = description[index++]
            if (hasWrap && index < description.size) wrap = description[index]
            if (spec + specLength <= description.size) {
                curve = when {
                    easingType == CubicEasing.CUBIC_CUSTOM && specLength >= 4 -> CubicEasing(
                        description[spec], description[spec + 1], description[spec + 2], description[spec + 3],
                    )
                    easingType == CubicEasing.SPLINE_CUSTOM && specLength >= 2 ->
                        StepCurve(description, spec, specLength)
                    else -> null
                }
            }
        }
        type = easingType
        easing = curve ?: CubicEasing.preset(easingType)
    }

    private companion object {
        /** `wrap(period, value)`: into `0..period`, the remainder brought back up if negative. */
        fun wrapInto(period: Float, value: Float): Float {
            if (value.isNaN()) return value
            val v = value % period
            return if (v < 0f) v + period else v
        }

        /**
         * `wrapDistance`: how far it is from one to the other the short way round. The real
         * method takes the remainder over 360 whatever the period is, which is mirrored here.
         */
        fun wrapDistance(period: Float, from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d < -period / 2f) d += period else if (d > period / 2f) d -= period
            return d
        }
    }

    /** `FloatAnimation.get`: the eased value [elapsed] seconds after the animation started. */
    fun get(elapsed: Float): Float {
        if (initialValue.isNaN()) return targetValue
        val fraction = if (duration <= 0f) 1f else elapsed / duration
        return easing.get(fraction) * (targetValue - initialValue) + initialValue
    }
}
