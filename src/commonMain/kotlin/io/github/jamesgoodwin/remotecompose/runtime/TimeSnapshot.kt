package io.github.jamesgoodwin.remotecompose.runtime

/**
 * `RemoteClock.TimeSnapshot`: a moment broken into the parts `TimeAttribute` reads off it.
 *
 * The real interface also carries a zone (`RemoteClock.getZoneId`), and its `SYSTEM` clock is not
 * in the extracted jars, so which zone it breaks a moment down in cannot be read from them. This
 * works in UTC, which is what this renderer's `TIME_IN_SEC`/`_MIN`/`_HR` system variables already
 * do, so a document reading the hour one way and the other agrees with itself.
 *
 * The calendar is the usual days-to-civil conversion: shift the era to start in March so that the
 * leap day falls at the end of a year, which makes the month lengths a straight line.
 */
class TimeSnapshot(val millis: Long) {

    private val days: Long = floorDiv(millis, MILLIS_PER_DAY)
    private val millisIntoDay: Long = millis - days * MILLIS_PER_DAY

    val second: Int get() = ((millisIntoDay / 1000L) % 60L).toInt()
    val minute: Int get() = ((millisIntoDay / 60_000L) % 60L).toInt()
    val hour: Int get() = (millisIntoDay / 3_600_000L).toInt()

    /** 1 is Monday and 7 is Sunday, as `DayOfWeek.getValue` numbers them. 1970-01-01 was a Thursday. */
    val dayOfWeek: Int get() = (floorMod(days + 3L, 7L) + 1L).toInt()

    val year: Int
    /** 1..12, as `LocalDate.getMonthValue` numbers them. */
    val month: Int
    val dayOfMonth: Int

    init {
        // Days from 1970-03-01, the start of the era this conversion counts in.
        val z = days + 719_468L
        val era = floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val shiftedYear = yearOfEra + era * 400L
        val dayOfYearFromMarch = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthFromMarch = (5L * dayOfYearFromMarch + 2L) / 153L
        dayOfMonth = (dayOfYearFromMarch - (153L * monthFromMarch + 2L) / 5L + 1L).toInt()
        month = (if (monthFromMarch < 10L) monthFromMarch + 3L else monthFromMarch - 9L).toInt()
        year = (if (month <= 2) shiftedYear + 1L else shiftedYear).toInt()
    }

    /** 1 on the first of January. */
    val dayOfYear: Int get() = (days - daysFromCivil(year, 1, 1) + 1L).toInt()

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /** Rounding towards negative infinity, which `kotlin`'s `/` and `%` do not do. */
        fun floorDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a xor b) < 0L) q - 1L else q
        }

        fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

        /** The inverse of the above, for counting back to the start of the year. */
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1L else year.toLong()
            val era = floorDiv(y, 400L)
            val yearOfEra = y - era * 400L
            val monthFromMarch = if (month > 2) month - 3L else month + 9L
            val dayOfYearFromMarch = (153L * monthFromMarch + 2L) / 5L + day - 1L
            val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYearFromMarch
            return era * 146_097L + dayOfEra - 719_468L
        }
    }
}
