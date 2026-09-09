package io.github.jamesgoodwin.remotecompose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeDocument
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsMode
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsNode
import io.github.jamesgoodwin.remotecompose.runtime.SemanticsRole
import kotlin.math.roundToInt

/**
 * The document's `ACCESSIBILITY_SEMANTICS` as Compose semantics, over the canvas that drew it.
 *
 * A document is one canvas: there are no composables for a screen reader to find, because
 * everything was drawn rather than laid out. So each labelled component gets an empty box of its
 * own, the size and place the component ended up, carrying nothing but semantics. The boxes take
 * no pointer input, so the canvas underneath still sees every gesture.
 *
 * [io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas] does this for itself. This is for
 * a host that draws a document some other way: put it in the same box as the canvas that drew it,
 * and give it the same mapping from document coordinates to that box — [scale] with [offsetX] and
 * [offsetY] in pixels, which for a canvas drawn one to one are the defaults.
 *
 * Activating a labelled control clicks [document] where the control is, as a finger would.
 */
@Composable
public fun RemoteComposeSemantics(
    document: RemoteComposeDocument,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) {
    val nodes = document.semantics
    if (nodes.isEmpty()) return
    SemanticsOverlay(nodes, FitTransform(scale, offsetX, offsetY)) { node ->
        document.click((node.left + node.right) / 2f, (node.top + node.bottom) / 2f)
    }
}

/**
 * One level of the overlay. Nested as the document nested it, which is what makes `MERGE` and
 * `CLEAR_AND_SET` work: Compose merges or clears descendants, and the descendants are here.
 *
 * @param origin where the enclosing node sits in canvas pixels, since a child's offset is
 *   relative to it.
 */
@Composable
internal fun SemanticsOverlay(
    nodes: List<SemanticsNode>,
    fit: FitTransform,
    origin: Offsets = Offsets(0f, 0f),
    onActivate: (SemanticsNode) -> Unit,
) {
    val density = LocalDensity.current
    for (node in nodes) {
        val left = fit.offsetX + node.left * fit.scale
        val top = fit.offsetY + node.top * fit.scale
        val width = (node.right - node.left) * fit.scale
        val height = (node.bottom - node.top) * fit.scale
        val dx = left - origin.x
        val dy = top - origin.y
        Box(
            Modifier
                .absoluteOffset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .nodeSemantics(node, onActivate),
        ) {
            // A node that merges or replaces what is under it has already said everything there
            // is to say about it, so there is nothing left to put on the screen underneath.
            if (node.mode == SemanticsMode.SET) {
                SemanticsOverlay(node.children, fit, Offsets(left, top), onActivate)
            }
        }
    }
}

/** Where a node sits in canvas pixels; a plain pair, to keep the recursion readable. */
internal data class Offsets(val x: Float, val y: Float)

/**
 * The node's own semantics, with what it merges already folded in by [SemanticsNode.spokenLabel].
 *
 * `MERGE` and `CLEAR_AND_SET` are both `clearAndSetSemantics` here rather than Compose's
 * `mergeDescendants`: the label is composed once, in common code, so a reader is told the same
 * thing on every platform and a test can check it. What differs between the two modes is what
 * went into that label, which [SemanticsNode.spokenLabel] has already decided.
 *
 * A merged node with nothing of its own to activate takes the first activatable thing under it,
 * so that a row read as one thing can also be pressed as one thing.
 */
private fun Modifier.nodeSemantics(node: SemanticsNode, onActivate: (SemanticsNode) -> Unit): Modifier {
    val label = node.spokenLabel()
    val target = when {
        node.clickable -> node
        node.mode == SemanticsMode.MERGE -> node.activatableDescendant()
        else -> null
    }
    val properties: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit = {
        label?.let { contentDescription = it }
        node.stateDescription?.let { stateDescription = it }
        node.role.toComposeRole()?.let { role = it }
        if (!node.enabled) disabled()
        if (target != null) onClick { onActivate(target); true }
    }
    return when (node.mode) {
        SemanticsMode.SET -> semantics(properties = properties)
        SemanticsMode.CLEAR_AND_SET, SemanticsMode.MERGE -> clearAndSetSemantics(properties)
    }
}

/** The first thing inside a merged node that can be activated, if it has one. */
private fun SemanticsNode.activatableDescendant(): SemanticsNode? {
    for (child in children) {
        if (child.clickable) return child
        child.activatableDescendant()?.let { return it }
    }
    return null
}

/**
 * `AccessibleComponent.Role` as one of Compose's, where there is one.
 *
 * The official player's `toComposeRole` answers `Role.Button` for everything it has no match for,
 * `UNKNOWN` included. That is not copied here: a role is read out, and a document that labels a
 * value or a container without saying what kind of control it is would have a screen reader call
 * all of them buttons. No role is the truthful answer, and Compose leaves those nodes unlabelled
 * as to kind rather than mislabelled.
 */
private fun SemanticsRole.toComposeRole(): Role? = when (this) {
    SemanticsRole.BUTTON -> Role.Button
    SemanticsRole.CHECKBOX -> Role.Checkbox
    SemanticsRole.SWITCH -> Role.Switch
    SemanticsRole.RADIO_BUTTON -> Role.RadioButton
    SemanticsRole.TAB -> Role.Tab
    SemanticsRole.IMAGE -> Role.Image
    SemanticsRole.DROPDOWN_LIST -> Role.DropdownList
    // Compose has no picker or carousel; a picker opens a list of choices, which is the nearest
    // thing it does have, and a carousel is a container rather than a control.
    SemanticsRole.PICKER -> Role.DropdownList
    SemanticsRole.CAROUSEL -> null
    SemanticsRole.UNKNOWN -> null
}
