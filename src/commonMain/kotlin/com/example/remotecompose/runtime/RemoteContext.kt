package com.example.remotecompose.runtime

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.parser.Operation

/**
 * The document's live state, mirroring `androidx.compose.remote.core.RemoteContext`: every pool
 * that operations write into and draw opcodes read from, plus the system variables (time, size,
 * density) that make a document dynamic.
 *
 * Pools are keyed by the writer's document-wide ids. `getFloat` serves ids 1..35 from the system
 * variables, as `RemoteContext.ID_*` reserves them; everything else comes from [floats].
 *
 * One context lives as long as its document. [beginFrame] advances time; the parser's per-frame
 * evaluation then re-applies every non-constant operation against it.
 */
class RemoteContext {
    val texts = mutableMapOf<Int, String>()
    val floats = mutableMapOf<Int, Float>()
    val ints = mutableMapOf<Int, Int>()
    val longs = mutableMapOf<Int, Long>()
    val booleans = mutableMapOf<Int, Boolean>()
    val colors = mutableMapOf<Int, Color>()
    val paths = mutableMapOf<Int, List<PathCommand>>()
    val idLists = mutableMapOf<Int, List<Int>>()
    val dataMaps = mutableMapOf<Int, List<Operation.DataMapEntry>>()
    val bitmaps = mutableMapOf<Int, ByteArray>()

    /** Per-expression animation state, keyed by the operation instance. */
    internal val floatExpressions = mutableMapOf<Operation.FloatExpression, FloatExpressionState>()

    var density: Float = 1f
    var windowWidth: Float = 0f
    var windowHeight: Float = 0f
    var fontSize: Float = 12f

    /** Wall-clock milliseconds of the current frame, as given to [beginFrame]. */
    var frameTimeMillis: Long = 0L
        private set
    private var loadTimeMillis: Long = -1L

    /** Seconds since the document was first drawn (`ID_CONTINUOUS_SEC`, `ID_ANIMATION_TIME`). */
    var animationTime: Float = 0f
        private set

    /** Seconds since the previous frame (`ID_ANIMATION_DELTA_TIME`). */
    var deltaTime: Float = 0f
        private set

    /** Set when a frame evaluated something time-dependent, so the host knows to schedule another. */
    var needsRepaint: Boolean = false
        internal set

    /** Whether any operation has been applied yet; constants are applied only on the first pass. */
    var inflated: Boolean = false
        internal set

    fun beginFrame(nowMillis: Long) {
        if (loadTimeMillis < 0) loadTimeMillis = nowMillis
        val previous = animationTime
        frameTimeMillis = nowMillis
        animationTime = (nowMillis - loadTimeMillis) / 1000f
        deltaTime = animationTime - previous
        needsRepaint = false
    }

    fun loadFloat(id: Int, value: Float) {
        floats[id] = value
    }

    /** A float by id: a system variable for ids 1..35, else the pool value, else NaN. */
    fun getFloat(id: Int): Float = when (id) {
        ID_CONTINUOUS_SEC, ID_ANIMATION_TIME -> { needsRepaint = true; animationTime }
        ID_TIME_IN_SEC -> { needsRepaint = true; ((frameTimeMillis / 1000L) % 60L).toFloat() }
        ID_TIME_IN_MIN -> { needsRepaint = true; ((frameTimeMillis / 60_000L) % 60L).toFloat() }
        ID_TIME_IN_HR -> { needsRepaint = true; ((frameTimeMillis / 3_600_000L) % 24L).toFloat() }
        ID_ANIMATION_DELTA_TIME -> { needsRepaint = true; deltaTime }
        ID_EPOCH_SECOND -> { needsRepaint = true; (frameTimeMillis / 1000L).toFloat() }
        ID_WINDOW_WIDTH -> windowWidth
        ID_WINDOW_HEIGHT -> windowHeight
        ID_DENSITY -> density
        ID_API_LEVEL -> API_LEVEL.toFloat()
        ID_FONT_SIZE -> fontSize
        else -> floats[id] ?: Float.NaN
    }

    /**
     * Resolves a wire float that may be a NaN-tagged id (`Utils.asNan`) into its current value.
     * A literal comes back unchanged; an id with no value stays NaN.
     */
    fun resolveFloat(raw: Float): Float {
        if (!raw.isNaN()) return raw
        return getFloat(raw.toRawBits() and 0x3FFFFF)
    }

    /**
     * `FloatExpression.apply`: resolve the expression's variables, evaluate, feed the result
     * through the expression's animation if it has one, and store it under the expression's id.
     */
    fun applyFloatExpression(op: Operation.FloatExpression) {
        val state = floatExpressions.getOrPut(op) { FloatExpressionState(op) }
        val resolved = FloatArray(op.expression.size) { i ->
            val v = op.expression[i]
            if (FloatExpressionEvaluator.isVariable(v)) resolveFloat(v) else v
        }
        val value = FloatExpressionEvaluator.eval(resolved)
        val animation = state.animation
        if (animation == null) {
            loadFloat(op.id, value)
            return
        }
        if (value != state.lastCalculatedValue && !(value.isNaN() && state.lastCalculatedValue.isNaN())) {
            // updateVariables: a new target starts a fresh animation from the previous target.
            animation.initialValue = if (animation.targetValue.isNaN()) value else animation.targetValue
            animation.targetValue = value
            state.lastCalculatedValue = value
            state.lastChange = animationTime
        }
        if (state.lastChange.isNaN()) state.lastChange = animationTime
        val elapsed = animationTime - state.lastChange
        if (elapsed < animation.duration) needsRepaint = true
        loadFloat(op.id, animation.get(elapsed))
    }

    companion object {
        const val ID_CONTINUOUS_SEC = 1
        const val ID_TIME_IN_SEC = 2
        const val ID_TIME_IN_MIN = 3
        const val ID_TIME_IN_HR = 4
        const val ID_WINDOW_WIDTH = 5
        const val ID_WINDOW_HEIGHT = 6
        const val ID_DENSITY = 27
        const val ID_API_LEVEL = 28
        const val ID_ANIMATION_TIME = 30
        const val ID_ANIMATION_DELTA_TIME = 31
        const val ID_EPOCH_SECOND = 32
        const val ID_FONT_SIZE = 33

        /** The highest `Operations` profile this player claims to implement. */
        const val API_LEVEL = 6
    }
}

/** Mutable per-`FloatExpression` state: the last evaluated target and when it last changed. */
internal class FloatExpressionState(op: Operation.FloatExpression) {
    val animation: FloatAnimation? = op.animation?.let { FloatAnimation(it) }
    var lastCalculatedValue: Float = Float.NaN
    var lastChange: Float = Float.NaN
}
