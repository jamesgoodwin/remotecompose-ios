package io.github.jamesgoodwin.remotecompose.harness

import java.awt.image.BufferedImage

/**
 * A rectangle of pixels, kept as packed ARGB so a comparison does not go through
 * `BufferedImage.getRGB` per pixel.
 */
class Raster(val width: Int, val height: Int, val pixels: IntArray) {

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun crop(left: Int, top: Int, cropWidth: Int, cropHeight: Int): Raster {
        val out = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            pixels.copyInto(
                out,
                destinationOffset = y * cropWidth,
                startIndex = (top + y) * width + left,
                endIndex = (top + y) * width + left + cropWidth,
            )
        }
        return Raster(cropWidth, cropHeight, out)
    }

    fun toImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return image
    }

    companion object {
        fun of(image: BufferedImage): Raster {
            val pixels = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            return Raster(image.width, image.height, pixels)
        }
    }
}

/**
 * What a comparison found. [shapeMismatches] is the count that decides pass or fail on its own,
 * since shapes are expected to match; [textMismatches] is judged against a share of [textPixels],
 * because two font rasterizers disagree about glyph pixels no matter how correct both are.
 */
data class DiffReport(
    val width: Int,
    val height: Int,
    val textPixels: Int,
    val textMismatches: Int,
    val shapePixels: Int,
    val shapeMismatches: Int,
    val worstShapeDelta: Int,
    val worstShapeAt: Pair<Int, Int>?,
) {
    val textMismatchFraction: Float get() = if (textPixels == 0) 0f else textMismatches.toFloat() / textPixels

    fun passed(maxTextFraction: Float, allowedShapePixels: Int = 0): Boolean =
        shapeMismatches <= allowedShapePixels && textMismatchFraction <= maxTextFraction

    fun describe(maxTextFraction: Float): String = buildString {
        append("${width}x$height: ")
        append("shapes $shapeMismatches/$shapePixels differ")
        if (worstShapeAt != null) append(" (worst ${worstShapeDelta} at ${worstShapeAt.first},${worstShapeAt.second})")
        append(", text $textMismatches/$textPixels differ")
        if (textPixels > 0) {
            val percent = (textMismatchFraction * 1000).toInt() / 10f
            append(" ($percent%, allowed ${(maxTextFraction * 100).toInt()}%)")
        }
    }
}

/**
 * Compares two renders of the same document.
 *
 * A pixel is accepted when each of its channels falls inside the range the *other* image spans
 * within [searchRadius] of it, give or take [channelTolerance] — checked in both directions, so
 * that something the candidate added and something it lost both count. That is deliberately not a
 * pixel-for-pixel comparison: two Skia builds rasterizing the same edge disagree about how much
 * of each pixel along it a shape covers, and one of them puts the extremes of a circle a pixel
 * further out; a document is not wrong because its antialiasing came out heavier. Inside a shape
 * or on flat background the neighbourhood is one colour and the rule is exact. What it still
 * catches is anything that moves further than the radius, changes colour, appears, or disappears,
 * which is what a geometry or paint bug does.
 *
 * Glyphs move further than that between font rasterizers however correct both are, so the pixels
 * [textMask] marks are only counted, and judged against a share of the text rather than expected
 * to match.
 */
object ImageDiff {

    /**
     * How far a channel may be off before a pixel counts as different. Measured, not chosen: the
     * most two agreeing renders were seen to disagree by is 13, where a bitmap's edge is sampled.
     */
    const val DEFAULT_CHANNEL_TOLERANCE = 16
    const val DEFAULT_TEXT_FRACTION = 0.35f

    /**
     * How far away the colours a pixel may be a blend of are looked for, which is the distance an
     * edge is allowed to have moved. Also measured: Android puts the extremes of a circle up to
     * two pixels further out than a CPU raster does, and nothing agreeing was seen to move more.
     */
    const val DEFAULT_SEARCH_RADIUS = 2

    /**
     * How far a glyph may land from where this renderer put it and still count as that glyph.
     * Glyph outlines differ by a share of their size rather than by a fixed distance, so this
     * follows the largest text in the document: a 22px title needed 6 where 10px labels needed 3.
     */
    fun maskRadiusFor(largestTextSize: Float): Int =
        maxOf(3, kotlin.math.ceil(largestTextSize / 4f).toInt())

    fun compare(
        reference: Raster,
        candidate: Raster,
        textMask: BooleanArray,
        channelTolerance: Int = DEFAULT_CHANNEL_TOLERANCE,
        searchRadius: Int = DEFAULT_SEARCH_RADIUS,
        diffOut: IntArray? = null,
    ): DiffReport {
        require(reference.width == candidate.width && reference.height == candidate.height) {
            "Size mismatch: reference ${reference.width}x${reference.height}, " +
                "candidate ${candidate.width}x${candidate.height}"
        }
        require(textMask.size == reference.pixels.size) { "The mask does not match the image size" }
        var textPixels = 0
        var textMismatches = 0
        var shapeMismatches = 0
        var worstShapeDelta = 0
        var worstShapeAt: Pair<Int, Int>? = null
        for (i in reference.pixels.indices) {
            val isText = textMask[i]
            if (isText) textPixels++
            val x = i % reference.width
            val y = i / reference.width
            val delta = maxOf(
                rangeDelta(reference, candidate.pixels[i], x, y, searchRadius),
                rangeDelta(candidate, reference.pixels[i], x, y, searchRadius),
            )
            val differs = delta > channelTolerance
            if (differs) {
                if (isText) {
                    textMismatches++
                } else {
                    shapeMismatches++
                    if (delta > worstShapeDelta) {
                        worstShapeDelta = delta
                        worstShapeAt = (i % reference.width) to (i / reference.width)
                    }
                }
            }
            diffOut?.set(i, diffColour(isText, differs, delta))
        }
        return DiffReport(
            width = reference.width,
            height = reference.height,
            textPixels = textPixels,
            textMismatches = textMismatches,
            shapePixels = reference.pixels.size - textPixels,
            shapeMismatches = shapeMismatches,
            worstShapeDelta = worstShapeDelta,
            worstShapeAt = worstShapeAt,
        )
    }

    /**
     * How far [colour] falls outside the range [image] spans within [radius] of ([x], [y]), as
     * the worst channel: zero when the pixel is some blend of what is already there, large when
     * it is a colour the neighbourhood does not reach.
     */
    fun rangeDelta(image: Raster, colour: Int, x: Int, y: Int, radius: Int): Int {
        val low = intArrayOf(255, 255, 255, 255)
        val high = intArrayOf(0, 0, 0, 0)
        val fromY = maxOf(0, y - radius)
        val toY = minOf(image.height - 1, y + radius)
        val fromX = maxOf(0, x - radius)
        val toX = minOf(image.width - 1, x + radius)
        for (ny in fromY..toY) {
            for (nx in fromX..toX) {
                val pixel = image[nx, ny]
                for (c in 0 until 4) {
                    val channel = (pixel shr SHIFTS[c]) and 0xFF
                    if (channel < low[c]) low[c] = channel
                    if (channel > high[c]) high[c] = channel
                }
            }
        }
        var worst = 0
        for (c in 0 until 4) {
            val channel = (colour shr SHIFTS[c]) and 0xFF
            val outside = when {
                channel < low[c] -> low[c] - channel
                channel > high[c] -> channel - high[c]
                else -> 0
            }
            if (outside > worst) worst = outside
        }
        return worst
    }

    private val SHIFTS = intArrayOf(24, 16, 8, 0)

    /** The largest single-channel distance between two packed ARGB pixels. */
    fun channelDelta(a: Int, b: Int): Int {
        var worst = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val delta = (((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            val magnitude = if (delta < 0) -delta else delta
            if (magnitude > worst) worst = magnitude
        }
        return worst
    }

    /**
     * The pixels the document's text drew: where a render with the text differs from one without
     * it, grown by [radius] so that a glyph landing a pixel or two off still counts as text
     * rather than as a shape that moved.
     */
    fun textMask(withText: Raster, withoutText: Raster, radius: Int): BooleanArray {
        require(withText.width == withoutText.width && withText.height == withoutText.height)
        val width = withText.width
        val height = withText.height
        val touched = BooleanArray(width * height)
        for (i in touched.indices) touched[i] = withText.pixels[i] != withoutText.pixels[i]
        if (radius <= 0) return touched
        return dilate(touched, width, height, radius)
    }

    /** Marks every pixel within [radius] of a set one, as two passes over a separable window. */
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val horizontal = BooleanArray(mask.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!mask[row + x]) continue
                val from = maxOf(0, x - radius)
                val to = minOf(width - 1, x + radius)
                for (k in from..to) horizontal[row + k] = true
            }
        }
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!horizontal[y * width + x]) continue
                val from = maxOf(0, y - radius)
                val to = minOf(height - 1, y + radius)
                for (k in from..to) out[k * width + x] = true
            }
        }
        return out
    }

    /**
     * Where the document sits inside a device screenshot.
     *
     * The demo host centres it in a full-screen box at one document pixel per device pixel, so
     * the position follows from the two sizes; see `RealPayloadDemoScreen`. Pixels are not
     * searched for it, because a document may draw the backdrop's own colour, and the coverage
     * fixture draws slightly outside its canvas — neither is a reason to fail to find it.
     *
     * Returns null when the screenshot is too small, or when the rectangle is mostly [backdrop],
     * which is what a screenshot of the wrong page or of an app that did not start looks like.
     */
    fun locateDocument(screen: Raster, width: Int, height: Int, backdrop: Int): Pair<Int, Int>? {
        if (width > screen.width || height > screen.height) return null
        val left = (screen.width - width) / 2
        val top = (screen.height - height) / 2
        var painted = 0
        for (y in top until top + height) {
            for (x in left until left + width) if (screen[x, y] != backdrop) painted++
        }
        return if (painted * 2 > width * height) left to top else null
    }

    /** Green where the text was allowed to differ, red where a shape pixel did, grey elsewhere. */
    private fun diffColour(isText: Boolean, differs: Boolean, delta: Int): Int = when {
        differs && !isText -> 0xFFFF0000.toInt()
        differs -> 0xFF00A000.toInt()
        else -> {
            val grey = 255 - minOf(255, delta * 8)
            0xFF000000.toInt() or (grey shl 16) or (grey shl 8) or grey
        }
    }
}
