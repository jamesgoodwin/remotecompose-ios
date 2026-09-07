package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.RemoteContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/watch.rc`: a watch face, which is the shape of surface this format most
 * obviously serves — handed over once and left to run.
 *
 * Nothing on it is told the time. The hands are `MATRIX_ROTATE` by angles worked out from
 * `TIME_IN_SEC`/`_MIN`/`_HR`, and the date is `ATTRIBUTE_TIME` reading the calendar off the same
 * moment, so every frame here comes from moving the clock rather than from sending anything.
 */
class WatchFaceTest {

    private val bytes = fixture("watch")

    /** 2024-03-07T14:30:45Z: half past two, forty-five seconds, on a Thursday in March. */
    private val moment = 1_709_821_845_000L

    private fun at(millis: Long, dark: Boolean = false): RemoteDocumentFrame {
        val document = RemoteComposeParser.load(bytes)
        document.paintTheme = if (dark) RemoteContext.THEME_DARK else RemoteContext.THEME_LIGHT
        val frame = document.frame(millis)
        return RemoteDocumentFrame(
            rotations = frame.opcodes.filterIsInstance<Opcode.Rotate>().map { it.degrees },
            texts = frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] },
            arcs = frame.opcodes.filterIsInstance<Opcode.DrawArc>().map { it.sweepAngleDegrees },
        )
    }

    private class RemoteDocumentFrame(
        val rotations: List<Float>,
        val texts: List<String>,
        val arcs: List<Float>,
    )

    @Test
    fun theThreeHandsAreTurnedByTheClock() {
        // Hour, minute, second in the order they are drawn.
        val turns = at(moment).rotations
        assertEquals(3, turns.size)
        // 14:30:45 — the hour hand is a quarter past two o'clock's mark, not on it.
        assertEquals(75f, turns[0], 0.1f, "hour: 2 o'clock plus half of thirty minutes")
        assertEquals(184.5f, turns[1], 0.1f, "minute: thirty minutes plus a little for the seconds")
        assertEquals(270f, turns[2], 0.1f, "second: forty-five")
    }

    @Test
    fun theHandsAreWhereTheyShouldBeAtMidnight() {
        val turns = at(0L).rotations
        assertTrue(turns.all { it == 0f }, "all three straight up at the epoch: $turns")
    }

    @Test
    fun theSecondHandMovesSixDegreesPerSecond() {
        val before = at(moment).rotations[2]
        val after = at(moment + 2000L).rotations[2]
        assertEquals(12f, after - before, 0.1f)
    }

    /** The second hand's angle through one tick, from a document already running. */
    private fun secondHandThroughATick(from: Long, offsets: List<Long>): List<Float> {
        val document = RemoteComposeParser.load(bytes)
        document.frame(from)
        return offsets.map { offset ->
            document.frame(from + offset).opcodes.filterIsInstance<Opcode.Rotate>()[2].degrees
        }
    }

    @Test
    fun theSecondHandOvershootsAndSettlesLikeAQuartzOne() {
        // `TIME_IN_SEC` counts whole seconds, so the hand jumps rather than sweeping; the
        // `ANIMATED_FLOAT` on it is what gives the jump its flick. Past the mark, then back.
        val angles = secondHandThroughATick(1_709_821_810_000L, listOf(1000L, 1060L, 1090L, 1160L, 1300L))
        val target = 66f
        assertEquals(60f, angles[0], 0.1f, "on the old mark as the second turns over")
        assertTrue(angles[2] > target, "past the new one on the way: ${angles[2]}")
        assertTrue(angles[2] - target < 3f, "but only just: ${angles[2] - target}")
        assertTrue(angles[3] < angles[2], "and coming back")
        assertEquals(target, angles[4], 0.1f, "settled by the time the next tick is due")
    }

    @Test
    fun theSecondHandGoesForwardOverTheMinute() {
        // 59 seconds to 0 is 354 degrees to 0, which without the animation's wrap would be a
        // swing backwards through the whole dial. With it the hand carries on to 360.
        val angles = secondHandThroughATick(1_709_821_859_000L, listOf(1000L, 1060L, 1300L))
        assertEquals(354f, angles[0], 0.1f)
        assertTrue(angles[1] > 354f, "forward, not back: ${angles[1]}")
        assertEquals(360f, angles[2], 0.1f, "arriving at the top of the dial")
    }

    @Test
    fun theHourHandCreepsWithTheMinutes() {
        // Not a hand that jumps on the hour: half a degree per minute.
        val onTheHour = at(1_709_820_000_000L).rotations[0] // 14:00:00Z
        val halfPast = at(1_709_821_800_000L).rotations[0] // 14:30:00Z
        assertEquals(15f, halfPast - onTheHour, 0.1f)
    }

    @Test
    fun theDateIsReadOffTheSameMoment() {
        val shown = at(moment).texts
        assertTrue("THU" in shown, "the weekday: $shown")
        assertTrue("7" in shown, "the day of the month")
        assertTrue("MAR" in shown, "the month")
    }

    @Test
    fun theDateFollowsTheClockOverAMonthEnd() {
        // 2024-02-29T12:00:00Z, a day that only exists in a leap year, and the day after it.
        assertTrue("FEB" in at(1_709_208_000_000L).texts)
        assertTrue("29" in at(1_709_208_000_000L).texts)
        assertTrue("MAR" in at(1_709_294_400_000L).texts)
        assertTrue("1" in at(1_709_294_400_000L).texts)
    }

    @Test
    fun theArcUnderTheDialIsHowFarThroughTheDayItIs() {
        // Half the 180-degree arc is midday; 14:30 is a little past that.
        val sweeps = at(moment).arcs
        assertEquals(2, sweeps.size, "a track and the part filled")
        assertEquals(180f, sweeps[0], 0.1f)
        assertEquals(108.75f, sweeps[1], 0.5f, "14 hours 30 of 1440 minutes, over half a circle")
        assertEquals(0f, at(0L).arcs[1], 0.1f, "nothing at midnight")
    }

    @Test
    fun theFaceHasAPaletteForEachMode() {
        // The same drawing either way round, in different colours.
        val light = RemoteComposeParser.load(bytes).also { it.paintTheme = RemoteContext.THEME_LIGHT }
        val dark = RemoteComposeParser.load(bytes).also { it.paintTheme = RemoteContext.THEME_DARK }
        val lightBackground = light.frame(moment).opcodes.filterIsInstance<Opcode.DrawRect>().first()
        val darkBackground = dark.frame(moment).opcodes.filterIsInstance<Opcode.DrawRect>().first()
        assertTrue(lightBackground.paint.color.red > 0.8f, "a pale face in the light")
        assertTrue(darkBackground.paint.color.red < 0.2f, "and a dark one in the dark")
    }

    @Test
    fun itAsksToBeDrawnAgain() {
        // A face that stopped asking would stop moving.
        val document = RemoteComposeParser.load(bytes)
        document.frame(moment)
        assertTrue(document.needsRepaint)
    }
}
