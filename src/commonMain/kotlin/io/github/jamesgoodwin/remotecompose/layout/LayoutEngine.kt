package io.github.jamesgoodwin.remotecompose.layout

import androidx.compose.ui.graphics.Color
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.model.PaintStyleKind
import io.github.jamesgoodwin.remotecompose.model.PathCommand
import io.github.jamesgoodwin.remotecompose.runtime.CubicEasing
import io.github.jamesgoodwin.remotecompose.runtime.HitRegion
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import io.github.jamesgoodwin.remotecompose.text.TextMetricsProvider
import io.github.jamesgoodwin.remotecompose.text.TextBlock
import io.github.jamesgoodwin.remotecompose.text.TextWrapping
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Measure, layout and paint for a [LayoutNode] tree: a transcription of remote-core's
 * `EnforceConstraintsMeasurePolicy.measure`, the `ColumnLayout`/`RowLayout`/`BoxLayout`/
 * `FlowLayout`/`CollapsibleRowLayout`/`StateLayout`/`TextLayout`/`ImageLayout` managers, and
 * `LayoutComponent.internalPaintingComponent` with `ComponentModifiers.paint`.
 *
 * Constraints are `(minWidth, maxWidth, minHeight, maxHeight)` in document pixels. Children of a
 * component are positioned relative to its padded content origin; painting translates by the
 * component's `(x, y)` and again by its padding, exactly as the real player does.
 */
internal class LayoutEngine(private val context: RemoteContext, private val textMetrics: TextMetricsProvider) {

    private class Size(var width: Float = 0f, var height: Float = 0f)

    private val unbounded = Float.MAX_VALUE

    /** `mAnimateRippleDuration`, in the seconds this renderer counts animation time in. */
    private val RIPPLE_SECONDS = 1f
    private val RIPPLE_EASING = CubicEasing.preset(CubicEasing.CUBIC_STANDARD)

    // ---------------------------------------------------------------- measure

    /** `RootLayoutComponent.measure`: the root is the window; each child gets its full constraints. */
    fun measureRoot(root: LayoutNode, windowWidth: Float, windowHeight: Float) {
        root.x = 0f; root.y = 0f
        root.width = windowWidth; root.height = windowHeight
        for (child in root.children) {
            resolveVisibility(child)
            measure(child, 0f, windowWidth, 0f, windowHeight)
            child.x = 0f; child.y = 0f
        }
    }

    private fun resolveVisibility(node: LayoutNode) {
        // Visibility modifiers reach the component through updateVariables; nothing to recompute
        // here since the tree builder already resolved the int variable.
        if (node.kind == LayoutNode.Kind.STATE) {
            val index = context.ints[node.stateIndexId] ?: 0
            node.children.forEachIndexed { i, child ->
                if (i != index) child.visibility = Visibility.GONE
            }
        }
    }

    /**
     * `BaseModernMeasurePolicy.measure` with `shouldApplyInsetWrap` and `shouldEnforceConstraints`
     * both true (the `EnforceConstraintsMeasurePolicy` that alpha18 documents select).
     */
    fun measure(node: LayoutNode, minWidthIn: Float, maxWidthIn: Float, minHeightIn: Float, maxHeightIn: Float) {
        var minW = minWidthIn; var maxW = maxWidthIn; var minH = minHeightIn; var maxH = maxHeightIn
        node.updatePadding()
        resolveVisibility(node)
        val wd = node.widthDimension
        val hd = node.heightDimension
        var w = min(maxW, modifierDefinedWidth(node))
        var h = min(maxH, modifierDefinedHeight(node))
        if (wd.hasRange) {
            if (wd.rangeMin >= 0f) minW = max(minW, wd.rangeMin)
            if (wd.rangeMax >= 0f) maxW = min(maxW, wd.rangeMax)
        }
        if (hd.hasRange) {
            if (hd.rangeMin >= 0f) minH = max(minH, hd.rangeMin)
            if (hd.rangeMax >= 0f) maxH = min(maxH, hd.rangeMax)
        }
        var insetMaxW = maxW - node.paddingLeft - node.paddingRight
        var insetMaxH = maxH - node.paddingTop - node.paddingBottom
        var hasWrapW = false
        var hasWrapH = false

        when {
            wd.isFill -> {
                val v = wd.value
                if (v.isNaN() || wd.isExact) { w = maxW; minW = insetMaxW } else { w = maxW * v; minW = w - node.paddingLeft - node.paddingRight }
            }
            wd.isFillParentMax -> {
                val v = if (wd.value.isNaN()) 1f else wd.value
                w = context.windowWidth * v
                minW = w - node.paddingLeft - node.paddingRight
            }
            wd.hasWeight -> w = max(w, modifierDefinedWidth(node))
            else -> {
                w = max(w, minW); w = min(w, maxW)
                hasWrapW = wd.isWrap || wd.isIntrinsicMin
                if (!hasWrapW) insetMaxW = w - node.paddingLeft - node.paddingRight
            }
        }
        when {
            hd.isFill -> {
                val v = hd.value
                if (v.isNaN() || hd.isExact) { h = maxH; minH = insetMaxH } else { h = maxH * v; minH = h - node.paddingTop - node.paddingBottom }
            }
            hd.isFillParentMax -> {
                val v = if (hd.value.isNaN()) 1f else hd.value
                h = context.windowHeight * v
                minH = h - node.paddingTop - node.paddingBottom
            }
            hd.hasWeight -> h = max(h, modifierDefinedHeight(node))
            else -> {
                h = max(h, minH); h = min(h, maxH)
                hasWrapH = hd.isWrap || hd.isIntrinsicMin
                if (!hasWrapH) insetMaxH = h - node.paddingTop - node.paddingBottom
            }
        }
        // shouldEnforceConstraints
        w = min(max(w, minW), maxW)
        h = min(max(h, minH), maxH)
        if (minW == maxW) w = maxW
        if (minH == maxH) h = maxH

        val scroll = node.modifiers.filterIsInstance<Modifier.Scroll>().firstOrNull()
        if (hasWrapW || hasWrapH) {
            val size = Size()
            computeWrapSize(node, minW, insetMaxW, minH, insetMaxH, wd.isWrap, hd.isWrap, size)
            if (hasWrapW) w = max(size.width + node.paddingLeft + node.paddingRight, minW)
            if (hasWrapH) h = max(size.height + node.paddingTop + node.paddingBottom, minH)
        } else if (scroll != null) {
            // A scrolling component measures its content as if it had all the room it wants, so
            // that the content can be larger than the window it is shown through; how much
            // larger is what there is to scroll.
            val contentW = w - node.paddingLeft - node.paddingRight
            val contentH = h - node.paddingTop - node.paddingBottom
            val vertical = scroll.direction == 0
            computeSize(
                node,
                0f, if (vertical) contentW else unbounded,
                0f, if (vertical) unbounded else contentH,
            )
        } else {
            computeSize(
                node, 0f, w - node.paddingLeft - node.paddingRight,
                0f, h - node.paddingTop - node.paddingBottom,
            )
        }
        w = max(w, minW); h = max(h, minH)
        w = min(w, maxW); h = min(h, maxH)
        node.width = w
        node.height = h
        applyLayoutCompute(node, Modifier.LayoutCompute.TYPE_MEASURE, maxWidthIn, maxHeightIn)
        internalLayout(node)
        // A position is only settled once the parent has placed its children, so a component's
        // own `TYPE_POSITION` block is run from here rather than from its own measure — and here
        // the parent's size is known, where at the child's measure it is not yet.
        for (child in node.children) {
            applyLayoutCompute(child, Modifier.LayoutCompute.TYPE_POSITION, node.width, node.height)
        }
        if (scroll != null) {
            // How far the content runs past the window, which is only known once the children
            // have been placed: measuring them says how big each is, not where it ends up.
            val vertical = scroll.direction == 0
            val extent = node.children.filterNot { it.isGone }.maxOfOrNull {
                if (vertical) it.y + it.height else it.x + it.width
            } ?: 0f
            val window = if (vertical) h - node.paddingTop - node.paddingBottom else w - node.paddingLeft - node.paddingRight
            // setVerticalScrollDimension(host, content): max(0, content - host).
            scroll.maxScroll = max(0f, extent - window)
            // ScrollModifierOperation.layout(): the bound the touch expression clamps its drag
            // to, which is what stops the list at its ends, and the content size beside it. The
            // last child's own position is the other bound, so a tall last item can still be
            // scrolled to its top rather than past it (getMaxScrollPosition).
            val lastChildPosition = node.children.lastOrNull()?.let { if (vertical) it.y else it.x } ?: 0f
            val limit =
                if (lastChildPosition > 0f) min(lastChildPosition, scroll.maxScroll) else scroll.maxScroll
            context.loadFloat(scroll.maxId, limit)
            context.loadFloat(scroll.notchMaxId, extent)
        }
    }

    /** `LayoutComponent.computeModifierDefinedWidth(context, false)`: paddings plus the exact width, or MAX for fill. */
    private fun modifierDefinedWidth(node: LayoutNode): Float {
        val d = node.widthDimension
        var value = 0f
        if (d.isExact) value = d.value
        else if (d.isFill || d.type == DimensionType.FILL_PARENT_MAX_WIDTH) value = unbounded
        if (d.rangeMin >= 0f) value = max(value, d.rangeMin)
        return node.paddingLeft + value + node.paddingRight
    }

    private fun modifierDefinedHeight(node: LayoutNode): Float {
        val d = node.heightDimension
        var value = 0f
        if (d.isExact) value = d.value
        else if (d.isFill || d.type == DimensionType.FILL_PARENT_MAX_HEIGHT) value = unbounded
        if (d.rangeMin >= 0f) value = max(value, d.rangeMin)
        return node.paddingTop + value + node.paddingBottom
    }

    /**
     * `LayoutComputeOperation.applyToMeasure`: `[x, y, width, height, parentWidth, parentHeight]`
     * go into the dynamic float list the operation names, the block runs over them, and what it
     * wrote back is read out — the width and height for `TYPE_MEASURE`, the x and y for
     * `TYPE_POSITION`, both for anything else.
     *
     * The library hands the block its parent's own `ComponentMeasure`. This measure pass has no
     * such record: a parent's size is not settled until after its children are measured, so
     * `TYPE_MEASURE` is given the room the parent offered instead, which is the parent's content
     * box wherever the parent has a size of its own. `TYPE_POSITION` runs late enough to be given
     * the real thing.
     */
    private fun applyLayoutCompute(node: LayoutNode, type: Int, parentWidth: Float, parentHeight: Float) {
        val compute = node.modifiers.filterIsInstance<Modifier.LayoutCompute>()
            .firstOrNull { it.type == type } ?: return
        val bounds = context.floatLists[compute.boundsId] ?: return
        if (bounds.size < 6) return
        bounds[0] = node.x
        bounds[1] = node.y
        bounds[2] = node.width
        bounds[3] = node.height
        bounds[4] = if (parentWidth == unbounded) 0f else parentWidth
        bounds[5] = if (parentHeight == unbounded) 0f else parentHeight
        compute.run()
        val after = context.floatLists[compute.boundsId] ?: return
        if (after.size < 6) return
        if (type != Modifier.LayoutCompute.TYPE_POSITION) {
            node.width = after[2]
            node.height = after[3]
        }
        if (type != Modifier.LayoutCompute.TYPE_MEASURE) {
            node.x = after[0]
            node.y = after[1]
        }
    }

    /**
     * `Component.mX`/`mY`/`mWidth`/`mHeight` and `getLocationInWindow`, by component id, for the
     * `COMPONENT_VALUE`s of the next frame to read.
     */
    fun collectComponentBounds(
        node: LayoutNode,
        originX: Float = 0f,
        originY: Float = 0f,
        out: MutableMap<Int, FloatArray> = mutableMapOf(),
    ): Map<Int, FloatArray> {
        val x = originX + node.x
        val y = originY + node.y
        if (node.componentId != 0) {
            out[node.componentId] = floatArrayOf(node.x, node.y, node.width, node.height, x, y)
        }
        if (node.contentComponentId != 0) {
            val contentW = node.width - node.paddingLeft - node.paddingRight
            val contentH = node.height - node.paddingTop - node.paddingBottom
            out[node.contentComponentId] = floatArrayOf(
                node.paddingLeft, node.paddingTop, contentW, contentH,
                x + node.paddingLeft, y + node.paddingTop,
            )
        }
        for (child in node.children) collectComponentBounds(child, x + node.paddingLeft, y + node.paddingTop, out)
        return out
    }

    // --------------------------------------------------- wrap size (intrinsic)    // --------------------------------------------------- wrap size (intrinsic)

    private fun computeWrapSize(
        node: LayoutNode, minW: Float, maxW: Float, minH: Float, maxH: Float,
        wrapW: Boolean, wrapH: Boolean, size: Size,
    ) {
        when (node.kind) {
            LayoutNode.Kind.TEXT -> {
                // `TextLayout.computeWrapSize`: one measurement of the whole string, and then the
                // complex path when that will not do — the string breaks itself, or it is wider
                // than the room and may take more than one line.
                val text = context.texts[node.textId] ?: ""
                val paint = node.textPaint ?: DEFAULT_TEXT_PAINT
                val metrics = textMetrics.measure(text, paint)
                val block = if (TextWrapping.needsLayout(text, metrics.width, maxW, node.maxLines, node.textOverflow)) {
                    TextWrapping.layout(
                        text = text, paint = paint, metrics = textMetrics, maxWidth = maxW,
                        maxLines = node.maxLines, overflow = node.textOverflow,
                        lineHeightAdd = node.lineHeightAdd,
                        lineHeightMultiplier = node.lineHeightMultiplier,
                    )
                } else {
                    null
                }
                node.textBlock = block
                node.textWidth = block?.width ?: metrics.width
                node.textHeight = block?.height ?: metrics.height
                size.width = min(maxW, node.textWidth)
                size.height = min(maxH, node.textHeight)
            }
            LayoutNode.Kind.IMAGE -> {
                context.bitmaps[node.bitmapId]?.let { pngNaturalSize(it) }?.let {
                    size.width = it[0].toFloat()
                    size.height = it[1].toFloat()
                }
            }
            LayoutNode.Kind.COLUMN -> columnWrapSize(node, visibleChildren(node), maxW, maxH, size)
            LayoutNode.Kind.ROW -> rowWrapSize(node, visibleChildren(node), maxW, maxH, size)
            LayoutNode.Kind.COLLAPSIBLE_COLUMN -> {
                columnWrapSize(node, visibleChildren(node), maxW, maxH, size)
                collapse(node, vertical = true, available = maxH)
                columnWrapSize(node, visibleChildren(node), maxW, maxH, size)
            }
            LayoutNode.Kind.COLLAPSIBLE_ROW -> {
                rowWrapSize(node, visibleChildren(node), maxW, maxH, size)
                collapse(node, vertical = false, available = maxW)
                rowWrapSize(node, visibleChildren(node), maxW, maxH, size)
            }
            LayoutNode.Kind.FLOW -> flowWrapSize(node, maxW, maxH, size)
            LayoutNode.Kind.STATE -> {
                val index = context.ints[node.stateIndexId] ?: 0
                node.children.getOrNull(index)?.let { child ->
                    measure(child, 0f, maxW, 0f, maxH)
                    size.width = child.width; size.height = child.height
                }
            }
            LayoutNode.Kind.FIT_BOX -> {
                chooseFittingChild(node, 0f, maxW, 0f, maxH)
                for (child in node.children) {
                    if (child.isGone) continue
                    size.width = max(size.width, child.width)
                    size.height = max(size.height, child.height)
                }
            }
            else -> { // BOX, CANVAS, CUSTOM, ROOT
                for (child in node.children) {
                    measure(child, 0f, maxW, 0f, maxH)
                    if (child.isGone) continue
                    size.width = max(size.width, child.width)
                    size.height = max(size.height, child.height)
                }
            }
        }
    }

    /**
     * `FitBoxLayout.computeSize`: the first child that fits is shown and the rest are hidden, so a
     * document can carry several versions of the same thing and let the room decide.
     *
     * Nothing here is scaled — the name is about choosing, not fitting. What a version needs is
     * the minimum of its `MODIFIER_WIDTH_IN`/`MODIFIER_HEIGHT_IN`, which is what
     * `computeSizeOriginal` compares; a version that declares none needs nothing and always fits.
     * The measured size is checked as well, as both of the library's branches do.
     *
     * `computeSizePriorityFix`, the branch a document takes unless it turns feature 23 off, tests
     * `minIntrinsicWidth` first. That reads what an earlier measure pass left, and this renderer
     * measures once, so it is not applied — `docs/OPCODES.md` says so.
     */
    private fun chooseFittingChild(node: LayoutNode, minW: Float, maxW: Float, minH: Float, maxH: Float) {
        var chosen = false
        for (child in node.children) {
            if (chosen) {
                child.visibility = Visibility.GONE
                continue
            }
            measure(child, minW, maxW, minH, maxH)
            val needsW = child.widthDimension.rangeMin.takeIf { it >= 0f } ?: 0f
            val needsH = child.heightDimension.rangeMin.takeIf { it >= 0f } ?: 0f
            val fits = needsW <= maxW && needsH <= maxH && child.width <= maxW && child.height <= maxH
            child.visibility = if (fits) Visibility.VISIBLE else Visibility.GONE
            chosen = chosen || fits
        }
        // A box with nothing it can show is not an empty box: it is not there.
        if (!chosen && node.children.isNotEmpty()) node.visibility = Visibility.GONE
    }

    private fun visibleChildren(node: LayoutNode): List<LayoutNode> = node.children.filter { !it.isGone }

    /** `ColumnLayout.computeWrapSize`: unweighted children first, then weighted ones share what is left. */
    private fun columnWrapSize(node: LayoutNode, children: List<LayoutNode>, maxW: Float, maxH: Float, size: Size) {
        size.width = 0f; size.height = 0f
        var remaining = maxH
        var totalWeight = 0f
        var count = 0
        for (child in children) if (child.heightDimension.hasWeight) totalWeight += child.heightDimension.value
        for (child in children) {
            if (child.heightDimension.hasWeight && totalWeight > 0f) continue
            measure(child, 0f, maxW, 0f, remaining)
            if (child.isGone) continue
            size.width = max(size.width, child.width)
            size.height += child.height
            count++
            remaining -= child.height
        }
        if (totalWeight > 0f) {
            val pool = remaining
            for (child in children) {
                if (!child.heightDimension.hasWeight) continue
                val share = child.heightDimension.value * pool / totalWeight
                measure(child, 0f, maxW, share, share)
                if (child.isGone) continue
                size.width = max(size.width, child.width)
                size.height += child.height
                count++
            }
        }
        if (count > 0) size.height += node.spacedBy * (count - 1)
    }

    private fun rowWrapSize(node: LayoutNode, children: List<LayoutNode>, maxW: Float, maxH: Float, size: Size) {
        size.width = 0f; size.height = 0f
        var remaining = maxW
        var totalWeight = 0f
        var count = 0
        for (child in children) if (child.widthDimension.hasWeight) totalWeight += child.widthDimension.value
        for (child in children) {
            if (child.widthDimension.hasWeight && totalWeight > 0f) continue
            measure(child, 0f, remaining, 0f, maxH)
            if (child.isGone) continue
            size.height = max(size.height, child.height)
            size.width += child.width
            count++
            remaining -= child.width
        }
        if (totalWeight > 0f) {
            val pool = remaining
            for (child in children) {
                if (!child.widthDimension.hasWeight) continue
                val share = child.widthDimension.value * pool / totalWeight
                measure(child, share, share, 0f, maxH)
                if (child.isGone) continue
                size.height = max(size.height, child.height)
                size.width += child.width
                count++
            }
        }
        if (count > 0) size.width += node.spacedBy * (count - 1)
    }

    /**
     * `CollapsibleRowLayout.computeVisibleChildren`: when the children overflow, hide them from
     * the lowest `CollapsiblePriority` up until the rest fit. A child without a priority for this
     * orientation never collapses.
     */
    private fun collapse(node: LayoutNode, vertical: Boolean, available: Float) {
        val children = visibleChildren(node)
        val orientation = if (vertical) 1 else 0
        fun priority(child: LayoutNode): Float = child.modifiers.filterIsInstance<Modifier.CollapsiblePriority>()
            .firstOrNull { it.orientation == orientation }?.priority ?: Float.MAX_VALUE
        var used = 0f
        for (child in children.sortedByDescending { priority(it) }) {
            val extent = if (vertical) child.height else child.width
            if (used + extent > available) child.visibility = Visibility.GONE else used += extent
        }
    }

    /** `FlowLayout`: rows of at most `maxItemsInMainAxis` children that fit in the width, at most `maxLines` rows. */
    private fun flowRows(node: LayoutNode, maxW: Float, maxH: Float): List<List<LayoutNode>> {
        val rows = mutableListOf<MutableList<LayoutNode>>()
        var current = mutableListOf<LayoutNode>()
        var rowWidth = 0f
        for (child in node.children) {
            measure(child, 0f, maxW, 0f, maxH)
            if (child.isGone) continue
            val needed = if (current.isEmpty()) child.width else rowWidth + node.spacedBy + child.width
            if (current.isNotEmpty() && (needed > maxW || current.size >= node.maxItemsInMainAxis)) {
                rows += current
                current = mutableListOf()
                rowWidth = 0f
            }
            rowWidth = if (current.isEmpty()) child.width else rowWidth + node.spacedBy + child.width
            current += child
        }
        if (current.isNotEmpty()) rows += current
        if (rows.size > node.maxLines) {
            for (row in rows.drop(node.maxLines)) for (child in row) child.visibility = Visibility.GONE
            return rows.take(node.maxLines)
        }
        return rows
    }

    private fun flowWrapSize(node: LayoutNode, maxW: Float, maxH: Float, size: Size) {
        size.width = 0f; size.height = 0f
        val rows = flowRows(node, maxW, maxH)
        for ((i, row) in rows.withIndex()) {
            val rowWidth = row.sumOf { it.width.toDouble() }.toFloat() + node.spacedBy * (row.size - 1)
            val rowHeight = row.maxOf { it.height }
            size.width = max(size.width, rowWidth)
            size.height += rowHeight + if (i > 0) node.spacedBy else 0f
        }
    }

    // ------------------------------------------------ exact size (given box)

    /** `LayoutManager.computeSize` overrides: measure children inside an already-decided content box. */
    private fun computeSize(node: LayoutNode, minW: Float, maxW: Float, minH: Float, maxH: Float) {
        when (node.kind) {
            LayoutNode.Kind.TEXT, LayoutNode.Kind.IMAGE -> computeWrapSize(node, minW, maxW, minH, maxH, true, true, Size())
            LayoutNode.Kind.COLUMN, LayoutNode.Kind.COLLAPSIBLE_COLUMN -> {
                val children = visibleChildren(node)
                var remaining = maxH
                var used = 0f
                var totalWeight = 0f
                for (child in children) if (child.heightDimension.hasWeight) totalWeight += child.heightDimension.value
                for (child in children) {
                    if (child.heightDimension.hasWeight && totalWeight > 0f) continue
                    measure(child, minW, maxW, minH, remaining)
                    if (child.isGone) continue
                    remaining -= child.height
                    used += child.height
                }
                if (totalWeight > 0f) {
                    for (child in children) {
                        if (!child.heightDimension.hasWeight) continue
                        val share = (maxH - used) * child.heightDimension.value / totalWeight
                        measure(child, minW, maxW, share, share)
                    }
                }
                if (node.kind == LayoutNode.Kind.COLLAPSIBLE_COLUMN) collapse(node, vertical = true, available = maxH)
            }
            LayoutNode.Kind.ROW, LayoutNode.Kind.COLLAPSIBLE_ROW -> {
                val children = visibleChildren(node)
                var remaining = maxW
                var used = 0f
                var totalWeight = 0f
                for (child in children) if (child.widthDimension.hasWeight) totalWeight += child.widthDimension.value
                for (child in children) {
                    if (child.widthDimension.hasWeight && totalWeight > 0f) continue
                    measure(child, minW, remaining, minH, maxH)
                    if (child.isGone) continue
                    remaining -= child.width
                    used += child.width
                }
                if (totalWeight > 0f) {
                    for (child in children) {
                        if (!child.widthDimension.hasWeight) continue
                        val share = (maxW - used) * child.widthDimension.value / totalWeight
                        measure(child, share, share, minH, maxH)
                    }
                }
                if (node.kind == LayoutNode.Kind.COLLAPSIBLE_ROW) collapse(node, vertical = false, available = maxW)
            }
            LayoutNode.Kind.FLOW -> flowRows(node, maxW, maxH)
            LayoutNode.Kind.STATE -> {
                val index = context.ints[node.stateIndexId] ?: 0
                node.children.getOrNull(index)?.let { measure(it, minW, maxW, minH, maxH) }
            }
            LayoutNode.Kind.FIT_BOX -> chooseFittingChild(node, minW, maxW, minH, maxH)
            else -> for (child in node.children) measure(child, minW, maxW, minH, maxH)
        }
    }

    // ---------------------------------------------------------------- layout

    /** `internalLayoutMeasure`: position children inside the padded content box. */
    private fun internalLayout(node: LayoutNode) {
        val contentW = node.width - node.paddingLeft - node.paddingRight
        val contentH = node.height - node.paddingTop - node.paddingBottom
        when (node.kind) {
            LayoutNode.Kind.COLUMN, LayoutNode.Kind.COLLAPSIBLE_COLUMN ->
                layoutColumn(node, visibleChildren(node), contentW, contentH, 0f)
            LayoutNode.Kind.ROW, LayoutNode.Kind.COLLAPSIBLE_ROW ->
                layoutRow(node, visibleChildren(node), contentW, contentH, 0f)
            LayoutNode.Kind.FLOW -> {
                // Rows were decided during measure; re-derive them from the current visible set.
                var y = 0f
                var row = mutableListOf<LayoutNode>()
                var rowWidth = 0f
                fun flush() {
                    if (row.isEmpty()) return
                    val rowHeight = row.maxOf { it.height }
                    layoutRow(node, row, contentW, rowHeight, y)
                    y += rowHeight + node.spacedBy
                    row = mutableListOf(); rowWidth = 0f
                }
                for (child in visibleChildren(node)) {
                    val needed = if (row.isEmpty()) child.width else rowWidth + node.spacedBy + child.width
                    if (row.isNotEmpty() && (needed > contentW || row.size >= node.maxItemsInMainAxis)) flush()
                    rowWidth = if (row.isEmpty()) child.width else rowWidth + node.spacedBy + child.width
                    row += child
                }
                flush()
            }
            LayoutNode.Kind.TEXT, LayoutNode.Kind.IMAGE -> Unit
            else -> for (child in node.children) { // BOX and friends: 2D alignment per child
                child.y = when (node.verticalPositioning) {
                    Positioning.CENTER -> (contentH - child.height) / 2f
                    Positioning.BOTTOM -> contentH - child.height
                    else -> 0f
                }
                child.x = when (node.horizontalPositioning) {
                    Positioning.CENTER -> (contentW - child.width) / 2f
                    Positioning.END -> contentW - child.width
                    else -> 0f
                }
            }
        }
    }

    /** `ColumnLayout.internalLayoutMeasure`'s positioning pass. */
    private fun layoutColumn(node: LayoutNode, children: List<LayoutNode>, contentW: Float, contentH: Float, startY: Float) {
        if (children.isEmpty()) return
        val count = children.size
        val sumH = children.sumOf { it.height.toDouble() }.toFloat()
        val total = sumH + node.spacedBy * (count - 1)
        var y = startY
        var gap = 0f
        when (node.verticalPositioning) {
            Positioning.CENTER -> y += (contentH - total) / 2f
            Positioning.BOTTOM -> y += contentH - total
            Positioning.SPACE_BETWEEN -> if (count > 1) gap = (contentH - sumH) / (count - 1) else y += (contentH - total) / 2f
            Positioning.SPACE_EVENLY -> { gap = (contentH - sumH) / (count + 1); y += gap }
            Positioning.SPACE_AROUND -> { gap = (contentH - sumH) / count; y += gap / 2f }
        }
        val spaced = node.verticalPositioning == Positioning.SPACE_BETWEEN ||
            node.verticalPositioning == Positioning.SPACE_EVENLY || node.verticalPositioning == Positioning.SPACE_AROUND
        for (child in children) {
            child.x = when (node.horizontalPositioning) {
                Positioning.CENTER -> (contentW - child.width) / 2f
                Positioning.END -> contentW - child.width
                else -> 0f
            }
            child.y = y
            y += child.height
            if (spaced) y += gap
            y += node.spacedBy
        }
    }

    /** `RowLayout.internalLayoutMeasure`'s positioning pass; [startY] offsets a flow row. */
    private fun layoutRow(node: LayoutNode, children: List<LayoutNode>, contentW: Float, contentH: Float, startY: Float) {
        if (children.isEmpty()) return
        val count = children.size
        val sumW = children.sumOf { it.width.toDouble() }.toFloat()
        val total = sumW + node.spacedBy * (count - 1)
        var x = 0f
        var gap = 0f
        when (node.horizontalPositioning) {
            Positioning.CENTER -> x += (contentW - total) / 2f
            Positioning.END -> x += contentW - total
            Positioning.SPACE_BETWEEN -> if (count > 1) gap = (contentW - sumW) / (count - 1) else x += (contentW - total) / 2f
            Positioning.SPACE_EVENLY -> { gap = (contentW - sumW) / (count + 1); x += gap }
            Positioning.SPACE_AROUND -> { gap = (contentW - sumW) / count; x += gap / 2f }
        }
        val spaced = node.horizontalPositioning == Positioning.SPACE_BETWEEN ||
            node.horizontalPositioning == Positioning.SPACE_EVENLY || node.horizontalPositioning == Positioning.SPACE_AROUND
        for (child in children) {
            child.y = startY + when (node.verticalPositioning) {
                Positioning.CENTER -> (contentH - child.height) / 2f
                Positioning.BOTTOM -> contentH - child.height
                else -> 0f
            }
            child.x = x
            x += child.width
            if (spaced) x += gap
            x += node.spacedBy
        }
    }

    // ---------------------------------------------------------------- paint

    /** `ComponentModifiers.layout`: each decorator's box is the component minus the paddings before it. */
    private fun layoutModifiers(node: LayoutNode) {
        var w = node.width
        var h = node.height
        for (m in node.modifiers) {
            when (m) {
                is Modifier.Decorator -> { m.width = w; m.height = h }
                is Modifier.Padding -> { w -= m.left + m.right; h -= m.top + m.bottom }
                else -> Unit
            }
        }
    }

    /**
     * `LayoutComponent.internalPaintingComponent`: translate to the component, apply its graphics
     * layer, paint modifiers in order (paddings translating as they go), translate by the total
     * padding, paint the canvas content, then the children in z order.
     */
    fun paint(node: LayoutNode, out: MutableList<Opcode>) {
        if (node.isGone || node.visibility == Visibility.INVISIBLE) return
        // `RunActionOperation.paint`: run because this component was painted, so a component that
        // was not — gone, or in the branch of a conditional that did not hold — runs nothing.
        if (node.paintActions.isNotEmpty()) context.paintActions += node.paintActions
        layoutModifiers(node)
        out += Opcode.MatrixSave
        out += Opcode.Translate(node.x, node.y)
        var layerRestores = 0
        // `AnimateMeasure.getVisibility()`: a component part way in or out is drawn faded, which
        // is the same compositing layer a graphics-layer alpha uses.
        if (node.fadeAlpha < 1f) {
            out += Opcode.SaveLayerAlpha(node.fadeAlpha)
            layerRestores++
        }
        node.modifiers.filterIsInstance<Modifier.GraphicsLayer>().firstOrNull()?.let { layer ->
            layerRestores += paintGraphicsLayer(node, layer, out)
        }
        var px = 0f
        var py = 0f
        for (m in node.modifiers) {
            when (m) {
                is Modifier.Padding -> {
                    out += Opcode.Translate(m.left, m.top)
                    px += m.left; py += m.top
                }
                is Modifier.Background -> {
                    val paint = PaintStyle(m.color, PaintStyleKind.FILL)
                    if (m.shapeType == 1) {
                        val r = min(m.width, m.height) / 2f
                        out += Opcode.DrawCircle(m.width / 2f, m.height / 2f, r, paint)
                    } else {
                        out += Opcode.DrawRect(0f, 0f, m.width, m.height, paint)
                    }
                }
                is Modifier.Border -> paintBorder(m, out)
                is Modifier.Ripple -> paintRipple(node, m, out)
                is Modifier.ClipRect -> out += Opcode.ClipRect(0f, 0f, m.width, m.height)
                is Modifier.RoundedClipRect -> out += Opcode.ClipPath(
                    roundedRectPath(0f, 0f, m.width, m.height, m.topStart, m.topEnd, m.bottomStart, m.bottomEnd),
                )
                is Modifier.Offset -> out += Opcode.Translate(m.x, m.y)
                else -> Unit
            }
        }
        if (px != 0f || py != 0f) out += Opcode.Translate(-px, -py)
        out += Opcode.Translate(node.paddingLeft, node.paddingTop)
        // ScrollModifierOperation.paint(): the offset is the position variable its touch
        // expression drives, clamped to what there is to scroll, and applied the other way —
        // scrolling down moves the content up. The window clips what falls outside it.
        var scrollRestores = 0
        node.modifiers.filterIsInstance<Modifier.Scroll>().firstOrNull()?.let { scroll ->
            val contentW = node.width - node.paddingLeft - node.paddingRight
            val contentH = node.height - node.paddingTop - node.paddingBottom
            val position = context.getFloat(scroll.positionExpressionId).takeUnless { it.isNaN() } ?: 0f
            // `mScrollY = -min(mMaxScrollY, position)`: only the upper bound here, the lower one
            // being the touch expression's own `min`, which it clamps the value to as it drags.
            val offset = min(position, scroll.maxScroll)
            out += Opcode.MatrixSave
            out += Opcode.ClipRect(0f, 0f, contentW, contentH)
            if (scroll.direction == 0) out += Opcode.Translate(0f, -offset) else out += Opcode.Translate(-offset, 0f)
            scrollRestores = 1
        }
        node.modifiers.filterIsInstance<Modifier.Marquee>().firstOrNull()?.let { marquee ->
            scrollRestores += paintMarquee(node, marquee, out)
        }
        when (node.kind) {
            LayoutNode.Kind.TEXT -> paintText(node, out)
            LayoutNode.Kind.IMAGE -> paintImage(node, out)
            else -> Unit
        }
        out.addAll(node.draws)
        out.addAll(node.cleanup)
        val ordered = if (node.children.any { it.zIndex != 0f }) node.children.sortedBy { it.zIndex } else node.children
        for (child in ordered) paint(child, out)
        repeat(scrollRestores) { out += Opcode.MatrixRestore }
        repeat(layerRestores) { out += Opcode.MatrixRestore }
        out += Opcode.MatrixRestore
    }

    /**
     * `MarqueeModifierOperation.paint`: content wider than the component slides back and forth
     * inside it, so that all of it can be read.
     *
     * The sweep is a raised sine rather than a constant speed — `(1 + sin(2*pi*t - pi/2)) / 2`
     * over `-overflow` — so the content eases to each end and turns round, arriving back where it
     * started after one period. The period is the overflow over `density * velocity`, and nothing
     * moves until the initial delay has passed twice: `mStartTime` is already the first paint
     * plus that delay, and the comparison then waits for it again.
     *
     * `layout` takes the content's width from `minIntrinsicWidth` plus the spacing, which for a
     * text is the width the whole run would take — so a marquee wants a text of one line, or the
     * line breaking will have made it fit before this is reached.
     */
    private fun paintMarquee(node: LayoutNode, marquee: Modifier.Marquee, out: MutableList<Opcode>): Int {
        val contentW = node.width - node.paddingLeft - node.paddingRight
        val contentH = node.height - node.paddingTop - node.paddingBottom
        val now = context.frameTimeMillis
        val start = context.marqueeStarts.getOrPut(node.componentId) {
            context.needsRepaint = true
            now + marquee.initialDelayMillis.toLong()
        }
        out += Opcode.MatrixSave
        out += Opcode.ClipRect(0f, 0f, contentW, contentH)
        val content = intrinsicWidth(node) + marquee.spacing
        val elapsed = (now - start).toFloat()
        if (content > contentW) {
            // Asked for while it is still waiting as well as while it is moving: the library's
            // host redraws of its own accord, and this one stops when nothing asks — which would
            // leave a marquee that had not started yet never starting.
            context.needsRepaint = true
            if (elapsed > marquee.initialDelayMillis) {
                val overflow = content - contentW
                val period = overflow / (context.density * marquee.velocity)
                if (period > 0f) {
                    val phase = (elapsed / 1000f % period) / period
                    val offset = (1f + sin(phase * 2f * PI.toFloat() - PI.toFloat() / 2f)) / 2f * -overflow
                    out += Opcode.Translate(offset, 0f)
                }
            }
        }
        return 1
    }

    /**
     * `LayoutComponent.minIntrinsicWidth`: how wide the content would be given all the room.
     *
     * A child measured inside a narrow box has already been cut down to it, so its laid-out width
     * says nothing; what a text would take on one line is what it kept from measuring, and a
     * container's is the furthest its children would reach.
     *
     * A component with no children has none: a box holding only canvas draws has no intrinsic
     * size upstream either, and reporting its own width here would make it wider than itself by
     * the marquee's spacing and set it sliding for no reason.
     */
    private fun intrinsicWidth(node: LayoutNode): Float = when (node.kind) {
        LayoutNode.Kind.TEXT -> node.textWidth
        else -> node.children.filterNot { it.isGone }
            .maxOfOrNull { it.x + intrinsicWidth(it) } ?: 0f
    }

    /** `BorderModifierOperation.defaultDrawing`. */    /** `BorderModifierOperation.defaultDrawing`. */
    private fun paintBorder(m: Modifier.Border, out: MutableList<Opcode>) {
        val half = min(m.width, m.height) / 2f
        if (m.borderWidth >= half) {
            val paint = PaintStyle(m.color, PaintStyleKind.FILL)
            when {
                m.shapeType == 0 -> out += Opcode.DrawRect(0f, 0f, m.width, m.height, paint)
                else -> {
                    val r = if (m.shapeType == 1) half else m.roundedCorner
                    out += Opcode.DrawRoundRect(0f, 0f, m.width, m.height, r, r, paint)
                }
            }
            return
        }
        val paint = PaintStyle(m.color, PaintStyleKind.STROKE, strokeWidth = m.borderWidth)
        val inset = m.borderWidth / 2f
        when {
            m.shapeType == 0 -> out += Opcode.DrawRect(inset, inset, m.width - inset, m.height - inset, paint)
            else -> {
                val r = if (m.shapeType == 1) half - inset else m.roundedCorner
                out += Opcode.DrawRoundRect(inset, inset, m.width - inset, m.height - inset, r, r, paint)
            }
        }
    }

    /**
     * `RippleModifierOperation.paint`: a circle spreading from where the component was pressed,
     * out to its longest side over a second, fading from a near-white to nothing over the first
     * half of that. It is clipped to the component, so a press near a corner shows an arc.
     */
    private fun paintRipple(node: LayoutNode, m: Modifier.Ripple, out: MutableList<Opcode>) {
        val ripple = context.ripples[node.componentId] ?: return
        val elapsed = context.animationTime - ripple.startedAt
        if (elapsed < 0f || elapsed > RIPPLE_SECONDS) {
            context.ripples.remove(node.componentId)
            return
        }
        context.needsRepaint = true
        val progress = elapsed / RIPPLE_SECONDS
        val spread = RIPPLE_EASING.get(progress)
        // The fade runs at twice the speed, so it is gone by the time the circle is half way.
        val fade = RIPPLE_EASING.get(min(1f, progress * 2f))
        val colour = Color(
            red = (250f + (200f - 250f) * fade) / 255f,
            green = (250f + (200f - 250f) * fade) / 255f,
            blue = (250f + (200f - 250f) * fade) / 255f,
            alpha = (180f + (0f - 180f) * fade) / 255f,
        )
        out += Opcode.MatrixSave
        out += Opcode.ClipRect(0f, 0f, m.width, m.height)
        out += Opcode.DrawCircle(
            ripple.x, ripple.y, max(m.width, m.height) * spread,
            PaintStyle(colour, PaintStyleKind.FILL),
        )
        out += Opcode.MatrixRestore
    }

    /**
     * Graphics-layer attributes with a renderable equivalent: alpha as a compositing layer,
     * scale/rotation/translation about the transform origin (default center), and a rounded or
     * circular shape clip. Returns how many `MatrixRestore`s the caller owes.
     *
     * `ROTATION_X`/`ROTATION_Y` turn a layer about an axis in its own plane, which foreshortens
     * it across that axis by the cosine of the angle. That cosine is all of the turn this
     * renderer can draw: its transform opcodes are affine, so `CAMERA_DISTANCE` — the vanishing
     * point that makes the near edge of a turned layer larger than the far one — has nowhere to
     * go. The two agree as the camera goes to infinity and part company as the angle opens.
     */
    private fun paintGraphicsLayer(node: LayoutNode, layer: Modifier.GraphicsLayer, out: MutableList<Opcode>): Int {
        fun f(tag: Int): Float? = layer.floats[tag]?.takeUnless { it.isNaN() }
        var restores = 0
        f(GL_ALPHA)?.let { out += Opcode.SaveLayerAlpha(it); restores++ }
        val sx = f(GL_SCALE_X); val sy = f(GL_SCALE_Y); val rz = f(GL_ROTATION_Z)
        val rx = f(GL_ROTATION_X); val ry = f(GL_ROTATION_Y)
        val tx = f(GL_TRANSLATION_X); val ty = f(GL_TRANSLATION_Y)
        if (sx != null || sy != null || rz != null || rx != null || ry != null || tx != null || ty != null) {
            val pivotX = (f(GL_TRANSFORM_ORIGIN_X) ?: 0.5f) * node.width
            val pivotY = (f(GL_TRANSFORM_ORIGIN_Y) ?: 0.5f) * node.height
            if (tx != null || ty != null) out += Opcode.Translate(tx ?: 0f, ty ?: 0f)
            if (rz != null) out += Opcode.Rotate(rz, pivotX, pivotY)
            if (sx != null || sy != null || rx != null || ry != null) {
                val acrossX = ry?.let { cos(it * PI_OVER_180) } ?: 1f
                val acrossY = rx?.let { cos(it * PI_OVER_180) } ?: 1f
                out += Opcode.Scale((sx ?: 1f) * acrossX, (sy ?: 1f) * acrossY, pivotX, pivotY)
            }
        }
        val shape = layer.ints[GL_SHAPE]
        if (shape != null && shape != 0) {
            val radius = if (shape == 2) min(node.width, node.height) / 2f else (f(GL_SHAPE_RADIUS) ?: 0f)
            out += Opcode.ClipPath(roundedRectPath(0f, 0f, node.width, node.height, radius, radius, radius, radius))
        }
        return restores
    }

    /** `TextLayout.paintingComponent`: align the measured text inside the content box, clipping overflow. */
    private fun paintText(node: LayoutNode, out: MutableList<Opcode>) {
        val contentW = node.width - node.paddingLeft - node.paddingRight
        val contentH = node.height - node.paddingTop - node.paddingBottom
        val block = node.textBlock
        val clip = node.textWidth > contentW ||
            (block != null && node.textOverflow != TextWrapping.OVERFLOW_VISIBLE && block.height > contentH + 0.01f)
        if (clip) {
            out += Opcode.MatrixSave
            out += Opcode.ClipRect(0f, 0f, contentW, contentH)
        }
        if (block == null) {
            out += Opcode.DrawText(
                stringIndex = node.textId, x = alignedX(node.textAlign, node.textWidth, contentW), y = 0f,
                paint = node.textPaint ?: DEFAULT_TEXT_PAINT, panY = 1f,
            )
        } else {
            paintTextBlock(node, block, contentW, out)
        }
        if (clip) out += Opcode.MatrixRestore
    }

    /**
     * A broken text, line by line: `drawComplexText` hands the whole laid-out block to the host,
     * which this renderer has no equivalent of, so each line is drawn where the layout put it.
     *
     * A line is drawn from a string of its own rather than from the component's, since the
     * component's is the whole paragraph; the strings the lines were broken into are registered
     * under generated ids the frame carries alongside the document's own.
     */
    private fun paintTextBlock(node: LayoutNode, block: TextBlock, contentW: Float, out: MutableList<Opcode>) {
        val paint = node.textPaint ?: DEFAULT_TEXT_PAINT
        val justifying = node.textAlign == TEXT_ALIGN_JUSTIFY ||
            node.justificationMode == JUSTIFICATION_MODE_INTER_WORD ||
            node.justificationMode == JUSTIFICATION_MODE_INTER_CHARACTER
        for ((index, line) in block.lines.withIndex()) {
            val baseline = block.ascent + index * block.lineHeight
            val width = block.lineWidths.getOrElse(index) { 0f }
            // A line the text ended is left alone: justification stretches the ones that were
            // broken, which is what keeps the last line of a paragraph from being pulled apart.
            val stretch = justifying && !block.ended.getOrElse(index) { true } && width < contentW
            if (stretch && paintJustified(node, line, width, contentW, baseline, paint, out)) continue
            val x = if (justifying) 0f else alignedX(node.textAlign, width, contentW)
            out += Opcode.DrawText(
                stringIndex = context.registerText(line), x = x, y = baseline,
                paint = paint, panY = null,
            )
        }
    }

    /**
     * One line with its words pushed apart to fill the width, as `JUSTIFICATION_MODE_INTER_WORD`
     * asks. Returns false for a line with nothing to push apart, which is then drawn as it is.
     *
     * `JUSTIFICATION_MODE_INTER_CHARACTER` is drawn this way too rather than by spacing the
     * glyphs; `docs/OPCODES.md` says so.
     */
    private fun paintJustified(
        node: LayoutNode, line: String, width: Float, contentW: Float,
        baseline: Float, paint: PaintStyle, out: MutableList<Opcode>,
    ): Boolean {
        val words = line.split(" ").filter { it.isNotEmpty() }
        if (words.size < 2) return false
        val slack = (contentW - width) / (words.size - 1)
        var x = 0f
        for ((index, word) in words.withIndex()) {
            out += Opcode.DrawText(
                stringIndex = context.registerText(word), x = x, y = baseline,
                paint = paint, panY = null,
            )
            if (index < words.size - 1) {
                x += textMetrics.measure("$word ", paint).width + slack
            }
        }
        return true
    }

    /** `getAlignValue`: where a line of [width] starts inside [contentW]. */
    private fun alignedX(align: Int, width: Float, contentW: Float): Float = when (align) {
        TEXT_ALIGN_CENTER -> (contentW - width) / 2f
        TEXT_ALIGN_RIGHT, TEXT_ALIGN_END -> contentW - width
        else -> 0f
    }

    /** `ImageLayout.paintingComponent`: the bitmap scaled into the content box per `scaleType`. */
    private fun paintImage(node: LayoutNode, out: MutableList<Opcode>) {
        val w = node.width - node.paddingLeft - node.paddingRight
        val h = node.height - node.paddingTop - node.paddingBottom
        if (w <= 0f || h <= 0f) return
        val wrapAlpha = node.imageAlpha < 1f
        if (wrapAlpha) out += Opcode.SaveLayerAlpha(node.imageAlpha)
        val natural = context.bitmaps[node.bitmapId]?.let { pngNaturalSize(it) }
        val realScale = node.imageScaleType in intArrayOf(0, 1, 4, 5)
        if (natural != null && realScale) {
            val dst = imageScaleDstRect(node.imageScaleType, natural[0], natural[1], 0f, 0f, w, h)
            val needsClip = node.imageScaleType == 0 || node.imageScaleType == 5
            if (needsClip) { out += Opcode.MatrixSave; out += Opcode.ClipRect(0f, 0f, w, h) }
            out += Opcode.DrawBitmap(node.bitmapId, dst[0], dst[1], dst[2], dst[3])
            if (needsClip) out += Opcode.MatrixRestore
        } else {
            out += Opcode.DrawBitmap(node.bitmapId, 0f, 0f, w, h)
        }
        if (wrapAlpha) out += Opcode.MatrixRestore
    }

    /**
     * Hit rectangles for every component carrying an action list, in window coordinates and in
     * paint order, so a later (visually higher) component wins a hit test. A child's origin is
     * its parent's origin plus that parent's padding, matching how [paint] translates.
     */
    fun collectHitRegions(node: LayoutNode, originX: Float = 0f, originY: Float = 0f, out: MutableList<HitRegion> = mutableListOf()): List<HitRegion> {
        if (node.isGone) return out
        val x = originX + node.x
        val y = originY + node.y
        if (node.actions.isNotEmpty()) {
            out += HitRegion(x, y, x + node.width, y + node.height, node.actions.mapValues { it.value.toList() })
        }
        for (child in node.children) collectHitRegions(child, x + node.paddingLeft, y + node.paddingTop, out)
        return out
    }

    /**
     * `TouchExpression.updateBounds`: where each scrolling component is in the window, under the
     * id of the touch expression that drives it, so that a press outside one leaves it alone.
     *
     * The library walks the component's parents adding their `getX`/`getY`; this adds their
     * padding as well, which is where a child actually sits — the same origin [collectHitRegions]
     * uses, and the same one [paint] translates by.
     */
    fun collectScrollBounds(
        node: LayoutNode,
        originX: Float = 0f,
        originY: Float = 0f,
        out: MutableMap<Int, ScrollBounds> = mutableMapOf(),
    ): Map<Int, ScrollBounds> {
        if (node.isGone) return out
        val x = originX + node.x
        val y = originY + node.y
        for (scroll in node.modifiers.filterIsInstance<Modifier.Scroll>()) {
            out[scroll.positionExpressionId] = ScrollBounds(x, y, x + node.width, y + node.height)
        }
        for (child in node.children) collectScrollBounds(child, x + node.paddingLeft, y + node.paddingTop, out)
        return out
    }

    /** `mScrLeft`/`mScrTop`/`mScrRight`/`mScrBottom`: a scrolling component's place in the window. */
    class ScrollBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
    }

    /** A component that ripples when pressed, and where it is in the window. */
    class RippleTarget(val componentId: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The components with a ripple modifier, in paint order, so that the last one containing a
     * press is the one on top.
     */
    fun collectRippleTargets(
        node: LayoutNode,
        originX: Float = 0f,
        originY: Float = 0f,
        out: MutableList<RippleTarget> = mutableListOf(),
    ): List<RippleTarget> {
        if (node.isGone) return out
        val x = originX + node.x
        val y = originY + node.y
        if (node.componentId != 0 && node.modifiers.any { it is Modifier.Ripple }) {
            out += RippleTarget(node.componentId, x, y, x + node.width, y + node.height)
        }
        for (child in node.children) collectRippleTargets(child, x + node.paddingLeft, y + node.paddingTop, out)
        return out
    }

    companion object {
        private val DEFAULT_TEXT_PAINT = PaintStyle(Color.Black, PaintStyleKind.FILL, textSize = 16f)

        /** Degrees to radians, for the two graphics-layer rotations that foreshorten. */
        private const val PI_OVER_180 = PI.toFloat() / 180f

        /** `CoreText.TEXT_ALIGN_*`. */
        const val TEXT_ALIGN_LEFT = 1
        const val TEXT_ALIGN_RIGHT = 2
        const val TEXT_ALIGN_CENTER = 3
        const val TEXT_ALIGN_JUSTIFY = 4
        const val TEXT_ALIGN_START = 5
        const val TEXT_ALIGN_END = 6

        /** `CoreText.JUSTIFICATION_MODE_*`. */
        const val JUSTIFICATION_MODE_INTER_WORD = 1
        const val JUSTIFICATION_MODE_INTER_CHARACTER = 2

        /** `GraphicsLayerModifierOperation` attribute keys; a float-valued key carries bit `0x400`. */
        const val GL_SCALE_X = 0 or 0x400
        const val GL_SCALE_Y = 1 or 0x400
        const val GL_ROTATION_X = 2 or 0x400
        const val GL_ROTATION_Y = 3 or 0x400
        const val GL_ROTATION_Z = 4 or 0x400
        const val GL_TRANSFORM_ORIGIN_X = 5 or 0x400
        const val GL_TRANSFORM_ORIGIN_Y = 6 or 0x400
        const val GL_TRANSLATION_X = 7 or 0x400
        const val GL_TRANSLATION_Y = 8 or 0x400
        const val GL_ALPHA = 11 or 0x400
        const val GL_SHAPE = 20
        const val GL_SHAPE_RADIUS = 21 or 0x400

        /**
         * A rounded rect as a path with quadratic corners (this renderer has no round-rect clip
         * primitive); each radius is clamped to half the smaller side. Corner names assume LTR.
         */
        fun roundedRectPath(
            left: Float, top: Float, right: Float, bottom: Float,
            topStart: Float, topEnd: Float, bottomStart: Float, bottomEnd: Float,
        ): List<PathCommand> {
            val maxRadius = minOf(right - left, bottom - top) / 2f
            val rTopStart = topStart.coerceIn(0f, maxRadius)
            val rTopEnd = topEnd.coerceIn(0f, maxRadius)
            val rBottomStart = bottomStart.coerceIn(0f, maxRadius)
            val rBottomEnd = bottomEnd.coerceIn(0f, maxRadius)
            return listOf(
                PathCommand.MoveTo(left + rTopStart, top),
                PathCommand.LineTo(right - rTopEnd, top),
                PathCommand.QuadraticTo(right, top, right, top + rTopEnd),
                PathCommand.LineTo(right, bottom - rBottomEnd),
                PathCommand.QuadraticTo(right, bottom, right - rBottomEnd, bottom),
                PathCommand.LineTo(left + rBottomStart, bottom),
                PathCommand.QuadraticTo(left, bottom, left, bottom - rBottomStart),
                PathCommand.LineTo(left, top + rTopStart),
                PathCommand.QuadraticTo(left, top, left + rTopStart, top),
                PathCommand.Close,
            )
        }

        /** A PNG's pixel size from its `IHDR` chunk, or null if [bytes] is too short. */
        fun pngNaturalSize(bytes: ByteArray): IntArray? {
            if (bytes.size < 24) return null
            fun beInt(offset: Int) =
                ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            return intArrayOf(beInt(16), beInt(20))
        }

        /**
         * `ImageScaling.adjustDrawToType` with its integer arithmetic: 0 natural size centered,
         * 1 inside (natural if it fits, else fit), 4 fit (letterbox), 5 crop (overflow one axis);
         * any other type returns the box unchanged (stretch).
         */
        fun imageScaleDstRect(
            scaleType: Int, naturalWidth: Int, naturalHeight: Int,
            dstLeft: Float, dstTop: Float, dstRight: Float, dstBottom: Float,
        ): FloatArray {
            if (naturalWidth <= 0 || naturalHeight <= 0 || scaleType !in intArrayOf(0, 1, 4, 5)) {
                return floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
            }
            val dstW = (dstRight - dstLeft).toInt()
            val dstH = (dstBottom - dstTop).toInt()
            var leftOffset = 0; var rightOffset = dstW; var topOffset = 0; var bottomOffset = dstH
            fun centerAtNaturalSize() {
                leftOffset = (dstW - naturalWidth) / 2; rightOffset = naturalWidth + leftOffset
                topOffset = (dstH - naturalHeight) / 2; bottomOffset = naturalHeight + topOffset
            }
            fun shrinkOrGrowToFit(shrinkHeightWhenSrcWider: Boolean) {
                val srcWider = naturalWidth * dstH > dstW * naturalHeight
                val shrinkHeight = if (shrinkHeightWhenSrcWider) srcWider else !srcWider
                if (shrinkHeight) {
                    val adjusted = dstW * naturalHeight / naturalWidth
                    topOffset = (dstH - adjusted) / 2; bottomOffset = adjusted + topOffset
                } else {
                    val adjusted = dstH * naturalWidth / naturalHeight
                    leftOffset = (dstW - adjusted) / 2; rightOffset = adjusted + leftOffset
                }
            }
            when (scaleType) {
                0 -> centerAtNaturalSize()
                1 -> if (dstW >= naturalWidth && dstH >= naturalHeight) centerAtNaturalSize() else shrinkOrGrowToFit(true)
                4 -> shrinkOrGrowToFit(true)
                5 -> shrinkOrGrowToFit(false)
            }
            return floatArrayOf(dstLeft + leftOffset, dstTop + topOffset, dstLeft + rightOffset, dstTop + bottomOffset)
        }
    }
}
