package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LAYOUT_COMPUTE` and `COMPONENT_VALUE` against `tools/rc-writer/layout.rc`: the document
 * working out its own layout.
 *
 * `LayoutComputeOperation.applyToMeasure` puts `[x, y, width, height, parentWidth, parentHeight]`
 * into the dynamic float list the operation names, runs the operations in its block over them,
 * and reads back what they wrote — the width and height for `TYPE_MEASURE`, the x and y for
 * `TYPE_POSITION`. `ComponentValue` goes the other way: `Component.updateVariables` publishes one
 * measurement of a named component under a float id.
 *
 * That publishing happens before the frame is measured, so a value reports the previous layout.
 * A document reading its own size therefore settles on the second frame, and the first asks to be
 * drawn again — which is the same lag the library has and for the same reason.
 */
class LayoutComputeTest {

    private val bytes = fixture("layout")

    /** The document after [frames] frames, so a value that feeds the layout has settled. */
    private fun settled(frames: Int = 2): RemoteComposeDocument {
        val document = RemoteComposeParser.load(bytes)
        repeat(frames) { document.frame(0L) }
        return document
    }

    private fun rects(document: RemoteComposeDocument): List<Opcode.DrawRect> =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>()

    @Test
    fun theBlockIsAModifierWithOperationsInsideIt() {
        val operations = OperationReader.readAll(bytes)
        val computes = operations.filterIsInstance<Operation.LayoutCompute>()
        assertEquals(2, computes.size, "one for a size, one for a place")
        assertEquals(
            listOf(0, 1),
            computes.map { it.type },
            "TYPE_MEASURE then TYPE_POSITION",
        )
        // Each names a dynamic float list, and that list is declared inside its own block: the
        // writer puts a six-long one there before it writes the expressions.
        for (compute in computes) {
            val at = operations.indexOf(compute)
            val declared = operations.drop(at + 1).takeWhile { it !is Operation.ContainerEnd }
                .filterIsInstance<Operation.DynamicFloatList>()
            assertEquals(1, declared.size, "a list of its own")
            assertEquals(compute.boundsId, declared[0].id)
            assertEquals(6f, declared[0].length, "x, y, width, height and the parent's two")
        }
    }

    @Test
    fun aComponentCanBeMadeTwoFifthsAsTallAsItIsWide() {
        // The blue box fills the column's 268 of content width, and its height is the block's
        // doing: nothing in the format's dimension modifiers can say an aspect ratio.
        val document = settled()
        val box = rects(document).first { it.paint.color.blue > 0.5f && it.paint.color.red < 0.3f }
        assertEquals(268f, box.right - box.left, 0.5f)
        assertEquals(268f * 0.4f, box.bottom - box.top, 0.5f)
    }

    @Test
    fun aComponentCanPutItselfAgainstItsParentsRightEdge() {
        // `setX(parentWidth - width)`: the green box is 96 wide inside a 268 parent, so it starts
        // at 172 rather than at 0 where a Box would otherwise place it.
        val document = settled()
        val opcodes = document.frame(0L).opcodes
        val green = opcodes.indexOfFirst {
            it is Opcode.DrawRect && it.paint.color.green > 0.35f && it.paint.color.red < 0.1f
        }
        assertTrue(green >= 0, "the pushed box is drawn")
        val offset = opcodes.take(green).filterIsInstance<Opcode.Translate>().fold(0f) { sum, t -> sum + t.dx }
        assertEquals(268f - 96f, offset - 16f, 0.5f, "268 less its own 96, inside the column's padding")
    }

    @Test
    fun aMeasurementIsPublishedForTheRestOfTheDocumentToRead() {
        // The rule under the words is drawn to whatever width they came out. Nothing in the
        // document states that width; the layout worked it out and `COMPONENT_VALUE` handed it on.
        val document = settled()
        val rule = rects(document).first { it.paint.color.red > 0.9f && it.paint.color.green > 0.3f }
        val width = rule.right - rule.left
        assertTrue(width > 20f, "the rule has the words' width: $width")
        assertTrue(width < 268f, "which is less than the whole column: $width")

        // And the same number, read back as text.
        val frame = document.frame(0L)
        val caption = frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { frame.strings[it.stringIndex] }
            .single { it.startsWith("that measured") }
        assertTrue(caption.contains(width.toInt().toString()), "$caption says $width")
    }

    @Test
    fun aValueReportsThePreviousLayoutAndAsksForAnotherFrame() {
        // The first frame has nothing to report — the component has not been measured yet — so it
        // publishes zero and marks the document for repaint. The second has the real number.
        val document = RemoteComposeParser.load(bytes)
        val first = document.frame(0L)
        val firstRule = first.opcodes.filterIsInstance<Opcode.DrawRect>()
            .first { it.paint.color.red > 0.9f && it.paint.color.green > 0.3f }
        assertEquals(0f, firstRule.right - firstRule.left, 0.01f, "nothing to report yet")
        assertTrue(document.needsRepaint, "so it asks to be drawn again")

        val secondRule = rects(document).first { it.paint.color.red > 0.9f && it.paint.color.green > 0.3f }
        assertTrue(secondRule.right - secondRule.left > 20f, "and then it has the width")
    }

    @Test
    fun aFitBoxShowsTheFirstVersionThatFitsAndHidesTheRest() {
        // `FitBoxLayout.computeSize` is about choosing, not scaling: three versions of one line,
        // each declaring what it needs through `MODIFIER_WIDTH_IN`, and the first whose minimum
        // fits the room is the one drawn. 268 has room for the second, 120 only for the third.
        val document = settled()
        val frame = document.frame(0L)
        val shown = frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { frame.strings[it.stringIndex] }
            .filter { it.startsWith("14:32") || it.startsWith("Departure") }
        assertEquals(listOf("14:32 · Gate B12", "14:32"), shown)
    }

    @Test
    fun theVersionThatNeedsMoreRoomThanThereIsIsNotDrawnAtAll() {
        // Not drawn small, and not clipped: gone. The words that need 400 are in the file — both
        // boxes name them, and the writer keeps one copy of a string — and reach the screen from
        // neither.
        val texts = OperationReader.readAll(bytes).filterIsInstance<Operation.TextData>()
        assertEquals(1, texts.count { it.text.startsWith("Departure") }, "in the file")
        val frame = settled().frame(0L)
        assertTrue(
            frame.opcodes.filterIsInstance<Opcode.DrawText>()
                .none { frame.strings[it.stringIndex]?.startsWith("Departure") == true },
            "and drawn for neither",
        )
    }

    @Test
    fun aComputedSizeIsSteadyOnceItIsReached() {
        // The block runs every frame off the same inputs, so it does not creep: a document whose
        // layout fed itself would show it here.
        val once = settled(2)
        val later = settled(6)
        fun box(document: RemoteComposeDocument) =
            rects(document).first { it.paint.color.blue > 0.5f && it.paint.color.red < 0.3f }
        assertEquals(box(once).bottom - box(once).top, box(later).bottom - box(later).top, 0.01f)
    }
}
