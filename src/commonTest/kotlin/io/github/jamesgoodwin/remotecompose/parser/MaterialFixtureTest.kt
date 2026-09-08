package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.ActionTrigger
import io.github.jamesgoodwin.remotecompose.runtime.HitRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Material order screen in `tools/rc-writer/material.rc` (see `buildMaterialSample()` in the
 * writer tool), driven the way a finger would drive it: the stepper's buttons change the
 * quantity, the line total follows, and the snackbar appears only once the cart flag is set.
 */
class MaterialFixtureTest {

    private fun load(): RemoteComposeDocument =
        RemoteComposeParser.load(fixture("material")).also { it.frame(0L) }

    /** Every laid-out string of the current frame, in draw order. */
    private fun texts(doc: RemoteComposeDocument): List<String> {
        val frame = doc.frame(0L)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
    }

    private fun buttons(doc: RemoteComposeDocument): List<HitRegion> =
        doc.hitRegions.filter { it.actions.containsKey(ActionTrigger.CLICK) }

    private fun tap(doc: RemoteComposeDocument, region: HitRegion) {
        assertTrue(doc.click((region.left + region.right) / 2f, (region.top + region.bottom) / 2f))
    }

    @Test
    fun theScreenReadsAsAMaterialOrderCard() {
        val doc = load()
        val texts = texts(doc)
        assertEquals("Remote Compose", texts.first())
        assertTrue("Flat white" in texts)
        assertTrue(texts.any { it.startsWith("Oat milk") })
        // Every button label is there, and the snackbar's is not.
        for (label in listOf("−", "+", "Add to cart", "Details", "Clear", "Tell the host")) {
            assertTrue(label in texts, "$label is on screen")
        }
        assertTrue("Added to your cart" !in texts)
    }

    @Test
    fun theStepperChangesTheQuantityAndTheTotalFollows() {
        val doc = load()
        assertTrue("1" in texts(doc) && "£3.40" in texts(doc))
        val plus = buttons(doc)[1]
        tap(doc, plus)
        assertTrue("2" in texts(doc), "the count went up")
        assertTrue("£6.80" in texts(doc), "the total followed: ${texts(doc)}")
        tap(doc, plus)
        assertTrue("£10.20" in texts(doc))
        val minus = buttons(doc)[0]
        tap(doc, minus)
        assertTrue("2" in texts(doc) && "£6.80" in texts(doc))
    }

    @Test
    fun theQuantityStopsAtOne() {
        val doc = load()
        val minus = buttons(doc)[0]
        repeat(3) { tap(doc, minus) }
        assertTrue("1" in texts(doc), "the clamp in the decrement expression holds")
    }

    @Test
    fun addingToTheCartShowsTheSnackbarAndClearingHidesIt() {
        val doc = load()
        val add = buttons(doc).first { it.top > 200f }
        tap(doc, add)
        assertTrue("Added to your cart" in texts(doc), "the conditional block ran")
        // Clear resets both the flag and the quantity.
        val clear = buttons(doc).first { it.top > 250f }
        tap(doc, clear)
        assertTrue("Added to your cart" !in texts(doc))
        assertTrue("1" in texts(doc))
    }

    @Test
    fun theProgressBarWidthFollowsTheQuantity() {
        val doc = load()
        fun fill(): Float {
            val bars = doc.frame(0L).opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            return bars.last().right - bars.last().left
        }
        val single = fill()
        tap(doc, buttons(doc)[1])
        assertEquals(single * 2f, fill(), 0.01f)
    }

    @Test
    fun theOutlinedButtonIsDrawnAsAPill() {
        val doc = load()
        // The "Details" button's outline is a stroked round rect with the button's own radius.
        val outline = doc.frame(0L).opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .first { it.paint.strokeWidth == 1f && it.radiusX == 20f }
        assertEquals(20f, outline.radiusX, 0.01f)
        assertEquals(40f - 1f, outline.bottom - outline.top, 0.01f)
    }

    @Test
    fun theTextButtonLeavesThroughAHostAction() {
        val doc = load()
        var seen = -1
        doc.onHostAction = { id, _ -> seen = id }
        tap(doc, buttons(doc).last())
        assertEquals(7, seen)
    }
}
