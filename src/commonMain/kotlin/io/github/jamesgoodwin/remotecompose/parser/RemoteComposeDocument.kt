package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.model.Header
import io.github.jamesgoodwin.remotecompose.model.RemoteDocument
import io.github.jamesgoodwin.remotecompose.model.ShaderSpec
import io.github.jamesgoodwin.remotecompose.runtime.ActionTrigger
import io.github.jamesgoodwin.remotecompose.runtime.DocumentAction
import io.github.jamesgoodwin.remotecompose.runtime.FloatExpressionEvaluator
import io.github.jamesgoodwin.remotecompose.runtime.HitRegion
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsNode
import io.github.jamesgoodwin.remotecompose.text.TextMetricsProvider

/**
 * A loaded `.rc` document: its decoded [operations] plus the [context] they evaluate against.
 * The equivalent of `CoreDocument`: one instance lives as long as the document is shown, each
 * [frame] re-evaluates the time-dependent operations and flattens the result, and the gesture
 * entry points run the action lists the components declare.
 */
public class RemoteComposeDocument internal constructor(
    public val header: Header,
    internal val operations: List<Operation>,
    internal val context: RemoteContext,
    private val textMetrics: TextMetricsProvider,
) {
    /** Decoded lazily after the first frame has collected every `DATA_BITMAP`. */
    private val bitmaps: BitmapPool by lazy { BitmapPool.fromEntries(context.bitmaps) }

    private val touchExpressions: List<Operation.TouchExpression> = operations.filterIsInstance<Operation.TouchExpression>()

    /** Hit rectangles of the most recent [frame], in document coordinates and in paint order. */
    public var hitRegions: List<HitRegion> = emptyList()
        private set

    /**
     * What the most recent [frame] tells a screen reader, as a tree in document coordinates.
     *
     * Empty for a document that carries no `ACCESSIBILITY_SEMANTICS`, which is most of them: the
     * format labels nothing by itself, so this is only what the document was written to say.
     * [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas] turns these into Compose
     * semantics; a host drawing the document some other way can read them here.
     */
    public var semantics: List<SemanticsNode> = emptyList()
        private set

    /** `ROOT_CONTENT_DESCRIPTION`: what the document as a whole is, or null if it does not say. */
    public val contentDescription: String?
        get() = operations.filterIsInstance<Operation.RootContentDescription>().lastOrNull()
            ?.let { context.texts[it.textId] }

    private var rippleTargets: List<io.github.jamesgoodwin.remotecompose.layout.LayoutEngine.RippleTarget> = emptyList()

    /**
     * True after a [frame] that read a time variable, advanced an animation, or followed a
     * gesture that changed a value: the host should schedule another frame.
     */
    public val needsRepaint: Boolean get() = context.needsRepaint

    /**
     * Which of the document's two palettes to paint.
     *
     * A document carries one for each mode, so changing this changes what the next frame draws.
     * The constants a mode declares were skipped while the other mode was showing, so they are
     * applied again on the frame after a change.
     */
    public var dark: Boolean
        get() = paintTheme == RemoteContext.THEME_DARK
        set(value) {
            paintTheme = if (value) RemoteContext.THEME_DARK else RemoteContext.THEME_LIGHT
        }

    /** The mode as the wire format spells it, including [RemoteContext.THEME_UNSPECIFIED]. */
    internal var paintTheme: Int
        get() = context.paintTheme
        set(value) {
            if (context.paintTheme == value) return
            context.paintTheme = value
            context.inflated = false
            context.needsRepaint = true
        }

    /** Called with the action id and metadata of every `HOST_ACTION` the document runs. */
    public var onHostAction: ((Int, String) -> Unit)?
        get() = context.onHostAction
        set(value) { context.onHostAction = value }

    /**
     * `HOST_NAMED_ACTION`: an action the document names with a string rather than a number, and
     * which carries one value with it — a Float, Int, String, FloatArray, or null.
     */
    public var onNamedAction: ((String, Any?) -> Unit)?
        get() = context.onNamedAction
        set(value) { context.onNamedAction = value }

    /**
     * Evaluates the document at wall-clock time [nowMillis] and returns its flattened opcodes.
     * The first call fixes the document's load time, so passing `0` first and `t` next yields an
     * animation time of `t` milliseconds.
     */
    public fun frame(nowMillis: Long): RemoteDocument {
        context.beginFrame(nowMillis)
        val opcodes = RemoteComposeParser.build(operations, context, textMetrics)
        hitRegions = RemoteComposeParser.hitRegions
        semantics = RemoteComposeParser.semantics
        rippleTargets = RemoteComposeParser.rippleTargets
        // `RunActionOperation.paint`: the blocks of the components that were painted, run in the
        // order they were. What they write is read by the frame after this one, which is where
        // painting puts them in the library too.
        for (action in context.paintActions) run(action)
        return RemoteDocument(header, context.texts.toMap(), bitmaps, opcodes, shaderSpecs())
    }

    /**
     * The `DATA_SHADER`s this document declared, with their source text resolved out of the string
     * pool. Cheap to rebuild per frame: a document has a handful of shaders at most, and what
     * costs is compiling one, which the render context does once.
     */
    private fun shaderSpecs(): Map<Int, ShaderSpec> {
        if (context.shaders.isEmpty()) return emptyMap()
        return context.shaders.mapNotNull { (id, data) ->
            val source = context.texts[data.shaderTextId] ?: return@mapNotNull null
            id to ShaderSpec(source, data.floatUniforms, data.intUniforms)
        }.toMap()
    }

    /**
     * `RemoteComposeState.getOpsToUpdate`: how long the host may wait before drawing this
     * document again, in milliseconds — 0 when something has already changed, and -1 when nothing
     * has asked for another frame at all.
     *
     * A `WAKE_IN` is how a document that changes rarely says so, instead of being drawn at every
     * frame. Reading the answer records it, so a later `WAKE_IN` can only bring the wake forward.
     */
    public fun nextRepaintDelayMillis(): Int = context.takeRepaintDelayMillis()

    /**
     * `CoreDocument.onClick`: runs the click actions of the topmost component containing
     * ([x], [y]) in document coordinates. Returns true when a component handled the tap, so the
     * host can decide what to do with an unhandled one.
     */
    public fun click(x: Float, y: Float): Boolean = dispatch(ActionTrigger.CLICK, x, y)

    /** `CoreDocument.touchDown`: arms every touch expression and runs any touch-down actions. */
    public fun touchDown(x: Float, y: Float): Boolean {
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
    public fun touchDrag(x: Float, y: Float) {
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
    public fun touchUp(x: Float, y: Float, velocityX: Float = 0f, velocityY: Float = 0f): Boolean {
        context.touchUp(velocityX, velocityY, touchExpressions)
        return dispatch(ActionTrigger.TOUCH_UP, x, y)
    }

    /** `CoreDocument.touchCancel`: as touchUp, but nothing carries on. */
    public fun touchCancel(x: Float, y: Float): Boolean {
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
    public val namedValues: Map<String, NamedValueKind>
        get() = context.namedValues.mapValues { (_, value) -> NamedValueKind.of(value.type) }

    /** The same names, with the document ids behind them, for the code that resolves them. */
    internal val namedValueEntries: Map<String, RemoteContext.NamedValue> get() = context.namedValues

    /**
     * `setNamedFloatOverride`: puts [value] into the float the document named [name].
     *
     * Returns false if the document never named one, or named it as a different kind, so a host
     * pushing a value it has no home for finds out rather than being ignored. The next frame
     * shows it, as any other value change does.
     */
    public fun setNamedFloat(name: String, value: Float): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_FLOAT) { context.overrideFloat(it, value) }

    /** `setNamedIntegerOverride`. */
    public fun setNamedInteger(name: String, value: Int): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_INT) { context.overrideInteger(it, value) }

    /** `setNamedColorOverride`; the value is ARGB, as every colour on the wire is. */
    public fun setNamedColor(name: String, argb: Int): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_COLOR) { context.overrideColorValue(it, argb) }

    /** `setNamedLong`. */
    public fun setNamedLong(name: String, value: Long): Boolean =
        context.setNamedValue(name, RemoteContext.NAMED_LONG) { context.overrideLong(it, value) }

    /** `setNamedStringOverride`. */
    public fun setNamedString(name: String, value: String): Boolean =
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
