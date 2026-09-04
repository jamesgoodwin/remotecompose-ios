import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RemoteComposeWriter
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

    val bytes = writer.encodeToByteArray()
    File("sample.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to sample.rc")
}
