package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/article.rc`: an article that scrolls, with a bar across the top showing how far
 * through it the reader is.
 *
 * The point of the fixture is that the bar is the document's own arithmetic. `MODIFIER_SCROLL`
 * drives a float and the fill is that float over the distance there is to scroll, so nothing but
 * the touch itself comes from the host.
 */
class ArticleProgressTest {

    private val bytes = fixture("article")

    /** The window the article is read through, and how far past it the article runs. */
    private val window = 360f
    private val overflow = 154f

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    /** The bar's fill: the second rectangle of the four-tall track at the top. */
    private fun fillWidth(document: RemoteComposeDocument): Float {
        val bars = document.frame(0L).opcodes.filterIsInstance<Opcode.DrawRect>()
            .filter { it.bottom - it.top == 4f }
        assertEquals(2, bars.size, "a track and a fill")
        return bars[1].right - bars[1].left
    }

    /** Drags the article up by [by] document units, which scrolls it down by the same. */
    private fun scrolled(by: Float): RemoteComposeDocument {
        val document = loaded()
        document.touchDown(150f, 300f)
        document.frame(0L)
        document.touchDrag(150f, 300f - by)
        document.frame(0L)
        document.touchUp(150f, 300f - by)
        return document
    }

    @Test
    fun theBarIsEmptyBeforeAnythingIsRead() {
        assertEquals(0f, fillWidth(loaded()), 0.01f)
    }

    @Test
    fun theBarFollowsHowFarThroughTheReaderIs() {
        // Half the overflow scrolled is half the width filled.
        assertEquals(150f, fillWidth(scrolled(overflow / 2f)), 1f)
        assertEquals(75f, fillWidth(scrolled(overflow / 4f)), 1f)
    }

    @Test
    fun theBarIsFullAtTheEndAndNoFurther() {
        // `min` holds it at the full width however far past the end the drag went.
        assertEquals(300f, fillWidth(scrolled(overflow)), 1f)
        assertEquals(300f, fillWidth(scrolled(overflow * 3f)), 1f)
    }

    @Test
    fun theArticleItselfMovesUnderTheWindow() {
        val document = scrolled(overflow / 2f)
        val opcodes = document.frame(0L).opcodes
        val clip = opcodes.indexOfLast { it is Opcode.ClipRect && it.bottom == window }
        assertTrue(clip >= 0, "the article is read through a window")
        val offset = -(opcodes[clip + 1] as Opcode.Translate).dy
        assertEquals(overflow / 2f, offset, 1f, "and it has moved by what was dragged")
    }

    @Test
    fun theEndOfTheArticleIsReachable() {
        // Every line is laid out whether or not it is in view — the window clips at paint rather
        // than dropping what falls outside it — so what says the end has been reached is the
        // article having moved by the whole of its overflow, which is what fills the bar.
        val document = scrolled(overflow * 3f)
        val opcodes = document.frame(0L).opcodes
        val clip = opcodes.indexOfLast { it is Opcode.ClipRect && it.bottom == window }
        val offset = -(opcodes[clip + 1] as Opcode.Translate).dy
        assertEquals(overflow, offset, 0.5f, "it stops with the last line against the bottom")

        val ending = "document that there is no more."
        val frame = document.frame(0L)
        assertTrue(
            frame.opcodes.filterIsInstance<Opcode.DrawText>()
                .any { frame.strings[it.stringIndex] == ending },
            "and the article does end where the bar says it does",
        )
    }

    @Test
    fun theBarIsDrivenByTheSameFloatTheScrollWrites() {
        // Not two things kept in step: the fill reads the float the scroll modifier drives.
        val operations = OperationReader.readAll(bytes)
        val scroll = operations.filterIsInstance<Operation.ModifierScroll>().single()
        val positionId = com.example.remotecompose.runtime.FloatExpressionEvaluator
            .idOf(scroll.positionExpression)
        val expressions = operations.filterIsInstance<Operation.FloatExpression>()
        assertTrue(
            expressions.any { expression ->
                expression.expression.any {
                    com.example.remotecompose.runtime.FloatExpressionEvaluator.isVariable(it) &&
                        com.example.remotecompose.runtime.FloatExpressionEvaluator.idOf(it) == positionId
                }
            },
            "the bar's width is an expression over the scroll position",
        )
    }
}
