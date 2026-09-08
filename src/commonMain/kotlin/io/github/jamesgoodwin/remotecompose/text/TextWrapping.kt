package io.github.jamesgoodwin.remotecompose.text

import io.github.jamesgoodwin.remotecompose.model.PaintStyle
import kotlin.math.max

/**
 * A string broken into the lines it is drawn as.
 *
 * [width] is the widest line, which is what a wrap-content component measures to; [height] is
 * [lineHeight] times the number of lines. Line `n`'s baseline sits at `ascent + n * lineHeight`
 * from the top of the block. [ended] says which lines were ended by the text rather than by
 * running out of room, so justification knows not to stretch them.
 */
class TextBlock(
    val lines: List<String>,
    val lineWidths: List<Float>,
    val ended: List<Boolean>,
    val width: Float,
    val height: Float,
    val lineHeight: Float,
    val ascent: Float,
) {
    val isSingleLine: Boolean get() = lines.size <= 1
}

/**
 * Line breaking for `TextLayout` and `CoreText`.
 *
 * Both hand the job to the host: `computeWrapSize` calls `PaintContext.layoutComplexText(...)`
 * and keeps a `RcPlatformServices.ComputedTextLayout`, and `paintingComponent` gives that back to
 * `PaintContext.drawComplexText`. Neither the breaking nor the drawing is in remote-core, so
 * there is no library version of what follows to transcribe — only of when it is asked for, and
 * with which parameters, which is what [needsLayout] and the arguments here mirror.
 *
 * What is implemented is the greedy break (`BREAK_STRATEGY_SIMPLE`), `maxLines`, the three
 * ellipsis overflows, and the two line-height parameters. `BREAK_STRATEGY_HIGH_QUALITY` and
 * `_BALANCED` fall back to the greedy break, and hyphenation is not applied — both want a
 * dictionary and a penalty model this renderer has neither of.
 */
object TextWrapping {

    /** `CoreText.OVERFLOW_*`. */
    const val OVERFLOW_CLIP = 1
    const val OVERFLOW_VISIBLE = 2
    const val OVERFLOW_ELLIPSIS = 3
    const val OVERFLOW_START_ELLIPSIS = 4
    const val OVERFLOW_MIDDLE_ELLIPSIS = 5

    private const val ELLIPSIS = "…"

    /**
     * `TextLayout.computeWrapSize`: the complex path is taken when the string carries a line
     * break of its own, or when it is wider than the room it has and more than one line is
     * allowed. A single line that will be ellipsised counts as complex too, since where to cut
     * it is the same question.
     */
    fun needsLayout(text: String, textWidth: Float, maxWidth: Float, maxLines: Int, overflow: Int): Boolean {
        if (text.any { it == '\n' || it == '\t' }) return true
        if (maxWidth <= 0f || maxWidth == Float.MAX_VALUE) return false
        if (textWidth <= maxWidth) return false
        if (maxLines > 1) return true
        return overflow == OVERFLOW_ELLIPSIS ||
            overflow == OVERFLOW_START_ELLIPSIS ||
            overflow == OVERFLOW_MIDDLE_ELLIPSIS
    }

    /**
     * [text] broken to fit [maxWidth], at most [maxLines] lines, with [overflow] saying what
     * happens to what will not fit.
     *
     * `lineHeightMultiplier` scales the font's own line height and `lineHeightAdd` is added to
     * it, in that order, as `StaticLayout`'s `spacingMult` and `spacingAdd` are.
     */
    fun layout(
        text: String,
        paint: PaintStyle,
        metrics: TextMetricsProvider,
        maxWidth: Float,
        maxLines: Int = Int.MAX_VALUE,
        overflow: Int = OVERFLOW_CLIP,
        lineHeightAdd: Float = 0f,
        lineHeightMultiplier: Float = 1f,
    ): TextBlock {
        fun widthOf(s: String) = metrics.measure(s, paint).width
        val font = metrics.measure(text.ifEmpty { " " }, paint)
        val room = if (maxWidth <= 0f) Float.MAX_VALUE else maxWidth

        val broken = mutableListOf<Line>()
        // Tabs are not tab stops here: they break a line the way `computeWrapSize` treats them,
        // and are otherwise a space.
        for (paragraph in text.split('\n')) {
            breakParagraph(paragraph.replace('\t', ' '), room, ::widthOf, broken)
        }
        if (broken.isEmpty()) broken += Line("", true, "")

        val limit = if (maxLines <= 0) Int.MAX_VALUE else maxLines
        val kept = if (overflow == OVERFLOW_VISIBLE || broken.size <= limit) {
            broken.take(if (overflow == OVERFLOW_VISIBLE) broken.size else limit).map { it.text }
        } else {
            // The last line that fits is cut from the rest of its own paragraph rather than from
            // the lines after it: those were broken already, and rejoining them would put a space
            // where the break was even when the text had none.
            val visible = broken.take(limit).map { it.text }.toMutableList()
            visible[limit - 1] = truncate(broken[limit - 1].rest, room, overflow, ::widthOf)
            visible
        }

        val ended = broken.map { it.ended }
        val widths = kept.map { widthOf(it) }
        val lineHeight = font.height * lineHeightMultiplier + lineHeightAdd
        return TextBlock(
            lines = kept,
            lineWidths = widths,
            ended = ended.take(kept.size) + List(max(0, kept.size - ended.size)) { true },
            width = widths.maxOrNull() ?: 0f,
            height = lineHeight * kept.size,
            lineHeight = lineHeight,
            ascent = font.ascent,
        )
    }

    /**
     * One line: what it says, whether the text ended there rather than the room running out, and
     * what remains of its paragraph from where the line begins — which is what an ellipsis is cut
     * from, since the lines after it have already had their breaks put in.
     */
    private class Line(val text: String, val ended: Boolean, val rest: String)

    /**
     * The greedy break: take words while they fit, then start a line. A word too wide for a line
     * of its own is cut where it runs out of room rather than left to overflow.
     */
    private fun breakParagraph(
        paragraph: String,
        room: Float,
        widthOf: (String) -> Float,
        out: MutableList<Line>,
    ) {
        if (paragraph.isEmpty()) {
            out += Line("", true, "")
            return
        }
        var rest = paragraph
        var line = ""
        var consumed = 0
        while (rest.isNotEmpty() || line.isNotEmpty()) {
            val space = rest.indexOf(' ')
            val word = if (space < 0) rest else rest.take(space)
            val candidate = if (line.isEmpty()) word else "$line $word"
            when {
                rest.isEmpty() -> {
                    out += Line(line, true, paragraph.substring(consumed))
                    return
                }
                widthOf(candidate) <= room -> {
                    line = candidate
                    rest = if (space < 0) "" else rest.substring(space + 1)
                }
                line.isNotEmpty() -> {
                    out += Line(line, false, paragraph.substring(consumed))
                    consumed = paragraph.length - rest.length
                    line = ""
                }
                else -> {
                    // One word, wider than a line of its own: cut it where it runs out of room
                    // and leave the remainder at the front of what is still to break.
                    val cut = fitting(word, room, widthOf).coerceAtLeast(1)
                    out += Line(word.take(cut), false, paragraph.substring(consumed))
                    rest = rest.substring(cut)
                    consumed = paragraph.length - rest.length
                }
            }
        }
        out += Line(line, true, paragraph.substring(consumed))
    }

    /** How many characters of [text] fit in [room]. */
    private fun fitting(text: String, room: Float, widthOf: (String) -> Float): Int {
        var count = 0
        while (count < text.length && widthOf(text.take(count + 1)) <= room) count++
        return count
    }

    /** One line cut to [room] with an ellipsis where [overflow] asks for it. */
    private fun truncate(text: String, room: Float, overflow: Int, widthOf: (String) -> Float): String {
        if (widthOf(text) <= room) return text
        if (overflow != OVERFLOW_ELLIPSIS && overflow != OVERFLOW_START_ELLIPSIS && overflow != OVERFLOW_MIDDLE_ELLIPSIS) {
            return text.take(fitting(text, room, widthOf))
        }
        val space = room - widthOf(ELLIPSIS)
        if (space <= 0f) return ELLIPSIS
        return when (overflow) {
            OVERFLOW_START_ELLIPSIS -> {
                var keep = 0
                while (keep < text.length && widthOf(text.takeLast(keep + 1)) <= space) keep++
                ELLIPSIS + text.takeLast(keep)
            }
            OVERFLOW_MIDDLE_ELLIPSIS -> {
                var head = 0
                var tail = 0
                while (head + tail < text.length) {
                    val grown = if (head <= tail) head + 1 to tail else head to tail + 1
                    if (widthOf(text.take(grown.first) + text.takeLast(grown.second)) > space) break
                    head = grown.first
                    tail = grown.second
                }
                text.take(head) + ELLIPSIS + text.takeLast(tail)
            }
            else -> text.take(fitting(text, space, widthOf)) + ELLIPSIS
        }
    }
}
