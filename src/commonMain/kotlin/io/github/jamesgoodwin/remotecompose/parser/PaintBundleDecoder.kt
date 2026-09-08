package io.github.jamesgoodwin.remotecompose.parser

import androidx.compose.ui.graphics.Color
import io.github.jamesgoodwin.remotecompose.model.FontFamilyKind
import io.github.jamesgoodwin.remotecompose.model.GradientSpec
import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import io.github.jamesgoodwin.remotecompose.model.PaintStyleKind
import io.github.jamesgoodwin.remotecompose.model.StrokeCapKind
import io.github.jamesgoodwin.remotecompose.model.StrokeJoinKind

/**
 * The cumulative paint the real player keeps on its `PaintContext`: every `PAINT_VALUES`
 * bundle is a delta (the writer's `RcPaint.commit()` writes the bundle and then `reset()`s it),
 * applied on top of whatever was set before. Defaults match `android.graphics.Paint()`.
 */
internal class PaintState {
    var color: Color = Color.Black
    var style: PaintStyleKind = PaintStyleKind.FILL
    var strokeWidth: Float = 0f
    var strokeCap: StrokeCapKind = StrokeCapKind.BUTT
    var strokeJoin: StrokeJoinKind = StrokeJoinKind.MITER
    var strokeMiter: Float = 4f
    var textSize: Float = PaintStyle.DEFAULT_TEXT_SIZE_PX
    var fontWeight: Int = 400
    var fontItalic: Boolean = false
    var fontFamily: FontFamilyKind = FontFamilyKind.DEFAULT
    var gradient: GradientSpec? = null
    var blendMode: Int? = null

    fun snapshot(): PaintStyle = PaintStyle(
        color = color,
        style = style,
        strokeWidth = strokeWidth,
        strokeCap = strokeCap,
        strokeJoin = strokeJoin,
        strokeMiter = strokeMiter,
        textSize = textSize,
        fontWeight = fontWeight,
        fontItalic = fontItalic,
        fontFamily = fontFamily,
        gradient = gradient,
        blendMode = blendMode,
    )

    fun copy(): PaintState = PaintState().also {
        it.color = color
        it.style = style
        it.strokeWidth = strokeWidth
        it.strokeCap = strokeCap
        it.strokeJoin = strokeJoin
        it.strokeMiter = strokeMiter
        it.textSize = textSize
        it.fontWeight = fontWeight
        it.fontItalic = fontItalic
        it.fontFamily = fontFamily
        it.gradient = gradient
        it.blendMode = blendMode
    }

    fun restoreFrom(other: PaintState) {
        color = other.color
        style = other.style
        strokeWidth = other.strokeWidth
        strokeCap = other.strokeCap
        strokeJoin = other.strokeJoin
        strokeMiter = other.strokeMiter
        textSize = other.textSize
        fontWeight = other.fontWeight
        fontItalic = other.fontItalic
        fontFamily = other.fontFamily
        gradient = other.gradient
        blendMode = other.blendMode
    }
}

/**
 * Decodes the word array of an `Operations.PAINT_VALUES` (opcode 40) record into a [PaintState].
 *
 * Wire shape, from `PaintBundle.writeBundle`: `[wordCount:i32][word × wordCount]`. Each attribute
 * starts with a word whose low 16 bits are the `PaintBundle.<ATTR>` id and whose high 16 bits
 * carry small enum payloads; larger payloads follow in subsequent words. The exact per-attribute
 * layout below is taken from `PaintBundle.applyPaintChange` and the matching `set*` writers:
 *
 * | id | attribute            | high bits            | following words                         |
 * |----|----------------------|----------------------|-----------------------------------------|
 * | 1  | TEXT_SIZE            |                      | float                                   |
 * | 4  | COLOR                |                      | argb int                                |
 * | 5  | STROKE_WIDTH         |                      | float                                   |
 * | 6  | STROKE_MITER         |                      | float                                   |
 * | 7  | STROKE_CAP           | cap ordinal          |                                         |
 * | 8  | STYLE                | style ordinal        |                                         |
 * | 9  | SHADER               |                      | shader id (0 clears)                    |
 * | 10 | IMAGE_FILTER_QUALITY | quality              |                                         |
 * | 11 | GRADIENT             | 0 linear/1 radial/2 sweep | see [readGradient]                |
 * | 12 | ALPHA                |                      | float                                   |
 * | 13 | COLOR_FILTER         | porter-duff mode     | argb int                                |
 * | 14 | ANTI_ALIAS           | flag                 |                                         |
 * | 15 | STROKE_JOIN          | join ordinal         |                                         |
 * | 16 | TYPEFACE             | weight, italic, flag | font type or text id                    |
 * | 17 | FILTER_BITMAP        | flag                 |                                         |
 * | 18 | BLEND_MODE           | mode                 |                                         |
 * | 19 | COLOR_ID             |                      | color pool id                           |
 * | 20 | COLOR_FILTER_ID      | mode                 | color pool id                           |
 * | 21 | CLEAR_COLOR_FILTER   |                      |                                         |
 * | 22 | SHADER_MATRIX        |                      | float                                   |
 * | 23 | FONT_AXIS            | count                | count × (text id, float)                |
 * | 24 | TEXTURE              |                      | bitmap id, packed short pair, packed short pair |
 * | 25 | PATH_EFFECT          | count                | count × float                           |
 * | 26 | FALLBACK_TYPEFACE    | weight, italic       | font type                               |
 *
 * Any other id has no payload words; the real reader logs it and moves on, and so does this one.
 * Float payloads may be NaN-tagged variable references, resolved through [resolveFloat] the same
 * way `PaintBundle.resolveIds` does.
 */
internal object PaintBundleDecoder {

    const val TEXT_SIZE = 1
    const val COLOR = 4
    const val STROKE_WIDTH = 5
    const val STROKE_MITER = 6
    const val STROKE_CAP = 7
    const val STYLE = 8
    const val SHADER = 9
    const val IMAGE_FILTER_QUALITY = 10
    const val GRADIENT = 11
    const val ALPHA = 12
    const val COLOR_FILTER = 13
    const val ANTI_ALIAS = 14
    const val STROKE_JOIN = 15
    const val TYPEFACE = 16
    const val FILTER_BITMAP = 17
    const val BLEND_MODE = 18
    const val COLOR_ID = 19
    const val COLOR_FILTER_ID = 20
    const val CLEAR_COLOR_FILTER = 21
    const val SHADER_MATRIX = 22
    const val FONT_AXIS = 23
    const val TEXTURE = 24
    const val PATH_EFFECT = 25
    const val FALLBACK_TYPEFACE = 26

    const val LINEAR_GRADIENT = 0
    const val RADIAL_GRADIENT = 1
    const val SWEEP_GRADIENT = 2

    /** `PaintBundle.BLEND_MODE_NULL`: the writer's "no blend mode" marker. */
    const val BLEND_MODE_NULL = 29

    fun apply(
        words: IntArray,
        state: PaintState,
        resolveFloat: (Float) -> Float,
        colorById: (Int) -> Color?,
        textById: (Int) -> String?,
    ) {
        var i = 0
        fun nextWord(): Int = words[i++]
        fun nextFloat(): Float = resolveFloat(Float.fromBits(nextWord()))

        while (i < words.size) {
            val word = nextWord()
            val id = word and 0xFFFF
            val hi = word shr 16
            when (id) {
                TEXT_SIZE -> state.textSize = nextFloat()
                COLOR -> state.color = Color(nextWord())
                STROKE_WIDTH -> state.strokeWidth = nextFloat()
                STROKE_MITER -> state.strokeMiter = nextFloat()
                STROKE_CAP -> state.strokeCap = StrokeCapKind.entries.getOrElse(hi) { StrokeCapKind.BUTT }
                STYLE -> state.style = PaintStyleKind.entries.getOrElse(hi) { PaintStyleKind.FILL }
                SHADER -> {
                    // Only id 0 (clear) has an effect here; a DATA_SHADER reference would need a
                    // runtime shader compiler this renderer does not have.
                    if (nextWord() == 0) state.gradient = null
                }
                IMAGE_FILTER_QUALITY -> Unit
                GRADIENT -> {
                    val gradient = readGradient(hi, words, i, resolveFloat)
                    i = gradient.second
                    if (gradient.first != null) state.gradient = gradient.first
                }
                ALPHA -> {
                    val alpha = nextFloat()
                    if (!alpha.isNaN()) state.color = state.color.copy(alpha = alpha.coerceIn(0f, 1f))
                }
                COLOR_FILTER -> nextWord()
                ANTI_ALIAS -> Unit
                STROKE_JOIN -> state.strokeJoin = StrokeJoinKind.entries.getOrElse(hi) { StrokeJoinKind.MITER }
                TYPEFACE -> {
                    val weight = hi and 1023
                    // The real reader derives italic from `(hi >> 10) > 0`, which is also true for
                    // the 1024 "force int typeface" bit that RcPaint.setTypeface(int) sets. It is
                    // mirrored as-is so output matches the Android player.
                    val italic = (hi shr 10) > 0
                    val forceIntType = (hi and 1024) != 0
                    val value = nextWord()
                    state.fontWeight = if (weight == 0) 400 else weight
                    state.fontItalic = italic
                    state.fontFamily = if (value > 10 && !forceIntType) {
                        familyFromName(textById(value))
                    } else {
                        FontFamilyKind.entries.getOrElse(value) { FontFamilyKind.DEFAULT }
                    }
                }
                FILTER_BITMAP -> Unit
                BLEND_MODE -> state.blendMode = hi.takeIf { it != BLEND_MODE_NULL }
                COLOR_ID -> colorById(nextWord())?.let { state.color = it }
                COLOR_FILTER_ID -> nextWord()
                CLEAR_COLOR_FILTER -> Unit
                SHADER_MATRIX -> nextWord()
                FONT_AXIS -> i += hi * 2
                TEXTURE -> i += 3
                PATH_EFFECT -> i += hi
                FALLBACK_TYPEFACE -> nextWord()
                else -> Unit
            }
        }
    }

    /**
     * Reads one gradient starting at [start] (the word after the `GRADIENT` id word) and returns
     * the decoded spec plus the index of the first word after it. Layout from
     * `PaintBundle.callSetGradient`: `[colorCount & 0xFF][colors...][stopCount][stops...]` then,
     * per [type]: linear `startX startY endX endY tileMode`, radial `centerX centerY radius
     * tileMode`, sweep `centerX centerY`.
     */
    private fun readGradient(
        type: Int,
        words: IntArray,
        start: Int,
        resolveFloat: (Float) -> Float,
    ): Pair<GradientSpec?, Int> {
        var i = start
        fun nextWord(): Int = words[i++]
        fun nextFloat(): Float = resolveFloat(Float.fromBits(nextWord()))

        val colorCount = nextWord() and 0xFF
        val colors = List(colorCount) { Color(nextWord()) }
        val stopCount = nextWord()
        val stops = if (stopCount > 0) List(stopCount) { nextFloat() } else null
        if (colorCount == 0) return null to i
        val spec: GradientSpec? = when (type) {
            LINEAR_GRADIENT -> {
                val sx = nextFloat(); val sy = nextFloat(); val ex = nextFloat(); val ey = nextFloat()
                GradientSpec.Linear(colors, stops, sx, sy, ex, ey, nextWord())
            }
            RADIAL_GRADIENT -> {
                val cx = nextFloat(); val cy = nextFloat(); val r = nextFloat()
                GradientSpec.Radial(colors, stops, cx, cy, r, nextWord())
            }
            SWEEP_GRADIENT -> {
                val cx = nextFloat(); val cy = nextFloat()
                GradientSpec.Sweep(colors, stops, cx, cy)
            }
            else -> null
        }
        return spec to i
    }

    private fun familyFromName(name: String?): FontFamilyKind = when (name?.lowercase()) {
        "sans-serif", "sans_serif", "sansserif" -> FontFamilyKind.SANS_SERIF
        "serif" -> FontFamilyKind.SERIF
        "monospace" -> FontFamilyKind.MONOSPACE
        else -> FontFamilyKind.DEFAULT
    }
}
