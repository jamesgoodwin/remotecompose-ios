package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/parallax.rc` and `tools/rc-writer/carousel.rc`: two ways of moving one thing
 * slower than another off a single scroll position.
 *
 * A scrolling component moves everything inside it by that position, so something is pulled back
 * again by `scroll * (1 - rate)` to make it lag. The scene does it to a photograph behind its
 * text; the carousel does it to each picture inside its own card. Nothing is measured while
 * running and nothing is asked of the host — the subtraction is in the file.
 */
class ParallaxTest {

    private val scene = fixture("parallax")
    private val carousel = fixture("carousel")

    /**
     * The translate that precedes each drawn picture, which is how far that picture has been
     * pulled back. Read from a frame already drawn, so the clock is left where the caller put it.
     */
    private fun lags(bytes: ByteArray, dragBy: Float, vertical: Boolean = true): List<Float> {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        if (dragBy != 0f) {
            val from = if (vertical) 300f else 250f
            document.touchDown(150f, from)
            document.frame(0L)
            if (vertical) document.touchDrag(150f, from - dragBy) else document.touchDrag(150f - dragBy, from)
            document.frame(0L)
            document.touchUp(150f, from)
        }
        val opcodes = document.frame(0L).opcodes
        return opcodes.withIndex()
            .filter { it.value is Opcode.DrawBitmap }
            .map { (index, _) ->
                val translate = opcodes[index - 1] as Opcode.Translate
                if (vertical) translate.dy else translate.dx
            }
    }

    @Test
    fun thePictureStartsWhereItWasDrawn() {
        assertTrue(lags(scene, 0f).all { it == 0f }, "nothing lags before anything has moved")
    }

    @Test
    fun thePictureTravelsAtItsOwnFractionOfTheScroll() {
        // Drawn at a rate of 0.4, so it is pulled back by the other 0.6 and what is left is 0.4.
        val moved = lags(scene, 100f)
        assertEquals(1, moved.size, "one photograph, not a stack of layers")
        assertEquals(60f, moved[0], 0.01f, "pulled back by three fifths")
    }

    @Test
    fun thePictureIsSlowerThanTheWordsOverIt() {
        // What the eye is shown: a hundred of scroll moves the text by a hundred and the picture
        // by forty, and the difference between those two is the whole effect.
        val lag = lags(scene, 100f)[0]
        val travelled = 100f - lag
        assertEquals(40f, travelled, 0.01f)
        assertTrue(travelled < 100f, "slower than the text, which travels the full scroll")
    }

    @Test
    fun theLagIsProportionalToTheScroll() {
        // The relationship rather than the numbers: twice as far scrolled, twice as far pulled.
        val once = lags(scene, 60f)
        val twice = lags(scene, 120f)
        assertEquals(once[0] * 2f, twice[0], 0.5f)
    }

    @Test
    fun eachPictureDriftsInsideItsOwnCard() {
        // The carousel's version: a quarter of how far the card is from the middle, so every card
        // drifts by the same quarter of the scroll and none of them by the scroll itself.
        val atRest = lags(carousel, 0f, vertical = false)
        val moved = lags(carousel, 100f, vertical = false)
        assertEquals(5, atRest.size, "one per card")
        for ((before, after) in atRest.zip(moved)) assertEquals(25f, after - before, 0.01f)
    }

    @Test
    fun theCardsThemselvesTravelWithTheScroll() {
        // The pictures lag their frames; the frames do not lag the finger. A hundred of drag is a
        // hundred of scroll, against the twenty-five the picture drifts.
        val document = RemoteComposeParser.load(carousel)
        document.frame(0L)
        document.touchDown(150f, 250f)
        document.frame(0L)
        document.touchDrag(50f, 250f)
        document.frame(0L)
        val scroll = OperationReader.readAll(carousel)
            .filterIsInstance<Operation.ModifierScroll>().single()
        val position = document.context.floats[
            io.github.jamesgoodwin.remotecompose.runtime.FloatExpressionEvaluator.idOf(scroll.positionExpression)
        ]
        assertEquals(1, scroll.direction, "horizontal")
        assertEquals(100f, position!!, 1f)
    }
}
