package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.FontFamilyKind
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `DATA_FONT` against `tools/rc-writer/font.rc` (see `buildFontSample()` in the writer tool): a
 * font file in the document, and a paint that names it by id in place of a built-in family.
 *
 * The document draws the same word twice, the second time in the embedded face, so the pair of
 * `DrawText` opcodes is what says the id reached the paint that needed it and no further.
 */
class FontFixtureTest {

    private val bytes by lazy { fixture("font") }
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    private val texts by lazy { document.opcodes.filterIsInstance<Opcode.DrawText>() }

    @Test
    fun theFontRecordCarriesTheFile() {
        val font = operations.filterIsInstance<Operation.FontData>().single()
        assertTrue(font.bytes.size > 1000, "the file came with it: ${font.bytes.size} bytes")
        // sfnt: a TrueType outline font opens with the version tag 0x00010000.
        assertEquals(listOf<Byte>(0, 1, 0, 0), font.bytes.take(4))
    }

    @Test
    fun onlyTheLastDrawNamesTheFont() {
        assertEquals(4, texts.size, "two labels and the word twice")
        for (text in texts.dropLast(1)) {
            assertNull(text.paint.font, "everything before the typeface is set is in the default")
            assertEquals(FontFamilyKind.DEFAULT, text.paint.fontFamily)
        }
        val font = assertNotNull(texts.last().paint.font)
        assertEquals(operations.filterIsInstance<Operation.FontData>().single().fontId, font.id)
    }

    @Test
    fun theSameWordIsDrawnInBothFaces() {
        // Same string id, same size, same colour: the typeface is the only difference, which is
        // what makes a screenshot of this document worth comparing.
        val plain = texts[1]
        val embedded = texts[3]
        assertEquals(plain.stringIndex, embedded.stringIndex)
        assertEquals(plain.paint.textSize, embedded.paint.textSize)
        assertEquals(plain.paint.color, embedded.paint.color)
    }

    @Test
    fun theFontIsOneObjectHoweverManyPaintsNameIt() {
        // The typeface behind it is built lazily and once; two frames asking for the same id must
        // not each build their own.
        val loaded = RemoteComposeParser.load(bytes)
        val first = loaded.frame(0L).opcodes.filterIsInstance<Opcode.DrawText>().last().paint.font
        val second = loaded.frame(16L).opcodes.filterIsInstance<Opcode.DrawText>().last().paint.font
        assertSame(assertNotNull(first), assertNotNull(second))
    }
}
