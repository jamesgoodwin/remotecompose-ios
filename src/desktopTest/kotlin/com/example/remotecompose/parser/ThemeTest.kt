package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.text.EstimatedTextMetrics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Light and dark, both of the ways a document carries them: `COLOR_THEME` colours that hold a
 * value for each mode, and `THEME` brackets that mark which operations belong to which.
 */
class ThemeTest {

    private val bytes by lazy { File("tools/rc-writer/coffee.rc").readBytes() }

    private fun load(theme: Int): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also {
            it.paintTheme = theme
            it.frame(0L)
        }

    /** The colour of the page's own background, which is the first thing the root paints. */
    private fun background(document: RemoteComposeDocument): Color =
        document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>().first().paint.color

    @Test
    fun aThemedColourCarriesBothValues() {
        val themed = OperationReader.readAll(bytes).filterIsInstance<Operation.ColorTheme>()
        assertTrue(themed.size >= 6, "one per colour on the screen")
        assertTrue(themed.all { it.lightMode != it.darkMode })
    }

    @Test
    fun theModeDecidesWhichValueIsUsed() {
        val light = background(load(RemoteContext.THEME_LIGHT))
        val dark = background(load(RemoteContext.THEME_DARK))
        assertTrue(light.red > 0.9f && light.green > 0.9f, "a light surface: $light")
        assertTrue(dark.red < 0.2f && dark.green < 0.2f, "a dark one: $dark")
    }

    @Test
    fun textFollowsAThemedColourToo() {
        fun headingColour(theme: Int): Color =
            load(theme).frame(0L).opcodes.filterIsInstance<Opcode.DrawText>().first().paint.color
        val light = headingColour(RemoteContext.THEME_LIGHT)
        val dark = headingColour(RemoteContext.THEME_DARK)
        assertTrue(light.red < 0.3f, "dark ink on a light page: $light")
        assertTrue(dark.red > 0.8f, "light ink on a dark page: $dark")
    }

    @Test
    fun changingTheModeChangesTheNextFrame() {
        val document = RemoteComposeParser.load(bytes)
        document.frame(0L)
        val before = background(document)
        document.paintTheme = RemoteContext.THEME_DARK
        assertTrue(document.needsRepaint, "the host is asked to draw again")
        val after = background(document)
        assertTrue(before != after, "$before became $after")
    }

    @Test
    fun operationsBracketedByAModeOnlyRunInThatMode() {
        // What `addThemedColor` writes: a value per mode, one of which is skipped.
        val operations = listOf(
            Operation.Theme(RemoteContext.THEME_LIGHT),
            Operation.TextData(50, "day"),
            Operation.Theme(RemoteContext.THEME_UNSPECIFIED),
            Operation.Theme(RemoteContext.THEME_DARK),
            Operation.TextData(50, "night"),
            Operation.Theme(RemoteContext.THEME_UNSPECIFIED),
        )
        for ((theme, expected) in listOf(RemoteContext.THEME_LIGHT to "day", RemoteContext.THEME_DARK to "night")) {
            val context = RemoteContext()
            context.paintTheme = theme
            RemoteComposeParser.build(operations, context, EstimatedTextMetrics)
            assertEquals(expected, context.texts[50])
        }
    }

    @Test
    fun aBracketedComponentIsSkippedWholeRatherThanLeavingItsEndBehind() {
        // Skipping only the opener would leave the ContainerEnd to close something else.
        val operations = listOf(
            Operation.LayoutRoot(-1),
            Operation.LayoutContent(-2),
            Operation.Theme(RemoteContext.THEME_DARK),
            Operation.LayoutBox(-3, -1, 1, 1),
            Operation.LayoutContent(-4),
            Operation.TextData(60, "night only"),
            Operation.LayoutText(-5, -1, 60, 0xFF000000.toInt(), 12f, 0, 400f, 0, 1, 1, 1),
            Operation.ContainerEnd,
            Operation.ContainerEnd,
            Operation.ContainerEnd,
            Operation.Theme(RemoteContext.THEME_UNSPECIFIED),
            Operation.ContainerEnd,
            Operation.ContainerEnd,
        )
        val context = RemoteContext()
        context.paintTheme = RemoteContext.THEME_LIGHT
        val opcodes = RemoteComposeParser.build(operations, context, EstimatedTextMetrics)
        assertTrue(opcodes.filterIsInstance<Opcode.DrawText>().isEmpty(), "the dark box did not draw")
    }
}
