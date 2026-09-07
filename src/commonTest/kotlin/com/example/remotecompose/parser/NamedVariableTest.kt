package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.RemoteContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `NAMED_VARIABLE` against `tools/rc-writer/named.rc`: a card that names every value it draws so
 * a host can fill them in without knowing an id.
 *
 * The operation itself only registers the name — `loadVariableName(name, id, type)`. What makes
 * it worth having is the other side: a host setting the value by that name.
 */
class NamedVariableTest {

    private val bytes = fixture("named")

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    private fun texts(document: RemoteComposeDocument): List<String> {
        val frame = document.frame(0L)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
    }

    /** The bar the amount drives, which is the second rectangle: the first is the background. */
    private fun bar(document: RemoteComposeDocument): Opcode.DrawRect =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>()[1]

    @Test
    fun everyNameIsDeclaredWithItsKind() {
        val declared = OperationReader.readAll(bytes).filterIsInstance<Operation.NamedVariable>()
        assertEquals(listOf("title", "amount", "accent", "count"), declared.map { it.name })
        assertEquals(
            listOf(
                RemoteContext.NAMED_STRING,
                RemoteContext.NAMED_FLOAT,
                RemoteContext.NAMED_COLOR,
                RemoteContext.NAMED_INT,
            ),
            declared.map { it.type },
        )
        // Each names a value that exists, rather than a number of its own.
        assertTrue(declared.all { it.id > 0 })
    }

    @Test
    fun theDocumentSaysWhatAHostMayFillIn() {
        val document = loaded()
        assertEquals(setOf("title", "amount", "accent", "count"), document.namedValues.keys)
        assertEquals(RemoteContext.NAMED_COLOR, document.namedValues.getValue("accent").type)
    }

    @Test
    fun aHostSetsAStringByName() {
        val document = loaded()
        assertTrue("Awaiting host" in texts(document))
        assertTrue(document.setNamedString("title", "Bean & Bone"))
        assertTrue("Bean & Bone" in texts(document))
        assertFalse("Awaiting host" in texts(document))
    }

    @Test
    fun aHostSetsAFloatByNameAndTheDocumentRedraws() {
        val document = loaded()
        assertEquals(0f, bar(document).right - bar(document).left, 0.01f, "nothing to show yet")
        assertTrue(document.setNamedFloat("amount", 37.5f))
        // The bar is the amount times 2.68, and the text is the same value written out.
        assertEquals(100.5f, bar(document).right - bar(document).left, 0.5f)
        assertTrue(texts(document).any { it.contains("37.5") }, "and it reads it out: ${texts(document)}")
    }

    @Test
    fun aHostSetsAColourByName() {
        val document = loaded()
        val before = bar(document).paint.color
        assertTrue(document.setNamedColor("accent", 0xFFE53935.toInt()))
        val after = bar(document).paint.color
        assertTrue(after != before, "the bar took the new colour")
        assertEquals(0.898f, after.red, 0.01f)
        assertEquals(0.223f, after.green, 0.01f)
    }

    @Test
    fun aValueNamedButNotDrawnIsStillOfferedToTheHost() {
        // `count` is declared and never used in the document; a host can still set it, which is
        // what lets a document declare more inputs than it happens to show.
        val document = loaded()
        assertTrue(document.setNamedInteger("count", 12))
        assertEquals(12, document.context.ints[document.namedValues.getValue("count").id])
    }

    @Test
    fun aHostPushingSomethingWithNoHomeIsToldSo() {
        val document = loaded()
        assertFalse(document.setNamedFloat("nope", 1f), "no such name")
        assertFalse(document.setNamedFloat("title", 1f), "named, but not as a float")
        assertFalse(document.setNamedColor("amount", 0), "named, but not as a colour")
    }

    @Test
    fun settingOneAsksForAFrame() {
        val document = loaded()
        document.frame(0L)
        document.setNamedFloat("amount", 1f)
        assertTrue(document.needsRepaint)
    }
}
