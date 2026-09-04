import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemotePath
import androidx.compose.remote.creation.actions.HostAction
import androidx.compose.remote.creation.modifiers.RecordingModifier
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

    val bytes = writer.encodeToByteArray()
    File("sample.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to sample.rc")
}
