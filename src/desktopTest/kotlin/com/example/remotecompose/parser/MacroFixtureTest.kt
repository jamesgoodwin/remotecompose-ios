package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MACRO_FOR_EACH` against `tools/rc-writer/list.rc` (see `buildListSample()` in the writer
 * tool): one row written once and expanded per entry of an id list.
 */
class MacroFixtureTest {

    private val bytes by lazy { File("tools/rc-writer/list.rc").readBytes() }
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    private fun texts(): List<String> =
        document.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { document.strings[it.stringIndex] }

    @Test
    fun theBodyIsWrittenOnceAndTheDocumentSaysWhatToIterate() {
        val forEach = operations.filterIsInstance<Operation.PatternForEach>().single()
        val list = operations.filterIsInstance<Operation.IdList>().single { it.id == forEach.collectionId }
        assertEquals(4, list.ids.size)
        // One text component in the body, not one per entry.
        assertEquals(2, operations.filterIsInstance<Operation.LayoutText>().size) // the heading and the row
    }

    @Test
    fun everyEntryGetsItsOwnRow() {
        assertEquals(listOf("Menu", "Espresso", "Cortado", "Flat white", "Filter"), texts())
    }

    @Test
    fun theRowsAreLaidOutOneBelowAnother() {
        val opcodes = document.opcodes
        val rows = opcodes.filterIsInstance<Opcode.DrawRect>()
        assertEquals(4, rows.size, "one background per expansion")
        // A component draws in its own coordinates, so the row's place is the translate that
        // opened it: each is a row height plus the column's spacing below the one before.
        val tops = opcodes.indices
            .filter { opcodes[it] is Opcode.DrawRect }
            .map { (opcodes[it - 1] as Opcode.Translate).dy }
        assertEquals(tops.sorted(), tops)
        assertTrue(tops.zipWithNext().all { (a, b) -> b - a == 36f }, "28 high, 8 apart: $tops")
        assertTrue(rows.all { it.bottom - it.top == 28f && it.right - it.left == rows[0].right - rows[0].left })
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
        val drawn = opcodes.filterIsInstance<Opcode.DrawText>().size
        assertEquals(1, drawn, "only the heading is left")
    }
}
