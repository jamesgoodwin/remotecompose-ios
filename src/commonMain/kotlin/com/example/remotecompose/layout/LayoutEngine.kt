package com.example.remotecompose.layout

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.text.TextMetricsProvider
import kotlin.math.max
import kotlin.math.min

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
class LayoutEngine(private val context: RemoteContext, private val textMetrics: TextMetricsProvider) {

    private class Size(var width: Float = 0f, var height: Float = 0f)

    private val unbounded = Float.MAX_VALUE

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

        if (hasWrapW || hasWrapH) {
            val size = Size()
            computeWrapSize(node, minW, insetMaxW, minH, insetMaxH, wd.isWrap, hd.isWrap, size)
            if (hasWrapW) w = max(size.width + node.paddingLeft + node.paddingRight, minW)
            if (hasWrapH) h = max(size.height + node.paddingTop + node.paddingBottom, minH)
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
        internalLayout(node)
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

    // --------------------------------------------------- wrap size (intrinsic)

    private fun computeWrapSize(
        node: LayoutNode, minW: Float, maxW: Float, minH: Float, maxH: Float,
        wrapW: Boolean, wrapH: Boolean, size: Size,
    ) {
        when (node.kind) {
            LayoutNode.Kind.TEXT -> {
                val metrics = textMetrics.measure(context.texts[node.textId] ?: "", node.textPaint ?: DEFAULT_TEXT_PAINT)
                node.textWidth = metrics.width
                node.textHeight = metrics.height
                size.width = min(maxW, metrics.width)
                size.height = min(maxH, metrics.height)
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
            else -> { // BOX, FIT_BOX, CANVAS, CUSTOM, ROOT
                for (child in node.children) {
                    measure(child, 0f, maxW, 0f, maxH)
                    if (child.isGone) continue
                    size.width = max(size.width, child.width)
                    size.height = max(size.height, child.height)
                }
            }
        }
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
        layoutModifiers(node)
        out += Opcode.MatrixSave
        out += Opcode.Translate(node.x, node.y)
        var layerRestores = 0
        node.modifiers.filterIsInstance<Modifier.GraphicsLayer>().firstOrNull()?.let { layer ->
            layerRestores = paintGraphicsLayer(node, layer, out)
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
        when (node.kind) {
            LayoutNode.Kind.TEXT -> paintText(node, out)
            LayoutNode.Kind.IMAGE -> paintImage(node, out)
            else -> Unit
        }
        out.addAll(node.draws)
        out.addAll(node.cleanup)
        val ordered = if (node.children.any { it.zIndex != 0f }) node.children.sortedBy { it.zIndex } else node.children
        for (child in ordered) paint(child, out)
        repeat(layerRestores) { out += Opcode.MatrixRestore }
        out += Opcode.MatrixRestore
    }

    /** `BorderModifierOperation.defaultDrawing`. */
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
     * Graphics-layer attributes with a renderable equivalent: alpha as a compositing layer,
     * scale/rotation/translation about the transform origin (default center), and a rounded or
     * circular shape clip. Returns how many `MatrixRestore`s the caller owes.
     */
    private fun paintGraphicsLayer(node: LayoutNode, layer: Modifier.GraphicsLayer, out: MutableList<Opcode>): Int {
        val a = layer.attributes
        fun f(tag: Int): Float? = a[tag]?.let { Float.fromBits(it) }
        var restores = 0
        f(GL_ALPHA)?.let { out += Opcode.SaveLayerAlpha(it); restores++ }
        val sx = f(GL_SCALE_X); val sy = f(GL_SCALE_Y); val rz = f(GL_ROTATION_Z)
        val tx = f(GL_TRANSLATION_X); val ty = f(GL_TRANSLATION_Y)
        if (sx != null || sy != null || rz != null || tx != null || ty != null) {
            val pivotX = (f(GL_TRANSFORM_ORIGIN_X) ?: 0.5f) * node.width
            val pivotY = (f(GL_TRANSFORM_ORIGIN_Y) ?: 0.5f) * node.height
            if (tx != null || ty != null) out += Opcode.Translate(tx ?: 0f, ty ?: 0f)
            if (rz != null) out += Opcode.Rotate(rz, pivotX, pivotY)
            if (sx != null || sy != null) out += Opcode.Scale(sx ?: 1f, sy ?: 1f, pivotX, pivotY)
        }
        val shape = a[GL_SHAPE]
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
        val x = when (node.textAlign) {
            3 -> (contentW - node.textWidth) / 2f // CENTER
            2, 6 -> contentW - node.textWidth // RIGHT, END
            else -> 0f
        }
        val clip = node.textWidth > contentW
        if (clip) {
            out += Opcode.MatrixSave
            out += Opcode.ClipRect(0f, 0f, contentW, contentH)
        }
        out += Opcode.DrawText(
            stringIndex = node.textId, x = x, y = 0f,
            paint = node.textPaint ?: DEFAULT_TEXT_PAINT, panY = 1f,
        )
        if (clip) out += Opcode.MatrixRestore
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

    companion object {
        private val DEFAULT_TEXT_PAINT = PaintStyle(Color.Black, PaintStyleKind.FILL, textSize = 16f)

        /** `GraphicsLayerModifierOperation` attribute keys; a float-valued key carries bit `0x400`. */
        const val GL_SCALE_X = 0 or 0x400
        const val GL_SCALE_Y = 1 or 0x400
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
