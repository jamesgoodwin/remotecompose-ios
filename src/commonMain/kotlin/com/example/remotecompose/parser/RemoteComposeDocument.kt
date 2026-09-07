package com.example.remotecompose.parser

import com.example.remotecompose.model.Header
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.runtime.ActionTrigger
import com.example.remotecompose.runtime.DocumentAction
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import com.example.remotecompose.runtime.HitRegion
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.text.TextMetricsProvider

/**
 * A loaded `.rc` document: its decoded [operations] plus the [context] they evaluate against.
 * The equivalent of `CoreDocument`: one instance lives as long as the document is shown, each
 * [frame] re-evaluates the time-dependent operations and flattens the result, and the gesture
 * entry points run the action lists the components declare.
 */
class RemoteComposeDocument internal constructor(
    val header: Header,
    val operations: List<Operation>,
    val context: RemoteContext,
    private val textMetrics: TextMetricsProvider,
) {
    /** Decoded lazily after the first frame has collected every `DATA_BITMAP`. */
    private val bitmaps: BitmapPool by lazy { BitmapPool.fromEntries(context.bitmaps) }

    private val touchExpressions: List<Operation.TouchExpression> = operations.filterIsInstance<Operation.TouchExpression>()

    /** Hit rectangles of the most recent [frame], in document coordinates and in paint order. */
    var hitRegions: List<HitRegion> = emptyList()
        private set

    private var rippleTargets: List<com.example.remotecompose.layout.LayoutEngine.RippleTarget> = emptyList()

    /**
     * True after a [frame] that read a time variable, advanced an animation, or followed a
     * gesture that changed a value: the host should schedule another frame.
     */
    val needsRepaint: Boolean get() = context.needsRepaint

    /**
     * The mode to paint in: [RemoteContext.THEME_LIGHT] or [RemoteContext.THEME_DARK].
     *
     * A document carries a palette for each, so changing this changes what the next frame draws.
     * The constants a mode declares were skipped while the other mode was showing, so they are
     * applied again on the frame after a change.
     */
    var paintTheme: Int
        get() = context.paintTheme
        set(value) {
            if (context.paintTheme == value) return
            context.paintTheme = value
            context.inflated = false
            context.needsRepaint = true
        }

    /** Called with the action id and metadata of every `HOST_ACTION` the document runs. */
    var onHostAction: ((Int, String) -> Unit)?
        get() = context.onHostAction
        set(value) { context.onHostAction = value }

    /**
     * `HOST_NAMED_ACTION`: an action the document names with a string rather than a number, and
     * which carries one value with it — a Float, Int, String, FloatArray, or null.
     */
    var onNamedAction: ((String, Any?) -> Unit)?
        get() = context.onNamedAction
        set(value) { context.onNamedAction = value }

    /**
     * Evaluates the document at wall-clock time [nowMillis] and returns its flattened opcodes.
     * The first call fixes the document's load time, so passing `0` first and `t` next yields an
     * animation time of `t` milliseconds.
     */
    fun frame(nowMillis: Long): RemoteDocument {
        context.beginFrame(nowMillis)
        val opcodes = RemoteComposeParser.build(operations, context, textMetrics)
        hitRegions = RemoteComposeParser.hitRegions
        rippleTargets = RemoteComposeParser.rippleTargets
        return RemoteDocument(header, context.texts.toMap(), bitmaps, opcodes)
    }

    /**
     * `RemoteComposeState.getOpsToUpdate`: how long the host may wait before drawing this
     * document again, in milliseconds — 0 when something has already changed, and -1 when nothing
     * has asked for another frame at all.
     *
     * A `WAKE_IN` is how a document that changes rarely says so, instead of being drawn at every
     * frame. Reading the answer records it, so a later `WAKE_IN` can only bring the wake forward.
     */
    fun nextRepaintDelayMillis(): Int = context.takeRepaintDelayMillis()

    /**
     * `CoreDocument.onClick`: runs the click actions of the topmost component containing
     * ([x], [y]) in document coordinates. Returns true when a component handled the tap, so the
     * host can decide what to do with an unhandled one.
     */
    fun click(x: Float, y: Float): Boolean = dispatch(ActionTrigger.CLICK, x, y)

    /** `CoreDocument.touchDown`: arms every touch expression and runs any touch-down actions. */
    fun touchDown(x: Float, y: Float): Boolean {
        context.touchDown(x, y, touchExpressions)
        startRipple(x, y)
        return dispatch(ActionTrigger.TOUCH_DOWN, x, y)
    }

    /**
     * `RippleModifierOperation.onTouchDown`: the topmost component with a ripple modifier under
     * the press starts one, from that point in its own coordinates.
     */
    private fun startRipple(x: Float, y: Float) {
        val target = rippleTargets.lastOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
            ?: return
        context.ripples[target.componentId] =
            RemoteContext.Ripple(context.animationTime, x - target.left, y - target.top)
        context.needsRepaint = true
    }

    /** `CoreDocument.touchDrag`: moves the pointer variables so touch expressions follow it. */
    fun touchDrag(x: Float, y: Float) {
        context.touchDrag(x, y)
    }

    /**
     * `CoreDocument.touchUp`: releases every touch expression and runs any touch-up actions.
     *
     * [velocityX] and [velocityY] are how fast the pointer was travelling when it left, in
     * document units per second. A touch expression uses them to keep going — a list carries on
     * under its own momentum and glides to a stop — so a caller with no velocity to report
     * leaves them at zero and the value simply stays where it was put.
     */
    fun touchUp(x: Float, y: Float, velocityX: Float = 0f, velocityY: Float = 0f): Boolean {
        context.touchUp(velocityX, velocityY, touchExpressions)
        return dispatch(ActionTrigger.TOUCH_UP, x, y)
    }

    /** `CoreDocument.touchCancel`: as touchUp, but nothing carries on. */
    fun touchCancel(x: Float, y: Float): Boolean {
        context.touchUp(0f, 0f, touchExpressions)
        return dispatch(ActionTrigger.TOUCH_CANCEL, x, y)
    }

    private fun dispatch(trigger: ActionTrigger, x: Float, y: Float): Boolean {
        val region = hitRegions.lastOrNull { it.contains(x, y) && it.actions.containsKey(trigger) } ?: return false
        for (action in region.actions[trigger].orEmpty()) run(action)
        context.needsRepaint = true
        return true
    }

    /** Runs one action, mirroring the `runAction` of the operation it came from. */
    /**
     * The names this document gave its values, and what kind each is: `NAMED_VARIABLE` is how a
     * document says which of its values a host is expected to fill in.
     */
    val namedValues: Map<String, RemoteContext.NamedValue> get() = context.namedValues

    /**
     * `setNamedFloatOverride`: puts [value] into the float the document named [name].
     *
     * Returns false if the document never named one, or named it as a different kind, so a host
     * pushing a value it has no home for finds out rather than being ignored. The next frame
     * shows it, as any other value change does.
     */
    fun setNamedFloat(name: String, value: Float): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_FLOAT) { context.overrideFloat(it, value) }

    /** `setNamedIntegerOverride`. */
    fun setNamedInteger(name: String, value: Int): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_INT) { context.overrideInteger(it, value) }

    /** `setNamedColorOverride`; the value is ARGB, as every colour on the wire is. */
    fun setNamedColor(name: String, argb: Int): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_COLOR) { context.overrideColorValue(it, argb) }

    /** `setNamedLong`. */
    fun setNamedLong(name: String, value: Long): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_LONG) { context.overrideLong(it, value) }

    /** `setNamedStringOverride`. */
    fun setNamedString(name: String, value: String): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_STRING) { context.overrideTextValue(it, value) }

    private fun run(action: DocumentAction) {
        when (action) {
            is DocumentAction.Host -> context.runHostAction(action.actionId, action.metadata ?: "")
            is DocumentAction.HostNamed -> context.runNamedAction(action.nameId, action.type, action.valueId)
            is DocumentAction.SetFloat -> context.overrideFloat(action.targetId, context.resolveFloat(action.value))
            is DocumentAction.SetInteger -> context.overrideInteger(action.targetId, action.value)
            is DocumentAction.SetText -> context.overrideText(action.targetId, action.sourceId)
            is DocumentAction.SetFloatFromExpression -> evaluateExpressionInto(action.expressionId)?.let {
                context.overrideFloat(action.targetId, it)
            }
            is DocumentAction.SetIntegerFromExpression -> evaluateExpressionInto(action.expressionId)?.let {
                context.overrideInteger(action.targetId, it.toInt())
            }
        }
    }

    /**
     * `CoreDocument.evaluateFloatExpression`: the value of the `FloatExpression` whose id is
     * [expressionId], or of that id's plain pool entry when no expression declares it.
     */
    private fun evaluateExpressionInto(expressionId: Int): Float? {
        val expression = operations.filterIsInstance<Operation.FloatExpression>().firstOrNull { it.id == expressionId }
            ?: return context.getFloat(expressionId).takeUnless { it.isNaN() }
        val resolved = FloatArray(expression.expression.size) { i ->
            val v = expression.expression[i]
            if (FloatExpressionEvaluator.isVariable(v)) context.resolveFloat(v) else v
        }
        return FloatExpressionEvaluator.eval(resolved, collections = context).takeUnless { it.isNaN() }
    }
}
