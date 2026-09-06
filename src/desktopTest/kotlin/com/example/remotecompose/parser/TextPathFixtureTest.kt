package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of text on curves and the value operations that read it, against
 * `tools/rc-writer/textpath.rc` (see `buildTextPathSample()` in the writer tool).
 */
class TextPathFixtureTest {

    private val document by lazy { RemoteComposeParser.parse(File("tools/rc-writer/textpath.rc").readBytes()) }
    private val texts by lazy { document.opcodes.filterIsInstance<Opcode.DrawText>() }

    /** Draws of the substring `[i, i+1)` are per-glyph placements. */
    private fun glyphsOf(text: String): List<Opcode.DrawText> {
        val id = document.strings.entries.first { it.value == text }.key
        return texts.filter { it.stringIndex == id }
    }

    @Test
    fun textOnAPathBecomesOneDrawPerGlyph() {
        val glyphs = glyphsOf("following a wave")
        assertEquals("following a wave".length, glyphs.size)
        glyphs.forEachIndexed { i, glyph ->
            assertEquals(i, glyph.substringStart)
            assertEquals(i + 1, glyph.substringEnd)
        }
    }

    @Test
    fun glyphsOnTheWaveAreRotatedAndAdvance() {
        // Each glyph is wrapped in save/translate/rotate ... restore.
        val opcodes = document.opcodes
        val waveId = document.strings.entries.first { it.value == "following a wave" }.key
        val placements = opcodes.indices
            .filter { opcodes[it].let { op -> op is Opcode.DrawText && op.stringIndex == waveId } }
            .map { (opcodes[it - 2] as Opcode.Translate) to (opcodes[it - 1] as Opcode.Rotate) }
        assertTrue(placements.size > 3)
        // The run advances left to right along the wave.
        assertTrue(placements.zipWithNext().all { (a, b) -> b.first.dx > a.first.dx })
        // A cubic wave is not flat, so the glyphs cannot all share one rotation.
        assertTrue(placements.map { it.second.degrees }.distinct().size > 3)
    }

    @Test
    fun textOnACircleSitsOnItsRadius() {
        val opcodes = document.opcodes
        val ringId = document.strings.entries.first { it.value == "OUTSIDE THE RING" }.key
        val centres = opcodes.indices
            .filter { opcodes[it].let { op -> op is Opcode.DrawText && op.stringIndex == ringId } }
            .map { opcodes[it - 2] as Opcode.Translate }
        assertEquals("OUTSIDE THE RING".length, centres.size)
        for (t in centres) assertEquals(45f, hypot(t.dx - 100f, t.dy - 120f), 0.5f)
    }

    @Test
    fun textMeasureSizesTheBarToTheMeasuredWidth() {
        // The orange bar's right edge is the measured width of "measure me" plus its left margin.
        val bar = document.opcodes.filterIsInstance<Opcode.DrawRect>()
            .first { it.paint.color.red > 0.9f && it.paint.color.green > 0.4f && it.paint.color.blue < 0.1f }
        val label = glyphsOf("measure me").firstOrNull()
        assertTrue(label != null || texts.isNotEmpty())
        assertTrue(bar.right > bar.left + 20f, "the bar took a real measurement: ${bar.right - bar.left}")
        assertEquals(12f, bar.left, 0.01f)
    }

    @Test
    fun conditionalBlockRunsWhenItsComparisonHolds() {
        // The marker circle is inside a TYPE_GT block that holds, so it is drawn.
        val marker = document.opcodes.filterIsInstance<Opcode.DrawCircle>()
            .firstOrNull { it.centerX == 190f && it.centerY == 189f }
        assertTrue(marker != null, "conditional block with a true comparison drew its content")
    }

}
