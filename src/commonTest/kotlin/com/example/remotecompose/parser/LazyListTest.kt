package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/lazylist.rc`: five hundred rows, of which only the ones in view are built.
 *
 * The rows are written once inside a `LOOP_START` over the index, and a `CONDITIONAL_OPERATIONS`
 * block draws one only when its middle is within half a window of the window's middle. A
 * conditional that does not hold skips its block outright, so a row out of view costs its
 * arithmetic and nothing else: no component, no text, no rectangle.
 *
 * What this is not is lazy loading in the sense of fetching: the whole document is in memory
 * either way, and the loop still goes round five hundred times. What it saves is the building.
 */
class LazyListTest {

    private val bytes = fixture("lazylist")
    private val rows = 500
    private val rowHeight = 56f

    private class Frame(val opcodes: Int, val rows: List<String>)

    private fun scrolledBy(distance: Float): Frame {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        if (distance != 0f) {
            document.touchDown(150f, 340f)
            document.frame(0L)
            document.touchDrag(150f, 340f - distance)
            document.frame(0L)
            document.touchUp(150f, 340f - distance)
        }
        val frame = document.frame(0L)
        return Frame(
            opcodes = frame.opcodes.size,
            rows = frame.opcodes.filterIsInstance<Opcode.DrawText>()
                .mapNotNull { frame.strings[it.stringIndex] }
                .filter { it.startsWith("Row ") },
        )
    }

    @Test
    fun theWholeListFitsInUnderAKilobyte() {
        // Five hundred rows written once: the document says how to draw a row and how many there
        // are, not what each of the five hundred looks like.
        assertTrue(bytes.size < 1024, "the document is ${bytes.size} bytes")
    }

    @Test
    fun onlyTheRowsInViewAreBuilt() {
        val atTop = scrolledBy(0f)
        assertTrue(atTop.rows.size in 5..10, "a window's worth, not five hundred: ${atTop.rows.size}")
        assertEquals("Row 0", atTop.rows.first())
    }

    @Test
    fun theCostDoesNotGrowAsTheListIsScrolled() {
        // The point of the whole thing: what it costs to draw is the same at the end as at the
        // start, though there are five hundred rows between them.
        val counts = listOf(0f, 500f, 5000f, 20000f, 27000f).map { scrolledBy(it).opcodes }
        val smallest = counts.min()
        val largest = counts.max()
        assertTrue(largest - smallest <= 6, "opcodes across the list: $counts")
        assertTrue(largest < 80, "and a small number of them: $largest")
    }

    @Test
    fun theRowsBuiltAreTheOnesUnderTheWindow() {
        // Not merely a constant number of rows: the right ones. A row's place is its index times
        // its height, so where the scroll is says which indices should be there.
        for (distance in listOf(500f, 5000f, 20000f)) {
            val frame = scrolledBy(distance)
            val expectedFirst = (distance / rowHeight).toInt()
            val first = frame.rows.first().removePrefix("Row ").trim().toInt()
            assertTrue(
                first in (expectedFirst - 1)..(expectedFirst + 1),
                "scrolled $distance expects about row $expectedFirst, built from $first",
            )
        }
    }

    @Test
    fun everyRowKnowsItsOwnNumber() {
        // Each pass round the loop declares the same ids, so without a copy of its own every row
        // would show whatever the last pass computed. They run consecutively instead.
        val built = scrolledBy(5000f).rows.map { it.removePrefix("Row ").trim().toInt() }
        assertEquals(built.sorted(), built, "in order")
        assertEquals(built.distinct(), built, "and each one different")
        assertEquals(built.first() + built.size - 1, built.last(), "with no gaps")
    }

    @Test
    fun theEndOfTheListIsReachable() {
        // The extent comes from an empty box as tall as all five hundred rows, so the scroll runs
        // the whole way even though nothing but the visible rows is ever built.
        val atEnd = scrolledBy(rows * rowHeight)
        val last = atEnd.rows.last().removePrefix("Row ").trim().toInt()
        assertTrue(last >= rows - 12, "the far end is reachable: got to row $last of $rows")
    }
}
