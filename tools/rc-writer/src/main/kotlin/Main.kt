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

fun main(args: Array<String>) {
    if (args.getOrNull(0) == "showcase") {
        buildShowcase()
        return
    }
    if (args.getOrNull(0) == "texttest") {
        buildTextTransformTest()
        return
    }
    buildCoverageSample()
}

/**
 * Minimal repro of an unresolved Compose Multiplatform/Skiko rendering bug, kept for whoever picks
 * this up next: this exact sequence of `drawText`/`drawRect`/`drawCircle` calls — all absolute
 * coordinates, *zero* transform opcodes (MatrixSave/Translate/MatrixRestore are entirely absent
 * from the parsed opcode list; verified) — renders correctly on the headless Desktop demo
 * (`runDesktopDemo`, an offscreen *software* Skia raster surface), but on a real on-screen Canvas
 * composable backed by GPU-accelerated Skiko — confirmed on *both* a real Android emulator and a
 * real iOS Simulator — "Users"/"Orders"/"Uptime" render on top of "Activity" instead of below it,
 * even though their coordinates are unambiguously non-overlapping (verified directly from the
 * parsed opcode dump). Removing the trailing DrawCircle calls after "Activity" (the showcase's
 * avatar row) makes the bug disappear; nothing about *why* trailing shape draws corrupt an
 * earlier, already-issued DrawText call's position was found despite extensive isolation (ruled
 * out: MatrixSave/Restore involvement, string content/length, color, draw-call repetition count,
 * leading vs. trailing position of the affected text). Given it reproduces identically on two
 * independent GPU-backed platforms but never on the software-only one, this looks like a genuine
 * upstream Skiko/Compose Multiplatform issue in how on-screen `drawText` interacts with later draw
 * calls in the same frame, not a bug in this renderer's own opcode handling.
 */
private fun buildTextTransformTest() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(360, 420, "texttest", platform)
    // Exact replica of the real showcase's parsed opcode list — same 18 opcodes, same absolute
    // coordinates verbatim from that dump — to rule out any mistake in a hand-approximated test
    // coordinate ever having accidentally overlapped another one (it did, on an earlier attempt).
    fun text(s: String, x: Float, y: Float, argb: Int) {
        writer.getRcPaint().setColor(argb).commit()
        writer.drawTextAnchored(s, x, y, 0f, 0f, 0)
    }
    val navy = 0xFF1A237E.toInt()
    val slate = 0xFF546E7A.toInt()
    writer.getRcPaint().setColor(0xFFE3F2FD.toInt()).commit()
    writer.drawRect(20f, 63.2f, 64f, 133.6f)
    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).commit()
    writer.drawCircle(42f, 73.2f, 10f)
    text("128", 28.8f, 89.2f, navy)
    text("Users", 20f, 114.4f, slate)
    writer.getRcPaint().setColor(0xFFE8F5E9.toInt()).commit()
    writer.drawRect(64f, 57.2f, 116.8f, 139.6f)
    writer.getRcPaint().setColor(0xFF43A047.toInt()).commit()
    writer.drawCircle(90.4f, 73.2f, 16f)
    text("42", 81.6f, 95.2f, navy)
    text("Orders", 64f, 120.4f, slate)
    writer.getRcPaint().setColor(0xFFFFF3E0.toInt()).commit()
    writer.drawRect(116.8f, 60.2f, 169.6f, 136.6f)
    writer.getRcPaint().setColor(0xFFFB8C00.toInt()).commit()
    writer.drawCircle(143.2f, 73.2f, 13f)
    text("97%", 130f, 92.2f, navy)
    text("Uptime", 116.8f, 117.4f, slate)
    text("Activity", 20f, 157.6f, navy)
    writer.getRcPaint().setColor(0xFFE53935.toInt()).commit()
    writer.drawCircle(30f, 210.8f, 10f)
    writer.getRcPaint().setColor(0xFF8E24AA.toInt()).commit()
    writer.drawCircle(56f, 210.8f, 16f)
    writer.getRcPaint().setColor(0xFF00897B.toInt()).commit()
    writer.drawCircle(84f, 210.8f, 12f)
    writer.getRcPaint().setColor(0xFFFDD835.toInt()).commit()
    writer.drawCircle(104f, 210.8f, 8f)
    val bytes = writer.encodeToByteArray()
    File("texttest.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to texttest.rc")
}

/**
 * A hand-built "dashboard card" — real text, real icons, real nested Row/Column arrangement and
 * alignment modes, real backgrounds — meant to be looked at as a UI, not decoded opcode-by-opcode
 * like `sample.rc`. Every child in every Row/Column below is deliberately drawn at a *placeholder*
 * position (or, for the stat cards, at literally the same raw coordinates as its siblings) so
 * nothing about the final layout comes from hand-placed document coordinates — only from this
 * renderer's own real arrangement code.
 */
private fun buildShowcase() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(360, 420, "showcase", platform)

    val navy = 0xFF1A237E.toInt()
    val slate = 0xFF546E7A.toInt()
    val cardBg1 = 0xFFE3F2FD.toInt()
    val cardBg2 = 0xFFE8F5E9.toInt()
    val cardBg3 = 0xFFFFF3E0.toInt()
    val accent1 = 0xFF1E88E5.toInt()
    val accent2 = 0xFF43A047.toInt()
    val accent3 = 0xFFFB8C00.toInt()

    fun text(s: String, x: Float, y: Float, color: Int) {
        writer.getRcPaint().setColor(color).commit()
        writer.drawTextAnchored(s, x, y, 0f, 0f, 0)
    }

    // Outer vertical rhythm: every top-level section is its own Box (so it's a real, arrangeable
    // Column child) stacked with real spacing — nothing here is manually y-offset by hand.
    writer.startColumn(RecordingModifier().spacedBy(18f), 0, 0)

    // -- Title --
    writer.startBox(RecordingModifier(), 0, 0)
    text("Dashboard", 20f, 20f, navy)
    writer.endBox()

    // -- Stat cards row: real declared width, SPACE_EVENLY main axis, CENTER cross axis. Each
    // card has a *different*-radius icon circle, so the three cards are different heights —
    // making the row's CENTER cross-alignment visibly stagger them, not just look coincidentally
    // aligned. Every card's content is drawn starting at the same local (0,0)-ish origin; only
    // the Row's own arrangement (via the enclosing Box below) spreads the three across x.
    writer.startBox(RecordingModifier().width(328f), 0, 0)
    writer.startRow(RecordingModifier(), 7, 2) // RowLayout.SPACE_EVENLY, .CENTER
    data class Stat(val value: String, val label: String, val radius: Float, val bg: Int, val accent: Int)
    val stats = listOf(
        Stat("128", "Users", 10f, cardBg1, accent1),
        Stat("42", "Orders", 16f, cardBg2, accent2),
        Stat("97%", "Uptime", 13f, cardBg3, accent3),
    )
    for ((value, label, radius, bg, accent) in stats) {
        writer.startBox(RecordingModifier().background(bg), 0, 0)
        writer.startColumn(RecordingModifier().spacedBy(6f), 2, 0) // horizontalPositioning=CENTER
        writer.startBox(RecordingModifier(), 0, 0)
        writer.getRcPaint().setColor(accent).commit()
        writer.drawCircle(radius, radius, radius)
        writer.endBox()
        writer.startBox(RecordingModifier(), 0, 0)
        text(value, 0f, 0f, navy)
        writer.endBox()
        writer.startBox(RecordingModifier(), 0, 0)
        text(label, 0f, 0f, slate)
        writer.endBox()
        writer.endColumn()
        writer.endBox()
    }
    writer.endRow()
    writer.endBox()

    // -- Subtitle --
    writer.startBox(RecordingModifier(), 0, 0)
    text("Activity", 20f, 20f, navy)
    writer.endBox()

    // -- Avatar row: real declared width, SPACE_BETWEEN main axis, CENTER cross axis, four
    // circles of different radii all drawn centered on the same raw point — real spread and real
    // vertical centering, exactly like the alignment-mode test but with visibly different sizes.
    writer.startBox(RecordingModifier().width(328f), 0, 0)
    writer.startRow(RecordingModifier(), 6, 2) // RowLayout.SPACE_BETWEEN, .CENTER
    val avatars = listOf(10f to 0xFFE53935.toInt(), 16f to 0xFF8E24AA.toInt(), 12f to 0xFF00897B.toInt(), 8f to 0xFFFDD835.toInt())
    for ((radius, color) in avatars) {
        writer.startBox(RecordingModifier(), 0, 0)
        writer.getRcPaint().setColor(color).commit()
        writer.drawCircle(radius, radius, radius)
        writer.endBox()
    }
    writer.endRow()
    writer.endBox()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("showcase.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to showcase.rc")
}

private fun buildCoverageSample() {
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
    // A 2x2 image, one solid color per quadrant, so cropping to just one quadrant (as opposed to
    // scaling the whole image down) is visually unambiguous. RemoteComposeWriter's own public
    // drawBitmap(...) overloads never expose DRAW_BITMAP_INT's real source-rect cropping (they
    // always set src == dst) — storeBitmap()/getBuffer().drawBitmap(...) are the only public path
    // that can, since RemoteComposeBuffer.drawBitmap's 12-arg signature maps directly onto
    // DrawBitmapInt.apply()'s real 10 wire ints (params 2 and 3 here are accepted but never
    // written to the wire; the trailing contentDescId *is* written, unlike an earlier pass over
    // this opcode concluded).
    val quadrantsImage = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    quadrantsImage.setRGB(0, 0, 0xFFE53935.toInt()) // top-left: red
    quadrantsImage.setRGB(1, 0, 0xFF43A047.toInt()) // top-right: green
    quadrantsImage.setRGB(0, 1, 0xFF1E88E5.toInt()) // bottom-left: blue
    quadrantsImage.setRGB(1, 1, 0xFFFDD835.toInt()) // bottom-right: yellow
    val quadrantsImageId = writer.storeBitmap(quadrantsImage)
    writer.getBuffer().drawBitmap(quadrantsImageId, 0, 0, 0, 0, 1, 1, 150, 2, 190, 42, 0)

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

    writer.startBox(RecordingModifier().background(0xFFFF6F00.toInt()), 0, 0)
    writer.getRcPaint().setColor(0xFF1565C0.toInt()).commit()
    writer.drawRect(2f, 61f, 12f, 71f)
    writer.getRcPaint().setColor(0xFF2E7D32.toInt()).commit()
    writer.drawCircle(35f, 78f, 6f)
    writer.endBox()

    // Three Box children, each drawn at the *same* raw (2,90)-(12,100) rect — deliberately
    // overlapping in the document's own absolute coordinates, so a rendering that shows them
    // stacked without overlap can only be the parser's real Column arrangement at work, not
    // coordinates the document author already spaced out by hand.
    writer.startColumn(RecordingModifier().spacedBy(3f), 0, 0)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFFD32F2F.toInt()).commit()
    writer.drawRect(2f, 90f, 12f, 100f)
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF1976D2.toInt()).commit()
    writer.drawRect(2f, 90f, 12f, 100f)
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF388E3C.toInt()).commit()
    writer.drawRect(2f, 90f, 12f, 100f)
    writer.endBox()
    writer.endColumn()

    // A Row with a real declared width (80f) and RowLayout.SPACE_BETWEEN (6) horizontal / .CENTER
    // (2) vertical positioning. Three children of different heights, all drawn starting at the
    // *same* raw (22,150) top-left — proving any horizontal spread or vertical centering seen can
    // only be this parser's real alignment-mode handling, not hand-placed document coordinates.
    writer.startRow(RecordingModifier().width(80f), 6, 2)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFFC62828.toInt()).commit()
    writer.drawRect(22f, 150f, 30f, 158f) // short
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFFF9A825.toInt()).commit()
    writer.drawRect(22f, 150f, 30f, 166f) // tall — defines the row's wrap-content cross extent
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF00838F.toInt()).commit()
    writer.drawRect(22f, 150f, 30f, 158f) // short
    writer.endBox()
    writer.endRow()

    writer.save()
    val clipTriangle = RemotePath()
    clipTriangle.moveTo(105f, 168f)
    clipTriangle.lineTo(125f, 150f)
    clipTriangle.lineTo(125f, 168f)
    clipTriangle.close()
    val clipPathId = writer.addPathData(clipTriangle)
    writer.addClipPath(clipPathId)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawRect(105f, 150f, 130f, 170f) // full rect; only the clipped triangle should paint
    writer.restore()

    val bytes = writer.encodeToByteArray()
    File("sample.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to sample.rc")
}
