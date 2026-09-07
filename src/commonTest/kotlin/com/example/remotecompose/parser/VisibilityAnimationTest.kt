package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enter and exit halves of `ANIMATION_SPEC` against `tools/rc-writer/visibility.rc`: a card
 * the buttons hide and show, which fades either way rather than blinking.
 *
 * `AnimateMeasure.getVisibility()` is `mVp` while a component arrives and `1 - mVp` while it
 * leaves, both off the spec's visibility duration and easing.
 */
class VisibilityAnimationTest {

    private val bytes = fixture("visibility")

    /** Where the buttons sit while the card is showing, and once it has gone and the row moved up. */
    private val hideButton = Pair(60f, 175f)
    private val showButtonAfterHiding = Pair(190f, 101f)

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    private fun texts(document: RemoteComposeDocument, at: Long): List<String> {
        val frame = document.frame(at)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
    }

    /** The compositing layer a fading component is drawn into, if one is running. */
    private fun fade(document: RemoteComposeDocument, at: Long): Float? =
        document.frame(at).opcodes.filterIsInstance<Opcode.SaveLayerAlpha>().firstOrNull()?.alpha

    private val card = "Now you see me"

    private fun hidden(): RemoteComposeDocument {
        val document = loaded()
        document.click(hideButton.first, hideButton.second)
        document.frame(0L)
        return document
    }

    @Test
    fun theSpecCarriesAnAnimationForEachDirection() {
        val spec = OperationReader.readAll(bytes).filterIsInstance<Operation.AnimationSpec>().single()
        // `AnimationSpec.ANIMATION` goes on the wire as its ordinal: FADE_IN 0, FADE_OUT 1.
        assertEquals(0, spec.enterAnimation)
        assertEquals(1, spec.exitAnimation)
        assertEquals(0.4f, spec.visibilityDuration, 0.001f)
    }

    @Test
    fun nothingFadesWhileNothingIsChanging() {
        val document = loaded()
        assertTrue(card in texts(document, 0L), "the card starts out showing")
        assertEquals(null, fade(document, 0L), "and is drawn plainly")
    }

    @Test
    fun aCardOnItsWayOutFadesRatherThanVanishing() {
        val document = hidden()
        assertTrue(card in texts(document, 100L), "still drawn part way through")
        val early = fade(document, 100L)!!
        val later = fade(document, 250L)!!
        assertTrue(early > later, "and getting fainter: $early then $later")
        assertTrue(early < 1f && later > 0f, "between the two ends: $early, $later")
    }

    @Test
    fun itKeepsItsPlaceUntilTheFadeIsDone() {
        // A component dropped from the layout the moment it goes has nothing left to fade, so
        // it stays measured until it has finished leaving.
        val document = hidden()
        val whileLeaving = document.frame(200L).opcodes.filterIsInstance<Opcode.DrawText>().size
        val afterLeaving = document.frame(600L).opcodes.filterIsInstance<Opcode.DrawText>().size
        assertEquals(whileLeaving - 1, afterLeaving, "one fewer once it has gone")
        assertTrue(card !in texts(document, 600L))
    }

    @Test
    fun aCardComingBackFadesTheOtherWay() {
        val document = hidden()
        document.frame(600L)
        document.click(showButtonAfterHiding.first, showButtonAfterHiding.second)
        document.frame(600L)
        assertTrue(card in texts(document, 700L), "it is being drawn again")
        val early = fade(document, 650L)!!
        val later = fade(document, 850L)!!
        assertTrue(early < later, "getting stronger: $early then $later")
    }

    @Test
    fun onceArrivedItIsDrawnPlainlyAgain() {
        val document = hidden()
        document.frame(600L)
        document.click(showButtonAfterHiding.first, showButtonAfterHiding.second)
        document.frame(600L)
        assertEquals(null, fade(document, 1100L), "no compositing layer once it is simply there")
        assertTrue(card in texts(document, 1100L))
    }

    @Test
    fun theDocumentAsksForFramesWhileOneIsRunning() {
        val document = hidden()
        document.frame(100L)
        assertTrue(document.needsRepaint)
    }
}
