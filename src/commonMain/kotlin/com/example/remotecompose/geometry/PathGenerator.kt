package com.example.remotecompose.geometry

import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.runtime.FloatCollections
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Samples a pair of float expressions into a path, a transcription of
 * `androidx.compose.remote.core.operations.utilities.PathGenerator` (remote-core 1.0.0-alpha18).
 *
 * The two expressions are evaluated once per sample with the sample position in the first
 * caller variable slot (`VAR1`), giving `count` points between `min` and `max`; the points are
 * then joined by [Kind]. Every segment is emitted as a cubic, exactly as `PathGenerator.Path`
 * does, so a generated path and a hand-written one are the same kind of object downstream.
 */
object PathGenerator {

    /** How the sampled points are joined: `PathGenerator`'s `SPLINE`(0), `MONOTONIC`(2), `LINEAR`(4). */
    enum class Kind { SPLINE, MONOTONIC, LINEAR }

    fun kindOf(flags: Int): Kind = when (flags and 6) {
        2 -> Kind.MONOTONIC
        4 -> Kind.LINEAR
        else -> Kind.SPLINE
    }

    /**
     * `getPath`: samples [expressionX] and [expressionY] at [count] positions from [min] to
     * [max] — the step is the range over `count` when [loop], over `count - 1` otherwise — and
     * joins the points. Both expressions must already have their pool variables resolved.
     */
    fun generate(
        expressionX: FloatArray,
        expressionY: FloatArray,
        min: Float,
        max: Float,
        count: Int,
        kind: Kind,
        loop: Boolean,
        collections: FloatCollections? = null,
    ): List<PathCommand> {
        if (count <= 0) return emptyList()
        val step = (max - min) / (if (loop) count else count - 1).toFloat()
        val x = FloatArray(count)
        val y = FloatArray(count)
        for (i in 0 until count) {
            val t = min + i * step
            x[i] = FloatExpressionEvaluator.eval(expressionX, floatArrayOf(t), collections)
            y[i] = FloatExpressionEvaluator.eval(expressionY, floatArrayOf(t), collections)
        }
        return join(x, y, kind, loop)
    }

    /**
     * `getPolarPath`: [expressionRadius] is sampled against the angle in radians and
     * [center] is a two-entry expression array holding the centre, so the sample lands at
     * `center + radius * (cos t, sin t)`.
     */
    fun generatePolar(
        expressionRadius: FloatArray,
        center: FloatArray,
        min: Float,
        max: Float,
        count: Int,
        kind: Kind,
        loop: Boolean,
        collections: FloatCollections? = null,
    ): List<PathCommand> {
        if (count <= 0 || center.size < 2) return emptyList()
        val step = (max - min) / (if (loop) count else count - 1).toFloat()
        val x = FloatArray(count)
        val y = FloatArray(count)
        for (i in 0 until count) {
            val t = min + i * step
            val r = FloatExpressionEvaluator.eval(expressionRadius, floatArrayOf(t), collections)
            x[i] = center[0] + r * cos(t)
            y[i] = center[1] + r * sin(t)
        }
        return join(x, y, kind, loop)
    }

    private fun join(x: FloatArray, y: FloatArray, kind: Kind, loop: Boolean): List<PathCommand> = when (kind) {
        Kind.LINEAR -> linear(x, y, loop)
        Kind.MONOTONIC -> curved(x, y, loop, monotone = true)
        Kind.SPLINE -> curved(x, y, loop, monotone = false)
    }

    /** `PathGenerator.Linear.asPath`: every segment is a cubic whose controls sit on its ends. */
    private fun linear(x: FloatArray, y: FloatArray, loop: Boolean): List<PathCommand> {
        val n = x.size
        if (n == 0) return emptyList()
        val out = mutableListOf<PathCommand>(PathCommand.MoveTo(x[0], y[0]))
        if (n == 1) return out
        val segments = if (loop) n else n - 1
        for (i in 0 until segments) {
            val j = (i + 1) % n
            out += PathCommand.CubicTo(x[i], y[i], x[j], y[j], x[j], y[j])
        }
        if (loop) out += PathCommand.Close
        return out
    }

    /**
     * `PathGenerator.Spline.asPath` and `Monotonic.asPath`, which differ only in how they pick
     * the tangents: each segment becomes a cubic whose control points are a third of the
     * segment's length along the tangent at each end.
     */
    private fun curved(x: FloatArray, y: FloatArray, loop: Boolean, monotone: Boolean): List<PathCommand> {
        val n = x.size
        if (n == 0) return emptyList()
        val out = mutableListOf<PathCommand>(PathCommand.MoveTo(x[0], y[0]))
        if (n == 1) return out
        val segments = if (loop) n else n - 1
        val h = FloatArray(segments)
        val dxSeg = FloatArray(segments)
        val dySeg = FloatArray(segments)
        for (i in 0 until segments) {
            val j = (i + 1) % n
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            var length = hypot(dx, dy)
            if (length == 0f) length = 1e-12f
            h[i] = length
            dxSeg[i] = dx / length
            dySeg[i] = dy / length
        }
        val tangentCount = if (loop) segments else segments + 1
        val dxTan = FloatArray(tangentCount)
        val dyTan = FloatArray(tangentCount)
        if (monotone) {
            monotoneTangents(dxTan, dxSeg, h, loop)
            monotoneTangents(dyTan, dySeg, h, loop)
        } else {
            smoothTangents(dxTan, dxSeg, h, loop)
            smoothTangents(dyTan, dySeg, h, loop)
        }
        for (i in 0 until segments) {
            val j = (i + 1) % n
            val length = h[i]
            out += PathCommand.CubicTo(
                x[i] + dxTan[i] * length / 3f, y[i] + dyTan[i] * length / 3f,
                x[j] - dxTan[j] * length / 3f, y[j] - dyTan[j] * length / 3f,
                x[j], y[j],
            )
        }
        if (loop) out += PathCommand.Close
        return out
    }

    /** `Spline.smoothTangents`: each tangent is the neighbouring directions weighted by the opposite segment. */
    private fun smoothTangents(tangents: FloatArray, segments: FloatArray, lengths: FloatArray, loop: Boolean) {
        val m = segments.size
        val count = if (loop) m else m + 1
        if (loop) {
            for (k in 0 until count) {
                val p = (k - 1 + m) % m
                val c = k % m
                tangents[k] = (lengths[p] * segments[c] + lengths[c] * segments[p]) / (lengths[p] + lengths[c])
            }
            return
        }
        tangents[0] = segments[0]
        tangents[count - 1] = segments[m - 1]
        for (k in 1 until count - 1) {
            val previous = lengths[k - 1]
            val current = lengths[k]
            tangents[k] = (previous * segments[k] + current * segments[k - 1]) / (previous + current)
        }
    }

    /**
     * `Monotonic.monotoneTangents`: the harmonic-mean tangent, zeroed where the direction turns,
     * then the Fritsch-Carlson limit that keeps each segment from overshooting.
     */
    private fun monotoneTangents(tangents: FloatArray, segments: FloatArray, lengths: FloatArray, loop: Boolean) {
        val m = segments.size
        val count = if (loop) m else m + 1
        for (k in 0 until count) {
            val p = (k - 1 + m) % m
            val c = k % m
            when {
                !loop && k == 0 -> tangents[k] = segments[0]
                !loop && k == count - 1 -> tangents[k] = segments[m - 1]
                else -> {
                    val before = segments[p]
                    val after = segments[c]
                    if (before == 0f || after == 0f || sign(before) != sign(after)) {
                        tangents[k] = 0f
                    } else {
                        val w1 = 2f * lengths[c] + lengths[p]
                        val w2 = lengths[c] + 2f * lengths[p]
                        tangents[k] = (w1 + w2) / (w1 / before + w2 / after)
                    }
                }
            }
        }
        for (k in 0 until m) {
            if (segments[k] == 0f) {
                tangents[k] = 0f
                tangents[(k + 1) % count] = 0f
                continue
            }
            val a = tangents[k] / segments[k]
            val b = tangents[(k + 1) % count] / segments[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                tangents[k] = t * a * segments[k]
                tangents[(k + 1) % count] = t * b * segments[k]
            }
        }
    }

    /** True when every coordinate of [commands] is finite: a sampled path with a NaN is not drawable. */
    fun isFinite(commands: List<PathCommand>): Boolean = commands.all { command ->
        when (command) {
            is PathCommand.MoveTo -> command.x.isFinite() && command.y.isFinite()
            is PathCommand.LineTo -> command.x.isFinite() && command.y.isFinite()
            is PathCommand.QuadraticTo ->
                command.x1.isFinite() && command.y1.isFinite() && command.x2.isFinite() && command.y2.isFinite()
            is PathCommand.CubicTo ->
                command.x1.isFinite() && command.y1.isFinite() && command.x2.isFinite() &&
                    command.y2.isFinite() && command.x3.isFinite() && command.y3.isFinite()
            PathCommand.Close -> true
        }
    }

}
