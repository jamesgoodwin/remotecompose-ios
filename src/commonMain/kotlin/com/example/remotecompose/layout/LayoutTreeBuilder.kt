package com.example.remotecompose.layout

import com.example.remotecompose.model.Opcode
import com.example.remotecompose.parser.PaintState
import com.example.remotecompose.runtime.ActionTrigger
import com.example.remotecompose.runtime.DocumentAction
import com.example.remotecompose.runtime.HitRegion
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
        if (root.kind == LayoutNode.Kind.ROOT) {
            engine.measureRoot(root, windowWidth, windowHeight)
        } else {
            engine.measure(root, 0f, windowWidth, 0f, windowHeight)
            root.x = 0f; root.y = 0f
        }
        engine.paint(root, out)
        hitRegions = engine.collectHitRegions(root)
        return out
    }
}
