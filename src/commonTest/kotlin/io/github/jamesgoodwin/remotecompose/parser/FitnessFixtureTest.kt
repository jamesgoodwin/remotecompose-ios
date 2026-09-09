package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/fitness.rc` (see `buildFitnessSample()` in the writer tool): a screen the size
 * of a phone, doing what an app screen does. The parts worth pinning are the ones a document
 * drives itself — the rings, the segmented control and the press highlight — rather than how it
 * looks, which the screenshot comparison covers.
 */
class FitnessFixtureTest {

    private val bytes by lazy { fixture("fitness") }

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    /** The three ring arcs, in the order they are drawn: move, exercise, stand. */
    private fun rings(document: RemoteComposeDocument): List<Opcode.DrawArc> =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawArc>().filter { it.sweepAngleDegrees != 360f }

    private fun texts(document: RemoteComposeDocument): List<String> {
        val frame = document.frame(0L)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
    }

    @Test
    fun theRingsAreSweptByTheDocumentsOwnFloats() {
        // Each ring is a track drawn all the way round and the filled part over it; the filled
        // part is the value times 360, which is what a segment changes.
        val document = loaded()
        val sweeps = rings(document).map { it.sweepAngleDegrees }
        assertEquals(listOf(0.81f * 360f, 0.93f * 360f, 0.75f * 360f), sweeps)
        assertTrue(rings(document).all { it.startAngleDegrees == -90f }, "and all start at twelve o'clock")
    }

    @Test
    fun tappingASegmentChangesTheWholeScreen() {
        // "Day" is the first segment; the pill, the rings, the bars and the figures all move
        // because one click writes all of them.
        val document = loaded()
        assertTrue("38.6 km" in texts(document), "the week's distance to begin with")

        document.click(60f, 143f)
        document.frame(16L)

        assertTrue("6.1 km" in texts(document), "the day's distance after the tap")
        assertTrue("Today" in texts(document))
        assertEquals(0.62f * 360f, rings(document).first().sweepAngleDegrees, 0.01f)
    }

    @Test
    fun theSegmentedPillMovesToTheSegmentThatWasTapped() {
        // The pill is a box with an offset bound to a float, so where it is is a translate.
        val document = loaded()
        fun pillOffset(): Float =
            document.frame(0L).opcodes.filterIsInstance<Opcode.Translate>()
                .first { it.dx == 122f || it.dx == 2f || it.dx == 242f }.dx

        assertEquals(122f, pillOffset(), "Week to begin with")
        document.click(60f, 143f)
        assertEquals(2f, pillOffset(), "Day after tapping the first segment")
        document.click(330f, 143f)
        assertEquals(242f, pillOffset(), "Month after tapping the last")
    }

    @Test
    fun pressingARowShowsTheHighlightAndReleasingTakesItAway() {
        // The highlight is a box that is only laid out while the row is held, so it is the count
        // of drawn rectangles that says whether it is there.
        val document = loaded()
        fun rects(): Int = document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>().size

        val atRest = rects()
        document.touchDown(200f, 698f)
        val pressed = rects()
        assertNotEquals(atRest, pressed, "a rectangle appears under the finger")
        document.touchUp(200f, 698f)
        assertEquals(atRest, rects(), "and goes when it lifts")
    }

    @Test
    fun bothPalettesAreInTheOneDocument() {
        val document = loaded()
        val light = document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>().first().paint.color
        document.paintTheme = RemoteContext.THEME_DARK
        val dark = document.frame(16L).opcodes.filterIsInstance<Opcode.DrawRect>().first().paint.color
        assertNotEquals(light, dark, "the background is not the same colour in the two modes")
    }

    @Test
    fun everyPartOfItSaysWhatItIs() {
        val document = loaded()
        assertEquals("Stride: your activity summary", document.contentDescription)
        val labels = document.semantics.map { it.spokenLabel() }
        assertTrue("Activity rings" in labels, labels.toString())
        assertTrue("Distance, 38.6 km" in labels, labels.toString())
        assertTrue(
            document.semantics.any { it.spokenLabel() == "Morning run, Riverside loop · 42 min, 7.2 km" },
            labels.toString(),
        )
    }
}
