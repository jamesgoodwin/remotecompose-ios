package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CORE_TEXT` and `TEXT_STYLE` against `tools/rc-writer/coretext.rc`. Both are runs of
 * `CommandParameters`: a key byte, then a value whose type comes from `TextStyle.PARAMETERS`
 * rather than from the wire, which is why one reader serves both.
 *
 * `CoreText` gives two of those keys its own meanings. `CoreText.read` fills an array by key and
 * hands it to the constructor, whose last two ints go to `mFlags` and `mTextStyleId` — so 23 is
 * the flags and 24 is the style the component inherits from, where `TextStyle` calls them
 * `flags` and `parentId`. The colour is key 3 for both, as it is everywhere else.
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
    fun theKeyCoreTextReadsAsAStyleIsTheOneTextStyleCallsAParent() {
        val texts = operations.filterIsInstance<Operation.CoreText>()
        val style = operations.filterIsInstance<Operation.TextStyleData>().single().parameters
        // Key 24: the first component points at the style that was written. The second names no
        // style, and -1 is that key's default, so nothing is written for it at all —
        // `countIfNotDefault` leaves a parameter out rather than spending bytes saying "none".
        assertEquals(style.int(Operation.StyleParameters.P_ID), texts[0].parameters.int(24))
        assertEquals(null, texts[1].parameters.int(24))
        // And the colour is key 3, where every other operation keeps one.
        assertEquals(0xFF1B1B1F.toInt(), texts[0].parameters.int(Operation.StyleParameters.P_COLOR))
        assertEquals(0xFF5F5A66.toInt(), texts[1].parameters.int(Operation.StyleParameters.P_COLOR))
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
        assertEquals(18f, drawn()[0].paint.textSize)
    }

    @Test
    fun eachComponentIsDrawnInItsOwnColour() {
        assertEquals(0.106f, drawn()[0].paint.color.red, 0.01f)
        assertEquals(0.373f, drawn()[1].paint.color.red, 0.01f)
    }
}
