package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MODIFIER_MARQUEE` against `tools/rc-writer/marquee.rc`: text too long for its box, slid rather
 * than broken.
 *
 * `MarqueeModifierOperation.layout` takes the content's width from `minIntrinsicWidth` plus the
 * spacing, and `paint` sweeps it by a raised sine — `(1 + sin(2*pi*t - pi/2)) / 2` over the
 * overflow — so it eases to the far end, turns round and comes back. The period is the overflow
 * over `density * velocity`.
 *
 * Nothing moves until the initial delay has passed twice: `mStartTime` is the first paint plus
 * that delay, and the comparison then waits for it again. That is the library's arithmetic rather
 * than a reading of it, and it is transcribed as it stands.
 */
class MarqueeTest {

    private val bytes = fixture("marquee")

    /**
     * How far each marquee has slid at [millis], in the order they are drawn. A marquee clips to
     * its component and translates inside that clip, so the offset is the translate after each
     * clip of a row's content box, which is the 30 the row was written as: padding counts
     * towards a stated height, so the box is 46 tall and its content is 30.
     */
    private fun offsets(document: RemoteComposeDocument, millis: Long): List<Float> {
        val opcodes = document.frame(millis).opcodes
        return opcodes.withIndex()
            .filter { (_, op) -> op is Opcode.ClipRect && op.bottom == 30f }
            .map { (index, _) -> (opcodes.getOrNull(index + 1) as? Opcode.Translate)?.dx ?: 0f }
    }

    /**
     * A document that has been drawn once, so each marquee has recorded when it began: the first
     * frame is what sets that, and everything after is measured from it.
     */
    private fun running(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    @Test
    fun theModifierCarriesTheSpeedAndTheDelays() {
        val marquees = OperationReader.readAll(bytes).filterIsInstance<Operation.ModifierMarquee>()
        assertEquals(3, marquees.size, "one a row")
        assertEquals(listOf(30f, 12f, 30f), marquees.map { it.velocity })
        assertTrue(marquees.all { it.initialDelay == 400f && it.spacing == 24f })
    }

    @Test
    fun nothingMovesUntilTheDelayHasPassed() {
        val document = running()
        assertTrue(offsets(document, 0L).all { it == 0f }, "still at the start")
        assertTrue(offsets(document, 500L).all { it == 0f }, "and at 500ms, the delay counting twice")
        assertTrue(offsets(document, 1000L).any { it != 0f }, "moving by 1000ms")
    }

    @Test
    fun aRowShortEnoughToFitStaysWhereItIs() {
        // The third row's words are narrower than its box, so there is no overflow to sweep.
        val document = running()
        for (millis in listOf(0L, 1000L, 4000L, 9000L)) {
            assertEquals(0f, offsets(document, millis)[2], 0.01f, "at $millis")
        }
    }

    @Test
    fun theContentSlidesToTheLeftAndNeverPastItsOverflow() {
        // Negative, because what is drawn moves the other way from the reading position; and
        // never further than the part that does not fit.
        val document = running()
        val seen = listOf(1000L, 2000L, 4000L, 6000L, 8000L, 11000L).map { offsets(document, it)[0] }
        assertTrue(seen.all { it <= 0f }, "leftwards: $seen")
        assertTrue(seen.all { it > -400f }, "and bounded by the overflow: $seen")
    }

    @Test
    fun theSweepTurnsRoundRatherThanJumpingBack() {
        // The raised sine goes out to the far end at half a period and comes back by the whole
        // of it, so the far point is somewhere in the middle of the run rather than at its end.
        val document = running()
        val times = (1..24).map { it * 500L }
        val path = times.map { offsets(document, it)[0] }
        val furthest = path.indices.minByOrNull { path[it] }!!
        assertTrue(furthest > 0, "it starts near where it began")
        assertTrue(furthest < path.lastIndex, "and comes back afterwards: $path")
        assertTrue(path.last() > path[furthest], "having turned round")
    }

    @Test
    fun aFasterVelocityIsAShorterPeriod() {
        // The two rows carry the same words and differ only in velocity, so the faster one is
        // further through its sweep at the same moment.
        val document = running()
        val early = offsets(document, 2000L)
        assertTrue(abs(early[0]) > abs(early[1]), "30 has gone further than 12: $early")
    }

    @Test
    fun aMarqueeKeepsAskingToBeDrawn() {
        // It moves with the clock and nothing else drives it, so a host that stopped would leave
        // it where it was.
        val document = running()
        document.frame(2000L)
        assertTrue(document.needsRepaint)
        assertEquals(0, document.nextRepaintDelayMillis(), "a frame now, not a wake later")
    }
}
