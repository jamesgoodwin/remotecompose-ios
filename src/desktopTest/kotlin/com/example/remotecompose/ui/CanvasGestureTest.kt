package com.example.remotecompose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.parser.Operation
import com.example.remotecompose.parser.OperationReader
import com.example.remotecompose.parser.RemoteComposeDocument
import com.example.remotecompose.parser.RemoteComposeParser
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gestures through the canvas's own handler rather than by calling the document directly. The
 * parser tests scroll `coffee.rc` by calling it, so they stayed green while a press on a device
 * never reached it at all: this is what covers the wiring between the two.
 */
class CanvasGestureTest {

    private val bytes = File("tools/rc-writer/coffee.rc").readBytes()

    /** The float the scroll offset is read from, which a drag is supposed to move. */
    private fun scrollValueId(): Int =
        FloatExpressionEvaluator.idOf(
            OperationReader.readAll(bytes).filterIsInstance<Operation.ModifierScroll>().single()
                .positionExpression,
        )

    /**
     * The document at its own size, one document unit to the dp, so that a touch in the test maps
     * to the same coordinates the parser tests use.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.showDocument(): RemoteComposeDocument {
        val loaded = RemoteComposeParser.load(bytes)
        setContent {
            val scale = LocalDensity.current.density
            Box(
                modifier = Modifier
                    .size(loaded.header.width.dp, loaded.header.height.dp)
                    .documentGestures(loaded, scale) { FitTransform(scale, 0f, 0f) },
            )
        }
        return loaded
    }

    /** Document-space point to a touch position in the test's pixels. */
    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.at(x: Float, y: Float): Offset =
        Offset(x * density.density, y * density.density)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDragScrollsTheList() = runComposeUiTest {
        val loaded = showDocument()
        loaded.frame(0L)
        val id = scrollValueId()
        assertEquals(0f, loaded.context.floats[id] ?: 0f, 0.01f, "nothing has moved yet")
        // A frame between the steps, as the canvas's own frame loop supplies on a device: a
        // touch expression only follows the pointer on the frames the document is evaluated.
        onRoot().performTouchInput { down(at(150f, 300f)) }
        loaded.frame(0L)
        onRoot().performTouchInput { moveTo(at(150f, 260f)) }
        loaded.frame(0L)
        onRoot().performTouchInput { moveTo(at(150f, 200f)) }
        loaded.frame(0L)
        onRoot().performTouchInput { up() }
        assertTrue((loaded.context.floats[id] ?: 0f) > 0f, "the list moved: ${loaded.context.floats[id]}")
    }

    /** One card per menu row; the footer is bordered too but is not one of them. */
    private fun cardHeights(loaded: RemoteComposeDocument): List<Float> =
        loaded.frame(2000L).opcodes.filterIsInstance<Opcode.DrawRoundRect>()
            .filter { it.paint.strokeWidth == 1f }
            .map { it.bottom - it.top }
            .filter { it >= 79f }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aPressThatDoesNotMoveIsStillAClick() = runComposeUiTest {
        val loaded = showDocument()
        loaded.frame(0L)
        assertTrue(cardHeights(loaded).all { it == 79f }, "every card starts the same size")
        onRoot().performTouchInput {
            down(at(150f, 220f))
            up()
        }
        waitForIdle()
        loaded.frame(0L)
        assertTrue(cardHeights(loaded).any { it > 79f }, "a tap expanded one: ${cardHeights(loaded)}")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aDragDoesNotAlsoClickWhatItStartedOn() = runComposeUiTest {
        // Scrolling a list past a row must not expand the row the finger came down on.
        val loaded = showDocument()
        loaded.frame(0L)
        onRoot().performTouchInput {
            down(at(150f, 220f))
            moveTo(at(150f, 180f))
            moveTo(at(150f, 120f))
            up()
        }
        waitForIdle()
        loaded.frame(0L)
        assertTrue(cardHeights(loaded).all { it == 79f }, "nothing expanded: ${cardHeights(loaded)}")
    }
}
