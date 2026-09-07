package com.example.remotecompose.runtime

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.text.BitmapFont
import com.example.remotecompose.parser.Operation
import com.example.remotecompose.parser.Operation.TouchExpression

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
class RemoteContext : FloatCollections {
    val texts = mutableMapOf<Int, String>()
    val floats = mutableMapOf<Int, Float>()
    val ints = mutableMapOf<Int, Int>()
    val longs = mutableMapOf<Int, Long>()
    val booleans = mutableMapOf<Int, Boolean>()
    val colors = mutableMapOf<Int, Color>()
    val paths = mutableMapOf<Int, List<PathCommand>>()
    val idLists = mutableMapOf<Int, List<Int>>()

    /** Float collections, readable by the expression evaluator's `A_*` operators. */
    val floatLists = mutableMapOf<Int, FloatArray>()

    /** `CollectionsAccess.getFloats`: the entries of a collection, for those operators. */
    override fun floats(id: Int): FloatArray? = floatLists[id]
    val dataMaps = mutableMapOf<Int, List<Operation.DataMapEntry>>()
    val bitmaps = mutableMapOf<Int, ByteArray>()
    val bitmapFonts = mutableMapOf<Int, BitmapFont>()

    /** Shaders, keyed by id. Decoded only: nothing here paints one. */
    val shaders = mutableMapOf<Int, Operation.ShaderData>()

    /** Matrices, keyed by id, as the raw values a `MatrixAccess` hands out. */
    val matrices = mutableMapOf<Int, FloatArray>()

    /** Particle variables, keyed by the id of the `ParticlesCreate` that made them. */
    val particles = mutableMapOf<Int, ParticleSystem>()

    /**
     * Previous operands of each `ConditionalOperations`, keyed by its index in the operation
     * list, so `TYPE_CHANGED` can compare against the last evaluation.
     */
    internal val conditionalPrevious = mutableMapOf<Int, FloatArray>()

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

    /**
     * Makes [alias] read as whatever [source] holds, across every pool.
     *
     * `PatternForEach` binds its local item to an entry's id by remapping ids as it re-reads the
     * body, which reaches the same place from the other end: this evaluator reads the body as it
     * stands, so the entry's value is copied to the id the body names. The difference shows only
     * if a body writes to its local item, which the real one would drop on the next expansion
     * and this one keeps until the next entry overwrites it.
     */
    fun aliasId(alias: Int, source: Int) {
        if (alias == source) return
        texts[source]?.let { texts[alias] = it } ?: texts.remove(alias)
        floats[source]?.let { floats[alias] = it } ?: floats.remove(alias)
        ints[source]?.let { ints[alias] = it } ?: ints.remove(alias)
        longs[source]?.let { longs[alias] = it } ?: longs.remove(alias)
        booleans[source]?.let { booleans[alias] = it } ?: booleans.remove(alias)
        colors[source]?.let { colors[alias] = it } ?: colors.remove(alias)
        paths[source]?.let { paths[alias] = it } ?: paths.remove(alias)
        idLists[source]?.let { idLists[alias] = it } ?: idLists.remove(alias)
        dataMaps[source]?.let { dataMaps[alias] = it } ?: dataMaps.remove(alias)
        bitmaps[source]?.let { bitmaps[alias] = it } ?: bitmaps.remove(alias)
    }

    fun loadFloat(id: Int, value: Float) {
        floats[id] = value
    }

    /** Pointer position in window coordinates, served as `ID_TOUCH_POS_X`/`_Y`. */
    var touchX: Float = 0f
        internal set
    var touchY: Float = 0f
        internal set

    /**
     * Receives `HostActionOperation` dispatches: the action id and its metadata text, which the
     * host application interprets (a link, a navigation target, an app-defined command).
     */
    var onHostAction: ((Int, String) -> Unit)? = null

    /**
     * `RemoteContext.overrideFloat`/`overrideInteger`/`overrideText`: an action's write to a
     * value id. Constants only apply on the first evaluation pass, so an override survives every
     * later frame until another action changes it.
     */
    fun overrideFloat(id: Int, value: Float) {
        floats[id] = value
        needsRepaint = true
    }

    fun overrideInteger(id: Int, value: Int) {
        ints[id] = value
        needsRepaint = true
    }

    fun overrideText(targetId: Int, sourceId: Int) {
        texts[sourceId]?.let { texts[targetId] = it }
        needsRepaint = true
    }

    fun runHostAction(actionId: Int, metadata: String) {
        onHostAction?.invoke(actionId, metadata)
    }

    /** Per-`TouchExpression` state: the value and expression result when the pointer went down. */
    internal class TouchState {
        var down: Boolean = false
        var valueAtDown: Float = 0f
        var expressionAtDown: Float = 0f
    }

    internal val touchStates = mutableMapOf<Int, TouchState>()

    /**
     * `TouchExpression.apply` in its default mode: while the pointer is down the expression is
     * evaluated against the current pointer position and the delta since the press is added to
     * the value the variable had then; the result is clamped to `[min, max]` and stored under the
     * expression's id. With no pointer down the default value is used.
     *
     * The real operation also carries velocity easing, wrap-around and notch stops
     * (`VelocityEasing`, `STOP_*`); those are decoded but not applied here.
     */
    fun applyTouchExpression(op: Operation.TouchExpression) {
        val state = touchStates.getOrPut(op.id) { TouchState() }
        val min = resolveFloat(op.min)
        val max = resolveFloat(op.max)
        if (!state.down) {
            val default = resolveFloat(op.defValue)
            if (!default.isNaN()) loadFloat(op.id, clampTo(default, min, max))
            return
        }
        val current = evaluateTouchExpression(op)
        if (current.isNaN()) return
        loadFloat(op.id, clampTo(state.valueAtDown + current - state.expressionAtDown, min, max))
        needsRepaint = true
    }

    private fun clampTo(value: Float, min: Float, max: Float): Float {
        var v = value
        if (!min.isNaN()) v = maxOf(v, min)
        if (!max.isNaN()) v = minOf(v, max)
        return v
    }

    internal fun evaluateTouchExpression(op: Operation.TouchExpression): Float {
        val resolved = FloatArray(op.srcExp.size) { i ->
            val v = op.srcExp[i]
            if (FloatExpressionEvaluator.isVariable(v)) resolveFloat(v) else v
        }
        return FloatExpressionEvaluator.eval(resolved, collections = this)
    }

    /** Records the pointer press for every touch expression, as `TouchExpression.touchDown` does. */
    internal fun touchDown(x: Float, y: Float, expressions: List<Operation.TouchExpression>) {
        touchX = x
        touchY = y
        for (op in expressions) {
            val state = touchStates.getOrPut(op.id) { TouchState() }
            state.down = true
            state.valueAtDown = getFloat(op.id).takeUnless { it.isNaN() } ?: resolveFloat(op.defValue).takeUnless { it.isNaN() } ?: 0f
            state.expressionAtDown = evaluateTouchExpression(op)
        }
        needsRepaint = true
    }

    internal fun touchDrag(x: Float, y: Float) {
        touchX = x
        touchY = y
        needsRepaint = true
    }

    internal fun touchUp() {
        for (state in touchStates.values) state.down = false
        needsRepaint = true
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
        ID_TOUCH_POS_X -> touchX
        ID_TOUCH_POS_Y -> touchY
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
        val value = FloatExpressionEvaluator.eval(resolved, collections = this)
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
        const val ID_TOUCH_POS_X = 13
        const val ID_TOUCH_POS_Y = 14
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
