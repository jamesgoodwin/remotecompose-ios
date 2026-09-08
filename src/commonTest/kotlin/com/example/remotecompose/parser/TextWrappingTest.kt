package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text that will not fit on one line, against `tools/rc-writer/wrap.rc`.
 *
 * `TextLayout` and `CoreText` both hand their layout to the host: `computeWrapSize` calls
 * `PaintContext.layoutComplexText` and keeps a `ComputedTextLayout`, and `paintingComponent`
 * gives it back to `drawComplexText`. Neither the breaking nor the drawing is in remote-core, so
 * what a document says about it is the parameters — the alignment, the overflow, the line limit,
 * the two line-height numbers and the justification mode — and those are what this checks.
 *
 * This renderer has no whole-block draw, so a broken text becomes one `DrawText` a line, at the
 * baseline the layout put it on.
 */
class TextWrappingTest {

    private val bytes = fixture("wrap")

    /** One text component's worth of draws: its lines, and how many runs each was drawn as. */
    private class Block(val lines: List<String>, val baselines: List<Float>, val runs: List<Int>)

    /**
     * The page's text components, in the order they are drawn.
     *
     * A single-line component draws at y = 0 and a broken one at a baseline per line, so a draw
     * at 0 or at a baseline above the last one begins a new component; draws sharing a baseline
     * are the words of one justified line.
     */
    private fun blocks(): List<Block> {
        val document = RemoteComposeParser.parse(bytes)
        val out = mutableListOf<MutableList<Pair<Float, MutableList<String>>>>()
        var last = Float.MAX_VALUE
        for (opcode in document.opcodes) {
            if (opcode !is Opcode.DrawText) continue
            val text = document.strings[opcode.stringIndex] ?: continue
            when {
                out.isEmpty() || opcode.y == 0f || opcode.y < last ->
                    out.add(mutableListOf(opcode.y to mutableListOf(text)))
                opcode.y == last -> out.last().last().second += text
                else -> out.last().add(opcode.y to mutableListOf(text))
            }
            last = if (opcode.y == 0f) Float.MAX_VALUE else opcode.y
        }
        return out.map { block ->
            Block(
                lines = block.map { it.second.joinToString(" ") },
                baselines = block.map { it.first },
                runs = block.map { it.second.size },
            )
        }
    }

    /** Every drawn string, whichever block it belongs to. */
    private fun texts(): List<String> {
        val document = RemoteComposeParser.parse(bytes)
        return document.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { document.strings[it.stringIndex] }
    }

    private fun blockContaining(word: String): Block =
        blocks().first { block -> block.lines.any { word in it } }

    /** The expanding paragraph's lines, as they stand. */
    private fun essay(document: RemoteComposeDocument): List<String> {
        val frame = document.frame(0L)
        val drawn = frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { frame.strings[it.stringIndex] }
        val from = drawn.indexOfFirst { it.startsWith("A document is a list") }
        return drawn.drop(from).takeWhile { !it.startsWith("Show ") }
    }

    /**
     * Presses the one button on the page, wherever the layout has just put it, and lets the
     * `ANIMATION_SPEC` that follows finish: the words swap at once, the heights ease.
     */
    private fun press(document: RemoteComposeDocument, atMillis: Long = 2000L) {
        val button = document.hitRegions.single()
        document.click((button.left + button.right) / 2f, (button.top + button.bottom) / 2f)
        // The frame after the tap is where the new layout is worked out and the animation is
        // aimed at it; a later one is where it has arrived.
        document.frame(0L)
        document.frame(atMillis)
    }

    /** How far down the page the paragraph under the expander has been pushed. */
    private fun topOfTheParagraphBelow(document: RemoteComposeDocument, atMillis: Long): Float {
        val frame = document.frame(atMillis)
        val at = frame.opcodes.indexOfFirst {
            it is Opcode.DrawText && frame.strings[it.stringIndex]?.startsWith("The document says") == true
        }
        return frame.opcodes.take(at).filterIsInstance<Opcode.Translate>().fold(0f) { sum, t -> sum + t.dy }
    }

    @Test
    fun aParagraphCanBeExpandedAndTheWordsAreBrokenAgain() {
        // One int chooses between two versions of the same words through `LAYOUT_STATE`: three
        // lines and an ellipsis, or all of them. Nothing is re-sent — the breaks are worked out
        // again from the width the version that is showing has.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        val collapsed = essay(document)
        assertEquals(3, collapsed.size, "three lines: $collapsed")
        assertTrue(collapsed.last().endsWith("…"), "and it says it was cut")

        press(document)
        val opened = essay(document)
        assertTrue(opened.size > collapsed.size, "more lines once opened: ${opened.size}")
        assertTrue(opened.none { it.endsWith("…") }, "and nothing cut off: $opened")
    }

    @Test
    fun theOpenedParagraphSaysTheWholeOfWhatTheClosedOneStarted() {
        // The same string either way, so the difference is where it was broken and not what it
        // says. The lines the closed one showed are the opened one's first lines, less the cut.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        val collapsed = essay(document)
        press(document)
        val opened = essay(document)
        assertEquals(collapsed.dropLast(1), opened.take(collapsed.size - 1), "broken the same way up to the cut")
        assertTrue(
            opened.joinToString(" ").endsWith("the width asks for."),
            "and it runs to the end: ${opened.last()}",
        )
    }

    @Test
    fun theButtonSaysWhicheverThingTheTapWouldDo() {
        // The button is a `LAYOUT_STATE` over the same int, so the one showing is the one that
        // does the opposite of what the paragraph is doing.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        assertTrue("Show more" in texts(), "closed")
        press(document)
        val opened = document.frame(0L).let { frame ->
            frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
        }
        assertTrue("Show less" in opened, "open: $opened")
        assertTrue("Show more" !in opened, "and only the one")
    }

    @Test
    fun openingItPushesWhatIsUnderItDownThePage() {
        // The reflow is a layout change and not a reveal: everything below moves.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        val closed = topOfTheParagraphBelow(document, 0L)
        press(document)
        assertTrue(topOfTheParagraphBelow(document, 2000L) > closed + 40f, "pushed down from $closed")
    }

    @Test
    fun theCardEasesToItsNewHeightRatherThanArrivingAtIt() {
        // `ANIMATION_SPEC`'s motion half: a component that has been re-measured is drawn on its
        // way there. The card is laid out at its open height on the frame after the tap and drawn
        // at its closed one, and the page below rides down with it over the third of a second the
        // spec asks for.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        val closed = topOfTheParagraphBelow(document, 0L)

        val button = document.hitRegions.single()
        document.click((button.left + button.right) / 2f, (button.top + button.bottom) / 2f)
        val path = listOf(0L, 80L, 170L, 260L, 340L, 1000L).map { topOfTheParagraphBelow(document, it) }
        assertEquals(closed, path.first(), 0.5f, "where it was, on the frame of the tap")
        assertEquals(path.sorted(), path, "and downwards from there: $path")
        assertTrue(path[2] > closed + 10f, "part way by the middle of it: ${path[2]}")
        assertTrue(path[2] < path.last() - 10f, "and not yet arrived")
        assertEquals(path[4], path.last(), 0.5f, "settled by the third of a second it asked for")
    }

    @Test
    fun theButtonRidesDownWithTheCardRatherThanJumping() {
        // The button is outside the card, so the card's clip does not take it away while it
        // opens; a spec of its own is what keeps it under the card's edge on the way.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        fun buttonTop(): Float = document.hitRegions.single().top
        val closed = buttonTop()
        val b = document.hitRegions.single()
        document.click((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
        val path = listOf(0L, 100L, 200L, 1000L).map { document.frame(it); buttonTop() }
        assertEquals(closed, path.first(), 0.5f)
        assertEquals(path.sorted(), path, "down the page: $path")
        assertTrue(path.last() > closed + 40f, "and well below where it started")
    }

    @Test
    fun aParagraphIsDrawnAsOneRunPerLine() {
        // The library gives the host a laid-out block; here each line is its own draw, so a
        // paragraph that broke four ways is four `DrawText`s and not one.
        val paragraph = blockContaining("The document says how wide").lines
        assertTrue(paragraph.size >= 3, "broken into lines: $paragraph")
        assertEquals(
            "The document says how wide the text may be and how many lines it may take; where the " +
                "breaks fall is worked out from that and from the font it is drawn in.",
            paragraph.joinToString(" "),
            "and the words are all still there, in order",
        )
    }

    @Test
    fun noLineIsWiderThanTheRoomItHas() {
        // The card is 268 wide less 8 of padding either side. Nothing is measured here — the
        // point is that every line was broken before it reached the edge.
        val document = RemoteComposeParser.parse(bytes)
        val metrics = com.example.remotecompose.text.EstimatedTextMetrics
        val paragraph = blockContaining("The document says how wide").lines
        val paint = document.opcodes.filterIsInstance<Opcode.DrawText>()
            .first { document.strings[it.stringIndex]?.startsWith("The document") == true }.paint
        for (line in paragraph) {
            assertTrue(metrics.measure(line, paint).width <= 252f, "\"$line\" fits")
        }
    }

    @Test
    fun aLineLimitCutsTheRestOff() {
        val limited = blockContaining("Overflow says what becomes").lines
        assertEquals(2, limited.size, "two lines were allowed: $limited")
    }

    @Test
    fun whatWasCutIsMarkedWithAnEllipsis() {
        val limited = blockContaining("Overflow says what becomes").lines
        assertTrue(limited.last().endsWith("…"), "the last line says it was cut: ${limited.last()}")
    }

    @Test
    fun anEllipsisMayGoAtTheStartOrTheMiddleInstead() {
        // A path is worth more at its end than at its beginning, which is what the other two
        // overflows are for.
        val start = texts().single { it.startsWith("…") }
        assertTrue(start.endsWith("dawn-chorus.wav"), "the end of the path is kept: $start")

        val middle = texts().single { it.contains("…") && !it.startsWith("…") && !it.endsWith("…") }
        assertTrue(middle.startsWith("/Volumes"), "both ends kept: $middle")
        assertTrue(middle.endsWith("dawn-chorus.wav"))
    }

    @Test
    fun cuttingOneLineOutOfAStringWithNoSpacesInventsNone() {
        // The path breaks by character rather than by word, and the pieces are put back as they
        // were: an ellipsis is cut from the text, not from the lines it was already broken into.
        for (line in texts().filter { "dawn-chorus" in it }) {
            assertTrue("field-recordings" in line || "…" in line.substringBefore("field"), line)
            assertTrue("- " !in line, "no space where the break was: $line")
        }
    }

    @Test
    fun aStringWithALineBreakOfItsOwnIsBrokenThere() {
        val block = blockContaining("Written on one line")
        assertEquals(listOf("Written on one line", "and continued on another"), block.lines)
    }

    @Test
    fun aLineHeightMultiplierSpacesTheLinesFurtherApart() {
        // One and a half, against the plain paragraph above it.
        val document = RemoteComposeParser.parse(bytes)
        val draws = document.opcodes.filterIsInstance<Opcode.DrawText>()
        fun gapAfter(prefix: String): Float {
            val at = draws.indexOfFirst { document.strings[it.stringIndex]?.startsWith(prefix) == true }
            return draws[at + 1].y - draws[at].y
        }
        val plain = gapAfter("The document says how wide")
        val spaced = gapAfter("Written on one line")
        assertEquals(plain * 1.5f, spaced, 0.5f)
    }

    @Test
    fun aJustifiedParagraphHasItsWordsPushedApart() {
        // Every broken line is drawn word by word so the line can be stretched to the width; the
        // line the paragraph ends on is left as one run.
        val document = RemoteComposeParser.parse(bytes)
        val draws = document.opcodes.filterIsInstance<Opcode.DrawText>()
        val words = draws.filter { document.strings[it.stringIndex] == "pushed" }
        assertEquals(1, words.size, "a justified line is drawn a word at a time")

        // The words of that line run left to right and the last of them ends at the right edge.
        val line = draws.filter { it.y == words[0].y }
        assertEquals(line.map { it.x }.sorted(), line.map { it.x }, "in order across the line")
        assertTrue(line.size >= 4, "several words: ${line.map { document.strings[it.stringIndex] }}")
    }

    @Test
    fun theLastLineOfAJustifiedParagraphIsNotStretched() {
        // Every line but the closing one is drawn a word at a time so it can be stretched; the
        // closing one is one run, which is what leaves it short of the right edge.
        val justified = blockContaining("The words on a broken line")
        assertTrue(justified.runs.dropLast(1).all { it > 1 }, "stretched: ${justified.runs}")
        assertEquals(1, justified.runs.last(), "except the last: ${justified.lines.last()}")
    }
}
