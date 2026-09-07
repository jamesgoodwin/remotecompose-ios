package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `tools/rc-writer/flight.rc`: a panel whose every value the host fills in by name.
 *
 * The point of the fixture is that nothing on it is drawn from a literal — the route, the gate,
 * the countdown, the status colour and whether the delay line exists at all are `NAMED_VARIABLE`s.
 * A host that knows nothing about the layout keeps it current through their names, and the
 * document does the easing, the arithmetic and the choosing itself.
 */
class FlightPanelTest {

    private val bytes = fixture("flight")

    private fun loaded(): RemoteComposeDocument =
        RemoteComposeParser.load(bytes).also { it.frame(0L) }

    private fun texts(document: RemoteComposeDocument, at: Long = 0L): List<String> {
        val frame = document.frame(at)
        return frame.opcodes.filterIsInstance<Opcode.DrawText>().mapNotNull { frame.strings[it.stringIndex] }
    }

    /** The boarding bar's fill: the second of the two six-tall rectangles. */
    private fun fillWidth(document: RemoteComposeDocument, at: Long = 0L): Float {
        val bars = document.frame(at).opcodes.filterIsInstance<Opcode.DrawRect>()
            .filter { it.bottom - it.top == 6f }
        return bars[1].right - bars[1].left
    }

    /** A feed, as a host would send one. */
    private fun fed(): RemoteComposeDocument {
        val document = loaded()
        document.setNamedString("flight", "BA 2490")
        document.setNamedString("route", "Bristol to Palma")
        document.setNamedString("gate", "B12")
        document.setNamedString("status", "Boarding")
        document.setNamedColor("statusColor", 0xFF2E7D32.toInt())
        document.setNamedFloat("minutes", 23f)
        document.setNamedFloat("boarded", 0.5f)
        document.frame(0L)
        return document
    }

    @Test
    fun theDocumentDeclaresEverythingAHostMaySet() {
        assertEquals(
            listOf("boarded", "delayNote", "delayed", "flight", "gate", "minutes", "route", "status", "statusColor"),
            loaded().namedValues.keys.sorted(),
        )
    }

    @Test
    fun itSaysSoWhenNobodyHasToldItAnything() {
        // A panel with no feed is not blank: it shows what it was written with.
        assertTrue("Waiting for feed" in texts(loaded()))
    }

    @Test
    fun aFeedReachesEveryPartOfIt() {
        val shown = texts(fed(), 2000L)
        assertTrue("BA 2490" in shown)
        assertTrue("Bristol to Palma" in shown)
        assertTrue("B12" in shown)
        assertTrue("Boarding" in shown)
        assertFalse("Waiting for feed" in shown)
    }

    @Test
    fun theCountdownIsTravelledToRatherThanJumpedTo() {
        // The number is read through an `ANIMATED_FLOAT`, so a value the host pushes is eased
        // over rather than appearing. Part way there it reads neither end.
        val document = fed()
        val early = texts(document, 100L).single { it.endsWith("min to boarding") }
        val settled = texts(document, 1500L).single { it.endsWith("min to boarding") }
        assertEquals("23 min to boarding", settled)
        assertTrue(early != settled, "it was still on its way at 100ms: $early")
    }

    @Test
    fun theBoardingBarFollowsTheNumberTheHostSent() {
        val document = fed()
        // Half boarded is half the track, once the easing has settled.
        assertEquals(134f, fillWidth(document, 2000L), 2f)
    }

    @Test
    fun theDelayLineExistsOnlyWhenThereIsADelay() {
        // A `CONDITIONAL_OPERATIONS` block the host opens: not hidden, not laid out at all.
        val document = fed()
        val note = "Delayed 25 min"
        assertFalse(texts(document, 2000L).any { it.startsWith(note) })

        document.setNamedFloat("delayed", 1f)
        document.setNamedString("delayNote", "$note · new gate B31")
        document.frame(2000L)
        assertTrue(texts(document, 2000L).any { it.startsWith(note) }, "the line is there once it is told")
    }

    @Test
    fun theStatusColourReachesTheThingsThatFollowIt() {
        // One named colour drives the dot, the status text and the boarding bar, so a host
        // setting it once changes all three.
        val document = fed()
        val frame = document.frame(2000L)
        val dot = frame.opcodes.filterIsInstance<Opcode.DrawCircle>().single()
        val bar = frame.opcodes.filterIsInstance<Opcode.DrawRect>().first { it.bottom - it.top == 6f && it.right - it.left < 268f }
        assertEquals(0.49f, dot.paint.color.green, 0.02f, "the dot took the green")
        assertEquals(dot.paint.color, bar.paint.color, "and so did the bar")
    }

    @Test
    fun theButtonAsksTheHostByNameAndSaysWhichFlight() {
        val heard = mutableListOf<Pair<String, Any?>>()
        val document = fed()
        document.onNamedAction = { name, value -> heard += name to value }
        // The button is the last thing in the column, which flows from the top rather than
        // filling to the bottom.
        document.click(150f, 265f)
        assertEquals(1, heard.size, "the action reached the host")
        assertEquals("notify", heard[0].first)
        assertEquals("BA 2490", heard[0].second, "carrying the flight it is about")
    }
}
