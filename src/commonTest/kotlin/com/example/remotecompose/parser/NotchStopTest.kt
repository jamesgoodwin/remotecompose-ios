package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/notches.rc`: the stop modes a released `TouchExpression` can be given.
 *
 * `getStopPosition` works out where the throw was heading — half the velocity past where the
 * finger left, held inside the bounds — and `mStopMode` says what to do with that. Four strips,
 * one for each answer: the whole length cut into equal steps, the same held to one step a
 * gesture, a set of fractions of the length, and a set of positions written into the file.
 *
 * None of it is the host's doing. The notches are in the document and the snap is the document's.
 */
class NotchStopTest {

    private val bytes = fixture("notches")
    private val scrolls = OperationReader.readAll(bytes).filterIsInstance<Operation.ModifierScroll>()

    /** One scrolling strip: the id its position is kept under, and where to press it. */
    private class Strip(val id: Int, val y: Float)

    private fun strip(index: Int, y: Float) =
        Strip(FloatExpressionEvaluator.idOf(scrolls[index].positionExpression), y)

    private val pager = strip(0, 100f)
    private val even = strip(1, 215f)
    private val percent = strip(2, 285f)
    private val absolute = strip(3, 354f)

    /** The window the strips are seen through, and how long each is. */
    private val window = 268f
    private val pagerContent = 3f * window
    private val stripContent = 544f
    private val stripScroll = stripContent - window

    /**
     * Drags [strip] left by [by], lets go at [velocity] (in the same direction), and gives the
     * position it comes to rest at once the glide is over.
     */
    private fun settled(strip: Strip, by: Float, velocity: Float = 0f): Float {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        document.touchDown(200f, strip.y)
        document.frame(0L)
        document.touchDrag(200f - by, strip.y)
        document.frame(0L)
        // A pointer travelling left carries the position forward, so the sign flips.
        document.touchUp(200f - by, strip.y, velocityX = -velocity)
        document.frame(0L)
        document.frame(4000L)
        return document.context.floats[strip.id] ?: Float.NaN
    }

    @Test
    fun aPageEitherArrivesOrGoesBack() {
        // STOP_NOTCHES_SINGLE_EVEN, three pages: the row is three windows long, so an even
        // division into three is one page, and there is nowhere between two pages to stop.
        assertEquals(0f, settled(pager, 40f), 0.5f, "not far enough, so back where it came from")
        assertEquals(window, settled(pager, 150f), 0.5f, "past half way, so on to the next")
    }

    @Test
    fun aSwipeMovesOnePageHoweverFarItIsDragged() {
        // The "single" in the mode's name: `mMaxAtDown` is one step past where the press landed,
        // so a drag the whole length of the row still only advances one page.
        assertEquals(window, settled(pager, 600f), 0.5f)
        assertEquals(window, settled(pager, pagerContent), 0.5f)
    }

    @Test
    fun aFlickCountsAsFarAsADrag() {
        // The throw is half the velocity past where the finger left, so a short fast movement
        // reaches the next page though a short slow one does not.
        assertEquals(0f, settled(pager, 40f), 0.5f)
        assertEquals(window, settled(pager, 40f, velocity = 600f), 0.5f)
    }

    @Test
    fun anEvenStripStopsOnAMultipleOfItsOwnLength() {
        // STOP_NOTCHES_EVEN, six notches: the step is the strip's whole length over six, which is
        // not the same as how far there is to scroll — the notches are cut across the content.
        val step = stripContent / 6f
        assertEquals(0f, settled(even, 30f), 0.5f)
        assertEquals(step, settled(even, 100f), 0.5f)
        assertEquals(step * 2f, settled(even, 200f), 0.5f)
        assertEquals(step * 3f, settled(even, 400f), 0.5f, "and no further than there is room for")
    }

    @Test
    fun anEvenStripIsNotHeldToOneStep() {
        // The difference between the two even modes, which is the whole of why there are two.
        assertTrue(settled(even, 400f) > settled(pager, 400f) - window, "the strip ran on")
        assertEquals(stripContent / 6f * 3f, settled(even, 400f), 0.5f)
    }

    @Test
    fun aPercentStripStopsOnFractionsOfWhatThereIsToScroll() {
        // STOP_NOTCHES_PERCENTS: 0, a quarter, a half and all of it — of the scroll rather than
        // of the content, since the fractions run from the minimum to the maximum.
        assertEquals(0f, settled(percent, 30f), 0.5f)
        assertEquals(stripScroll * 0.25f, settled(percent, 100f), 0.5f)
        assertEquals(stripScroll * 0.5f, settled(percent, 200f), 0.5f)
        assertEquals(stripScroll, settled(percent, 400f), 0.5f)
    }

    @Test
    fun anAbsoluteStripStopsWherePositionsWereWrittenAndNowhereElse() {
        // STOP_NOTCHES_ABSOLUTE: 0, 92 and 184 are in the file, and 276 — the end of the scroll —
        // is not, so the strip cannot be left at its own end.
        assertEquals(92f, settled(absolute, 100f), 0.5f)
        assertEquals(184f, settled(absolute, 150f), 0.5f)
        assertEquals(184f, settled(absolute, 400f), 0.5f, "the end is not one of them")
        assertTrue(settled(absolute, 400f) < stripScroll)
    }

    @Test
    fun eachStripAnswersOnlyToAPressOnItself() {
        // `TouchExpression.touchDown` starts by returning if the press is outside the rectangle
        // its component was laid out at; without that a drag anywhere would move all four.
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        document.touchDown(200f, pager.y)
        document.frame(0L)
        document.touchDrag(60f, pager.y)
        document.frame(0L)
        assertTrue(document.context.floats[pager.id]!! > 100f, "the pager followed the finger")
        for (other in listOf(even, percent, absolute)) {
            assertEquals(0f, document.context.floats[other.id] ?: 0f, 0.01f, "and nothing else did")
        }
    }

    @Test
    fun aStripLetGoOfWithoutADragStaysWhereItIs() {
        // Nothing to round: the throw is where it already was, and every mode's nearest notch to
        // a notch is that notch.
        for (strip in listOf(pager, even, percent, absolute)) {
            assertEquals(0f, settled(strip, 0f), 0.01f)
        }
    }
}
