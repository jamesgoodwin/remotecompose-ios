package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import io.github.jamesgoodwin.remotecompose.text.EstimatedTextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Patterns against `tools/rc-writer/pattern.rc` (see `buildPatternSample()` in the writer tool):
 * one card written once and called three times, each with its own title and its own contents.
 */
class PatternFixtureTest {

    private val bytes by lazy { fixture("pattern") }
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    private fun texts(): List<String> =
        document.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { document.strings[it.stringIndex] }

    @Test
    fun theCardIsWrittenOnceAndCalledThreeTimes() {
        val define = operations.filterIsInstance<Operation.PatternDefine>().single()
        assertEquals(1, define.paramIds.size, "one value parameter, the title")
        assertTrue(define.body.any { it is Operation.PatternArgument }, "and one slot")
        val calls = operations.filterIsInstance<Operation.PatternCall>()
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.id == define.id && it.argIds.size == 1 })
        // The body is the define's own list rather than operations in the stream around it.
        assertTrue(define.body.any { it is Operation.LayoutColumn })
        // Only the page's own column is in the stream; the card's is inside the body.
        assertEquals(1, operations.count { it is Operation.LayoutColumn })
    }

    @Test
    fun everyCallGetsItsOwnTitle() {
        val titles = texts().filter { it in listOf("Today", "Stock", "Level") }
        assertEquals(listOf("Today", "Stock", "Level"), titles)
    }

    @Test
    fun aValueTheBodyDerivesIsPerCallRatherThanShared() {
        // The caption is built inside the body from the title, so each expansion has to end up
        // with its own: sharing one id would show the last call's caption on every card.
        val captions = texts().filter { it.endsWith("· tap for detail") }
        assertEquals(
            listOf("Today · tap for detail", "Stock · tap for detail", "Level · tap for detail"),
            captions,
        )
    }

    @Test
    fun eachCallsBlockIsPutWhereTheBodyAsksForIt() {
        // The slot sits under the caption, so a card's contents follow its own caption and come
        // before the next card's title.
        val order = texts()
        assertEquals("Today · tap for detail", order[1])
        assertEquals("3 orders, 2 collected", order[2])
        assertEquals("Stock", order[3])
        assertEquals("Beans: 4 kg", order[5])
        assertEquals("Oat milk: 9 l", order[6], "a block may hold more than one thing")
    }

    @Test
    fun aBlockMayDrawRatherThanWriteText() {
        // The third card's block is a progress bar: a track and a fill, inside the card.
        val bars = document.opcodes.filterIsInstance<Opcode.DrawRoundRect>().filter { it.bottom - it.top == 10f }
        assertEquals(2, bars.size)
        assertEquals(196f, bars[0].right - bars[0].left)
        assertEquals(130f, bars[1].right - bars[1].left)
    }

    @Test
    fun theThreeCardsAreLaidOutOneBelowAnother() {
        // Each card's border, in the column's order, and none of them overlapping.
        val borders = document.opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .filter { it.paint.strokeWidth == 1f }
        assertEquals(3, borders.size)
        // The pattern gives every card the same box, whatever the caller puts in it.
        assertTrue(borders.all { it.right - it.left == borders[0].right - borders[0].left })
        assertTrue(borders.all { it.bottom - it.top == borders[0].bottom - borders[0].top })
    }

    @Test
    fun aCallToAPatternThatWasNeverDefinedDrawsNothing() {
        val withoutDefine = operations.filterNot { it is Operation.PatternDefine }
        val opcodes = RemoteComposeParser.build(withoutDefine, RemoteContext(), EstimatedTextMetrics)
        assertTrue(opcodes.filterIsInstance<Opcode.DrawText>().isEmpty(), "no card body to draw")
    }

    @Test
    fun expandingAgainDoesNotGrowTheDocument() {
        // A pattern's declarations get ids of their own, and those start again each frame: a
        // document that redraws for a minute should not have a minute's worth of ids in it.
        val loaded = RemoteComposeParser.load(bytes)
        loaded.frame(0L)
        val afterFirst = loaded.context.texts.size
        repeat(5) { loaded.frame(it * 16L) }
        assertEquals(afterFirst, loaded.context.texts.size)
    }

    @Test
    fun aPatternThatCallsItselfStopsAtTheDepthLimit() {
        // Hand-built rather than written, since the writer has no way to express it: a pattern
        // whose body calls itself would expand for ever without the limit.
        val call = Operation.PatternCall(7, emptyList())
        val recursive = Operation.PatternDefine(7, emptyList(), listOf(call, Operation.ContainerEnd))
        val operations = listOf(
            recursive,
            Operation.ContainerEnd,
            call,
            Operation.ContainerEnd,
        )
        val opcodes = RemoteComposeParser.build(operations, RemoteContext(), EstimatedTextMetrics)
        assertTrue(opcodes.isEmpty(), "it stopped, and drew nothing")
    }
}
