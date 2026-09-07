package com.example.remotecompose.layout

import com.example.remotecompose.model.Opcode
import com.example.remotecompose.parser.PaintState
import com.example.remotecompose.runtime.ActionTrigger
import com.example.remotecompose.runtime.DocumentAction
import com.example.remotecompose.runtime.HitRegion
import com.example.remotecompose.runtime.CubicEasing
import com.example.remotecompose.runtime.RemoteContext

/**
 * Builds the [LayoutNode] tree while the evaluator walks the operations, the way
 * `LayoutComponent.inflate` collects a component's modifiers, canvas operations and children.
 *
 * Every container-style operation opens a frame that a later `CONTAINER_END` closes. Three
 * kinds of frame exist: a component ([openNode]); a transparent pass-through such as
 * `LAYOUT_CONTENT` whose draws and children belong to the enclosing component
 * ([openContent]); and an action list such as `MODIFIER_CLICK` whose contents are not painted
 * ([openActionList]). The cumulative paint is saved when a frame opens and restored when it
 * closes, mirroring `Component.paint()`'s `savePaint`/`restorePaint`.
 *
 * When the outermost component closes, the whole tree is measured against the window, laid out
 * and painted into opcodes.
 */
internal class LayoutTreeBuilder(private val context: RemoteContext, private val engine: LayoutEngine) {

    private class Frame(
        val node: LayoutNode?,
        val ownsNode: Boolean,
        val savedPaint: PaintState,
        /** Non-null inside an action list: where [addAction] puts what it collects. */
        val actionSink: MutableList<DocumentAction>? = null,
    )

    private val stack = mutableListOf<Frame>()

    /** The component that modifiers and draws currently attach to, or null outside any component. */
    val current: LayoutNode? get() = stack.lastOrNull()?.node

    /** Hit regions of the most recently rendered tree, in window coordinates. */
    var hitRegions: List<HitRegion> = emptyList()
        private set

    /** The components of that tree that ripple when pressed, in window coordinates. */
    var rippleTargets: List<LayoutEngine.RippleTarget> = emptyList()
        private set

    val isOpen: Boolean get() = stack.isNotEmpty()

    fun openNode(node: LayoutNode, paint: PaintState) {
        current?.addChild(node)
        stack += Frame(node, ownsNode = true, savedPaint = paint.copy())
    }

    fun openContent(paint: PaintState) {
        stack += Frame(current, ownsNode = false, savedPaint = paint.copy())
    }

    /**
     * Opens an action list for [trigger] on the enclosing component. Its contents are collected
     * as [DocumentAction]s rather than painted, as `ListActionsOperation` does.
     */
    fun openActionList(trigger: ActionTrigger, paint: PaintState) {
        val sink = current?.actions?.getOrPut(trigger) { mutableListOf() }
        stack += Frame(null, ownsNode = false, savedPaint = paint.copy(), actionSink = sink)
    }

    /** Adds [action] to the innermost open action list; false when no action list is open. */
    fun addAction(action: DocumentAction): Boolean {
        val sink = stack.lastOrNull()?.actionSink ?: return false
        sink += action
        return true
    }

    /**
     * Routes a draw opcode to the open component. Returns false when no frame is open, in which
     * case the caller owns the opcode (a top-level canvas draw); inside an action list the opcode
     * is dropped and true is returned.
     */
    fun emit(op: Opcode): Boolean {
        if (stack.isEmpty()) return false
        current?.draws?.add(op)
        return true
    }

    /** Queues [op] to be painted after the open component's own draws; false when nothing is open. */
    fun addCleanup(op: Opcode): Boolean {
        val node = current ?: return false
        node.cleanup += op
        return true
    }

    /**
     * Closes the innermost frame, restoring [paint]. When that frame was the outermost
     * component, returns the opcodes of the measured, laid-out and painted tree.
     */
    fun close(paint: PaintState): List<Opcode>? {
        val frame = stack.removeLastOrNull() ?: return null
        paint.restoreFrom(frame.savedPaint)
        val node = frame.node
        if (!frame.ownsNode || node == null || stack.any { it.node != null }) return null
        return render(node)
    }

    /** Renders any component left open by a truncated document. */
    fun flush(paint: PaintState): List<Opcode> {
        val out = mutableListOf<Opcode>()
        while (stack.isNotEmpty()) close(paint)?.let { out += it }
        return out
    }

    private fun render(root: LayoutNode): List<Opcode> {
        val out = mutableListOf<Opcode>()
        val windowWidth = context.windowWidth
        val windowHeight = context.windowHeight
        // Before measuring: a component on its way out has to keep its place in the layout while
        // it fades, so whether it counts as visible is decided here rather than after.
        animateVisibility(root)
        if (root.kind == LayoutNode.Kind.ROOT) {
            engine.measureRoot(root, windowWidth, windowHeight)
        } else {
            engine.measure(root, 0f, windowWidth, 0f, windowHeight)
            root.x = 0f; root.y = 0f
        }
        animateMeasures(root)
        engine.paint(root, out)
        hitRegions = engine.collectHitRegions(root)
        rippleTargets = engine.collectRippleTargets(root)
        context.scrollBounds = engine.collectScrollBounds(root)
        context.componentBounds.clear()
        context.componentBounds.putAll(engine.collectComponentBounds(root))
        return out
    }

    /**
     * `AnimateMeasure`: a component that named an `AnimationSpec` and has moved or resized since
     * the last frame is drawn on its way there rather than at its new place.
     *
     * The tree is built afresh every frame, so what identifies a component between frames is the
     * id the document gave it. Anything without a spec is left where it was laid out.
     */
    /**
     * `AnimateMeasure` for a component whose visibility changed: `getVisibility()` is `mVp` when
     * it is arriving and `1 - mVp` when it is leaving, off the spec's own visibility duration and
     * easing rather than the motion one.
     *
     * The component keeps its place in the layout until the fade is over, which is why this runs
     * before the measure pass — a component excluded from layout has nothing left to fade.
     *
     * Only `FADE_IN` and `FADE_OUT` are acted on. The slides, the rotate and the particle
     * animation are decoded and not applied; see `docs/OPCODES.md`.
     */
    private fun animateVisibility(root: LayoutNode) {
        val now = context.animationTime
        var running = false
        fun visit(node: LayoutNode) {
            val id = node.componentId
            if (id != 0 && node.visibilityDuration > 0f) {
                val wanted = node.visibility
                val previous = context.visibilityStates[id]
                if (previous == null) {
                    // First seen: it is however it is, with nothing to animate from.
                    context.visibilityStates[id] = RemoteContext.VisibilityState(wanted, wanted, now)
                } else {
                    if (previous.target != wanted) {
                        previous.from = previous.target
                        previous.target = wanted
                        previous.startedAt = now
                    }
                    val elapsed = now - previous.startedAt
                    val arriving = wanted != Visibility.GONE
                    val animation = if (arriving) node.enterAnimation else node.exitAnimation
                    if (previous.from != previous.target && animation in FADE_ANIMATIONS) {
                        val fraction = (elapsed / node.visibilityDuration).coerceIn(0f, 1f)
                        val vp = CubicEasing.preset(node.visibilityEasing).get(fraction)
                        node.fadeAlpha = if (arriving) vp else 1f - vp
                        if (elapsed < node.visibilityDuration) {
                            running = true
                            // Still on its way out, so it still occupies the layout.
                            if (!arriving) node.visibility = Visibility.VISIBLE
                        } else {
                            previous.from = previous.target
                            node.fadeAlpha = if (arriving) 1f else 0f
                        }
                    }
                }
            }
            for (child in node.children) visit(child)
        }
        visit(root)
        if (running) context.needsRepaint = true
    }

    private fun animateMeasures(root: LayoutNode) {
        val now = context.animationTime
        var running = false
        fun visit(node: LayoutNode) {
            if (node.motionDuration > 0f && node.componentId != 0) {
                val existing = context.measureAnimations[node.componentId]
                val duration = node.motionDuration
                if (existing == null) {
                    // First seen: it is simply where it is, and next frame has something to
                    // animate from.
                    context.measureAnimations[node.componentId] = MeasureAnimation(
                        node.x, node.y, node.width, node.height,
                        node.x, node.y, node.width, node.height,
                        duration, node.motionEasing, now,
                    )
                } else {
                    val moved = existing.toX != node.x || existing.toY != node.y ||
                        existing.toWidth != node.width || existing.toHeight != node.height
                    if (moved) existing.retarget(node.x, node.y, node.width, node.height, now)
                    existing.applyTo(node, now)
                    if (existing.isRunning(now)) running = true
                }
            }
            for (child in node.children) visit(child)
        }
        visit(root)
        if (running) context.needsRepaint = true
    }

    private companion object {
        /** `AnimationSpec.ANIMATION` ordinals: FADE_IN is 0 and FADE_OUT is 1. */
        val FADE_ANIMATIONS = setOf(0, 1)
    }
}
