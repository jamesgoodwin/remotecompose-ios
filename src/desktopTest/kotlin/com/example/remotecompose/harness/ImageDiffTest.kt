package com.example.remotecompose.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The comparison rules the cross-platform harness judges a device screenshot by. */
class ImageDiffTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    /** A raster from a picture: '.' white, '#' black, 'r' red, and any digit a grey of digit*28. */
    private fun raster(vararg rows: String): Raster {
        val width = rows[0].length
        val pixels = IntArray(width * rows.size)
        for ((y, row) in rows.withIndex()) {
            for ((x, c) in row.withIndex()) {
                pixels[y * width + x] = when (c) {
                    '.' -> white
                    '#' -> black
                    'r' -> red
                    else -> {
                        val grey = c.digitToInt() * 28
                        0xFF000000.toInt() or (grey shl 16) or (grey shl 8) or grey
                    }
                }
            }
        }
        return Raster(width, rows.size, pixels)
    }

    private fun noMask(raster: Raster) = BooleanArray(raster.pixels.size)

    private fun compare(a: Raster, b: Raster, radius: Int = 1, tolerance: Int = 0) =
        ImageDiff.compare(a, b, noMask(a), tolerance, radius)

    @Test
    fun anIdenticalImageMatches() {
        val image = raster("..#..", ".###.", "..#..")
        assertEquals(0, compare(image, image).shapeMismatches)
    }

    @Test
    fun anEdgeThatFellDifferentlyIsAccepted() {
        // The same edge, antialiased more heavily: every blend either side uses lies between two
        // the other already has next to it. Near an edge the rule is deliberately permissive —
        // that is what it is for — and the tests below cover what it still refuses.
        val reference = raster(".3##", ".3##", ".3##")
        val heavier = raster(".51#", ".51#", ".51#")
        assertEquals(0, compare(reference, heavier).shapeMismatches)
    }

    @Test
    fun aShapeThatMovedFurtherThanTheRadiusFails() {
        val reference = raster("r....", "r....", "r....")
        val onePixelOver = raster(".r...", ".r...", ".r...")
        val threePixelsOver = raster("...r.", "...r.", "...r.")
        assertEquals(0, compare(reference, onePixelOver).shapeMismatches)
        assertTrue(compare(reference, threePixelsOver).shapeMismatches > 0)
    }

    @Test
    fun aShapeThatVanishedFails() {
        // The one-way check would pass this: white is next to the line in the reference. The
        // comparison runs in both directions so that a missing line is caught too.
        val reference = raster(".r.", ".r.", ".r.")
        val blank = raster("...", "...", "...")
        assertTrue(compare(reference, blank).shapeMismatches > 0)
    }

    @Test
    fun aWrongColourFails() {
        val reference = raster("###", "###", "###")
        val wrong = raster("rrr", "rrr", "rrr")
        assertEquals(9, compare(reference, wrong).shapeMismatches)
    }

    @Test
    fun theChannelToleranceAbsorbsRounding() {
        val reference = raster("444", "444", "444")
        val slightlyOff = raster("555", "555", "555") // 28 apart per channel
        assertTrue(compare(reference, slightlyOff, tolerance = 8).shapeMismatches > 0)
        assertEquals(0, compare(reference, slightlyOff, tolerance = 32).shapeMismatches)
    }

    @Test
    fun maskedPixelsAreCountedRatherThanFailed() {
        val reference = raster("###", "###", "###")
        val wrong = raster("rrr", "rrr", "rrr")
        val allText = BooleanArray(9) { true }
        val report = ImageDiff.compare(reference, wrong, allText, 0, 1)
        assertEquals(0, report.shapeMismatches)
        assertEquals(9, report.textMismatches)
        assertEquals(1f, report.textMismatchFraction)
        assertTrue(!report.passed(0.35f))
        assertTrue(report.passed(1f))
    }

    @Test
    fun aShapeAllowanceLetsAKnownDifferenceThrough() {
        val reference = raster("rrr", "###", "###")
        val candidate = raster("...", "###", "###")
        val report = compare(reference, candidate)
        assertEquals(3, report.shapeMismatches)
        assertTrue(!report.passed(0.35f))
        assertTrue(report.passed(0.35f, allowedShapePixels = 3))
    }

    @Test
    fun theTextMaskIsWhereTheTextChangedThings() {
        val withText = raster(".....", "..#..", ".....")
        val withoutText = raster(".....", ".....", ".....")
        val tight = ImageDiff.textMask(withText, withoutText, radius = 0)
        assertEquals(1, tight.count { it })
        assertTrue(tight[1 * 5 + 2])
        // Dilating it covers where another rasterizer might have put the same glyph.
        val grown = ImageDiff.textMask(withText, withoutText, radius = 1)
        assertEquals(9, grown.count { it })
    }

    @Test
    fun theMaskRadiusFollowsTheLargestText() {
        assertEquals(3, ImageDiff.maskRadiusFor(0f))
        assertEquals(3, ImageDiff.maskRadiusFor(10f))
        assertEquals(4, ImageDiff.maskRadiusFor(13f))
        assertEquals(6, ImageDiff.maskRadiusFor(22f))
    }

    @Test
    fun theDocumentIsFoundWhereTheHostCentresIt() {
        val backdrop = 0xFF37474F.toInt()
        val screen = Raster(20, 20, IntArray(400) { backdrop })
        for (y in 8 until 12) for (x in 8 until 12) screen.pixels[y * 20 + x] = white
        assertEquals(8 to 8, ImageDiff.locateDocument(screen, 4, 4, backdrop))
    }

    @Test
    fun aScreenshotWithNoDocumentOnItIsRefused() {
        val backdrop = 0xFF37474F.toInt()
        val empty = Raster(20, 20, IntArray(400) { backdrop })
        assertNull(ImageDiff.locateDocument(empty, 4, 4, backdrop))
        // And a document larger than the screen is not somewhere on it.
        assertNull(ImageDiff.locateDocument(empty, 40, 4, backdrop))
    }

    @Test
    fun aDocumentDrawingTheBackdropsOwnColourIsStillFound() {
        // advanced.rc draws a path in exactly the slate the host paints behind it.
        val backdrop = 0xFF37474F.toInt()
        val screen = Raster(20, 20, IntArray(400) { backdrop })
        for (y in 8 until 12) for (x in 8 until 12) screen.pixels[y * 20 + x] = white
        screen.pixels[9 * 20 + 9] = backdrop
        assertEquals(8 to 8, ImageDiff.locateDocument(screen, 4, 4, backdrop))
    }

    @Test
    fun croppingTakesTheRegionAsked() {
        val image = raster("0123", "4567", "89..")
        val middle = image.crop(1, 0, 2, 2)
        assertEquals(2, middle.width)
        assertEquals(image[1, 0], middle[0, 0])
        assertEquals(image[2, 1], middle[1, 1])
    }
}
