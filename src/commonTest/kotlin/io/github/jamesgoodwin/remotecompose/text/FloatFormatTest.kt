package io.github.jamesgoodwin.remotecompose.text

import kotlin.test.Test
import kotlin.test.assertEquals

class FloatFormatTest {

    private fun format(
        value: Float,
        before: Int = 3,
        after: Int = 2,
        prePad: Char = FloatFormat.NO_PAD,
        afterPad: Char = FloatFormat.NO_PAD,
        separator: Int = 0,
        grouping: Int = 0,
        options: Int = 0,
    ) = FloatFormat.format(value, before, after, prePad, afterPad, separator, grouping, options)

    @Test
    fun aFractionShrinksToItsOwnDigitsWhenNothingPadsIt() {
        assertEquals("3.4", format(3.4f))
        assertEquals("10.25", format(10.25f))
        assertEquals("7.0", format(7f))
    }

    @Test
    fun aZeroPadFillsTheFractionOutToItsWidth() {
        assertEquals("3.40", format(3.4f, afterPad = '0'))
        assertEquals("7.00", format(7f, afterPad = '0'))
        assertEquals("10.25", format(10.25f, afterPad = '0'))
    }

    @Test
    fun noFractionDigitsMeansNoPoint() {
        assertEquals("42", format(42.7f, after = 0))
        assertEquals("1", format(1f, before = 1, after = 0))
    }

    @Test
    fun theWholePartIsPaddedOrTruncatedToItsWidth() {
        assertEquals("007", format(7f, after = 0, prePad = '0'))
        assertEquals("  7", format(7f, after = 0, prePad = ' '))
        assertEquals("7", format(7f, after = 0))
        // More digits than there is room for: the leading ones go.
        assertEquals("234", format(1234f, after = 0))
    }

    @Test
    fun negativeNumbersTakeASignOrBrackets() {
        assertEquals("-3.4", format(-3.4f))
        assertEquals("(3.4)", format(-3.4f, options = 1))
        assertEquals("-42", format(-42f, after = 0))
    }

    @Test
    fun groupingAndSeparatorsFollowTheFlags() {
        assertEquals("1,234,567", format(1234567f, before = 9, after = 0, grouping = 1))
        assertEquals("123,4567", format(1234567f, before = 9, after = 0, grouping = 2))
        // Separator 1 swaps the pair over: groups by period, decimal by comma.
        assertEquals("1.234,5", format(1234.5f, before = 9, after = 1, grouping = 1, separator = 1))
    }

    @Test
    fun roundingIsOptional() {
        assertEquals("2", format(2.7f, after = 0))
        assertEquals("3", format(2.7f, after = 0, options = 2))
    }

    @Test
    fun theLegacyFormAlwaysWritesAPoint() {
        assertEquals("3.4", FloatFormat.formatLegacy(3.4f, 3, 2, FloatFormat.NO_PAD, FloatFormat.NO_PAD))
        assertEquals("3.40", FloatFormat.formatLegacy(3.4f, 3, 2, FloatFormat.NO_PAD, '0'))
        assertEquals("-3.4", FloatFormat.formatLegacy(-3.4f, 3, 2, FloatFormat.NO_PAD, FloatFormat.NO_PAD))
        assertEquals("42", FloatFormat.formatLegacy(42.9f, 3, 0, FloatFormat.NO_PAD, FloatFormat.NO_PAD))
    }
}
