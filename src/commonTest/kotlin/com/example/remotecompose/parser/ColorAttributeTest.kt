package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.RemoteContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ATTRIBUTE_COLOR` against `tools/rc-writer/coffee.rc`, where the bar under the heading is as
 * long as the accent colour is bright — a different length in each palette, from one document.
 */
class ColorAttributeTest {

    private val bytes by lazy { fixture("coffee") }

    /** The filled part of the brightness bar, which is 3 tall and the accent's colour. */
    private fun barWidth(theme: Int): Float {
        val document = RemoteComposeParser.load(bytes)
        document.paintTheme = theme
        val bars = document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>()
            .filter { it.bottom - it.top == 3f }
        return bars.last().right - bars.last().left
    }

    @Test
    fun theAttributeIsReadFromTheColourItNames() {
        val attribute = OperationReader.readAll(bytes).filterIsInstance<Operation.ColorAttribute>().single()
        assertEquals(2, attribute.type, "brightness")
        val themed = OperationReader.readAll(bytes).filterIsInstance<Operation.ColorTheme>()
        assertTrue(themed.any { it.id == attribute.colorId }, "and the colour it names is a themed one")
    }

    @Test
    fun brightnessIsTheLargestChannel() {
        // The accent is 0xFF8D5524 in light and 0xFFD9A066 in dark: 0x8D/255 and 0xD9/255.
        assertEquals(268f * 0x8D / 255f, barWidth(RemoteContext.THEME_LIGHT), 0.5f)
        assertEquals(268f * 0xD9 / 255f, barWidth(RemoteContext.THEME_DARK), 0.5f)
    }

    @Test
    fun everyComponentComesOutBetweenZeroAndOne() {
        val context = RemoteContext()
        context.colors[70] = androidx.compose.ui.graphics.Color(0xFF8D5524.toInt())
        val operations = (0..6).map { Operation.ColorAttribute(100 + it, 70, it) }
        RemoteComposeParser.build(operations, context, com.example.remotecompose.text.EstimatedTextMetrics)
        val values = (0..6).map { context.getFloat(100 + it) }
        assertTrue(values.all { it in 0f..1f }, "hue included: $values")
        assertEquals(0x8D / 255f, values[2], 0.001f, "brightness is the largest channel")
        assertEquals(0x8D / 255f, values[3], 0.001f, "and red is that channel here")
        assertEquals(1f, values[6], "opaque")
        // 0x8D5524 is an orange-brown: a hue in the first sixth of the wheel.
        assertTrue(values[0] > 0f && values[0] < 1f / 6f, "hue ${values[0]}")
    }
}
