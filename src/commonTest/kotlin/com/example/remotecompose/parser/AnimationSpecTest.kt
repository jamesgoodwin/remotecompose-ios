package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ANIMATION_SPEC` against `tools/rc-writer/coffee.rc`: tapping a menu item makes its card
 * taller, and the spec on the row is what turns that into a movement rather than a jump.
 */
class AnimationSpecTest {

    private val bytes by lazy { fixture("coffee") }

    /**
     * The height of each menu card in the current frame. The page's footer is bordered too, and
     * is not one of them, so the six that start at the row height are what count.
     */
    private fun cardHeights(document: RemoteComposeDocument, at: Long): List<Float> =
        document.frame(at).opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .filter { it.paint.strokeWidth == 1f }
            .map { it.bottom - it.top }
            .filter { it >= 79f }

    /**
     * The second item tapped, and one frame drawn at zero. An animation starts on the frame that
     * first sees the change, so without that frame everything below would be timed from
     * whenever the next one happened to be.
     */
    private fun tapped(): RemoteComposeDocument {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        document.click(150f, 220f)
        document.frame(0L)
        return document
    }

    @Test
    fun theSpecBelongsToTheComponentItIsWrittenAmong() {
        // It is a modifier, so which component it animates is where it appears, not an id.
        val operations = OperationReader.readAll(bytes)
        val specs = operations.filterIsInstance<Operation.AnimationSpec>()
        assertEquals(6, specs.size, "one per row")
        assertTrue(specs.all { it.motionDuration == 0.35f })
        val firstRow = operations.indexOfFirst { it is Operation.LayoutRow }
        assertTrue(operations.indexOf(specs.first()) > firstRow, "written inside the row it belongs to")
    }

    @Test
    fun aCardThatChangedSizeIsDrawnOnItsWayThere() {
        val document = tapped()
        val start = cardHeights(document, 0L)
        assertTrue(start.all { it == start[0] }, "nothing has moved yet: $start")
        val quarter = cardHeights(document, 100L)[1]
        val half = cardHeights(document, 200L)[1]
        val end = cardHeights(document, 350L)[1]
        assertTrue(quarter > start[1], "it has started: $quarter")
        assertTrue(half > quarter, "and kept going: $half")
        assertTrue(end > half, "until it arrives: $end")
        assertEquals(115f, end, 0.01f)
    }

    @Test
    fun onlyTheCardThatChangedMoves() {
        val document = tapped()
        val heights = cardHeights(document, 200L)
        assertEquals(1, heights.count { it > heights[0] }, "one card grew: $heights")
    }

    @Test
    fun aMovementSettlesRatherThanCarryingOn() {
        val document = tapped()
        assertTrue(document.needsRepaint, "there is something to draw")
        // Past the end of the 350ms it is where it was going, and stays there. The document
        // itself still asks for frames, because its status line follows the clock.
        val arrived = cardHeights(document, 400L)
        assertEquals(arrived, cardHeights(document, 900L))
        assertEquals(115f, arrived[1], 0.01f)
    }

    @Test
    fun aSecondTapWhileMovingCarriesOnFromWhereItIs() {
        val document = tapped()
        val midway = cardHeights(document, 150L)[1]
        assertTrue(midway > 79f && midway < 115f, "it is part way there: $midway")
        // Tapping another item sends the first back and the second out, each from where it is.
        document.click(150f, 140f)
        cardHeights(document, 150L)
        val after = cardHeights(document, 300L)
        assertTrue(after[1] < midway, "the one that was growing turns back: $midway then ${after[1]}")
        assertTrue(after[0] > 79f, "and the newly tapped one has started: ${after[0]}")
    }
}
