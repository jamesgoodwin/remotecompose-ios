package com.example.remotecompose.parser

import com.example.remotecompose.model.Opcode
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end check of time-driven evaluation against `tools/rc-writer/anim.rc` (see
 * `buildAnimSample()` in the writer tool). Each assertion corresponds to a numbered step there.
 */
class AnimFixtureTest {

    private fun load() = RemoteComposeParser.load(File("tools/rc-writer/anim.rc").readBytes())

    @Test
    fun readerDecodesFloatExpressions() {
        val expressions = load().operations.filterIsInstance<Operation.FloatExpression>()
        assertEquals(4, expressions.size)
        assertTrue(expressions.any { it.animation != null }, "step 3 carries an animation description")
    }

    @Test
    fun circleFollowsTime() {
        val doc = load()
        val at0 = assertIs<Opcode.DrawCircle>(doc.frame(0L).opcodes[0])
        assertEquals(100f, at0.centerX, 0.001f)
        val at1 = assertIs<Opcode.DrawCircle>(doc.frame(1_000L).opcodes[0])
        assertEquals(100f + 60f * sin(2f), at1.centerX, 0.01f)
        assertTrue(doc.needsRepaint)
    }

    @Test
    fun staticExpressionIsConstant() {
        val doc = load()
        val circle = assertIs<Opcode.DrawCircle>(doc.frame(0L).opcodes[1])
        assertEquals(20f, circle.radius, 0.001f)
    }

    @Test
    fun animatedRectEasesBetweenSteps() {
        val doc = load()
        doc.frame(0L)
        val settled = assertIs<Opcode.DrawRect>(doc.frame(900L).opcodes[2])
        assertEquals(0f, settled.left, 0.01f)
        // The frame at t = 1.0 sees the target flip to 100 and starts the half-second slide
        // (FloatExpression.updateVariables stamps the change with the frame's animation time);
        // a quarter second later the rect is mid-slide.
        val starting = assertIs<Opcode.DrawRect>(doc.frame(1_000L).opcodes[2])
        assertEquals(0f, starting.left, 0.01f)
        val moving = assertIs<Opcode.DrawRect>(doc.frame(1_250L).opcodes[2])
        assertTrue(moving.left > 5f && moving.left < 95f, "left=${moving.left}")
        assertEquals(moving.left + 40f, moving.right, 0.001f)
        val arrived = assertIs<Opcode.DrawRect>(doc.frame(1_600L).opcodes[2])
        assertEquals(100f, arrived.left, 0.01f)
    }

    @Test
    fun clockTextTracksElapsedSeconds() {
        val doc = load()
        doc.frame(0L)
        val frame = doc.frame(2_500L)
        val text = assertIs<Opcode.DrawText>(frame.opcodes[3])
        assertEquals("2.5", frame.strings[text.stringIndex])
    }
}
