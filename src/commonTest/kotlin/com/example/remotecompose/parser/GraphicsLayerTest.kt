package com.example.remotecompose.parser

import com.example.remotecompose.fixture
import com.example.remotecompose.model.Opcode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MODIFIER_GRAPHICS_LAYER`: the attributes of a graphics layer, and the fact that a float one is
 * a value rather than a number.
 *
 * `GraphicsLayerModifierOperation` keeps each attribute as an `AttributeValue`, and its `paint`
 * does nothing but call `evaluate(PaintContext)` on every one of them — so a float attribute is
 * whatever the document's own arithmetic says it is this frame, not the constant the writer put
 * in the file. `tools/rc-writer/carousel.rc` sets `SCALE_X`, `SCALE_Y` and `ALPHA` from the scroll
 * position, so the cards shrink and dim as they leave the middle of the window.
 *
 * The rest is read off `tools/rc-writer/sample.rc`, which carries one box per attribute.
 */
class GraphicsLayerTest {

    private val carousel = fixture("carousel")
    private val sample = fixture("sample")

    /** What each card's layer is set to this frame: its alpha, and the scale that follows. */
    private class Layer(val alpha: Float, val scale: Float)

    private fun layers(document: RemoteComposeDocument): List<Layer> {
        val opcodes = document.frame(0L).opcodes
        return opcodes.withIndex().mapNotNull { (index, opcode) ->
            if (opcode !is Opcode.SaveLayerAlpha) return@mapNotNull null
            val scale = opcodes.drop(index + 1).takeWhile { it !is Opcode.SaveLayerAlpha }
                .filterIsInstance<Opcode.Scale>().firstOrNull() ?: return@mapNotNull null
            Layer(opcode.alpha, scale.sx)
        }
    }

    /** The carousel, dragged sideways by [by] and settled. */
    private fun dragged(by: Float): List<Layer> {
        val document = RemoteComposeParser.load(carousel)
        document.frame(0L)
        if (by != 0f) {
            document.touchDown(250f, 200f)
            document.frame(0L)
            document.touchDrag(250f - by, 200f)
            document.frame(0L)
            document.touchUp(250f - by, 200f)
        }
        return layers(document)
    }

    @Test
    fun everyCardCarriesItsOwnLayer() {
        val cards = dragged(0f)
        assertEquals(5, cards.size, "five pictures, five layers")
    }

    @Test
    fun theNearestCardIsTheLargestAndTheBrightest() {
        // Scale runs 1 at the middle of the window down to 0.84 at 260 away, alpha 1 down to
        // 0.45. Cards are 168 wide on a 180 stride, so at rest the first one — 50 from the middle
        // of a 300-wide window — is the nearest, and each one after it is further out.
        val cards = dragged(0f)
        assertEquals(0, cards.indices.maxByOrNull { cards[it].scale }, "the first card is nearest")
        assertEquals(cards.map { it.scale }.sortedDescending(), cards.map { it.scale }, "shrinking away")
        assertEquals(cards.map { it.alpha }.sortedDescending(), cards.map { it.alpha }, "and dimming")
    }

    @Test
    fun aCardGrowsAsItComesToTheMiddleAndShrinksAsItLeaves() {
        // The third card's middle sits 310 to the right of the window's, so that much scroll
        // brings it to the centre and more takes it away again.
        val third = { by: Float -> dragged(by)[2] }
        val before = third(200f)
        val middle = third(310f)
        val after = third(460f)
        assertEquals(1f, middle.scale, 0.02f, "at the middle it is not shrunk at all")
        assertEquals(1f, middle.alpha, 0.02f, "nor dimmed")
        assertTrue(before.scale < middle.scale, "growing on the way in: ${before.scale}")
        assertTrue(after.scale < middle.scale, "and shrinking on the way out: ${after.scale}")
        assertTrue(before.alpha < middle.alpha && after.alpha < middle.alpha, "dimmer either side")
    }

    @Test
    fun theScaleAndAlphaAreTheDocumentsOwnArithmeticRatherThanWrittenNumbers() {
        // scale = 1 - min(|fromMiddle|, 260) / 260 * 0.16, and the file holds that expression
        // rather than any of the numbers it produces. Read it back at a distance we choose.
        val cards = dragged(180f)
        for ((index, card) in cards.withIndex()) {
            val fromMiddle = 16f + index * 180f + 84f - 150f - 180f
            val distance = minOf(abs(fromMiddle), 260f) / 260f
            assertEquals(1f - distance * 0.16f, card.scale, 0.01f, "card $index scale")
            assertEquals(1f - distance * 0.55f, card.alpha, 0.01f, "card $index alpha")
        }
    }

    @Test
    fun theSameLayerIsNotStuckAtWhateverItWasFirstEvaluatedAt() {
        // One document, moved: an attribute read once at load would leave every card as it was.
        val document = RemoteComposeParser.load(carousel)
        val atRest = layers(document).map { it.scale }
        document.touchDown(250f, 200f)
        document.frame(0L)
        document.touchDrag(70f, 200f)
        val moved = layers(document).map { it.scale }
        assertTrue(atRest != moved, "the layer followed the scroll: $atRest then $moved")
    }

    /**
     * The scale the sample fixture's 16x16 square at [left] is drawn under, which is the last one
     * before that rectangle.
     */
    private fun scaleBefore(left: Float): Opcode.Scale {
        val opcodes = RemoteComposeParser.load(sample).frame(0L).opcodes
        val rect = opcodes.indexOfFirst {
            it is Opcode.DrawRect && it.left == left && it.top == 61f && it.bottom == 77f
        }
        assertTrue(rect >= 0, "no square at $left in the fixture")
        return opcodes.take(rect).filterIsInstance<Opcode.Scale>().last()
    }

    @Test
    fun turningALayerAboutTheHorizontalAxisForeshortensItsHeight() {
        // ROTATION_X = 60 degrees: the width is untouched and the height is cos 60 of itself.
        val scale = scaleBefore(100f)
        assertEquals(1f, scale.sx, 0.001f)
        assertEquals(0.5f, scale.sy, 0.001f)
    }

    @Test
    fun turningItAboutTheVerticalAxisForeshortensItsWidth() {
        val scale = scaleBefore(120f)
        assertEquals(0.5f, scale.sx, 0.001f)
        assertEquals(1f, scale.sy, 0.001f)
    }
}
