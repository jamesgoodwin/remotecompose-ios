package io.github.jamesgoodwin.remotecompose.parser

import io.github.jamesgoodwin.remotecompose.fixture
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.TimeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ATTRIBUTE_TIME` against `tools/rc-writer/timeattr.rc`: parts read off a moment the document
 * carries, so the numbers are the same on every run.
 *
 * That moment is 2024-03-07T14:30:45Z — a Thursday, in a leap year, in March, which is what makes
 * the day-of-year and the weekday worth checking rather than merely plausible.
 */
class TimeAttributeTest {

    private val bytes = fixture("timeattr")
    private val moment = 1_709_821_845_000L

    /** The bars, in the order the document draws them, each as wide as the number it read. */
    private fun barWidths(): List<Float> =
        RemoteComposeParser.parse(bytes).opcodes.filterIsInstance<Opcode.DrawRect>()
            .map { it.right - it.left }

    @Test
    fun eachAttributeNamesTheMomentAndThePartItWants() {
        val attributes = OperationReader.readAll(bytes).filterIsInstance<Operation.TimeAttribute>()
        assertEquals(listOf(6, 7, 8, 9, 10, 11, 15, 4), attributes.map { it.type })
        // Only the last measures from somewhere: TIME_FROM_ARG_MIN takes the moment in args[0].
        assertTrue(attributes.dropLast(1).all { it.args.isEmpty() })
        assertEquals(1, attributes.last().args.size)
    }

    @Test
    fun theClockPartsAreReadOffTheMoment() {
        val widths = barWidths()
        assertEquals(45f, widths[0], 0.01f, "seconds")
        assertEquals(30f, widths[1], 0.01f, "minutes")
        assertEquals(14f, widths[2], 0.01f, "hours")
    }

    @Test
    fun theCalendarPartsAreReadOffTheMoment() {
        val widths = barWidths()
        assertEquals(7f, widths[3], 0.01f, "the seventh of the month")
        // `TIME_MONTH_VALUE` and `TIME_DAY_OF_WEEK` both come back one less than the calendar
        // numbers them, which is what the real operation does.
        assertEquals(2f, widths[4], 0.01f, "March, less one")
        assertEquals(3f, widths[5], 0.01f, "Thursday, less one")
    }

    @Test
    fun theDayOfYearCountsTheLeapDay() {
        // 31 in January and 29 in February, so the seventh of March is the 67th day. In a
        // non-leap year it would be the 66th, which is the mistake this is here to catch.
        assertEquals(67f, barWidths()[6], 0.01f)
    }

    @Test
    fun aMomentMeasuredFromAnotherIsTheGapBetweenThem() {
        // TIME_FROM_ARG_MIN against a moment an hour earlier.
        assertEquals(60f, barWidths()[7], 0.01f)
    }

    // ------------------------------------------------------------ the calendar itself

    @Test
    fun theEpochIsAThursdayInJanuary() {
        val snapshot = TimeSnapshot(0L)
        assertEquals(1970, snapshot.year)
        assertEquals(1, snapshot.month)
        assertEquals(1, snapshot.dayOfMonth)
        assertEquals(1, snapshot.dayOfYear)
        assertEquals(4, snapshot.dayOfWeek, "Thursday, as DayOfWeek numbers it")
        assertEquals(0, snapshot.hour)
    }

    @Test
    fun theMomentTheFixtureUsesBreaksDownAsExpected() {
        val snapshot = TimeSnapshot(moment)
        assertEquals(2024, snapshot.year)
        assertEquals(3, snapshot.month)
        assertEquals(7, snapshot.dayOfMonth)
        assertEquals(14, snapshot.hour)
        assertEquals(30, snapshot.minute)
        assertEquals(45, snapshot.second)
        assertEquals(67, snapshot.dayOfYear)
    }

    @Test
    fun theLeapDayItselfIsADay() {
        // 2024-02-29T00:00:00Z: a date that only exists in a leap year.
        val snapshot = TimeSnapshot(1_709_164_800_000L)
        assertEquals(2024, snapshot.year)
        assertEquals(2, snapshot.month)
        assertEquals(29, snapshot.dayOfMonth)
        assertEquals(60, snapshot.dayOfYear)
    }

    @Test
    fun aCenturyThatIsNotALeapYearIsNotOne() {
        // 1900 was divisible by four and still not a leap year; 2000 was. 2000-03-01T00:00:00Z
        // is the 61st day because February had 29.
        val snapshot = TimeSnapshot(951_868_800_000L)
        assertEquals(2000, snapshot.year)
        assertEquals(3, snapshot.month)
        assertEquals(1, snapshot.dayOfMonth)
        assertEquals(61, snapshot.dayOfYear)
    }

    @Test
    fun aMomentBeforeTheEpochStillBreaksDown() {
        // 1969-12-31T23:59:59Z, one second before the epoch: the floor division has to round the
        // right way or the day comes out as the first of January.
        val snapshot = TimeSnapshot(-1000L)
        assertEquals(1969, snapshot.year)
        assertEquals(12, snapshot.month)
        assertEquals(31, snapshot.dayOfMonth)
        assertEquals(23, snapshot.hour)
        assertEquals(59, snapshot.minute)
        assertEquals(59, snapshot.second)
        assertEquals(365, snapshot.dayOfYear)
    }

    @Test
    fun theDaysOfTheWeekRunInOrder() {
        // Seven consecutive days from the fixture's Thursday, wrapping through Sunday to Monday.
        val day = 86_400_000L
        val expected = listOf(4, 5, 6, 7, 1, 2, 3)
        assertEquals(expected, (0..6).map { TimeSnapshot(moment + it * day).dayOfWeek })
    }
}
