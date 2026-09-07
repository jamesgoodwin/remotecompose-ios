package com.example.remotecompose.layout

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.runtime.ActionTrigger
import com.example.remotecompose.runtime.DocumentAction

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
    class GraphicsLayer(val attributes: Map<Int, Int>) : Modifier()
    class CollapsiblePriority(val orientation: Int, val priority: Float) : Modifier()

    /**
     * `ScrollModifierOperation`: the component shows a window onto content taller or wider than
     * itself. [direction] 0 scrolls vertically, anything else horizontally, and
     * [positionExpressionId] names the float the offset is read from — the one the modifier's own
     * touch expression drives.
     */
    class Scroll(val direction: Int, val positionExpressionId: Int) : Modifier() {
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

    /** The id of the `AnimationSpec` this component animates by, or -1 for none. */
    var animationId: Int = -1

    /**
     * How long this component takes to move to a new layout, and on what curve, from the
     * `AnimationSpec` among its modifiers. Zero means it simply appears where it now is.
     */
    var motionDuration: Float = 0f
    var motionEasing: Int = 0

    var textId: Int = 0
    var textPaint: PaintStyle? = null
    var textAlign: Int = 1
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
