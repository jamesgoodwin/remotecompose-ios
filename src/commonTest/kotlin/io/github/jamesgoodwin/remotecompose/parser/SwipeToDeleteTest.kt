package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.HitRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Swipe to delete against `tools/rc-writer/swipe.rc`, which does the whole interaction inside the
 * document: a `MODIFIER_SCROLL` with a two-notch `TOUCH_EXPRESSION` for the swipe, a
 * `ValueIntegerChange` on the row's own visibility for the tap, and `ANIMATION_SPEC` for the gap
 * closing afterwards.
 *
 * The host is never told anything, so these tests drive gestures and read the frame back.
 */
class SwipeToDeleteTest {

    /** The document's own numbers: its padding, the window inside it, and the panel behind a row. */
    private val PAD = 16f
    private val WINDOW = 268f
    private val ACTION_WIDTH = 88f

    /** Where a closed row's panel sits: just past the right edge of the window. */
    private val CLOSED = PAD + WINDOW

    private val bytes = fixture("swipe")

    /**
     * The clock, which only ever moves forward.
     *
     * Both the notch settling after a swipe and the gap closing after a delete are animations, so
     * a helper that re-framed at zero to read the screen would wind them back to their start.
     */
    private var now = 0L

    private fun loaded(): RemoteComposeDocument {
        now = 0L
        return RemoteComposeParser.load(bytes).also { it.frame(now) }
    }

    /** Advances the clock by [frames] at 60Hz, drawing each one. */
    private fun run(document: RemoteComposeDocument, frames: Int) {
        repeat(frames) {
            now += 16L
            document.frame(now)
        }
    }

    /** The senders still on screen, in the order they are drawn. */
    private fun senders(document: RemoteComposeDocument): List<String> {
        val frame = document.frame(now)
        val names = setOf("Dispatch", "Library", "Allotment", "Weather")
        return frame.opcodes.filterIsInstance<Opcode.DrawText>()
            .mapNotNull { frame.strings[it.stringIndex] }
            .filter { it in names }
    }

    /**
     * The Delete panels, found by shape rather than position: each is [ACTION_WIDTH] wide, where
     * Restore all is the full width of the window. Ordered down the screen.
     */
    private fun deletePanels(document: RemoteComposeDocument): List<HitRegion> =
        document.hitRegions
            .filter { kotlin.math.abs((it.right - it.left) - ACTION_WIDTH) < 1f }
            .sortedBy { it.top }

    private fun restoreAll(document: RemoteComposeDocument): HitRegion =
        document.hitRegions.first { (it.right - it.left) > ACTION_WIDTH * 2 }

    private fun click(document: RemoteComposeDocument, region: HitRegion) {
        document.click((region.left + region.right) / 2f, (region.top + region.bottom) / 2f)
    }

    /** Drags [row]'s card to the left, far enough that the two-notch stop lands it open. */
    private fun swipeOpen(document: RemoteComposeDocument, row: HitRegion) {
        val y = (row.top + row.bottom) / 2f
        document.touchDown(240f, y)
        run(document, 1)
        document.touchDrag(120f, y)
        run(document, 1)
        document.touchUp(120f, y)
        run(document, 40)
    }

    @Test
    fun everyRowIsThereToBeginWith() {
        val document = loaded()
        assertEquals(listOf("Dispatch", "Library", "Allotment", "Weather"), senders(document))
        assertEquals(4, deletePanels(document).size, "one Delete panel per row")
    }

    @Test
    fun thePanelSitsOffTheWindowUntilTheRowIsSwiped() {
        // Closed, the card fills the window and the panel is the next thing along the row, so it
        // starts at the window's right edge and is not on screen.
        val document = loaded()
        assertEquals(CLOSED, deletePanels(document).first().left, 0.5f)
    }

    @Test
    fun swipingBringsThePanelIn() {
        val document = loaded()
        val before = deletePanels(document).first()
        swipeOpen(document, before)
        // The two-notch stop lands it fully open, so the panel ends flush with the window's edge.
        val after = deletePanels(document).first()
        assertEquals(CLOSED - ACTION_WIDTH, after.left, 0.5f)
        assertEquals(CLOSED, after.right, 0.5f)
        assertEquals(before.top, after.top, 0.5f, "and stayed on its own row")
    }

    @Test
    fun tappingDeleteTakesThatRowOutAndTheOthersCloseUp() {
        val document = loaded()
        val panel = deletePanels(document).first()
        swipeOpen(document, panel)
        click(document, deletePanels(document).first())
        // The row fades out and the ones below slide up, which takes about a second.
        run(document, 80)

        assertEquals(listOf("Library", "Allotment", "Weather"), senders(document))
        val remaining = deletePanels(document)
        assertEquals(3, remaining.size, "the deleted row's panel went with it")
        assertEquals(
            panel.top, remaining.first().top, 0.5f,
            "the row below moved up into the gap the deleted one left",
        )
    }

    @Test
    fun restoreAllBringsThemBack() {
        // One click writing four values, which is the whole of "undo" in this document.
        val document = loaded()
        swipeOpen(document, deletePanels(document).first())
        click(document, deletePanels(document).first())
        run(document, 80)
        assertEquals(3, senders(document).size)

        click(document, restoreAll(document))
        run(document, 80)
        assertEquals(listOf("Dispatch", "Library", "Allotment", "Weather"), senders(document))
    }
}
