package com.example.remotecompose.runtime

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.parser.OperationReader
import com.example.remotecompose.parser.Operation
import com.example.remotecompose.parser.RemoteComposeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `BounceCurve` and `ElasticOutCurve`, the two `FloatAnimation` builds for easing types 13 and
 * 14, against `tools/rc-writer/easing.rc`: one target eased three ways.
 *
 * Both were falling back to the standard cubic, which is a curve that only ever climbs — so the
 * thing to check is that these two do not.
 */
class EasingCurveTest {

    private val bytes = fixture("easing")

    @Test
    fun theFixtureAsksForEachCurve() {
        // The standard cubic packs to nothing, since it is what an animation does by default, so
        // only the two that need naming are on the wire.
        val types = OperationReader.readAll(bytes).filterIsInstance<Operation.FloatExpression>()
            .mapNotNull { it.animation }
            .map { FloatAnimation(it).type }
        assertEquals(listOf(CubicEasing.EASE_OUT_BOUNCE, CubicEasing.EASE_OUT_ELASTIC), types)
    }

    @Test
    fun eachTypeBuildsItsOwnCurve() {
        assertTrue(CubicEasing.preset(CubicEasing.EASE_OUT_BOUNCE) is BounceCurve)
        assertTrue(CubicEasing.preset(CubicEasing.EASE_OUT_ELASTIC) is ElasticOutCurve)
        assertTrue(CubicEasing.preset(CubicEasing.CUBIC_STANDARD) is CubicEasing)
    }

    @Test
    fun everyCurveStartsAtNothingAndEndsAtOne() {
        for (curve in listOf(BounceCurve(), ElasticOutCurve())) {
            assertEquals(0f, curve.get(0f), 0.001f, "$curve at 0")
            assertEquals(1f, curve.get(1f), 0.001f, "$curve at 1")
        }
    }

    @Test
    fun theBounceTouchesTheTopAndFallsAwayAgain() {
        val curve = BounceCurve()
        // The four arcs meet at the top: each reaches 1 at its boundary and falls away after it,
        // which is the bounce. Between the first two the value drops to about three quarters.
        for (edge in listOf(0.36363637f, 0.72727275f, 0.90909090f)) {
            assertEquals(1f, curve.get(edge), 0.01f, "it lands at $edge")
        }
        assertEquals(0.75f, curve.get(0.54545456f), 0.01f, "and is well below the top between them")
        assertTrue(curve.get(0.45f) < curve.get(0.36363637f), "having fallen away from the first")
    }

    @Test
    fun theElasticOvershootsAndSettles() {
        val curve = ElasticOutCurve()
        // It goes past its destination early on, which a cubic never does.
        val peak = (1..60).map { curve.get(it / 100f) }.max()
        assertTrue(peak > 1.05f, "it overshoots: $peak")
        // And the oscillation dies away, so late on it is near enough arrived.
        assertTrue(curve.get(0.9f) in 0.97f..1.03f, "it has settled: ${curve.get(0.9f)}")
    }

    /**
     * The bars while the target is changing: it steps from 0 to 100 as the second ticks over, and
     * each curve takes a second to follow it, so the second of the document is where to look.
     */
    private fun barWidths(): List<List<Float>> {
        val document = RemoteComposeParser.load(bytes)
        // The first frame is what the document's clock is measured from, so it starts at zero;
        // the target steps at one second and the curves have until two to follow it.
        document.frame(0L)
        return (20..39).map { step ->
            document.frame(step * 50L).opcodes.filterIsInstance<Opcode.DrawRect>()
                .map { it.right - it.left }
        }
    }

    @Test
    fun aBouncingValueIsNotJustARisingOne() {
        // The bar the bounce drives goes backwards on its way; the one the plain step drives
        // never does, because nothing eases it at all.
        val samples = barWidths()
        val stepped = samples.map { it[0] }
        val bounce = samples.map { it[1] }
        assertTrue(stepped.zipWithNext().all { (a, b) -> b >= a - 0.01f }, "the step only climbs: $stepped")
        assertTrue(bounce.zipWithNext().any { (a, b) -> b < a - 0.01f }, "the bounce falls back: $bounce")
        assertTrue(bounce.last() > 95f, "and has nearly arrived: ${bounce.last()}")
    }

    @Test
    fun anElasticValuePassesItsTargetOnTheWay() {
        // The target is 100, so anything past that is the overshoot a cubic never has.
        val elastic = barWidths().map { it[2] }
        assertTrue(elastic.any { it > 101f }, "it goes past the target: ${elastic.max()}")
        assertTrue(elastic.last() in 95f..105f, "and settles onto it: ${elastic.last()}")
    }
}
