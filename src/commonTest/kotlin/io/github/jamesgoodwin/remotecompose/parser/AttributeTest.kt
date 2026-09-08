package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ATTRIBUTE_TEXT` and `ATTRIBUTE_IMAGE` against `tools/rc-writer/attributes.rc`: values the
 * document reads off a string and a bitmap rather than being told, each driving a bar.
 *
 * They are the siblings of `ATTRIBUTE_COLOR`, which reads a component off a colour, and are the
 * same shape on the wire: `[id][sourceId][short type][short argCount]`.
 */
class AttributeTest {

    private val bytes = fixture("attributes")
    private val operations by lazy { OperationReader.readAll(bytes) }
    private val document by lazy { RemoteComposeParser.parse(bytes) }

    /** The four bars, in the order the document draws them. */
    private fun barWidths(): List<Float> =
        document.opcodes.filterIsInstance<Opcode.DrawRect>().map { it.right - it.left }

    @Test
    fun eachAttributeNamesWhatItReadsAndWhichPartOfIt() {
        val text = operations.filterIsInstance<Operation.TextAttribute>()
        assertEquals(listOf(0, 6), text.map { it.type }, "MEASURE_WIDTH then TEXT_LENGTH")
        assertTrue(text.all { it.textId == text[0].textId }, "both read the same string")

        val image = operations.filterIsInstance<Operation.ImageAttribute>()
        assertEquals(listOf(0, 1), image.map { it.type }, "IMAGE_WIDTH then IMAGE_HEIGHT")
        assertTrue(image.all { it.args.isEmpty() }, "neither takes arguments")
    }

    @Test
    fun theLengthOfAStringIsItsCharacterCount() {
        // "measure me" is ten characters, and the bar is ten times that.
        assertEquals(100f, barWidths()[1], 0.01f)
    }

    @Test
    fun theWidthOfAStringIsWhatMeasuringItGives() {
        // Font-dependent, so what matters is that it measured rather than gave up: the string is
        // ten characters at 18pt, so somewhere well inside these bounds.
        val width = barWidths()[0]
        assertTrue(width in 40f..200f, "a plausible measurement: $width")
    }

    @Test
    fun theSizeOfAnImageIsWhatItWasDeclaredAs() {
        // The bitmap is 24x12, and each bar is four times the dimension it read.
        assertEquals(96f, barWidths()[2], 0.01f)
        assertEquals(48f, barWidths()[3], 0.01f)
    }

    @Test
    fun theTwoImageAttributesReadDifferentNumbers() {
        // A width and a height that came out the same would pass the test above by accident.
        assertTrue(barWidths()[2] != barWidths()[3])
    }

    @Test
    fun anAttributeOnSomethingAbsentLeavesItsValueAlone() {
        // An image the document never declared: the attribute has nothing to read, and the bar
        // it drives falls back rather than the parse failing.
        val without = operations.filterNot { it is Operation.BitmapData }
        val opcodes = RemoteComposeParser.build(
            without,
            io.github.jamesgoodwin.remotecompose.runtime.RemoteContext(),
            io.github.jamesgoodwin.remotecompose.text.EstimatedTextMetrics,
        )
        assertTrue(opcodes.isNotEmpty(), "the rest of the document still draws")
    }
}
