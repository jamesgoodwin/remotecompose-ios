package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lists against `tools/rc-writer/list.rc` (see `buildListSample()` in the writer tool): a row
 * body expanded per entry of an id list by `MACRO_FOR_EACH`, a `FLOAT_LIST` read by the
 * expression operators, and a `DYNAMIC_FLOAT_LIST` written and read back.
 */
class MacroFixtureTest {

    private val bytes by lazy { fixture("list") }
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    private fun texts(): List<String> =
        document.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { document.strings[it.stringIndex] }

    @Test
    fun theBodyIsWrittenOnceAndTheDocumentSaysWhatToIterate() {
        val forEach = operations.filterIsInstance<Operation.PatternForEach>().single()
        val list = operations.filterIsInstance<Operation.IdList>().single { it.id == forEach.collectionId }
        assertEquals(4, list.ids.size)
        // One text component in the body, not one per entry: the heading, the row, the caption.
        assertEquals(3, operations.filterIsInstance<Operation.LayoutText>().size)
    }

    @Test
    fun everyEntryGetsItsOwnRow() {
        assertEquals(listOf("Espresso", "Cortado", "Flat white", "Filter"), texts().drop(1).dropLast(1))
    }

    @Test
    fun theRowsAreLaidOutOneBelowAnother() {
        val opcodes = document.opcodes
        // The row backgrounds, which are the full width of the column's content.
        val rows = opcodes.filterIsInstance<Opcode.DrawRect>().filter { it.right - it.left == 208f }
        assertEquals(4, rows.size, "one background per expansion")
        // A component draws in its own coordinates, so the row's place is the translate that
        // opened it: each is a row height plus the column's spacing below the one before.
        val tops = opcodes.indices
            .filter { opcodes[it].let { op -> op is Opcode.DrawRect && op.right - op.left == 208f } }
            .map { (opcodes[it - 1] as Opcode.Translate).dy }
        assertEquals(tops.sorted(), tops)
        assertTrue(tops.zipWithNext().all { (a, b) -> b - a == 36f }, "28 high, 8 apart: $tops")
        assertTrue(rows.all { it.bottom - it.top == 28f && it.right - it.left == rows[0].right - rows[0].left })
    }

    /** The bars sized from the float list, in the order the document draws them. */
    private fun bars(width: Float): List<Float> = document.opcodes.filterIsInstance<Opcode.DrawRect>()
        .filter { it.right - it.left == width }
        .map { it.bottom - it.top }

    @Test
    fun everyBarIsOneEntryOfTheFloatList() {
        // A_DEREF of each entry, times nine: 2.4, 2.8, 3.4, 3.0.
        val heights = bars(20f)
        assertEquals(4, heights.size)
        listOf(21.6f, 25.2f, 30.6f, 27.0f).forEachIndexed { i, expected ->
            assertEquals(expected, heights[i], 0.01f)
        }
    }

    @Test
    fun theCaptionCountsAndAveragesTheListItself() {
        // A_LEN and A_AVG over 2.4, 2.8, 3.4, 3.0.
        assertTrue(texts().any { it == "n=4  avg=2.90" }, "the caption reads the list: ${texts()}")
    }

    @Test
    fun aDynamicListIsWrittenThenReadBack() {
        // Three entries with nothing on the wire: 1, 2 and 2*2, summed and scaled by ten.
        val total = document.opcodes.filterIsInstance<Opcode.DrawRect>().single { it.bottom - it.top == 12f }
        assertEquals(70f, total.right - total.left, 0.01f)
    }

    @Test
    fun anEmptyFloatListReadsAsZeroRatherThanFailing() {
        val stripped = operations.map {
            if (it is Operation.FloatListData) Operation.FloatListData(it.id, FloatArray(0)) else it
        }
        val context = com.example.remotecompose.runtime.RemoteContext()
        val opcodes = RemoteComposeParser.build(
            stripped, context, com.example.remotecompose.text.EstimatedTextMetrics,
        )
        // Every bar collapses to nothing, and the document still renders.
        assertTrue(opcodes.filterIsInstance<Opcode.DrawRect>().none { it.right - it.left == 20f })
        assertTrue(opcodes.filterIsInstance<Opcode.DrawText>().isNotEmpty())
    }

    @Test
    fun aForEachOverNothingDrawsNothing() {
        // The same document with its list emptied: the body has no entry to expand for.
        val stripped = operations.map {
            if (it is Operation.IdList) Operation.IdList(it.id, emptyList()) else it
        }
        val context = com.example.remotecompose.runtime.RemoteContext()
        val opcodes = RemoteComposeParser.build(
            stripped, context, com.example.remotecompose.text.EstimatedTextMetrics,
        )
        val drawn = opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { context.texts[it.stringIndex] }
        assertTrue(drawn.none { it in listOf("Espresso", "Cortado", "Flat white", "Filter") }, "no rows: $drawn")
        assertTrue("Menu" in drawn, "the heading is still there")
    }
}
