package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.FloatExpressionEvaluator
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

    private val bytes by lazy { fixture("coffee") }
    private val operations by lazy { OperationReader.readAll(bytes) }

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    /**
     * The offset the scrolling component is drawing its content at. `verticalScroll` writes a
     * clip modifier as well as the scroll, so the window is the last clip of that size and the
     * translate straight after it is the scroll.
     */
    private fun scrollOffset(document: RemoteComposeDocument): Float = offsetIn(document.frame(0L).opcodes)

    /** The same, read from a frame already drawn, so the clock is left where the caller put it. */
    private fun offsetIn(opcodes: List<Opcode>): Float {
        val window = opcodes.indexOfLast { it is Opcode.ClipRect && it.bottom == WINDOW }
        assertTrue(window >= 0, "the scrolling component clips to its window")
        return -(opcodes[window + 1] as Opcode.Translate).dy
    }

    /** One per menu row: the bordered card each item is drawn in, which the footer is not. */
    private fun rows(document: RemoteComposeDocument): List<Opcode.DrawRoundRect> =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .filter { it.paint.strokeWidth == 1f && it.bottom - it.top == 79f }

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
        // And far past the bottom stops at the overflow: the content less the window. The press
        // has to land on the menu — a touch expression ignores one outside its own component —
        // though the drag that follows may go anywhere.
        document.touchUp(150f, 400f)
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, -600f)
        assertEquals(CONTENT - WINDOW, scrollOffset(document), 0.01f)
    }

    /** The value the drag actually writes, as opposed to the offset the paint clamps it to. */
    private fun scrollValue(document: RemoteComposeDocument): Float {
        val scroll = operations.filterIsInstance<Operation.ModifierScroll>().single()
        return document.context.floats[FloatExpressionEvaluator.idOf(scroll.positionExpression)] ?: Float.NaN
    }

    @Test
    fun theExtentIsPublishedForTheTouchExpressionToStopAt() {
        // `ScrollModifierOperation.layout()` writes how far there is to scroll into the float the
        // touch expression reads as its `max`, and the content size into the notch one.
        val document = loaded()
        val scroll = operations.filterIsInstance<Operation.ModifierScroll>().single()
        val floats = document.context.floats
        assertEquals(CONTENT - WINDOW, floats[FloatExpressionEvaluator.idOf(scroll.max)]!!, 0.01f)
        assertEquals(CONTENT, floats[FloatExpressionEvaluator.idOf(scroll.notchMax)]!!, 0.01f)
    }

    @Test
    fun anOverDragStopsAtTheEndRatherThanRunningOn() {
        // Without the bound the drag keeps accumulating out of sight: the list looks stopped
        // because the paint clamps it, then will not move until the excess is dragged back.
        val document = loaded()
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, -500f)
        document.frame(0L)
        document.touchUp(150f, -500f)
        assertEquals(CONTENT - WINDOW, scrollValue(document), 0.01f, "the value itself stops")
        // So dragging back moves on the first pixel rather than after a dead zone.
        document.touchDown(150f, 100f)
        document.frame(0L)
        document.touchDrag(150f, 140f)
        document.frame(0L)
        assertEquals(CONTENT - WINDOW - 40f, scrollOffset(document), 0.01f)
    }

    @Test
    fun growingAnItemDoesNotShiftAListThatIsAlreadyAtItsEnd() {
        // Tapping an item makes its card taller, so there is more to scroll than a moment ago.
        // The list has to stay where it was put: it moved with nothing touching it while the
        // value was past the end and the new end caught up with it.
        val document = loaded()
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, -500f)
        document.frame(0L)
        document.touchUp(150f, -500f)
        val before = scrollOffset(document)
        document.click(150f, 130f)
        document.frame(0L)
        document.frame(2000L)
        assertEquals(before, scrollOffset(document), 0.01f)
    }

    /** Drags the menu up by 100 and lets go at [speed] document units a second. */
    private fun flung(speed: Float): RemoteComposeDocument {
        val document = loaded()
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, 200f)
        document.frame(0L)
        document.touchUp(150f, 200f, 0f, -speed)
        return document
    }

    private fun offsetAt(document: RemoteComposeDocument, at: Long): Float =
        offsetIn(document.frame(at).opcodes)

    @Test
    fun lettingGoWhileMovingCarriesOn() {
        val document = flung(600f)
        assertEquals(100f, offsetAt(document, 0L), 0.01f, "where the finger left it")
        val early = offsetAt(document, 100L)
        assertTrue(early > 100f, "it kept going: $early")
        assertTrue(offsetAt(document, 200L) > early, "and is still going")
    }

    @Test
    fun itSlowsDownRatherThanStoppingDead() {
        val document = flung(600f)
        offsetAt(document, 0L)
        val first = offsetAt(document, 100L) - 100f
        val second = offsetAt(document, 200L) - offsetAt(document, 100L)
        val third = offsetAt(document, 300L) - offsetAt(document, 200L)
        assertTrue(first > second && second > third, "each step is shorter: $first, $second, $third")
    }

    @Test
    fun itComesToRestAndStaysThere() {
        val document = flung(600f)
        // Half the velocity further on is past the end, so it settles against it.
        val settled = offsetAt(document, 1000L)
        assertEquals(CONTENT - WINDOW, settled, 0.01f)
        assertEquals(settled, offsetAt(document, 2000L), 0.01f, "and does not drift after")
    }

    @Test
    fun aSlowerReleaseTravelsLessFar() {
        // `getStopPosition` for STOP_GENTLY is half the velocity beyond where the finger left.
        val gentle = flung(120f)
        gentle.frame(0L)
        assertEquals(160f, offsetAt(gentle, 2000L), 1f, "100 plus half of 120")
    }

    @Test
    fun aFingerComingBackDownStopsIt() {
        val document = flung(600f)
        offsetAt(document, 0L)
        val caught = offsetAt(document, 100L)
        document.touchDown(150f, 300f)
        assertEquals(caught, offsetAt(document, 150L), 1f, "it stopped where it was caught")
        assertEquals(caught, offsetAt(document, 400L), 1f, "and stays under the finger")
    }

    @Test
    fun aReleaseWithNoSpeedLeavesItWhereItIs() {
        val document = flung(0f)
        assertEquals(100f, offsetAt(document, 0L), 0.01f)
        assertEquals(100f, offsetAt(document, 800L), 0.01f, "nothing to carry on with")
    }

    @Test
    fun aScrollingComponentKeepsItsOwnSize() {
        // The window is the size the document asked for, whatever the content adds up to.
        val document = loaded()
        val clip = document.frame(0L).opcodes.filterIsInstance<Opcode.ClipRect>().last { it.bottom == WINDOW }
        assertEquals(WINDOW, clip.bottom - clip.top)
    }
}
