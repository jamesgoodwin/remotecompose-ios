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
    private fun rings(document: RemoteComposeDocument, atMillis: Long = 0L): List<Opcode.DrawArc> =
        document.frame(atMillis).opcodes.filterIsInstance<Opcode.DrawArc>().filter { it.sweepAngleDegrees != 360f }

    private fun texts(document: RemoteComposeDocument, atMillis: Long = 0L): List<String> {
        val frame = document.frame(atMillis)
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
        document.frame(0L)
        document.frame(1000L)

        assertTrue("6.1 km" in texts(document), "the day's distance after the tap")
        assertTrue("Today" in texts(document))
        assertEquals(0.62f * 360f, rings(document, 1000L).first().sweepAngleDegrees, 0.01f)
    }

    /**
     * Where the pill is, in document coordinates. It is the box the segmented control offsets,
     * and the offset is the only translate in the document that moves between these frames.
     */
    private fun pillOffset(document: RemoteComposeDocument, atMillis: Long): Float =
        document.frame(atMillis).opcodes.filterIsInstance<Opcode.Translate>()[20].dx

    @Test
    fun theSegmentedPillSlidesToTheSegmentThatWasTapped() {
        // iOS slides its pill rather than jumping it, so the offset is an ANIMATED_FLOAT: the tap
        // writes where it should be and the document eases it there over a quarter of a second.
        val document = loaded()
        assertEquals(122f, pillOffset(document, 0L), "Week to begin with")

        document.click(60f, 143f)
        // The frame the tap lands in is where the animation starts from, so the movement is in
        // the frames after it.
        pillOffset(document, 0L)
        val midway = pillOffset(document, 80L)
        assertTrue(midway in 3f..121f, "on its way to Day, not there yet: $midway")
        assertTrue(document.needsRepaint, "and asking for the frames to get there")

        assertEquals(2f, pillOffset(document, 400L), 0.5f, "arrived, a quarter of a second later")
        // The rings and the bars are still settling at that point, since the same tap started
        // them and they are given longer; once they are done the document stops asking at all.
        pillOffset(document, 1200L)
        assertTrue(!document.needsRepaint, "and nothing is left animating")
    }

    @Test
    fun theRingsEaseToTheirNewValueRatherThanJumping() {
        val document = loaded()
        document.click(60f, 143f)
        rings(document, 0L)
        val midway = rings(document, 100L).first().sweepAngleDegrees
        assertTrue(midway in (0.62f * 360f)..(0.81f * 360f), "between the two: $midway")
        assertEquals(0.62f * 360f, rings(document, 800L).first().sweepAngleDegrees, 0.5f)
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
