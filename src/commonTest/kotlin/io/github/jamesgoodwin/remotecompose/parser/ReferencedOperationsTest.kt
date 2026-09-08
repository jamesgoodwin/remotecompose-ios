package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `REFERENCED_OPERATIONS` and `INCLUDE_REFERENCED_OPERATIONS` against
 * `tools/rc-writer/referenced.rc`: a badge written once and drawn on each of three cards.
 *
 * The block is kept under an id rather than run where it is written, and an include puts it
 * wherever it is named. `IncludeReferencedOperations.materialize` re-reads the block's bytes
 * through a forked remap context marked as inside a macro, so what the block declares belongs to
 * that inclusion alone — the badge's label is a different id on each card though the string
 * behind all three is the one in the file.
 */
class ReferencedOperationsTest {

    private val bytes = fixture("referenced")
    private val operations = OperationReader.readAll(bytes)

    private class RemoteDocumentFrame(val opcodes: List<Opcode>, val strings: Map<Int, String>)

    private fun drawn(): RemoteDocumentFrame {
        val document = RemoteComposeParser.load(bytes)
        val frame = document.frame(0L)
        return RemoteDocumentFrame(frame.opcodes, frame.strings)
    }

    /** The badge's label as it is drawn: one entry per inclusion, id and string. */
    private fun labels(): List<Pair<Int, String?>> {
        val frame = drawn()
        return frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .map { it.stringIndex to frame.strings[it.stringIndex] }
            .filter { it.second == "Verified" }
    }

    @Test
    fun theBlockIsInTheFileOnceAndNamedThreeTimes() {
        assertEquals(1, operations.filterIsInstance<Operation.ReferencedOperations>().size)
        assertEquals(3, operations.filterIsInstance<Operation.IncludeReferencedOperations>().size)
        assertEquals(
            1,
            operations.filterIsInstance<Operation.TextData>().count { it.text == "Verified" },
            "the label is written once, not once a card",
        )
    }

    @Test
    fun everyIncludeNamesTheBlockThatWasWritten() {
        val block = operations.filterIsInstance<Operation.ReferencedOperations>().single()
        val includes = operations.filterIsInstance<Operation.IncludeReferencedOperations>()
        assertTrue(includes.all { it.id == block.id }, "the same block each time: $includes")
    }

    @Test
    fun theBadgeIsDrawnOncePerInclusion() {
        val badges = drawn().opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .filter { it.right - it.left == 96f && it.bottom - it.top == 20f }
        assertEquals(3, badges.size)
        assertEquals(3, labels().size)
    }

    @Test
    fun theBlockIsNotDrawnWhereItIsWritten() {
        // A fourth badge would mean the block had been run on the way past as well as included.
        // It is written before the layout root, so one would land outside every card.
        val badges = drawn().opcodes.filterIsInstance<Opcode.DrawRoundRect>()
        assertEquals(3, badges.size, "three, not four")
    }

    @Test
    fun eachBadgeSitsOnItsOwnCard() {
        // The three are at three different heights, which is what says the include drew where it
        // was named rather than all three landing at the same place.
        val opcodes = drawn().opcodes
        val badges = opcodes.withIndex()
            .filter { (_, op) -> op is Opcode.DrawRoundRect && op.bottom - op.top == 20f }
            .map { (index, _) ->
                opcodes.take(index).filterIsInstance<Opcode.Translate>().fold(0f) { sum, t -> sum + t.dy }
            }
        assertEquals(3, badges.size)
        assertEquals(badges.sorted(), badges, "down the page")
        assertEquals(badges.distinct(), badges, "at three different heights")
    }

    @Test
    fun eachInclusionNamesItsOwnCopyOfWhatTheBlockDeclares() {
        // The remap context is forked per inclusion, so the label's `DATA_TEXT` id is a fresh one
        // each time even though there is one record behind all three.
        val drawnLabels = labels()
        assertEquals(3, drawnLabels.size)
        assertEquals(3, drawnLabels.map { it.first }.distinct().size, "three ids: $drawnLabels")
        assertTrue(drawnLabels.all { it.second == "Verified" }, "and one string behind them")
    }

    @Test
    fun theCardsAroundTheBadgesAreTheirOwn() {
        // The block is the badge and nothing else: each card still says its own name.
        val frame = drawn()
        val texts = frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
        for (title in listOf("Harbour Lights", "Fen Causeway", "Cold Fell")) {
            assertTrue(title in texts, "$title is on the page")
        }
    }
}
