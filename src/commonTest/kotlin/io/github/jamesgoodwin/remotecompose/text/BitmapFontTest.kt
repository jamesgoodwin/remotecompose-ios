package io.github.jamesgoodwin.remotecompose.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitmapFontTest {

    private fun glyph(
        chars: String, bitmapId: Int, width: Int = 10, height: Int = 14,
        left: Int = 1, right: Int = 1, top: Int = 0, bottom: Int = 0,
    ) = BitmapGlyph(chars, bitmapId, left, top, right, bottom, width, height)

    private val font = BitmapFont(
        listOf(glyph("A", 1), glyph("B", 2, width = 6), glyph(" ", -1, width = 0, left = 4, right = 4)),
        kerning = mapOf("AB" to -3),
    )

    @Test
    fun eachGlyphSitsAfterItsNeighboursMargins() {
        val run = font.layout("AA")
        assertEquals(2, run.placements.size)
        // First: left margin 1, then 10 wide. Second: right margin 1 then its own left margin 1.
        assertEquals(1f, run.placements[0].left)
        assertEquals(11f, run.placements[0].right)
        assertEquals(13f, run.placements[1].left)
        assertEquals(23f, run.placements[1].right)
        assertEquals(24f, run.width)
    }

    @Test
    fun kerningPullsThePairTogether() {
        val kerned = font.layout("AB").placements[1]
        val unkerned = BitmapFont(font.glyphs).layout("AB").placements[1]
        assertEquals(3f, unkerned.left - kerned.left, "the AB pair is kerned by -3")
    }

    @Test
    fun aGlyphWithoutABitmapOnlyAdvances() {
        val run = font.layout("A A")
        assertEquals(2, run.placements.size, "the space draws nothing")
        // The space adds its two margins (8) between the first A's right margin and the second's
        // left margin: 11 + 1 + 8 + 1.
        assertEquals(21f, run.placements[1].left)
    }

    @Test
    fun glyphSpacingIsAddedAfterEveryDrawnGlyphAndNotAfterASpace() {
        assertEquals(font.layout("AA").width + 2f, font.layout("AA", glyphSpacing = 1f).width)
        assertEquals(font.layout("A A").width + 2f, font.layout("A A", glyphSpacing = 1f).width)
    }

    @Test
    fun charactersWithNoGlyphAreSkippedAndBreakTheKerningPair() {
        val run = font.layout("A?B")
        assertEquals(2, run.placements.size)
        // With the pair broken, B sits where an unkerned B would.
        assertEquals(font.layout("A").width + 1f, run.placements[1].left)
    }

    @Test
    fun theShortestMatchingGlyphWins() {
        val ligature = BitmapFont(listOf(glyph("ab", 5, width = 20), glyph("a", 6)))
        assertEquals("a", ligature.lookupGlyph("abc", 0)?.chars)
        assertNull(ligature.lookupGlyph("xyz", 0))
        // Only the longer glyph can match here, so it does.
        val onlyLong = BitmapFont(listOf(glyph("ab", 5, width = 20)))
        assertEquals("ab", onlyLong.lookupGlyph("ab", 0)?.chars)
    }

    @Test
    fun aGlyphsVerticalExtentComesFromItsTopMarginAndHeight() {
        val tall = BitmapFont(listOf(glyph("A", 1, height = 20, top = 3)))
        val placement = tall.layout("A").placements.single()
        assertEquals(3f, placement.top)
        assertEquals(23f, placement.bottom)
    }

    @Test
    fun anEmptyRunMeasuresZero() {
        assertEquals(0f, font.measureWidth(""))
        assertTrue(font.layout("").placements.isEmpty())
    }
}
