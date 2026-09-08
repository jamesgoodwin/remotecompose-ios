package io.github.jamesgoodwin.remotecompose.layout

import androidx.compose.ui.graphics.Color
import io.github.jamesgoodwin.remotecompose.model.Opcode
import io.github.jamesgoodwin.remotecompose.runtime.RemoteContext
import io.github.jamesgoodwin.remotecompose.text.EstimatedTextMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Measure/layout semantics transcribed from the real `LayoutManager`s, on hand-built trees. */
class LayoutEngineTest {

    private val context = RemoteContext().apply { windowWidth = 300f; windowHeight = 200f }
    private val engine = LayoutEngine(context, EstimatedTextMetrics)

    private fun box(w: Float? = null, h: Float? = null, vararg modifiers: Modifier): LayoutNode =
        LayoutNode(LayoutNode.Kind.BOX).apply {
            if (w != null) widthDimension = Dimension(DimensionType.EXACT, w)
            if (h != null) heightDimension = Dimension(DimensionType.EXACT, h)
            this.modifiers += modifiers
        }

    @Test
    fun columnStacksChildrenWithSpacingAndWrapsToContent() {
        val column = LayoutNode(LayoutNode.Kind.COLUMN).apply { spacedBy = 5f }
        column.addChild(box(40f, 10f))
        column.addChild(box(20f, 30f))
        engine.measure(column, 0f, 300f, 0f, 200f)
        assertEquals(40f, column.width)
        assertEquals(45f, column.height)
        assertEquals(0f, column.children[0].y)
        assertEquals(15f, column.children[1].y)
    }

    @Test
    fun rowSpaceEvenlyDistributesSlack() {
        val row = LayoutNode(LayoutNode.Kind.ROW).apply {
            widthDimension = Dimension(DimensionType.FILL)
            horizontalPositioning = Positioning.SPACE_EVENLY
            verticalPositioning = Positioning.CENTER
            heightDimension = Dimension(DimensionType.EXACT, 50f)
        }
        row.addChild(box(60f, 10f))
        row.addChild(box(60f, 30f))
        engine.measure(row, 0f, 300f, 0f, 200f)
        assertEquals(300f, row.width)
        // slack = 300 - 120 = 180 over 3 gaps
        assertEquals(60f, row.children[0].x)
        assertEquals(180f, row.children[1].x)
        assertEquals(20f, row.children[0].y, 0.001f) // (50 - 10) / 2
        assertEquals(10f, row.children[1].y, 0.001f)
    }

    @Test
    fun paddingAddsToDeclaredWidthAndInsetsContent() {
        val padded = box(76f, null, Modifier.Background(Color.Red, 0), Modifier.Padding(10f, 10f, 10f, 10f))
        padded.addChild(box(20f, 20f))
        engine.measure(padded, 0f, 300f, 0f, 200f)
        assertEquals(96f, padded.width)
        assertEquals(40f, padded.height)
        val out = mutableListOf<Opcode>()
        engine.paint(padded, out)
        val background = assertIs<Opcode.DrawRect>(out.first { it is Opcode.DrawRect })
        assertEquals(96f, background.right, "background before padding covers the padded box")
        assertTrue(out.any { it is Opcode.Translate && (it as Opcode.Translate).dx == 10f && it.dy == 10f })
    }

    @Test
    fun weightedChildrenShareRemainingHeight() {
        val column = LayoutNode(LayoutNode.Kind.COLUMN).apply { heightDimension = Dimension(DimensionType.EXACT, 100f) }
        column.addChild(box(10f, 20f))
        column.addChild(box(10f, null).apply { heightDimension = Dimension(DimensionType.WEIGHT, 1f) })
        column.addChild(box(10f, null).apply { heightDimension = Dimension(DimensionType.WEIGHT, 3f) })
        engine.measure(column, 0f, 300f, 0f, 200f)
        assertEquals(20f, column.children[1].height)
        assertEquals(60f, column.children[2].height)
    }

    @Test
    fun goneChildTakesNoSpaceAndInvisibleKeepsIt() {
        val column = LayoutNode(LayoutNode.Kind.COLUMN)
        column.addChild(box(10f, 10f).apply { visibility = Visibility.GONE })
        column.addChild(box(10f, 10f).apply { visibility = Visibility.INVISIBLE })
        column.addChild(box(10f, 10f))
        engine.measure(column, 0f, 300f, 0f, 200f)
        assertEquals(20f, column.height)
        val out = mutableListOf<Opcode>()
        engine.paint(column, out)
        assertEquals(1, out.count { it is Opcode.Translate && (it as Opcode.Translate).dy == 10f }, "only the visible box is painted at y=10")
    }

    @Test
    fun boxCentersChild() {
        val box = box(100f, 50f).apply { horizontalPositioning = Positioning.CENTER; verticalPositioning = Positioning.CENTER }
        box.addChild(box(20f, 10f))
        engine.measure(box, 0f, 300f, 0f, 200f)
        assertEquals(40f, box.children[0].x)
        assertEquals(20f, box.children[0].y)
    }

    @Test
    fun textComponentWrapsToMeasuredText() {
        context.texts[7] = "hello"
        val text = LayoutNode(LayoutNode.Kind.TEXT).apply { textId = 7 }
        engine.measure(text, 0f, 300f, 0f, 200f)
        assertEquals(5 * 16f * 0.55f, text.width, 0.001f)
        assertEquals(16f * 1.2f, text.height, 0.001f)
    }
}
