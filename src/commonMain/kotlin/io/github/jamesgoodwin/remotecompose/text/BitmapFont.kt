package io.github.jamesgoodwin.remotecompose.text

/**
 * One entry of a [BitmapFont]: the characters it stands for and the bitmap that draws them, with
 * the margins that space it from its neighbours. `BitmapFontData.Glyph` (remote-core
 * 1.0.0-alpha18); a [bitmapId] of -1 is a glyph that only advances, such as a space.
 */
data class BitmapGlyph(
    val chars: String,
    val bitmapId: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
)

/** Where one glyph of a laid-out run sits: its bitmap fills `(left, top, right, bottom)`. */
data class BitmapGlyphPlacement(
    val glyph: BitmapGlyph,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * A laid-out run: where each drawn glyph goes, and where the run ends. Glyphs that only advance
 * carry no placement but still move [width] along.
 */
data class BitmapTextRun(val placements: List<BitmapGlyphPlacement>, val width: Float)

/**
 * A font whose glyphs are bitmaps rather than outlines: `BitmapFontData` (remote-core
 * 1.0.0-alpha18). [kerning] is keyed by the pair of glyph strings either side of the join.
 *
 * Glyphs are matched by prefix, shortest first, exactly as `BitmapFontData` sorts its array
 * before `lookupGlyph` scans it — so where a font has both "a" and "ab", "a" wins.
 */
class BitmapFont(glyphs: List<BitmapGlyph>, val kerning: Map<String, Int> = emptyMap()) {

    val glyphs: List<BitmapGlyph> = glyphs.sortedBy { it.chars.length }

    /** `lookupGlyph`: the first glyph whose characters start [text] at [index], or null. */
    fun lookupGlyph(text: String, index: Int): BitmapGlyph? =
        glyphs.firstOrNull { it.chars.isNotEmpty() && text.startsWith(it.chars, index) }

    /**
     * `DrawBitmapFontText.paint`'s advance loop: walks [text] glyph by glyph from `x = 0`,
     * applying each glyph's margins, the kerning for the pair it forms with the glyph before it,
     * and [glyphSpacing] after it. A character no glyph matches is skipped and breaks the pair.
     *
     * Placements are relative to the run's origin, which is its top left: a glyph's top is its
     * own top margin.
     */
    fun layout(text: String, glyphSpacing: Float = 0f): BitmapTextRun {
        val out = mutableListOf<BitmapGlyphPlacement>()
        var x = 0f
        var previous = ""
        var i = 0
        while (i < text.length) {
            val glyph = lookupGlyph(text, i)
            if (glyph == null || glyph.chars.isEmpty()) {
                i += 1
                previous = ""
                continue
            }
            i += glyph.chars.length
            if (glyph.bitmapId == -1) {
                // Advance-only: a space carries no bitmap, and takes no glyph spacing either.
                x += (glyph.marginLeft + glyph.marginRight).toFloat()
                previous = glyph.chars
                continue
            }
            x += glyph.marginLeft.toFloat()
            kerning[previous + glyph.chars]?.let { x += it.toFloat() }
            val right = x + glyph.bitmapWidth
            out += BitmapGlyphPlacement(
                glyph, x, glyph.marginTop.toFloat(), right, (glyph.bitmapHeight + glyph.marginTop).toFloat(),
            )
            x = right + glyph.marginRight + glyphSpacing
            previous = glyph.chars
        }
        return BitmapTextRun(out, x)
    }

    /** `DrawBitmapFontTextOnPath.measureWidth`: where the run ends, including the last margin. */
    fun measureWidth(text: String, glyphSpacing: Float = 0f): Float = layout(text, glyphSpacing).width
}
