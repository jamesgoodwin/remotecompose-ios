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

    /**
     * True after a [frame] that read a time variable, advanced an animation, or followed a
     * gesture that changed a value: the host should schedule another frame.
     */
    val needsRepaint: Boolean get() = context.needsRepaint

    /** Called with the action id and metadata of every `HOST_ACTION` the document runs. */
    var onHostAction: ((Int, String) -> Unit)?
        get() = context.onHostAction
        set(value) { context.onHostAction = value }

    /**
     * Evaluates the document at wall-clock time [nowMillis] and returns its flattened opcodes.
     * The first call fixes the document's load time, so passing `0` first and `t` next yields an
     * animation time of `t` milliseconds.
     */
    fun frame(nowMillis: Long): RemoteDocument {
        context.beginFrame(nowMillis)
        val opcodes = RemoteComposeParser.build(operations, context, textMetrics)
        hitRegions = RemoteComposeParser.hitRegions
        return RemoteDocument(header, context.texts.toMap(), bitmaps, opcodes)
    }

    /**
     * `CoreDocument.onClick`: runs the click actions of the topmost component containing
     * ([x], [y]) in document coordinates. Returns true when a component handled the tap, so the
     * host can decide what to do with an unhandled one.
     */
    fun click(x: Float, y: Float): Boolean = dispatch(ActionTrigger.CLICK, x, y)

    /** `CoreDocument.touchDown`: arms every touch expression and runs any touch-down actions. */
    fun touchDown(x: Float, y: Float): Boolean {
        context.touchDown(x, y, touchExpressions)
        return dispatch(ActionTrigger.TOUCH_DOWN, x, y)
    }

    /** `CoreDocument.touchDrag`: moves the pointer variables so touch expressions follow it. */
    fun touchDrag(x: Float, y: Float) {
        context.touchDrag(x, y)
    }

    /** `CoreDocument.touchUp`: releases every touch expression and runs any touch-up actions. */
    fun touchUp(x: Float, y: Float): Boolean {
        context.touchUp()
        return dispatch(ActionTrigger.TOUCH_UP, x, y)
    }

    /** `CoreDocument.touchCancel`. */
    fun touchCancel(x: Float, y: Float): Boolean {
        context.touchUp()
        return dispatch(ActionTrigger.TOUCH_CANCEL, x, y)
    }

    private fun dispatch(trigger: ActionTrigger, x: Float, y: Float): Boolean {
        val region = hitRegions.lastOrNull { it.contains(x, y) && it.actions.containsKey(trigger) } ?: return false
        for (action in region.actions[trigger].orEmpty()) run(action)
        context.needsRepaint = true
        return true
    }

    /** Runs one action, mirroring the `runAction` of the operation it came from. */
    private fun run(action: DocumentAction) {
        when (action) {
            is DocumentAction.Host -> context.runHostAction(action.actionId, action.metadata ?: "")
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
