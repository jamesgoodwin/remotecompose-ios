package com.example.remotecompose.runtime

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * `VelocityEasing`: where a value carried by a finger goes after the finger leaves.
 *
 * The motion is a run of stages, each holding velocity ramping linearly from one value to
 * another, so position within a stage is the area under that ramp. `config` picks the shortest
 * shape that reaches the destination without exceeding the acceleration and velocity it is given,
 * trying them in order: ramp straight down; cruise then ramp down; ramp up then down; and failing
 * those, ramp up, cruise and ramp down over exactly the time allowed.
 *
 * The real class also adapts the profile to a supplied `Easing`. `TouchExpression.touchUp` passes
 * none, which is the only caller here, so that path is not transcribed.
 */
class VelocityEasing {

    /**
     * `VelocityEasing.Stage`: velocity going from one value to another in a straight line.
     * Position is the trapezoid under it, which is why only the start position is needed —
     * `mEndPos` is stored by the real class and never read by `getPos`.
     */
    private class Stage {
        var startV = 0f
        var startPos = 0f
        var startTime = 0f
        var endTime = 0f
        var deltaV = 0f
        var deltaT = 0f

        fun setUp(startV: Float, startPos: Float, startTime: Float, endV: Float, endTime: Float) {
            this.startV = startV
            this.startPos = startPos
            this.startTime = startTime
            this.endTime = endTime
            deltaV = endV - startV
            deltaT = endTime - startTime
        }

        fun position(at: Float): Float {
            val dt = at - startTime
            val v = startV + deltaV * (dt / deltaT)
            return dt * (startV + v) / 2f + startPos
        }
    }

    private val stages = Array(4) { Stage() }
    private var stageCount = 0
    private var endPos = 0f

    /** How long the movement lasts; past it the value is wherever it was going. */
    var duration: Float = 0f
        private set

    /**
     * `config(start, end, velocity, duration, maxAcceleration, maxVelocity, null)`: the movement
     * from [start] to [end] that begins at [velocity] and settles inside [duration].
     */
    fun config(
        start: Float,
        end: Float,
        velocity: Float,
        duration: Float,
        maxAcceleration: Float,
        maxVelocity: Float,
    ) {
        // A movement that is already where it is going still needs a direction to stop in.
        var from = start
        if (from == end) from += 1f
        endPos = end
        val direction = sign(end - from)
        val maxV = maxVelocity * direction
        val maxA = maxAcceleration * direction
        // A finger that came off without moving still has to go somewhere, so it goes slowly.
        val v = if (velocity == 0f) 1e-4f * direction else velocity

        if (rampDown(from, end, v, duration)) return
        // `mOneDimension` is set in the constructor and never cleared, so this one is always tried.
        if (cruiseThenRampDown(from, end, v, duration, maxA)) return
        if (rampUpRampDown(from, end, v, maxA, maxV, duration)) return
        rampUpCruiseRampDown(from, end, v, duration)
    }

    /** Carrying on at the speed it was going, then slowing to a stop at the end of it. */
    private fun cruiseThenRampDown(
        from: Float,
        to: Float,
        v: Float,
        duration: Float,
        maxA: Float,
    ): Boolean {
        val down = v / maxA
        val downDistance = v * down / 2f
        val cruiseDistance = (to - from) - downDistance
        val cruise = cruiseDistance / v
        val total = cruise + down
        if (total <= 0f || total >= duration) return false
        stageCount = 2
        stages[0].setUp(v, from, 0f, v, cruise)
        stages[1].setUp(v, from + cruiseDistance, cruise, 0f, cruise + down)
        this.duration = total
        return true
    }

    /** Slowing straight to a stop arrives in time, so there is nothing else to do. */
    private fun rampDown(from: Float, to: Float, v: Float, duration: Float): Boolean {
        val time = 2f * (to - from) / v
        if (time <= 0f || time > duration) return false
        stageCount = 1
        stages[0].setUp(v, from, 0f, 0f, time)
        this.duration = time
        return true
    }

    /** Speeding up to a peak and back down to nothing, within the acceleration allowed. */
    private fun rampUpRampDown(
        from: Float,
        to: Float,
        v: Float,
        maxA: Float,
        maxV: Float,
        duration: Float,
    ): Boolean {
        var peakV = sign(maxA) * sqrt(maxA * (to - from) + v * v / 2f)
        if (maxV / peakV <= 1f) return false
        var up = (peakV - v) / maxA
        var atPeak = (peakV + v) * up / 2f + from
        var down = peakV / maxA
        stageCount = 2
        stages[0].setUp(v, from, 0f, peakV, up)
        stages[1].setUp(peakV, atPeak, up, 0f, down + up)
        this.duration = down + up
        if (this.duration > duration) return false
        if (this.duration < duration / 2f) {
            // There is time in hand, so the same distance is covered more gently.
            up = duration / 2f
            down = up
            peakV = (2f * (to - from) / up - v) / 2f
            atPeak = (peakV + v) * up / 2f + from
            stageCount = 2
            stages[0].setUp(v, from, 0f, peakV, up)
            stages[1].setUp(peakV, atPeak, up, 0f, down + up)
            this.duration = down + up
            if (this.duration > duration) return false
        }
        return true
    }

    /** The fallback: three even stages filling exactly the time allowed. */
    private fun rampUpCruiseRampDown(from: Float, to: Float, v: Float, duration: Float) {
        val third = duration / 3f
        val twoThirds = 2f * third
        val distance = to - from
        val cruise = twoThirds - third
        val tail = duration - twoThirds
        val peakV = (2f * distance - v * third) / (third + 2f * cruise + tail)
        val first = (v + peakV) * third / 2f
        val second = (peakV + peakV) * (twoThirds - third) / 2f
        stageCount = 3
        stages[0].setUp(v, from, 0f, peakV, third)
        stages[1].setUp(peakV, from + first, third, peakV, twoThirds)
        stages[2].setUp(peakV, from + first + second, twoThirds, 0f, duration)
        this.duration = duration
    }

    /** Where the value is [at] seconds after the finger left; past the end, where it was going. */
    fun position(at: Float): Float {
        for (i in 0 until stageCount) {
            if (stages[i].endTime > at) return stages[i].position(at)
        }
        return endPos
    }

    /** Whether [at] is still inside the movement. */
    fun isRunning(at: Float): Boolean = duration >= at && stageCount > 0

    /** Guards against a profile that came out with no distance to cover. */
    fun isUseful(): Boolean = stageCount > 0 && duration > 0f && abs(duration) < 1e6f
}
