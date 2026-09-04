import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemotePath
import androidx.compose.remote.creation.actions.HostAction
import androidx.compose.remote.creation.actions.ValueFloatChange
import androidx.compose.remote.creation.actions.ValueFloatExpressionChange
import androidx.compose.remote.creation.actions.ValueIntegerChange
import androidx.compose.remote.creation.actions.ValueIntegerExpressionChange
import androidx.compose.remote.creation.actions.ValueStringChange
import androidx.compose.remote.creation.modifiers.RecordingModifier
import androidx.compose.remote.creation.modifiers.GraphicsLayerModifier
import androidx.compose.remote.creation.modifiers.MarqueeModifier
import androidx.compose.remote.creation.modifiers.RectShape
import androidx.compose.remote.creation.modifiers.RippleModifier
import androidx.compose.remote.creation.modifiers.RoundedRectShape
import androidx.compose.remote.creation.modifiers.WidthInModifier
import androidx.compose.remote.creation.modifiers.ZIndexModifier
import java.io.File

fun main() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "demo", platform)

    writer.getRcPaint()
        .setColor(0xFFE53935.toInt())
        .commit()
    writer.drawRect(20f, 20f, 180f, 180f)

    writer.getRcPaint()
        .setColor(0xFF1E88E5.toInt())
        .commit()
    writer.drawCircle(60f, 140f, 30f)

    writer.getRcPaint()
        .setColor(0xFF43A047.toInt())
        .commit()
    writer.drawRoundRect(110f, 110f, 190f, 190f, 12f, 12f)

    writer.getRcPaint()
        .setColor(0xFF000000.toInt())
        .commit()
    writer.drawTextAnchored("Hi", 100f, 20f, 0f, 0f, 0)

    writer.getRcPaint()
        .setColor(0xFF8E24AA.toInt())
        .commit()
    writer.drawLine(10f, 195f, 190f, 195f)

    writer.getRcPaint()
        .setColor(0xFFFB8C00.toInt())
        .commit()
    writer.drawOval(140f, 30f, 195f, 60f)

    writer.getRcPaint()
        .setColor(0xFF00ACC1.toInt())
        .commit()
    writer.drawArc(2f, 2f, 40f, 40f, 0f, 90f)

    writer.getRcPaint()
        .setColor(0xFFD81B60.toInt())
        .commit()
    writer.drawSector(140f, 140f, 198f, 198f, 200f, 100f)

    writer.getRcPaint()
        .setColor(0xFF3949AB.toInt())
        .commit()
    val trianglePath = RemotePath()
    trianglePath.moveTo(155f, 178f)
    trianglePath.lineTo(198f, 178f)
    trianglePath.lineTo(176f, 199f)
    trianglePath.close()
    writer.drawPath(trianglePath)

    val checkerImage = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (yy in 0 until 8) {
        for (xx in 0 until 8) {
            checkerImage.setRGB(xx, yy, if ((xx + yy) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF212121.toInt())
        }
    }
    writer.drawBitmap(checkerImage, 70f, 65f, 110f, 105f, "checker")

    writer.addClickArea(7, "tap target", 20f, 20f, 180f, 180f, "https://example.com/tapped")

    writer.getRcPaint()
        .setColor(0xFF00838F.toInt())
        .commit()
    val curvePath = RemotePath()
    curvePath.moveTo(2f, 150f)
    curvePath.quadTo(18f, 150f, 18f, 170f)
    curvePath.cubicTo(18f, 185f, 10f, 195f, 2f, 195f)
    curvePath.close()
    writer.drawPath(curvePath)

    writer.startColumn(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFFDD835.toInt())
        .commit()
    writer.drawRect(85f, 150f, 105f, 165f)
    writer.getRcPaint()
        .setColor(0xFF6D4C41.toInt())
        .commit()
    writer.drawCircle(95f, 175f, 8f)
    writer.endColumn()

    writer.startRow(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00695C.toInt())
        .commit()
    writer.drawRect(115f, 150f, 130f, 165f)
    writer.getRcPaint()
        .setColor(0xFFF06292.toInt())
        .commit()
    writer.drawCircle(140f, 157f, 7f)
    writer.endRow()

    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF9E9D24.toInt())
        .commit()
    writer.drawRect(150f, 148f, 165f, 163f)
    writer.endBox()

    writer.startBox(RecordingModifier().width(20f).height(10f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF5D4037.toInt())
        .commit()
    writer.drawRect(170f, 148f, 190f, 163f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(HostAction(9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFEC407A.toInt())
        .commit()
    writer.drawRect(190f, 2f, 199f, 11f)
    writer.endBox()

    writer.startBox(RecordingModifier().padding(3f).background(0xFF7B1FA2.toInt()), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF7B1FA2.toInt())
        .commit()
    writer.drawRect(45f, 2f, 60f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().visibility(0), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF388E3C.toInt())
        .commit()
    writer.drawRect(65f, 2f, 80f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().offset(5f, 5f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFFF5722.toInt())
        .commit()
    writer.drawRect(85f, 2f, 100f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().border(2f, 4f, 0xFF000000.toInt(), 0), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF009688.toInt())
        .commit()
    writer.drawRect(105f, 2f, 120f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().clip(RectShape(0f, 0f, 8f, 8f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF5E35B1.toInt())
        .commit()
    writer.drawRect(125f, 2f, 140f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().clip(RoundedRectShape(4f, 4f, 4f, 4f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFC0CA33.toInt())
        .commit()
    writer.drawRect(145f, 2f, 160f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().onLongClick(HostAction(9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00838F.toInt())
        .commit()
    writer.drawRect(165f, 2f, 180f, 12f)
    writer.endBox()

    writer.startBox(RecordingModifier().onTouchDown(HostAction(9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF8D6E63.toInt())
        .commit()
    writer.drawRect(2f, 15f, 17f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().onTouchUp(HostAction(9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF26A69A.toInt())
        .commit()
    writer.drawRect(20f, 15f, 35f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().onTouchCancel(HostAction(9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFEF5350.toInt())
        .commit()
    writer.drawRect(38f, 15f, 53f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().widthIn(10f, 20f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF7986CB.toInt())
        .commit()
    writer.drawRect(56f, 15f, 71f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().heightIn(3f, 10f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFFFB300.toInt())
        .commit()
    writer.drawRect(74f, 15f, 89f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().collapsiblePriority(0, 2f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF546E7A.toInt())
        .commit()
    writer.drawRect(92f, 15f, 107f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().alignByBaseline(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF8E24AA.toInt())
        .commit()
    writer.drawRect(110f, 15f, 125f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().then(ZIndexModifier(3f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF3E2723.toInt())
        .commit()
    writer.drawRect(128f, 15f, 143f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().then(RippleModifier()), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF1976D2.toInt())
        .commit()
    writer.drawRect(146f, 15f, 161f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().drawContent(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFAD1457.toInt())
        .commit()
    writer.drawRect(164f, 15f, 179f, 25f)
    writer.endBox()

    writer.startBox(RecordingModifier().then(MarqueeModifier(1, 0, 1000f, 500f, 8f, 30f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF827717.toInt())
        .commit()
    writer.drawRect(2f, 28f, 17f, 38f)
    writer.endBox()

    val graphicsLayer = GraphicsLayerModifier()
    graphicsLayer.setFloatAttribute(11, 0.5f)
    writer.startBox(RecordingModifier().then(graphicsLayer), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF6A1B9A.toInt())
        .commit()
    writer.drawRect(20f, 28f, 35f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().then(WidthInModifier(1, 5f, 40f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF4527A0.toInt())
        .commit()
    writer.drawRect(38f, 28f, 53f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(ValueIntegerChange(3, 7)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00695C.toInt())
        .commit()
    writer.drawRect(56f, 28f, 71f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(ValueFloatChange(4, 2.5f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFD84315.toInt())
        .commit()
    writer.drawRect(74f, 28f, 89f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(ValueStringChange(5, "hi")), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF283593.toInt())
        .commit()
    writer.drawRect(92f, 28f, 107f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(ValueIntegerExpressionChange(6L, 42L)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF33691E.toInt())
        .commit()
    writer.drawRect(110f, 28f, 125f, 38f)
    writer.endBox()

    writer.startBox(RecordingModifier().onClick(ValueFloatExpressionChange(7, 9)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF6D4C41.toInt())
        .commit()
    writer.drawRect(128f, 28f, 143f, 38f)
    writer.endBox()

    writer.startCollapsibleColumn(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF37474F.toInt())
        .commit()
    writer.drawRect(146f, 28f, 161f, 38f)
    writer.endCollapsibleColumn()

    writer.startCollapsibleRow(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF880E4F.toInt())
        .commit()
    writer.drawRect(164f, 28f, 179f, 38f)
    writer.endCollapsibleRow()

    writer.startFlow(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00838F.toInt())
        .commit()
    writer.drawRect(2f, 41f, 17f, 51f)
    writer.endFlow()

    writer.startFitBox(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFE64A19.toInt())
        .commit()
    writer.drawRect(20f, 41f, 35f, 51f)
    writer.endFitBox()

    writer.startRoot()
    writer.getRcPaint()
        .setColor(0xFF1A237E.toInt())
        .commit()
    writer.drawRect(38f, 41f, 53f, 51f)
    writer.endRoot()

    writer.startStateLayout(RecordingModifier(), 0)
    writer.getRcPaint()
        .setColor(0xFFAD1457.toInt())
        .commit()
    writer.drawRect(56f, 41f, 71f, 51f)
    writer.endStateLayout()

    writer.startCanvas(RecordingModifier())
    writer.getRcPaint()
        .setColor(0xFF558B2F.toInt())
        .commit()
    writer.drawRect(74f, 41f, 89f, 51f)
    writer.endCanvas()

    writer.startCustom(RecordingModifier(), "myCustom", emptyList())
    writer.getRcPaint()
        .setColor(0xFF4E342E.toInt())
        .commit()
    writer.drawRect(92f, 41f, 107f, 51f)
    writer.endCustom()

    writer.image(RecordingModifier(), 3, 1, 0.75f)

    writer.performHaptic(4)
    writer.setTheme(1)
    writer.setRootContentBehavior(1, 2, 3, 4)

    writer.startBox(RecordingModifier().animationSpec(3), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF5D4037.toInt())
        .commit()
    writer.drawRect(110f, 41f, 125f, 51f)
    writer.endBox()

    writer.save()
    writer.translate(130f, 41f)
    writer.getRcPaint().setColor(0xFF00ACC1.toInt()).commit()
    writer.drawRect(0f, 0f, 15f, 10f)
    writer.restore()

    writer.save()
    writer.scale(2f, 2f, 145f, 46f)
    writer.getRcPaint().setColor(0xFFF57F17.toInt()).commit()
    writer.drawRect(145f, 41f, 152f, 46f)
    writer.restore()

    writer.save()
    writer.rotate(45f, 175f, 46f)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawRect(168f, 41f, 183f, 51f)
    writer.restore()

    writer.save()
    writer.clipRect(190f, 41f, 205f, 51f)
    writer.getRcPaint().setColor(0xFFAA00FF.toInt()).commit()
    writer.drawRect(185f, 36f, 210f, 56f)
    writer.restore()

    val bytes = writer.encodeToByteArray()
    File("sample.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to sample.rc")
}
