package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.FontFamilyKind
import com.example.remotecompose.model.GradientSpec
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.StrokeCapKind
import com.example.remotecompose.model.StrokeJoinKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Word arrays below are laid out exactly as `PaintBundle.set*` writes them (see the table in
 * [PaintBundleDecoder]'s KDoc), so these tests pin the decoder to the real wire format without
 * needing the writer on the classpath.
 */
class PaintBundleDecoderTest {

    private fun w(id: Int, hi: Int = 0): Int = id or (hi shl 16)
    private fun f(value: Float): Int = value.toRawBits()

    private fun apply(
        words: IntArray,
        state: PaintState = PaintState(),
        resolveFloat: (Float) -> Float = { it },
        colorById: (Int) -> Color? = { null },
        textById: (Int) -> String? = { null },
    ): PaintState {
        PaintBundleDecoder.apply(words, state, resolveFloat, colorById, textById)
        return state
    }

    @Test
    fun decodesColorStyleStrokeAttributes() {
        val state = apply(
            intArrayOf(
                w(PaintBundleDecoder.COLOR), 0xFFE53935.toInt(),
                w(PaintBundleDecoder.STYLE, hi = 1),
                w(PaintBundleDecoder.STROKE_WIDTH), f(6f),
                w(PaintBundleDecoder.STROKE_CAP, hi = 1),
                w(PaintBundleDecoder.STROKE_JOIN, hi = 2),
                w(PaintBundleDecoder.STROKE_MITER), f(2.5f),
                w(PaintBundleDecoder.TEXT_SIZE), f(24f),
            ),
        )
        assertEquals(Color(0xFFE53935), state.color)
        assertEquals(PaintStyleKind.STROKE, state.style)
        assertEquals(6f, state.strokeWidth)
        assertEquals(StrokeCapKind.ROUND, state.strokeCap)
        assertEquals(StrokeJoinKind.BEVEL, state.strokeJoin)
        assertEquals(2.5f, state.strokeMiter)
        assertEquals(24f, state.textSize)
    }

    @Test
    fun bundlesAreCumulativeDeltas() {
        val state = apply(intArrayOf(w(PaintBundleDecoder.COLOR), 0xFF112233.toInt(), w(PaintBundleDecoder.STYLE, hi = 1)))
        apply(intArrayOf(w(PaintBundleDecoder.STYLE, hi = 0)), state)
        assertEquals(Color(0xFF112233), state.color)
        assertEquals(PaintStyleKind.FILL, state.style)
    }

    @Test
    fun alphaReplacesColorAlphaAndColorResetsIt() {
        val state = apply(intArrayOf(w(PaintBundleDecoder.COLOR), 0xFF0000FF.toInt(), w(PaintBundleDecoder.ALPHA), f(0.5f)))
        // Compose's Color stores 8-bit sRGB channels, so 0.5 round-trips as 128/255.
        assertEquals(0.5f, state.color.alpha, 0.01f)
        assertEquals(1f, state.color.blue)
        apply(intArrayOf(w(PaintBundleDecoder.COLOR), 0xFF00FF00.toInt()), state)
        assertEquals(1f, state.color.alpha)
    }

    @Test
    fun decodesLinearGradientWithStopsAndTileMode() {
        val state = apply(
            intArrayOf(
                w(PaintBundleDecoder.GRADIENT, hi = PaintBundleDecoder.LINEAR_GRADIENT),
                2, 0xFFFF0000.toInt(), 0xFF0000FF.toInt(),
                2, f(0f), f(1f),
                f(10f), f(20f), f(30f), f(40f),
                2,
            ),
        )
        val gradient = state.gradient as GradientSpec.Linear
        assertEquals(listOf(Color.Red, Color.Blue), gradient.colors)
        assertEquals(listOf(0f, 1f), gradient.stops)
        assertEquals(listOf(10f, 20f, 30f, 40f), listOf(gradient.startX, gradient.startY, gradient.endX, gradient.endY))
        assertEquals(2, gradient.tileMode)
    }

    @Test
    fun decodesRadialAndSweepGradientsWithoutStops() {
        val radial = apply(
            intArrayOf(
                w(PaintBundleDecoder.GRADIENT, hi = PaintBundleDecoder.RADIAL_GRADIENT),
                2, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0,
                f(50f), f(60f), f(25f),
                0,
            ),
        ).gradient as GradientSpec.Radial
        assertNull(radial.stops)
        assertEquals(listOf(50f, 60f, 25f), listOf(radial.centerX, radial.centerY, radial.radius))

        val sweep = apply(
            intArrayOf(
                w(PaintBundleDecoder.GRADIENT, hi = PaintBundleDecoder.SWEEP_GRADIENT),
                3, 1, 2, 3,
                0,
                f(5f), f(6f),
                // The next attribute must still be read after the sweep's two floats.
                w(PaintBundleDecoder.COLOR), 0xFF123456.toInt(),
            ),
        )
        val spec = sweep.gradient as GradientSpec.Sweep
        assertEquals(3, spec.colors.size)
        assertEquals(listOf(5f, 6f), listOf(spec.centerX, spec.centerY))
        assertEquals(Color(0xFF123456), sweep.color)
    }

    @Test
    fun shaderZeroClearsGradient() {
        val state = apply(
            intArrayOf(
                w(PaintBundleDecoder.GRADIENT, hi = PaintBundleDecoder.SWEEP_GRADIENT), 2, 1, 2, 0, f(0f), f(0f),
                w(PaintBundleDecoder.SHADER), 0,
            ),
        )
        assertNull(state.gradient)
    }

    @Test
    fun decodesTypefaceWeightItalicAndFamily() {
        val state = apply(intArrayOf(w(PaintBundleDecoder.TYPEFACE, hi = 700 or 2048), 3))
        assertEquals(700, state.fontWeight)
        assertTrue(state.fontItalic)
        assertEquals(FontFamilyKind.MONOSPACE, state.fontFamily)

        val named = apply(
            intArrayOf(w(PaintBundleDecoder.TYPEFACE, hi = 0), 42),
            textById = { if (it == 42) "serif" else null },
        )
        assertEquals(400, named.fontWeight)
        assertEquals(FontFamilyKind.SERIF, named.fontFamily)
    }

    @Test
    fun colorIdResolvesThroughColorPool() {
        val state = apply(
            intArrayOf(w(PaintBundleDecoder.COLOR_ID), 7),
            colorById = { if (it == 7) Color.Magenta else null },
        )
        assertEquals(Color.Magenta, state.color)
    }

    @Test
    fun nanTaggedFloatsResolveThroughVariablePool() {
        val tagged = Float.fromBits(0x7FC00000 or 5)
        val state = apply(
            intArrayOf(w(PaintBundleDecoder.STROKE_WIDTH), f(tagged)),
            resolveFloat = { if (it.isNaN()) 9f else it },
        )
        assertEquals(9f, state.strokeWidth)
    }

    @Test
    fun payloadlessAndSkippedAttributesKeepAlignment() {
        val state = apply(
            intArrayOf(
                w(PaintBundleDecoder.ANTI_ALIAS, hi = 1),
                w(PaintBundleDecoder.BLEND_MODE, hi = 14),
                w(PaintBundleDecoder.PATH_EFFECT, hi = 2), f(4f), f(2f),
                w(PaintBundleDecoder.TEXTURE), 99, 0, 0,
                w(PaintBundleDecoder.FONT_AXIS, hi = 1), 11, f(0.5f),
                w(PaintBundleDecoder.COLOR_FILTER, hi = 3), 0xFF000000.toInt(),
                w(PaintBundleDecoder.CLEAR_COLOR_FILTER),
                w(PaintBundleDecoder.SHADER_MATRIX), f(1f),
                w(PaintBundleDecoder.FALLBACK_TYPEFACE, hi = 400), 1,
                w(PaintBundleDecoder.COLOR), 0xFF654321.toInt(),
            ),
        )
        assertEquals(14, state.blendMode)
        assertEquals(Color(0xFF654321), state.color)
    }

    @Test
    fun blendModeNullClearsBlendMode() {
        val state = apply(intArrayOf(w(PaintBundleDecoder.BLEND_MODE, hi = 14)))
        apply(intArrayOf(w(PaintBundleDecoder.BLEND_MODE, hi = PaintBundleDecoder.BLEND_MODE_NULL)), state)
        assertNull(state.blendMode)
    }
}
