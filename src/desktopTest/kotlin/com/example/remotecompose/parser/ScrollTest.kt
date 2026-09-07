package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MODIFIER_SCROLL` against `tools/rc-writer/coffee.rc`: a menu taller than the window it is
 * shown through, moved by dragging it.
 */
class ScrollTest {

    /** The menu's window, and the six rows of 56 plus their padding with 8 between them. */
    private val WINDOW = 240f
    private val CONTENT = 6 * (56f + 24f) + 5 * 8f

    private val bytes by lazy { File("tools/rc-writer/coffee.rc").readBytes() }
    private val operations by lazy { OperationReader.readAll(bytes) }

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    /**
     * The offset the scrolling component is drawing its content at. `verticalScroll` writes a
     * clip modifier as well as the scroll, so the window is the last clip of that size and the
     * translate straight after it is the scroll.
     */
    private fun scrollOffset(document: RemoteComposeDocument): Float {
        val opcodes = document.frame(0L).opcodes
        val window = opcodes.indexOfLast { it is Opcode.ClipRect && it.bottom == WINDOW }
        assertTrue(window >= 0, "the scrolling component clips to its window")
        return -(opcodes[window + 1] as Opcode.Translate).dy
    }

    /** One per menu row: the bordered card each item is drawn in. */
    private fun rows(document: RemoteComposeDocument): List<Opcode.DrawRoundRect> =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRoundRect>().filter { it.paint.strokeWidth == 1f }

    @Test
    fun theModifierIsAContainerHoldingTheTouchExpressionThatDrivesIt() {
        val scroll = operations.filterIsInstance<Operation.ModifierScroll>().single()
        val at = operations.indexOf(scroll)
        assertEquals(0, scroll.direction, "vertical")
        val touch = operations[at + 1]
        assertTrue(touch is Operation.TouchExpression, "the expression follows the modifier")
        assertEquals(FloatExpressionEvaluator.idOf(scroll.positionExpression), (touch as Operation.TouchExpression).id)
        assertTrue(operations[at + 2] is Operation.ContainerEnd, "and a ContainerEnd closes it")
    }

    @Test
    fun theWindowClipsWhatDoesNotFit() {
        val document = loaded()
        val clips = document.frame(0L).opcodes.filterIsInstance<Opcode.ClipRect>()
        assertTrue(clips.any { it.bottom == WINDOW }, "the menu is clipped to its window")
        // Every row is laid out, though together they are taller than the window.
        assertEquals(6, rows(document).size)
        assertTrue(CONTENT > WINDOW, "$CONTENT of content in a $WINDOW window")
    }

    @Test
    fun draggingMovesTheContentAndReleasingLeavesItThere() {
        val document = loaded()
        assertEquals(0f, scrollOffset(document), 0.01f)
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, 180f)
        assertEquals(120f, scrollOffset(document), 0.01f)
        // The finger comes off and the list stays where it was put.
        document.touchUp(150f, 180f)
        assertEquals(120f, scrollOffset(document), 0.01f)
    }

    @Test
    fun theContentStopsAtItsEnds() {
        val document = loaded()
        // Dragging the other way cannot go above the top.
        document.touchDown(150f, 100f)
        document.frame(0L)
        document.touchDrag(150f, 400f)
        assertEquals(0f, scrollOffset(document), 0.01f)
        // And far past the bottom stops at the overflow: the content less the window.
        document.touchUp(150f, 400f)
        document.touchDown(150f, 400f)
        document.frame(0L)
        document.touchDrag(150f, -600f)
        assertEquals(CONTENT - WINDOW, scrollOffset(document), 0.01f)
    }

    @Test
    fun aScrollingComponentKeepsItsOwnSize() {
        // The window is the size the document asked for, whatever the content adds up to.
        val document = loaded()
        val clip = document.frame(0L).opcodes.filterIsInstance<Opcode.ClipRect>().last { it.bottom == WINDOW }
        assertEquals(WINDOW, clip.bottom - clip.top)
    }
}
