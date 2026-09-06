package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.FontFamilyKind
import com.example.remotecompose.model.GradientSpec
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.StrokeCapKind
import com.example.remotecompose.model.StrokeJoinKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * End-to-end check of paint decoding against `tools/rc-writer/paint.rc`, which the official
 * writer produced from `buildPaintSample()` in `tools/rc-writer/src/main/kotlin/Main.kt`. Each
 * assertion corresponds to one numbered step in that function.
 */
class PaintFixtureTest {

    private val document by lazy {
        RealRemoteComposeParser.parse(File("tools/rc-writer/paint.rc").readBytes())
    }

    private inline fun <reified T : Opcode> op(index: Int): T = assertIs<T>(document.opcodes[index])

    private fun paintOf(index: Int): PaintStyle = when (val op = document.opcodes[index]) {
        is Opcode.DrawRect -> op.paint
        is Opcode.DrawCircle -> op.paint
        is Opcode.DrawLine -> op.paint
        is Opcode.DrawText -> op.paint
        else -> error("opcode $index carries no paint: $op")
    }

    @Test
    fun fixtureHasExpectedDrawSequence() {
        assertEquals(
            listOf(
                "DrawRect", "DrawCircle", "DrawRect", "DrawLine", "DrawText", "DrawText",
                "DrawRect", "DrawCircle", "DrawRect", "DrawRect", "DrawRect", "DrawRect", "DrawCircle",
            ),
            document.opcodes.map { it::class.simpleName },
        )
    }

    @Test
    fun strokeAttributesDecode() {
        val paint = paintOf(0)
        assertEquals(Color(0xFFE53935), paint.color)
        assertEquals(PaintStyleKind.STROKE, paint.style)
        assertEquals(6f, paint.strokeWidth)
        assertEquals(StrokeCapKind.ROUND, paint.strokeCap)
        assertEquals(StrokeJoinKind.ROUND, paint.strokeJoin)
    }

    @Test
    fun styleOnlyCommitKeepsPreviousColorAndStroke() {
        val paint = paintOf(1)
        assertEquals(Color(0xFFE53935), paint.color)
        assertEquals(PaintStyleKind.FILL, paint.style)
        assertEquals(6f, paint.strokeWidth)
        assertEquals(StrokeCapKind.ROUND, paint.strokeCap)
    }

    @Test
    fun alphaAppliesToColorAndFreshColorResetsIt() {
        assertEquals(0.5f, paintOf(2).color.alpha, 0.01f)
        assertEquals(1f, paintOf(3).color.alpha)
    }

    @Test
    fun lineCarriesWidthAndCap() {
        val paint = paintOf(3)
        assertEquals(4f, paint.strokeWidth)
        assertEquals(StrokeCapKind.SQUARE, paint.strokeCap)
    }

    @Test
    fun textSizeAndTypefaceDecode() {
        assertEquals(24f, paintOf(4).textSize)
        val mono = paintOf(5)
        assertEquals(10f, mono.textSize)
        assertEquals(700, mono.fontWeight)
        assertEquals(true, mono.fontItalic)
        assertEquals(FontFamilyKind.MONOSPACE, mono.fontFamily)
    }

    @Test
    fun gradientsDecode() {
        val linear = assertIs<GradientSpec.Linear>(paintOf(6).gradient)
        assertEquals(listOf(Color.Red, Color.Blue), linear.colors)
        assertEquals(listOf(0f, 1f), linear.stops)
        assertEquals(listOf(12f, 110f, 92f, 110f), listOf(linear.startX, linear.startY, linear.endX, linear.endY))

        val radial = assertIs<GradientSpec.Radial>(paintOf(7).gradient)
        assertNull(radial.stops)
        assertEquals(listOf(140f, 115f, 25f), listOf(radial.centerX, radial.centerY, radial.radius))

        val sweep = assertIs<GradientSpec.Sweep>(paintOf(8).gradient)
        assertEquals(4, sweep.colors.size)
        assertEquals(listOf(52f, 165f), listOf(sweep.centerX, sweep.centerY))
    }

    @Test
    fun shaderClearAndColorIdDecode() {
        val paint = paintOf(9)
        assertNull(paint.gradient)
        assertEquals(Color(0xFFD500F9), paint.color)
    }

    @Test
    fun fillAndStrokeWithBevelDecodes() {
        val paint = paintOf(10)
        assertEquals(PaintStyleKind.FILL_AND_STROKE, paint.style)
        assertEquals(StrokeJoinKind.BEVEL, paint.strokeJoin)
    }

    @Test
    fun paintSetInsideComponentIsRestoredAfterIt() {
        assertEquals(Color(0xFF43A047), paintOf(11).color)
        val after = paintOf(12)
        assertEquals(Color(0xFFD500F9), after.color)
        assertEquals(PaintStyleKind.FILL_AND_STROKE, after.style)
    }
}
