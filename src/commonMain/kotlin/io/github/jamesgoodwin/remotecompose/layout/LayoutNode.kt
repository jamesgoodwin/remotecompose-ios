package io.github.jamesgoodwin.remotecompose.layout

import androidx.compose.ui.graphics.Color
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.text.TextBlock
import io.github.jamesgoodwin.remotecompose.text.TextWrapping
import io.github.jamesgoodwin.remotecompose.runtime.ActionTrigger
import io.github.jamesgoodwin.remotecompose.runtime.DocumentAction

/**
 * `DimensionModifierOperation.Type` in ordinal order (the wire int of `MODIFIER_WIDTH` /
 * `MODIFIER_HEIGHT`).
 */
enum class DimensionType {
    EXACT, FILL, WRAP, WEIGHT, INTRINSIC_MIN, INTRINSIC_MAX, EXACT_DP, FILL_PARENT_MAX_WIDTH, FILL_PARENT_MAX_HEIGHT;

    companion object {
        fun fromWire(value: Int): DimensionType = entries.getOrElse(value) { WRAP }
    }
}

/** A width or height modifier plus any `widthIn`/`heightIn` range attached to it. */
class Dimension(var type: DimensionType = DimensionType.WRAP, var value: Float = Float.NaN) {
    /** From `MODIFIER_WIDTH_IN` etc.; `-1` means unset, as `DimensionInModifierOperation` uses. */
    var rangeMin: Float = -1f
    var rangeMax: Float = -1f

    val isFill: Boolean get() = type == DimensionType.FILL
    val isExact: Boolean get() = type == DimensionType.EXACT || type == DimensionType.EXACT_DP
    val isWrap: Boolean get() = type == DimensionType.WRAP
    val hasWeight: Boolean get() = type == DimensionType.WEIGHT
    val isIntrinsicMin: Boolean get() = type == DimensionType.INTRINSIC_MIN
    val isFillParentMax: Boolean get() =
        type == DimensionType.FILL_PARENT_MAX_WIDTH || type == DimensionType.FILL_PARENT_MAX_HEIGHT
    val hasRange: Boolean get() = rangeMin >= 0f || rangeMax >= 0f
}

/**
 * A component modifier, kept in document order because order matters: a `Padding` translates
 * every modifier and the content after it, and a decorator's box is the component box minus
 * the paddings that precede it (`ComponentModifiers.layout`).
 */
sealed class Modifier {
    class Padding(val left: Float, val top: Float, val right: Float, val bottom: Float) : Modifier()

    /** A modifier that paints or clips a box of its own; sized by [LayoutEngine.layoutModifiers]. */
    sealed class Decorator : Modifier() {
        var width: Float = 0f
        var height: Float = 0f
    }

    /** `shapeType` 0 rectangle, 1 circle inscribed in the box. */
    class Background(val color: Color, val shapeType: Int) : Decorator()
    class Border(val color: Color, val borderWidth: Float, val roundedCorner: Float, val shapeType: Int) : Decorator()
    class ClipRect : Decorator()

    /**
     * `RippleModifierOperation`: a circle spreading from where the component was pressed. It is
     * a decorator, so it paints where it sits among the modifiers and knows the component's size.
     */
    class Ripple : Decorator()
    class RoundedClipRect(val topStart: Float, val topEnd: Float, val bottomStart: Float, val bottomEnd: Float) : Decorator()
    class Offset(val x: Float, val y: Float) : Modifier()
    class ZIndex(val zIndex: Float) : Modifier()
    /**
     * `GraphicsLayerModifierOperation`: each attribute is an `AttributeValue` holding an
     * `AnimatableValue`, evaluated against the paint context every frame — so a float-valued one
     * can be an expression rather than a constant. [floats] are those, already resolved; [ints]
     * are the attributes whose value is a plain int (the shape, the tile mode, a shadow colour).
     */
    class GraphicsLayer(val floats: Map<Int, Float>, val ints: Map<Int, Int>) : Modifier()
    class CollapsiblePriority(val orientation: Int, val priority: Float) : Modifier()

    /**
     * `ScrollModifierOperation`: the component shows a window onto content taller or wider than
     * itself. [direction] 0 scrolls vertically, anything else horizontally, and
     * [positionExpressionId] names the float the offset is read from — the one the modifier's own
     * touch expression drives.
     *
     * [maxId] and [notchMaxId] name floats the other way round: `layout()` writes how far there
     * is to scroll and how big the content is into them, and the touch expression reads [maxId]
     * as its upper bound. Without that write a drag has nothing to stop it.
     */
    /**
     * `MarqueeModifierOperation`: a component whose content is wider than it is slides back and
     * forth so that all of it can be read.
     *
     * `paint` sweeps by a raised sine rather than a constant speed, so the content eases to each
     * end and turns round: at phase 0 it sits at its start, at half a period it is fully over,
     * and at a whole period it is back. The period is the overflow over `density * velocity`.
     */
    class Marquee(
        val initialDelayMillis: Float,
        val spacing: Float,
        val velocity: Float,
    ) : Modifier() {
        /** `mLastTime` and `mStartTime`: when this was first painted, and when it starts moving. */
        var startTimeMillis: Long = 0L
    }

    /**
     * `LayoutComputeOperation`: the document works out this component's own size or place.
     *
     * [run] is the block between the operation and its `ContainerEnd`, which the parser hands
     * over as a closure because running it means walking operations — the measure pass has no
     * other way in. The block reads and writes the dynamic float list [boundsId].
     */
    class LayoutCompute(val type: Int, val boundsId: Int, val run: () -> Unit) : Modifier() {
        companion object {
            const val TYPE_MEASURE = 0
            const val TYPE_POSITION = 1
        }
    }

    class Scroll(
        val direction: Int,
        val positionExpressionId: Int,
        val maxId: Int,
        val notchMaxId: Int,
    ) : Modifier() {
        /** How far the content overflows the window, worked out while measuring. */
        var maxScroll: Float = 0f
    }
}

/** `Component.Visibility` values. */
object Visibility {
    const val GONE = 0
    const val VISIBLE = 1
    const val INVISIBLE = 2
}

/** `RowLayout`/`ColumnLayout`/`BoxLayout` positioning constants. */
object Positioning {
    const val START = 1
    const val CENTER = 2
    const val END = 3
    const val TOP = 4
    const val BOTTOM = 5
    const val SPACE_BETWEEN = 6
    const val SPACE_EVENLY = 7
    const val SPACE_AROUND = 8
}

/**
 * One component of the layout tree, the equivalent of `LayoutComponent` and its `LayoutManager`
 * subclasses: what it is ([kind]), how it is decorated ([modifiers]), what it draws ([draws]),
 * what it contains ([children]) and, after [LayoutEngine.measure] and [LayoutEngine.layout],
 * where it sits ([x], [y], [width], [height]).
 */
class LayoutNode(val kind: Kind) {

    enum class Kind { ROOT, BOX, FIT_BOX, CANVAS, CUSTOM, COLUMN, ROW, COLLAPSIBLE_COLUMN, COLLAPSIBLE_ROW, FLOW, STATE, TEXT, IMAGE }

    var parent: LayoutNode? = null
    val children = mutableListOf<LayoutNode>()

    /** Canvas draw opcodes issued inside this component, in document order, painted before children. */
    val draws = mutableListOf<Opcode>()

    /** Opcodes appended after [draws] when this component's own scope closes (e.g. a `MatrixRestore`). */
    val cleanup = mutableListOf<Opcode>()

    val modifiers = mutableListOf<Modifier>()
    var widthDimension = Dimension()
    var heightDimension = Dimension()

    var horizontalPositioning: Int = Positioning.START
    var verticalPositioning: Int = Positioning.TOP
    var spacedBy: Float = 0f

    /** `FlowLayout`: children per row and rows allowed; `Int.MAX_VALUE` when unlimited. */
    var maxItemsInMainAxis: Int = Int.MAX_VALUE
    var maxLines: Int = Int.MAX_VALUE

    /** `StateLayout`: the id of the int variable selecting the visible child. */
    var stateIndexId: Int = 0

    // TEXT
    /** The id the document gave this component, which is what identifies it between frames. */
    var componentId: Int = 0

    /** `LAYOUT_CONTENT`'s own id: the content box inside this component's padding. */
    var contentComponentId: Int = 0

    /** The id of the `AnimationSpec` this component animates by, or -1 for none. */
    var animationId: Int = -1

    /**
     * How long this component takes to move to a new layout, and on what curve, from the
     * `AnimationSpec` among its modifiers. Zero means it simply appears where it now is.
     */
    var motionDuration: Float = 0f
    var motionEasing: Int = 0

    /**
     * The other half of an `ANIMATION_SPEC`: how long a component takes to come or go, on what
     * curve, and which animation each way. `AnimationSpec.ANIMATION` goes on the wire as its
     * ordinal, so 0 is `FADE_IN` and 1 is `FADE_OUT`.
     */
    var visibilityDuration: Float = 0f
    var visibilityEasing: Int = 0
    var enterAnimation: Int = -1
    var exitAnimation: Int = -1

    /** `AnimateMeasure.getVisibility()`: how far through coming or going this component is. */
    var fadeAlpha: Float = 1f

    var textId: Int = 0
    var textPaint: PaintStyle? = null
    var textAlign: Int = 1

    /** `CoreText.OVERFLOW_*`: what becomes of text that will not fit in the lines it has. */
    var textOverflow: Int = TextWrapping.OVERFLOW_CLIP

    /** `StaticLayout`'s `spacingAdd` and `spacingMult`, from `CoreText`'s line-height parameters. */
    var lineHeightAdd: Float = 0f
    var lineHeightMultiplier: Float = 1f

    /** `CoreText.JUSTIFICATION_MODE_*`, which stretches a broken line to the full width. */
    var justificationMode: Int = 0

    /** The lines this text was broken into, once it has been measured; null while it is one line. */
    internal var textBlock: TextBlock? = null
    internal var textWidth: Float = 0f
    internal var textHeight: Float = 0f

    // IMAGE
    var bitmapId: Int = 0
    var imageScaleType: Int = 6
    var imageAlpha: Float = 1f

    /** Explicit visibility from a `MODIFIER_VISIBILITY`; `Visibility.VISIBLE` otherwise. */
    var visibility: Int = Visibility.VISIBLE

    /** Action lists from `MODIFIER_CLICK` and the `MODIFIER_TOUCH_*` modifiers. */
    val actions = mutableMapOf<ActionTrigger, MutableList<DocumentAction>>()

    /** `RunActionOperation`: actions run every time this component is painted. */
    val paintActions = mutableListOf<DocumentAction>()

    var zIndex: Float = 0f
    var paddingLeft: Float = 0f
    var paddingTop: Float = 0f
    var paddingRight: Float = 0f
    var paddingBottom: Float = 0f

    var x: Float = 0f
    var y: Float = 0f
    var width: Float = 0f
    var height: Float = 0f

    val isGone: Boolean get() = visibility == Visibility.GONE
    val isLinear: Boolean get() = kind == Kind.COLUMN || kind == Kind.ROW ||
        kind == Kind.COLLAPSIBLE_COLUMN || kind == Kind.COLLAPSIBLE_ROW || kind == Kind.FLOW
    val isVertical: Boolean get() = kind == Kind.COLUMN || kind == Kind.COLLAPSIBLE_COLUMN

    fun addChild(child: LayoutNode) {
        child.parent = this
        children += child
    }

    /** `LayoutComponent.updatePadding`: the sum of every `Padding` modifier. */
    fun updatePadding() {
        paddingLeft = 0f; paddingTop = 0f; paddingRight = 0f; paddingBottom = 0f
        for (m in modifiers) {
            if (m is Modifier.Padding) {
                paddingLeft += m.left; paddingTop += m.top; paddingRight += m.right; paddingBottom += m.bottom
            }
        }
        zIndex = modifiers.filterIsInstance<Modifier.ZIndex>().lastOrNull()?.zIndex ?: 0f
    }
}
