package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RUN_ACTION` against `tools/rc-writer/runaction.rc`.
 *
 * `RunActionOperation.paint` runs every `ActionOperation` in its block, and its `isDirty` is
 * hardcoded true with a `markNotDirty` that does nothing — so it runs on every paint rather than
 * once. `paint` is only reached for a component that is painted, which is what makes it a way of
 * saying "while this is on screen".
 *
 * The fixture is two counters, one on a card always drawn and one on a card the buttons hide.
 */
class RunActionTest {

    private val bytes = fixture("runaction")

    /**
     * Clicks the [index]th button of the row under the cards, found from where it was last laid
     * out: hiding a card reflows the column, so the buttons do not stay put.
     */
    private fun click(document: RemoteComposeDocument, index: Int) {
        val button = document.hitRegions[index]
        document.click((button.left + button.right) / 2f, (button.top + button.bottom) / 2f)
    }

    /** The numbers on the two cards, in the order they are drawn. */
    private fun counts(document: RemoteComposeDocument): List<Int> {
        val frame = document.frame(0L)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { frame.strings[it.stringIndex] }
            .mapNotNull { it.trim().toIntOrNull() }
    }

    @Test
    fun theBlockCarriesNothingButTheActionsInIt() {
        val operations = OperationReader.readAll(bytes)
        val at = operations.indexOfFirst { it is Operation.RunAction }
        assertTrue(at >= 0, "the fixture has one")
        // `read` writes no fields, and a `ContainerEnd` closes the block as it closes every other.
        val block = operations.drop(at + 1).takeWhile { it !is Operation.ContainerEnd }
        assertEquals(1, block.size, "one action: $block")
        assertTrue(block[0] is Operation.ValueFloatExpressionChange)
    }

    @Test
    fun aCounterAdvancesOnceForEveryFrameItIsDrawnIn() {
        // The block runs when the component is painted, which is after the frame that shows the
        // number has read it — so a frame shows what the frames before it counted.
        val document = RemoteComposeParser.load(bytes)
        assertEquals(listOf(0, 0), counts(document), "nothing counted before the first frame")
        assertEquals(listOf(1, 1), counts(document))
        assertEquals(listOf(2, 2), counts(document))
    }

    @Test
    fun aHiddenComponentRunsNothing() {
        // `paint` is not reached for a component that is gone, so its block does not run — while
        // the card beside it carries on counting.
        val document = RemoteComposeParser.load(bytes)
        repeat(3) { document.frame(0L) }
        val before = counts(document)
        assertEquals(2, before.size, "both cards showing")

        // The Hide button, in the row under the two cards.
        click(document, 0)
        repeat(4) { document.frame(0L) }
        val after = counts(document)
        assertEquals(1, after.size, "only the card that is left")
        assertTrue(after[0] > before[0], "which kept counting: ${before[0]} then ${after[0]}")
    }

    @Test
    fun aHiddenComponentTakesUpWhereItLeftOff() {
        // Nothing is reset by being hidden: the count stands still and starts again from there.
        val document = RemoteComposeParser.load(bytes)
        repeat(3) { document.frame(0L) }
        val stopped = counts(document)[1]

        click(document, 0)
        repeat(5) { document.frame(0L) }
        click(document, 1)
        document.frame(0L)
        val resumed = counts(document)
        assertEquals(2, resumed.size, "showing again")
        assertTrue(
            resumed[1] in stopped..(stopped + 2),
            "it stood still while it was away: $stopped then ${resumed[1]}",
        )
        assertTrue(resumed[0] > resumed[1] + 3, "and the other one did not: ${resumed[0]}")
    }
}
