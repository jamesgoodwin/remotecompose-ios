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
        assertEquals(
            listOf(CubicEasing.EASE_OUT_BOUNCE, CubicEasing.EASE_OUT_ELASTIC, CubicEasing.SPLINE_CUSTOM),
            types,
        )
    }

    @Test
    fun eachTypeBuildsItsOwnCurve() {
        assertTrue(CubicEasing.preset(CubicEasing.EASE_OUT_BOUNCE) is BounceCurve)
        assertTrue(CubicEasing.preset(CubicEasing.EASE_OUT_ELASTIC) is ElasticOutCurve)
        assertTrue(CubicEasing.preset(CubicEasing.CUBIC_STANDARD) is CubicEasing)
    }

    @Test
    fun theSplineIsBuiltFromTheSpecRatherThanTheTypeAlone() {
        // A spline needs its shape, which lives in the description; `preset` has only the type,
        // so it cannot build one and the animation does it instead.
        val animation = OperationReader.readAll(bytes).filterIsInstance<Operation.FloatExpression>()
            .mapNotNull { it.animation }
            .single { FloatAnimation(it).type == CubicEasing.SPLINE_CUSTOM }
        val curve = FloatAnimation(animation)
        // The spec is 0, 1, 0.5, 1 evenly spaced, so the curve passes through each in turn.
        curve.initialValue = 0f
        curve.targetValue = 1f
        assertEquals(0f, curve.get(0f), 0.02f)
        assertEquals(1f, curve.get(1f / 3f), 0.02f, "up to the first")
        assertEquals(0.5f, curve.get(2f / 3f), 0.02f, "back down to the second")
        assertEquals(1f, curve.get(1f), 0.02f, "and up to the last")
    }

    @Test
    fun aStepCurveGoesThroughEveryValueItWasGiven() {
        val curve = StepCurve(floatArrayOf(0f, 0.25f, 0.75f, 1f), 0, 4)
        assertEquals(0f, curve.get(0f), 0.02f)
        assertEquals(0.25f, curve.get(1f / 3f), 0.02f)
        assertEquals(0.75f, curve.get(2f / 3f), 0.02f)
        assertEquals(1f, curve.get(1f), 0.02f)
    }

    @Test
    fun aMonotonicSplineDoesNotOvershootBetweenItsPoints() {
        // The tangent limiting is the whole point of the fit: a plain cubic through these would
        // bulge above the flat run, and this must not.
        val spline = MonotonicSpline(
            doubleArrayOf(0.0, 1.0, 2.0, 3.0),
            doubleArrayOf(0.0, 1.0, 1.0, 2.0),
        )
        for (step in 0..100) {
            val x = 1.0 + step / 100.0
            val v = spline.position(x)
            assertTrue(v in 0.999..1.001, "flat between its ends at $x: $v")
        }
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
    fun aSplineFollowsTheShapeItWasGiven() {
        // The fourth bar: up to the target, back to half of it, and up again, which is the spec.
        val spline = barWidths().map { it[3] }
        val third = spline[spline.size / 3]
        val twoThirds = spline[spline.size * 2 / 3]
        assertTrue(third > 90f, "up to the first control point: $third")
        assertTrue(twoThirds < 60f, "and back down to the second: $twoThirds")
        assertTrue(spline.last() > 85f, "and climbing to the last: ${spline.last()}")
    }

    @Test
    fun anElasticValuePassesItsTargetOnTheWay() {
        // The target is 100, so anything past that is the overshoot a cubic never has.
        val elastic = barWidths().map { it[2] }
        assertTrue(elastic.any { it > 101f }, "it goes past the target: ${elastic.max()}")
        assertTrue(elastic.last() in 95f..105f, "and settles onto it: ${elastic.last()}")
    }

    /**
     * `FloatAnimation`'s wrap: a value that lives on a circle. `setTargetValue` brings both ends
     * into the period and pushes a target that lies backwards the short way round a whole turn
     * forward instead, so a hand at 354 degrees heading for 0 goes on to 360.
     */
    @Test
    fun aWrappedValueTakesTheShortWayRound() {
        val description = floatArrayOf(
            1f,
            Float.fromBits((1 shl 8) or CubicEasing.CUBIC_LINEAR), // hasWrap, linear
            360f,
        )
        val animation = FloatAnimation(description)
        assertEquals(360f, animation.wrap)

        animation.initialValue = 354f
        animation.targetValue = 0f
        assertEquals(354f, animation.initialValue, 0.01f)
        assertEquals(360f, animation.targetValue, 0.01f, "carried forward rather than back")

        // Half way through a linear second it is between the two rather than off round the dial.
        assertEquals(357f, animation.get(0.5f), 0.5f)
    }

    @Test
    fun aWrappedValueGoesBackwardsWhenThatIsTheShortWay() {
        val animation = FloatAnimation(
            floatArrayOf(1f, Float.fromBits((1 shl 8) or CubicEasing.CUBIC_LINEAR), 360f),
        )
        animation.initialValue = 10f
        animation.targetValue = 350f
        // Ten degrees back is shorter than three hundred and fifty forward.
        assertEquals(350f, animation.targetValue, 0.01f)
        assertTrue(animation.get(0.5f) in 170f..190f, "the long way is not taken: ${animation.get(0.5f)}")
    }

    @Test
    fun anUnwrappedValueIsLeftAlone() {
        val animation = FloatAnimation(floatArrayOf(1f))
        assertTrue(animation.wrap.isNaN())
        animation.initialValue = 354f
        animation.targetValue = 0f
        assertEquals(0f, animation.targetValue, 0.01f, "no period, no carrying forward")
    }
}
