package com.example.remotecompose.runtime

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.text.BitmapFont
import com.example.remotecompose.parser.Operation
import com.example.remotecompose.parser.Operation.TouchExpression
import kotlin.math.abs

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

    /** `DATA_BITMAP` carries the size beside the bytes, which is what `ImageAttribute` reads. */
    val bitmapSizes = mutableMapOf<Int, Pair<Int, Int>>()
    val bitmapFonts = mutableMapOf<Int, BitmapFont>()

    /** Shaders, keyed by id. Decoded only: nothing here paints one. */
    val shaders = mutableMapOf<Int, Operation.ShaderData>()

    /** Matrices, keyed by id, as the raw values a `MatrixAccess` hands out. */
    val matrices = mutableMapOf<Int, FloatArray>()

    /** `AnimationSpec`s by id, for the components that name one. */
    val animationSpecs = mutableMapOf<Int, Operation.AnimationSpec>()

    /**
     * The press each rippling component is showing: when it started, and where it was touched
     * in that component's own coordinates. `RippleModifierOperation` keeps the same three.
     */
    class Ripple(val startedAt: Float, val x: Float, val y: Float)

    /** A pool value the document gave a name to, and what kind of value it is. */
    class NamedValue(val id: Int, val type: Int)

    /** `loadVariableName`: what the host can find a value by, filled in by `NAMED_VARIABLE`. */
    val namedValues = mutableMapOf<String, NamedValue>()

    fun loadVariableName(name: String, id: Int, type: Int) {
        namedValues[name] = NamedValue(id, type)
    }

    /**
     * `setNamed*Override`: the host putting a value in by name, which is the point of naming one.
     * Returns false when the document never named it, or named it as something else.
     *
     * The concrete player's own override bookkeeping is not in the extracted jars — only the
     * abstract declarations and `loadVariableName`, which is what the operation itself does — so
     * this writes straight into the pool the name points at.
     */
    fun setNamedValue(name: String, type: Int, write: (Int) -> Unit): Boolean {
        val named = namedValues[name] ?: return false
        if (named.type != type) return false
        write(named.id)
        needsRepaint = true
        return true
    }

    /** What a component's visibility was, what it is heading for, and when it set off. */
    class VisibilityState(var from: Int, var target: Int, var startedAt: Float)

    internal val visibilityStates = mutableMapOf<Int, VisibilityState>()

    internal val ripples = mutableMapOf<Int, Ripple>()

    /** Where each animating component is on its way from its last layout to its current one. */
    internal val measureAnimations = mutableMapOf<Int, com.example.remotecompose.layout.MeasureAnimation>()

    /** Particle variables, keyed by the id of the `ParticlesCreate` that made them. */
    val particles = mutableMapOf<Int, ParticleSystem>()

    /**
     * Previous operands of each `ConditionalOperations`, keyed by its index in the operation
     * list, so `TYPE_CHANGED` can compare against the last evaluation.
     */
    internal val conditionalPrevious = mutableMapOf<Int, FloatArray>()

    /** Per-expression animation state, keyed by the operation instance. */
    internal val floatExpressions = mutableMapOf<Operation.FloatExpression, FloatExpressionState>()

    /**
     * The mode the host is painting in: `THEME_LIGHT` or `THEME_DARK`. A document carries a
     * palette for each, and the one that does not match is skipped as the operations are walked.
     * Light by default, which is what a host that has not asked for either gets.
     */
    var paintTheme: Int = THEME_LIGHT

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

    private var generatedIds = FIRST_GENERATED_ID

    /**
     * An id no document uses, for a value a pattern's body declares: each expansion of a body
     * needs its own, or the copies share one slot and the last one written is the one every
     * copy shows. `RemapContext.allocateNewId` does the same from the document's own counter.
     */
    fun nextGeneratedId(): Int = generatedIds++

    /** Starts the generated ids again, so that a frame reuses the previous frame's. */
    fun resetGeneratedIds() {
        generatedIds = FIRST_GENERATED_ID
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

    fun overrideColorValue(id: Int, argb: Int) {
        colors[id] = Color(argb)
        needsRepaint = true
    }

    fun overrideLong(id: Int, value: Long) {
        longs[id] = value
        needsRepaint = true
    }

    fun overrideTextValue(id: Int, value: String) {
        texts[id] = value
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
        /** `TouchExpression.mUnmodified`, inverted: false until a drag has moved this. */
        var moved: Boolean = false
        /** `mCurrentValue`: what the last drag left, which outlives the finger. */
        var value: Float = Float.NaN
        /** `mEasingToStop` and `mTouchUpTime`: the glide the finger left behind, and when. */
        var easingToStop: Boolean = false
        var touchUpTime: Float = 0f
        val easing: VelocityEasing = VelocityEasing()
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
        if (state.easingToStop) {
            // `apply` while `mEasingToStop`: the value is wherever the glide has reached, and the
            // glide is over once the clock has passed its duration.
            val elapsed = animationTime - state.touchUpTime
            val position = state.easing.position(elapsed)
            state.value = position
            state.moved = true
            loadFloat(op.id, clampTo(position, min, max))
            if (!state.easing.isRunning(elapsed)) state.easingToStop = false
            needsRepaint = true
            return
        }
        if (!state.down) {
            // `TouchExpression.apply` only shows the default while nothing has moved it; what a
            // drag left stays after the finger goes, which is what makes a list stay scrolled.
            // It is clamped again in case the bounds have changed since.
            val value = if (state.moved) state.value else resolveFloat(op.defValue)
            if (!value.isNaN()) loadFloat(op.id, clampTo(value, min, max))
            return
        }
        val current = evaluateTouchExpression(op)
        if (current.isNaN()) return
        val value = clampTo(state.valueAtDown + current - state.expressionAtDown, min, max)
        state.value = value
        state.moved = true
        loadFloat(op.id, value)
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
            // A finger landing on something still gliding stops it where it is.
            state.easingToStop = false
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

    /**
     * `TouchExpression.touchUp`: the finger leaves at some speed, and what it was carrying keeps
     * going. The velocity that matters is the expression's, not the pointer's, so the expression
     * is evaluated once where the finger is and once a moment further along the way it was
     * travelling, and the difference over that moment is the speed the value was moving at.
     *
     * `getStopPosition` then says where it should come to rest — for `STOP_GENTLY`, half the
     * velocity further on, held inside the bounds — and `VelocityEasing` shapes the journey.
     */
    internal fun touchUp(velocityX: Float, velocityY: Float, expressions: List<Operation.TouchExpression>) {
        val byId = expressions.associateBy { it.id }
        for ((id, state) in touchStates) {
            if (!state.down) continue
            state.down = false
            val op = byId[id] ?: continue
            if (stopModeOf(op) == STOP_INSTANTLY) continue
            val before = evaluateTouchExpression(op)
            val after = withPointerAt(touchX + velocityX * TOUCH_EPSILON, touchY + velocityY * TOUCH_EPSILON) {
                evaluateTouchExpression(op)
            }
            if (before.isNaN() || after.isNaN()) continue
            val velocity = (after - before) / TOUCH_EPSILON
            val current = getFloat(id).takeUnless { it.isNaN() } ?: continue
            val min = resolveFloat(op.min)
            val max = resolveFloat(op.max)
            val stop = stopPosition(op, current, velocity, min, max)
            val limits = velocityLimits(op)
            // `min(2, maxTime * |stop - current| / (2 * maxVelocity))`.
            val time = minOf(
                2f,
                limits.maxTime * abs(stop - current) / (2f * limits.maxVelocity),
            )
            state.easing.config(current, stop, velocity, time, limits.maxAcceleration, limits.maxVelocity)
            if (!state.easing.isUseful()) continue
            state.touchUpTime = animationTime
            state.easingToStop = true
        }
        needsRepaint = true
    }

    /** Runs [block] as though the pointer were somewhere else, then puts it back. */
    private inline fun <T> withPointerAt(x: Float, y: Float, block: () -> T): T {
        val savedX = touchX
        val savedY = touchY
        touchX = x
        touchY = y
        try {
            return block()
        } finally {
            touchX = savedX
            touchY = savedY
        }
    }

    /** `mStopMode`: the high half of the packed int the tap expression's length is in. */
    private fun stopModeOf(op: Operation.TouchExpression): Int = op.tapExpPacked shr 16

    /**
     * `getStopPosition`: where a value let go of at [velocity] should settle.
     *
     * `STOP_GENTLY` (and `STOP_ABSOLUTE_POS`) carry on half the velocity further and stay inside
     * the bounds; `STOP_ENDS` goes to whichever end is nearer. The notch modes are decoded and
     * not applied — see `docs/OPCODES.md`.
     */
    private fun stopPosition(
        op: Operation.TouchExpression,
        value: Float,
        velocity: Float,
        min: Float,
        max: Float,
    ): Float {
        val target = clampTo(value + velocity / 2f, min, max)
        return when (stopModeOf(op)) {
            STOP_ENDS -> {
                val floor = if (min.isNaN()) 0f else min
                if (value + velocity > (max + floor) / 2f) max else floor
            }
            else -> target
        }
    }

    /** `mMaxTime`/`mMaxAcceleration`/`mMaxVelocity`: the trailing floats, or the class defaults. */
    private fun velocityLimits(op: Operation.TouchExpression): Limits {
        val spec = op.tapExpFloats
        return if (spec.size >= 4 && spec[0] == 0f) Limits(spec[1], spec[2], spec[3])
        else Limits(maxTime = 1f, maxAcceleration = 5f, maxVelocity = 7f)
    }

    internal class Limits(val maxTime: Float, val maxAcceleration: Float, val maxVelocity: Float)

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
        /** Above anything a writer allocates, so a generated id cannot land on a real one. */
        private const val FIRST_GENERATED_ID = 1 shl 24

        /** `Theme`'s own values. */
        const val THEME_SYSTEM = 0
        const val THEME_UNSPECIFIED = -1
        const val THEME_DARK = -2
        const val THEME_LIGHT = -3

        const val ID_CONTINUOUS_SEC = 1
        /** `NamedVariable` types. 6 is both `FLOAT_ARRAY_TYPE` and `PATH_TYPE` upstream. */
        const val NAMED_STRING = 0
        const val NAMED_FLOAT = 1
        const val NAMED_COLOR = 2
        const val NAMED_IMAGE = 3
        const val NAMED_INT = 4
        const val NAMED_LONG = 5
        const val NAMED_FLOAT_ARRAY = 6

        /** `TouchExpression.STOP_*`: what a released touch expression settles on. */
        const val STOP_GENTLY = 0
        const val STOP_INSTANTLY = 1
        const val STOP_ENDS = 2

        /** The moment `TouchExpression.touchUp` looks ahead by to measure the value's speed. */
        const val TOUCH_EPSILON = 1e-4f

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
