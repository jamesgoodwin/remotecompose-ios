package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MODIFIER_RIPPLE` against `tools/rc-writer/material.rc`, whose buttons ripple when pressed.
 */
class RippleTest {

    private val bytes by lazy { fixture("material") }

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    /** The ripple: the only circle this document draws, and only while one is running. */
    private fun ripple(document: RemoteComposeDocument, at: Long): Opcode.DrawCircle? =
        document.frame(at).opcodes.filterIsInstance<Opcode.DrawCircle>().firstOrNull()

    /** Presses "Add to cart", which is the filled button on the left of the first row. */
    private fun pressed(): RemoteComposeDocument = loaded().also { it.touchDown(82f, 238f) }

    @Test
    fun everyButtonCarriesTheModifier() {
        val operations = OperationReader.readAll(bytes)
        // The four buttons plus the two of the stepper, which the same helper writes.
        assertEquals(6, operations.count { it is Operation.ModifierRipple })
    }

    @Test
    fun nothingIsDrawnUntilSomethingIsPressed() {
        assertTrue(ripple(loaded(), 0L) == null)
    }

    @Test
    fun aPressSpreadsFromWhereItLanded() {
        val document = pressed()
        val early = ripple(document, 50L)
        assertTrue(early != null, "a press draws one")
        // The button is 40 tall and its top is at y=218, so a press at 238 is halfway down it.
        assertEquals(20f, early.centerY, 0.5f)
        assertTrue(early.centerX > 0f && early.centerX < 133f, "and inside it: ${early.centerX}")
        // It grows.
        assertTrue(ripple(document, 300L)!!.radius > early.radius)
        assertTrue(ripple(document, 600L)!!.radius > ripple(document, 300L)!!.radius)
    }

    @Test
    fun itFadesOutOverTheFirstHalf() {
        val document = pressed()
        val early = ripple(document, 50L)!!.paint.color.alpha
        val middle = ripple(document, 250L)!!.paint.color.alpha
        val late = ripple(document, 490L)!!.paint.color.alpha
        assertTrue(early > middle && middle > late, "$early then $middle then $late")
        assertTrue(early < 180f / 255f + 0.01f, "it starts at the library's own alpha: $early")
        assertTrue(late < 0.02f, "and is gone by halfway: $late")
    }

    @Test
    fun itIsOverAfterASecond() {
        val document = pressed()
        assertTrue(ripple(document, 900L) != null)
        assertTrue(ripple(document, 1100L) == null, "past the end there is nothing left to draw")
    }

    @Test
    fun theDocumentAsksForFramesWhileOneIsRunning() {
        val document = pressed()
        document.frame(100L)
        assertTrue(document.needsRepaint)
    }

    @Test
    fun itIsClippedToTheComponentItBelongsTo() {
        val document = pressed()
        val opcodes = document.frame(300L).opcodes
        val at = opcodes.indexOfFirst { it is Opcode.DrawCircle }
        val clip = opcodes[at - 1] as Opcode.ClipRect
        // The button's own box: a press near an edge shows an arc rather than spilling out.
        assertEquals(40f, clip.bottom - clip.top, 0.01f)
        assertTrue((opcodes[at + 1] as? Opcode.MatrixRestore) != null, "and the clip is put back")
    }

    @Test
    fun pressingASecondButtonStartsItsOwn() {
        val document = pressed()
        val first = ripple(document, 100L)!!.centerX
        // "Clear" sits on the row below, so its ripple is centred somewhere else.
        document.touchDown(60f, 294f)
        val second = document.frame(100L).opcodes.filterIsInstance<Opcode.DrawCircle>()
        assertTrue(second.isNotEmpty())
        assertTrue(second.any { it.centerX != first }, "a different button, a different press")
    }
}
