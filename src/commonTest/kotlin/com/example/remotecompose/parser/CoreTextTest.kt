package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CORE_TEXT` and `TEXT_STYLE` against `tools/rc-writer/coretext.rc`. Both are runs of
 * `CommandParameters`: a key byte, then a value whose type comes from `TextStyle.PARAMETERS`
 * rather than from the wire, which is why one reader serves both.
 *
 * `CoreText` gives two of those keys its own meanings — 23 is the style it points at and 24 its
 * colour, where `TextStyle` calls them `flags` and `parentId`. That is read off the bytes the
 * writer produces rather than assumed, and the fixture varies both so a coincidence would show.
 */
class CoreTextTest {

    private val bytes = fixture("coretext")
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    private fun drawn(): List<Opcode.DrawText> = document.opcodes.filterIsInstance<Opcode.DrawText>()

    @Test
    fun theStyleIsABundleOfParametersUnderAnId() {
        val style = operations.filterIsInstance<Operation.TextStyleData>().single().parameters
        assertEquals(22f, style.float(Operation.StyleParameters.P_FONT_SIZE))
        assertEquals(700f, style.float(Operation.StyleParameters.P_FONT_WEIGHT))
        assertEquals(2, style.int(Operation.StyleParameters.P_TEXT_ALIGN))
        assertTrue(style.int(Operation.StyleParameters.P_ID) != null, "and it has an id to be found by")
    }

    @Test
    fun aComponentLeadsWithItsTextAndCarriesTheRestAsParameters() {
        val texts = operations.filterIsInstance<Operation.CoreText>()
        assertEquals(2, texts.size)
        // The leading int is the text, not the component: the two records lead with the two
        // strings' ids, and each names its own component under P_ID.
        val strings = operations.filterIsInstance<Operation.TextData>().associate { it.id to it.text }
        assertEquals(listOf("Core text", "styled through TEXT_STYLE"), texts.map { strings[it.textId] })
        assertTrue(texts.all { it.parameters.int(Operation.StyleParameters.P_ID) != null })
    }

    @Test
    fun theTwoKeysCoreTextReadsDifferentlyVaryWithWhatWasAsked() {
        val texts = operations.filterIsInstance<Operation.CoreText>()
        // Key 24 is the colour: two different colours were asked for and both land there.
        assertEquals(0xFF1B1B1F.toInt(), texts[0].parameters.int(24))
        assertEquals(0xFF5F5A66.toInt(), texts[1].parameters.int(24))
        // Key 23 is the style: the first points at one, the second at nothing.
        assertEquals(-1, texts[1].parameters.int(23))
        assertTrue(texts[0].parameters.int(23) != -1, "the first points at a style")
    }

    @Test
    fun bothComponentsAreDrawn() {
        assertEquals(
            listOf("Core text", "styled through TEXT_STYLE"),
            drawn().map { document.strings[it.stringIndex] },
        )
    }

    @Test
    fun aComponentTakesFromTheStyleWhatItDidNotStateItself() {
        // `applyStyle` fills in what the component left unset: it never said a weight, so the
        // style's 700 reaches it, while the one pointing at no style stays at the default.
        assertEquals(700, drawn()[0].paint.fontWeight)
        assertEquals(400, drawn()[1].paint.fontWeight)
    }

    @Test
    fun aComponentKeepsWhatItDidStateOverTheStyle() {
        // The component writes its own font size, so the style's 22 does not displace it. That is
        // the same precedence `applyStyle` has, which only fills in nulls.
        assertEquals(16f, drawn()[0].paint.textSize)
    }

    @Test
    fun eachComponentIsDrawnInItsOwnColour() {
        assertEquals(0.106f, drawn()[0].paint.color.red, 0.01f)
        assertEquals(0.373f, drawn()[1].paint.color.red, 0.01f)
    }
}
