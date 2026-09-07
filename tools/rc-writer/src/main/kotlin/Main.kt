import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.RcPaint
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
import androidx.compose.remote.creation.modifiers.WidthModifier
import androidx.compose.remote.creation.modifiers.ZIndexModifier
import androidx.compose.remote.core.operations.BitmapFontData
import androidx.compose.remote.core.operations.DrawTextOnCircle
import androidx.compose.remote.core.operations.utilities.MatrixOperations
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import java.io.File

/**
 * MODIFIER_BACKGROUND's real shapeType=1 (CIRCLE) has no public `RecordingModifier` fluent
 * wrapper — `SolidBackgroundModifier.write()` (backing `.background(...)`) always hardcodes
 * shapeType 0, javap-confirmed. Reaching shapeType 1 on the wire needs the lower-level
 * `RemoteComposeWriter.addModifierBackground(r,g,b,a,shapeType)` writer method directly; this
 * small `RecordingModifier.Element` just calls that real method from inside `.then(...)`, so the
 * resulting bytes are still 100% real SDK output, not a hand-rolled encoding.
 */
class CircleBackgroundElement(
    private val r: Float,
    private val g: Float,
    private val b: Float,
    private val a: Float,
) : RecordingModifier.Element {
    override fun write(writer: RemoteComposeWriter) {
        writer.addModifierBackground(r, g, b, a, 1)
    }
}

/**
 * `MODIFIER_RIPPLE` has no fluent wrapper on `RecordingModifier`, so this element calls the
 * writer method from inside `.then(...)`. The bytes are the real writer's, as everywhere else.
 */
class RippleElement : RecordingModifier.Element {
    override fun write(writer: RemoteComposeWriter) {
        writer.addModifierRipple()
    }
}

fun main(args: Array<String>) {
    if (args.getOrNull(0) == "showcase") {
        buildShowcase()
        return
    }
    if (args.getOrNull(0) == "texttest") {
        buildTextTransformTest()
        return
    }
    if (args.getOrNull(0) == "paint") {
        buildPaintSample()
        return
    }
    if (args.getOrNull(0) == "anim") {
        buildAnimSample()
        return
    }
    if (args.getOrNull(0) == "actions") {
        buildActionsSample()
        return
    }
    if (args.getOrNull(0) == "textpath") {
        buildTextPathSample()
        return
    }
    if (args.getOrNull(0) == "advanced") {
        buildAdvancedSample()
        return
    }
    if (args.getOrNull(0) == "shader") {
        buildShaderSample()
        return
    }
    if (args.getOrNull(0) == "material") {
        buildMaterialSample()
        return
    }
    if (args.getOrNull(0) == "list") {
        buildListSample()
        return
    }
    if (args.getOrNull(0) == "pattern") {
        buildPatternSample()
        return
    }
    if (args.getOrNull(0) == "visibility") {
        buildVisibilitySample()
        return
    }
    if (args.getOrNull(0) == "easing") {
        buildEasingSample()
        return
    }
    if (args.getOrNull(0) == "coffee") {
        buildCoffeeSample()
        return
    }
    buildCoverageSample()
}

/**
 * The coffee shop: the demo the other fixtures are parts of.
 *
 * Every colour is themed, so the whole screen has a light and a dark palette in the one document
 * and the host picks between them.
 */
private fun buildCoffeeSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(300, 420, "coffee", platform)

    // A colour with a value for each mode. The pair is written once and read by id after that,
    // so the rest of the document says nothing about which mode it is in.
    var nextThemeId = 900
    fun themed(light: Int, dark: Int): Int {
        val id = nextThemeId++
        writer.getBuffer().addThemedColor(id, 0, 0.toShort(), 0.toShort(), light, dark)
        return id
    }

    val surface = themed(0xFFFDF8F3.toInt(), 0xFF1B1614.toInt())
    val card = themed(0xFFFFFFFF.toInt(), 0xFF2A2320.toInt())
    val onSurface = themed(0xFF2E1E14.toInt(), 0xFFF3E9E1.toInt())
    val muted = themed(0xFF7A6455.toInt(), 0xFFB9A79A.toInt())
    val accent = themed(0xFF8D5524.toInt(), 0xFFD9A066.toInt())
    val outline = themed(0xFFE7DACE.toInt(), 0xFF3D332D.toInt())

    // The first short is TextLayout's flags: bit 0 says the colour field is the id of a colour
    // rather than an ARGB, which is what lets a text follow a themed one.
    fun text(value: Int, colorId: Int, size: Float, weight: Float = 400f, modifier: RecordingModifier = RecordingModifier()) {
        writer.startTextComponent(modifier, value, colorId, size, 0, weight, "", 1.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
    }

    writer.startColumn(RecordingModifier().fillMaxSize().backgroundId(surface.toShort()).padding(16f).spacedBy(12f), 1, 4)

    text(writer.addText("Bean & Bone"), onSurface, 22f, 700f)

    // "closes in N min", from the clock: 18:00 less the time of day, in minutes. TIME_IN_HR and
    // TIME_IN_MIN are system variables, so this follows the clock without the host saying a word.
    val minutesLeft = writer.floatExpression(
        18f, Rc.Time.TIME_IN_HR, Rc.FloatExpression.SUB, 60f, Rc.FloatExpression.MUL,
        Rc.Time.TIME_IN_MIN, Rc.FloatExpression.SUB,
        0f, Rc.FloatExpression.MAX,
    )
    val closing = writer.textMerge(
        writer.textMerge(writer.addText("Cotham Hill · closes in "), writer.createTextFromFloat(minutesLeft, 2, 0, 4 or 1)),
        writer.addText(" min"),
    )
    text(closing, muted, 12f)

    // A brightness bar under the heading: the accent's own brightness through COLOR_ATTRIBUTE,
    // which is a different number in each palette, so the bar is shorter in the dark.
    val brightness = writer.getColorAttribute(accent, 2.toShort())
    writer.startBox(RecordingModifier().fillMaxWidth().height(3f), 1, 2)
    writer.getRcPaint().setColorId(outline).commit()
    writer.drawRect(0f, 0f, 268f, 3f)
    writer.getRcPaint().setColorId(accent).commit()
    writer.drawRect(0f, 0f, writer.floatExpression(brightness, 268f, Rc.FloatExpression.MUL), 3f)
    writer.endBox()

    // The menu: taller than the window it is shown through, so it scrolls. The scroll position
    // is a document value the modifier's own touch expression drives, and dragging moves it.
    writer.startColumn(
        RecordingModifier().fillMaxWidth().height(240f).verticalScroll(0f).spacedBy(8f),
        1, 4,
    )
    data class Item(val name: String, val note: String, val price: String)
    val menu = listOf(
        Item("Flat white", "Silky, two shots", "£3.40"),
        Item("Filter", "Ethiopia, washed", "£3.00"),
        Item("Cortado", "Short and sweet", "£3.20"),
        Item("Mocha", "Dark chocolate", "£3.80"),
        Item("Cold brew", "18 hour steep", "£3.60"),
        Item("Espresso", "One shot", "£2.40"),
    )
    // Tapping an item makes its card taller. The height is a float the tap writes, and the
    // animation spec on the row is what turns the change into a movement rather than a jump.
    val expanded = writer.addFloatConstant(0f)
    val expandedId = androidx.compose.remote.core.operations.Utils.idFromNan(expanded)
    for ((index, item) in menu.withIndex()) {
        // 56 normally, 92 for whichever item was tapped: (expanded == index) ? 92 : 56.
        val rowHeight = writer.floatExpression(
            expanded, (index + 1).toFloat(), Rc.FloatExpression.SUB, Rc.FloatExpression.ABS,
            0.5f, Rc.FloatExpression.STEP, 36f, Rc.FloatExpression.MUL, 92f, Rc.FloatExpression.SUB,
            Rc.FloatExpression.CHANGE_SIGN,
        )
        writer.startRow(
            RecordingModifier().fillMaxWidth().height(rowHeight)
                .animationSpec(
                    index + 1, 0.35f, androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD,
                    0.35f, androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD,
                    androidx.compose.remote.core.operations.layout.animation.AnimationSpec.ANIMATION.FADE_IN,
                    androidx.compose.remote.core.operations.layout.animation.AnimationSpec.ANIMATION.FADE_OUT,
                )
                .onClick(ValueFloatChange(expandedId, (index + 1).toFloat()))
                .clip(RoundedRectShape(12f, 12f, 12f, 12f))
                .backgroundId(card.toShort())
                // After the background, so the ripple washes over it rather than under it.
                .then(RippleElement())
                .dynamicBorder(1f, 12f, outline.toShort(), 2)
                .padding(12f),
            6, 2,
        )
        writer.startColumn(RecordingModifier().spacedBy(2f), 1, 4)
        text(writer.addText(item.name), onSurface, 14f, 700f)
        text(writer.addText(item.note), muted, 11f)
        writer.endColumn()
        writer.startBox(RecordingModifier().width(60f).height(20f), 3, 2)
        text(writer.addText(item.price), accent, 14f, 700f)
        writer.endBox()
        writer.endRow()
    }
    writer.endColumn()

    // A way out of the page, and a demonstration that a host action still reaches the host from
    // inside all of this.
    writer.startBox(
        RecordingModifier().fillMaxWidth().height(36f)
            .clip(RoundedRectShape(18f, 18f, 18f, 18f))
            .then(RippleElement())
            .dynamicBorder(1f, 18f, outline.toShort(), 2)
            .onClick(HostAction(11)),
        1, 2,
    )
    text(writer.addText("Next demo"), accent, 13f, 700f)
    writer.endBox()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("coffee.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to coffee.rc")
}

/**
 * A pattern: the format's component. `MACRO_DEFINE` writes a card once — a bordered box with a
 * title and a slot — and `MACRO_CALL` uses it three times, each with its own title through a
 * value parameter and its own contents through a `MACRO_BLOCK` filling the slot the body marked
 * with `MACRO_ARGUMENT`.
 */
private fun buildPatternSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(240, 344, "pattern", platform)

    // The card: one parameter for its title, one slot for whatever the caller puts inside.
    val title = writer.definePatternParameter("title")
    val card = writer.definePattern("card", intArrayOf(title))
    // A fixed height rather than one that wraps: a card that grows with its text is a
    // different height on every platform, which moves everything under it and leaves the page
    // impossible to compare pixel for pixel. The padding adds to it, so each card is 100 tall.
    writer.startColumn(
        RecordingModifier().fillMaxWidth().height(80f)
            .clip(RoundedRectShape(8f, 8f, 8f, 8f))
            .background(0xFFF3F4F8.toInt())
            .border(1f, 8f, 0xFFC5CAE9.toInt(), 2)
            .padding(10f)
            .spacedBy(6f),
        1, 4,
    )
    writer.startTextComponent(RecordingModifier(), title, 0xFF283593.toInt(), 14f, 0, 700f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()
    // A value the body derives from its parameter, rather than one the caller passes ready-made:
    // each call has to end up with its own, or every card shows the last one's.
    val caption = writer.textMerge(title, writer.addText(" \u00b7 tap for detail"))
    writer.startTextComponent(RecordingModifier(), caption, 0xFF9FA8DA.toInt(), 10f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()
    writer.addPatternArgument(0) // the caller's contents go here
    writer.endColumn()
    writer.endPatternDefine()

    fun card(name: String, block: () -> Unit) {
        writer.patternInflation(card, intArrayOf(writer.textCreateId(name)))
        writer.addPatternBlock(0)
        block()
        writer.endPatternBlock()
        writer.endPatternInflation()
    }

    fun label(text: String, color: Int) {
        val id = writer.addText(text)
        writer.startTextComponent(RecordingModifier(), id, color, 12f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
    }

    writer.startColumn(RecordingModifier().fillMaxSize().padding(12f).spacedBy(10f), 1, 4)

    card("Today") { label("3 orders, 2 collected", 0xFF546E7A.toInt()) }
    card("Stock") {
        label("Beans: 4 kg", 0xFF546E7A.toInt())
        label("Oat milk: 9 l", 0xFF546E7A.toInt())
    }
    // The same card again with a drawing inside instead of text, to show the slot takes whatever
    // the caller writes rather than a fixed shape.
    card("Level") {
        writer.startBox(RecordingModifier().fillMaxWidth().height(10f), 1, 2)
        writer.getRcPaint().setColor(0xFFE0E0E0.toInt()).commit()
        writer.drawRoundRect(0f, 0f, 196f, 10f, 5f, 5f)
        writer.getRcPaint().setColor(0xFF00897B.toInt()).commit()
        writer.drawRoundRect(0f, 0f, 130f, 10f, 5f, 5f)
        writer.endBox()
    }

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("pattern.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to pattern.rc")
}

/**
 * Lists, both kinds. `MACRO_FOR_EACH` over an id list of strings expands one row body per entry,
 * so the document holds one copy of it rather than four. A `FLOAT_LIST` underneath is read by the
 * expression operators: each bar's height is one entry through `A_DEREF`, and the caption's
 * count and average come from `A_LEN` and `A_AVG` without the document naming an entry twice. A
 * `DYNAMIC_FLOAT_LIST` under that carries nothing on the wire — its three entries are written by
 * `UPDATE_DYNAMIC_FLOAT_LIST` and read back with `A_SUM`.
 */
private fun buildListSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(240, 280, "list", platform)

    val names = writer.addStringList("Espresso", "Cortado", "Flat white", "Filter")
    val collection = androidx.compose.remote.core.operations.Utils.idFromNan(names)
    val item = writer.definePatternParameter("item")

    writer.startColumn(RecordingModifier().fillMaxSize().padding(16f).spacedBy(8f), 1, 4)

    val heading = writer.addText("Menu")
    writer.startTextComponent(RecordingModifier(), heading, 0xFF1A237E.toInt(), 20f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()

    // One row per name, written once. The text component reads the loop's local item, which each
    // expansion binds to that entry's own string id.
    writer.addPatternForEach(collection, item)
    writer.startRow(RecordingModifier().fillMaxWidth().height(28f).background(0xFFE8EAF6.toInt()).padding(8f, 0f, 8f, 0f), 1, 2)
    writer.startTextComponent(RecordingModifier(), item, 0xFF283593.toInt(), 14f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()
    writer.endRow()
    writer.endPatternForEach()

    // A float list read by the expression operators: the bars below are sized from it, and the
    // caption reads its length, total and average without the document naming any entry twice.
    val prices = writer.addFloatArray(floatArrayOf(2.4f, 2.8f, 3.4f, 3.0f))
    fun listExpression(vararg ops: Float) = writer.floatExpression(*ops)

    writer.startRow(RecordingModifier().fillMaxWidth().height(40f).spacedBy(4f), 1, 5)
    for (index in 0 until 4) {
        // Each bar's height is that entry, scaled: deref(prices, index) * 9.
        val entry = listExpression(prices, index.toFloat(), Rc.FloatExpression.A_DEREF, 9f, Rc.FloatExpression.MUL)
        writer.startBox(RecordingModifier().width(20f).height(entry).background(0xFF5C6BC0.toInt()), 1, 5)
        writer.endBox()
    }
    writer.endRow()

    // Length, sum and average, each straight out of the list.
    val summary = writer.textMerge(
        writer.textMerge(
            writer.addText("n="),
            writer.createTextFromFloat(listExpression(prices, Rc.FloatExpression.A_LEN), 1, 0, 4 or 1),
        ),
        writer.textMerge(
            writer.addText("  avg="),
            writer.createTextFromFloat(listExpression(prices, Rc.FloatExpression.A_AVG), 1, 2, 4 or 3),
        ),
    )
    writer.startTextComponent(RecordingModifier(), summary, 0xFF37474F.toInt(), 12f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()

    // A dynamic list: three entries with nothing on the wire, written from expressions and read
    // straight back. Its total is 1 + 2 + 4 = 7, so the last bar is 70 wide.
    val running = writer.addDynamicFloatArray(3f)
    val runningId = androidx.compose.remote.core.operations.Utils.idFromNan(running)
    writer.setArrayValue(runningId, 0f, 1f)
    writer.setArrayValue(runningId, 1f, 2f)
    writer.setArrayValue(runningId, 2f, writer.floatExpression(2f, 2f, Rc.FloatExpression.MUL))
    val total = writer.floatExpression(running, Rc.FloatExpression.A_SUM, 10f, Rc.FloatExpression.MUL)
    writer.startBox(RecordingModifier().width(total).height(12f).background(0xFF00897B.toInt()), 1, 5)
    writer.endBox()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("list.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to list.rc")
}

/**
 * A Material 3 order screen: a top app bar, an outlined card, a quantity stepper, and the four
 * button kinds — filled, tonal, outlined and text — at their real specs (40 high, fully rounded,
 * 24 of horizontal padding, Label Large at 14/500). Colours are the baseline light scheme's roles
 * rather than picked by eye, so the surfaces and their `on` pairs stay in step.
 *
 * It is the interactive parts that make it a demo rather than a picture: the stepper's buttons
 * write the quantity through expression actions, so the count, the line total and the width of
 * the progress bar all follow; "Add to cart" sets a flag that a conditional block reads to show
 * the snackbar; and "Next demo" leaves through a host action rather than handling itself.
 */
private fun buildMaterialSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(360, 480, "material", platform)

    // Baseline light scheme roles.
    val surface = 0xFFFEF7FF.toInt()
    val surfaceContainer = 0xFFF3EDF7.toInt()
    val surfaceContainerLow = 0xFFF7F2FA.toInt()
    val surfaceContainerHighest = 0xFFE6E0E9.toInt()
    val onSurface = 0xFF1D1B20.toInt()
    val onSurfaceVariant = 0xFF49454F.toInt()
    val primary = 0xFF6750A4.toInt()
    val onPrimary = 0xFFFFFFFF.toInt()
    val secondaryContainer = 0xFFE8DEF8.toInt()
    val onSecondaryContainer = 0xFF1D192B.toInt()
    val outline = 0xFF79747E.toInt()
    val outlineVariant = 0xFFCAC4D0.toInt()
    val inverseSurface = 0xFF322F35.toInt()
    val inverseOnSurface = 0xFFF5EFF7.toInt()

    fun text(
        value: Any,
        color: Int,
        size: Float,
        weight: Float = 400f,
        modifier: RecordingModifier = RecordingModifier(),
    ) {
        val id = if (value is Int) value else writer.addText(value as String)
        writer.startTextComponent(modifier, id, color, size, 0, weight, "", 0.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
    }

    // The quantity, and the two expressions the stepper buttons write back into it.
    val quantity = writer.addFloatConstant(1f)
    val quantityId = androidx.compose.remote.core.operations.Utils.idFromNan(quantity)
    val more = writer.floatExpression(quantity, 1f, Rc.FloatExpression.ADD)
    val fewer = writer.floatExpression(quantity, 1f, Rc.FloatExpression.SUB, 1f, Rc.FloatExpression.MAX)
    val added = writer.addFloatConstant(0f)
    val addedId = androidx.compose.remote.core.operations.Utils.idFromNan(added)

    // PAD_PRE_NONE(4) leaves the count unpadded; PAD_AFTER_ZERO(3) keeps the price at two places.
    val snackbarText = writer.addText("Added to your cart")
    val quantityText = writer.createTextFromFloat(quantity, 1, 0, 4 or 1)
    val totalText = writer.textMerge(
        writer.addText("\u00a3"),
        writer.createTextFromFloat(
            writer.floatExpression(quantity, 3.4f, Rc.FloatExpression.MUL), 2, 2, 4 or 3,
        ),
    )

    /** One Material button: a fully rounded 40-high container with its label centred. */
    fun button(
        label: String,
        container: Int?,
        labelColor: Int,
        borderColor: Int?,
        horizontalPadding: Float,
        vararg actions: androidx.compose.remote.creation.actions.Action,
    ) {
        var modifier = RecordingModifier().height(40f).clip(RoundedRectShape(20f, 20f, 20f, 20f))
        if (container != null) modifier = modifier.background(container)
        // After the container so the press washes over it, and inside the clip so it keeps the
        // button's shape.
        modifier = modifier.then(RippleElement())
        if (borderColor != null) modifier = modifier.border(1f, 20f, borderColor, 2)
        modifier = modifier.padding(horizontalPadding, 0f, horizontalPadding, 0f).onClick(*actions)
        writer.startBox(modifier, 2, 2)
        text(label, labelColor, 14f, weight = 500f)
        writer.endBox()
    }

    writer.startColumn(RecordingModifier().fillMaxSize().background(surface), 1, 4)

    // Top app bar, small: 56 high, Title Large, on a container surface.
    writer.startBox(RecordingModifier().fillMaxWidth().height(56f).background(surfaceContainer).padding(16f, 0f, 16f, 0f), 1, 2)
    text("Remote Compose", onSurface, 22f)
    writer.endBox()

    writer.startColumn(RecordingModifier().fillMaxWidth().padding(16f).spacedBy(16f), 1, 4)

    // Outlined card: 12 corner, 1 outline-variant border, 16 padding.
    writer.startColumn(
        RecordingModifier().fillMaxWidth()
            .clip(RoundedRectShape(12f, 12f, 12f, 12f))
            .background(surfaceContainerLow)
            .border(1f, 12f, outlineVariant, 2)
            .padding(16f)
            .spacedBy(6f),
        1, 4,
    )
    text("Flat white", onSurface, 16f, weight = 500f)
    text("Oat milk · 250 ml · \u00a33.40", onSurfaceVariant, 14f)

    // Quantity stepper: two tonal buttons either side of the count, total on the right.
    writer.startRow(RecordingModifier().fillMaxWidth().padding(0f, 10f, 0f, 0f), 1, 2)
    button("\u2212", secondaryContainer, onSecondaryContainer, null, 16f, ValueFloatExpressionChange(quantityId, androidx.compose.remote.core.operations.Utils.idFromNan(fewer)))
    writer.startBox(RecordingModifier().width(48f).height(40f), 2, 2)
    text(quantityText, onSurface, 16f, weight = 500f)
    writer.endBox()
    button("+", secondaryContainer, onSecondaryContainer, null, 16f, ValueFloatExpressionChange(quantityId, androidx.compose.remote.core.operations.Utils.idFromNan(more)))
    writer.startBox(RecordingModifier().width(70f).height(40f), 3, 2)
    writer.endBox()
    writer.startBox(RecordingModifier().height(40f), 3, 2)
    text(totalText, primary, 16f, weight = 500f)
    writer.endBox()
    writer.endRow()
    writer.endColumn()

    // The four button kinds, at their real specs.
    writer.startRow(RecordingModifier().fillMaxWidth().spacedBy(8f), 1, 2)
    button("Add to cart", primary, onPrimary, null, 24f, ValueFloatChange(addedId, 1f))
    button("Details", null, primary, outline, 24f)
    writer.endRow()

    writer.startRow(RecordingModifier().fillMaxWidth().spacedBy(8f), 1, 2)
    button("Clear", secondaryContainer, onSecondaryContainer, null, 24f, ValueFloatChange(addedId, 0f), ValueFloatChange(quantityId, 1f))
    button("Next demo", null, primary, null, 16f, HostAction(7))
    writer.endRow()

    // A progress track whose fill follows the quantity: 1 of 8 cups.
    writer.startBox(RecordingModifier().fillMaxWidth().height(4f), 1, 2)
    writer.getRcPaint().setColor(surfaceContainerHighest).commit()
    writer.drawRoundRect(0f, 0f, 328f, 4f, 2f, 2f)
    writer.getRcPaint().setColor(primary).commit()
    writer.drawRoundRect(
        0f, 0f,
        writer.floatExpression(quantity, 8f, Rc.FloatExpression.MIN, 41f, Rc.FloatExpression.MUL),
        4f, 2f, 2f,
    )
    writer.endBox()

    writer.endColumn()

    // A weighted spacer pushes the snackbar to the bottom of the screen, where one belongs.
    writer.startBox(RecordingModifier().fillMaxWidth().verticalWeight(1f), 1, 4)
    writer.endBox()

    // Snackbar: inverse surface, 4 corner, only while the cart flag is set. Its string is
    // created out here on purpose: a text record inside the block would only be applied on a
    // frame where the block runs, and this evaluator applies each constant once.
    writer.conditionalOperations(4.toByte(), added, 0.5f) // TYPE_GT
    writer.startBox(RecordingModifier().fillMaxWidth().padding(16f), 1, 4)
    writer.startBox(
        RecordingModifier().fillMaxWidth().height(48f)
            .clip(RoundedRectShape(4f, 4f, 4f, 4f))
            .background(inverseSurface)
            .padding(16f, 0f, 16f, 0f),
        1, 2,
    )
    text(snackbarText, inverseOnSurface, 14f)
    writer.endBox()
    writer.endBox()
    writer.endConditionalOperations()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("material.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to material.rc")
}

/**
 * A document carrying an AGSL shader with float, int and bitmap uniforms, plus two plain draws
 * either side of the shaded one. This renderer decodes `DATA_SHADER` and keeps the stream
 * aligned, but paints nothing with it — `ShaderFixtureTest` asserts both halves of that: the
 * uniforms decode, and the draws that do not depend on the shader still come out.
 */
private fun buildShaderSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(120, 120, "shader", platform)

    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).commit()
    writer.drawRect(8f, 8f, 52f, 52f)

    val texture = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    texture.setRGB(0, 0, 0xFFFF0000.toInt())
    texture.setRGB(1, 1, 0xFF00FF00.toInt())
    val shader = writer.createShader(
        """
        uniform float2 iResolution;
        uniform int iSteps;
        half4 main(float2 coord) {
          float2 uv = coord / iResolution;
          return half4(uv.x, uv.y, float(iSteps) / 8.0, 1.0);
        }
        """.trimIndent(),
    )
        .setFloatUniform("iResolution", 120f, 120f)
        .setIntUniform("iSteps", 4)
        .setBitmapUniform("iTexture", writer.storeBitmap(texture))
    val shaderId = shader.commit()
    writer.getRcPaint().setShader(shaderId).commit()
    writer.drawRect(60f, 8f, 112f, 60f)

    writer.getRcPaint().setShader(0).setColor(0xFF43A047.toInt()).commit()
    writer.drawCircle(60f, 90f, 20f)

    val bytes = writer.encodeToByteArray()
    File("shader.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to shader.rc")
}

/**
 * The operations that generate their own content rather than drawing what the buffer spells out:
 * `FLOAT_FUNCTION_DEFINE`/`CALL` for a body that runs once per call with new arguments, and
 * `PATH_EXPRESSION` for a path sampled from a pair of float expressions, in both cartesian and
 * polar form.
 */
private fun buildAdvancedSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "advanced", platform)

    // 1. A float function: bar(value, scale) leaves value * scale in one float id. The writer
    //    fills `args` with the ids the body reads, so the body is written in terms of them.
    val args = floatArrayOf(0f, 0f)
    val function = writer.createFloatFunction(args)
    val barWidth = writer.floatExpression(args[0], args[1], Rc.FloatExpression.MUL)
    writer.endFloatFunction()

    // Two calls with different arguments: each bar is drawn from the value its own call left.
    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).commit()
    writer.callFloatFunction(function, 30f, 4f) // 120
    writer.drawRect(12f, 12f, writer.floatExpression(barWidth, 12f, Rc.FloatExpression.ADD), 26f)
    writer.getRcPaint().setColor(0xFF43A047.toInt()).commit()
    writer.callFloatFunction(function, 15f, 4f) // 60
    writer.drawRect(12f, 30f, writer.floatExpression(barWidth, 12f, Rc.FloatExpression.ADD), 44f)

    // 2. A spline path expression: x = 20 + 25t, y = 130 + 20 sin(2t), sampled 16 times over
    //    t in [0, 6.2832]. The sample position arrives in the first caller variable slot.
    val curve = writer.addPathExpression(
        floatArrayOf(Rc.FloatExpression.VAR1, 25f, Rc.FloatExpression.MUL, 20f, Rc.FloatExpression.ADD),
        floatArrayOf(
            Rc.FloatExpression.VAR1, 2f, Rc.FloatExpression.MUL, Rc.FloatExpression.SIN,
            20f, Rc.FloatExpression.MUL, 130f, Rc.FloatExpression.ADD,
        ),
        0f, 6.2831855f, 16f, Rc.PathExpression.SPLINE_PATH,
    )
    writer.getRcPaint().setColor(0xFFE53935.toInt()).setStyle(1).setStrokeWidth(3f).commit()
    writer.drawPath(curve)

    // 3. The same operation in polar form, looped into a closed flower: r = 26 + 8 sin(5t)
    //    around (100, 80), sampled 40 times over a full turn.
    val flower = writer.addPolarPathExpression(
        floatArrayOf(
            Rc.FloatExpression.VAR1, 5f, Rc.FloatExpression.MUL, Rc.FloatExpression.SIN,
            8f, Rc.FloatExpression.MUL, 26f, Rc.FloatExpression.ADD,
        ),
        0f, 6.2831855f, 40f, 100f, 80f, Rc.PathExpression.POLAR_PATH or Rc.PathExpression.LOOP_PATH,
    )
    writer.getRcPaint().setColor(0xFF8E24AA.toInt()).setStyle(0).commit()
    writer.drawPath(flower)

    // 4. A linear path expression over the same points, so the two joins can be told apart.
    val zigzag = writer.addPathExpression(
        floatArrayOf(Rc.FloatExpression.VAR1, 25f, Rc.FloatExpression.MUL, 20f, Rc.FloatExpression.ADD),
        floatArrayOf(
            Rc.FloatExpression.VAR1, 2f, Rc.FloatExpression.MUL, Rc.FloatExpression.SIN,
            12f, Rc.FloatExpression.MUL, 180f, Rc.FloatExpression.ADD,
        ),
        0f, 6.2831855f, 16f, Rc.PathExpression.LINEAR_PATH,
    )
    writer.getRcPaint().setColor(0xFF37474F.toInt()).setStyle(1).setStrokeWidth(2f).commit()
    writer.drawPath(zigzag)

    // 5. A bitmap font: each glyph is its own little image, spaced by the margins in the font
    //    table and by a kerning pair. The same run is then laid along a path.
    fun glyphImage(color: Int, width: Int, height: Int): java.awt.image.BufferedImage {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (py in 0 until height) {
            for (px in 0 until width) {
                // A hollow block, so a wrongly sized destination rect is obvious.
                val edge = px == 0 || py == 0 || px == width - 1 || py == height - 1
                image.setRGB(px, py, if (edge) color else (color and 0x40FFFFFF))
            }
        }
        return image
    }
    val glyphs = arrayOf(
        BitmapFontData.Glyph(
            "R", writer.storeBitmap(glyphImage(0xFFE53935.toInt(), 10, 14)),
            1.toShort(), 0.toShort(), 1.toShort(), 0.toShort(), 10.toShort(), 14.toShort(),
        ),
        BitmapFontData.Glyph(
            "C", writer.storeBitmap(glyphImage(0xFF1E88E5.toInt(), 12, 14)),
            1.toShort(), 0.toShort(), 1.toShort(), 0.toShort(), 12.toShort(), 14.toShort(),
        ),
        BitmapFontData.Glyph(
            "!", writer.storeBitmap(glyphImage(0xFF43A047.toInt(), 6, 14)),
            1.toShort(), 0.toShort(), 1.toShort(), 0.toShort(), 6.toShort(), 14.toShort(),
        ),
        // No bitmap: a space that only advances.
        BitmapFontData.Glyph(" ", -1, 4.toShort(), 0.toShort(), 4.toShort(), 0.toShort(), 0.toShort(), 0.toShort()),
    )
    // "RC" is kerned two pixels tighter than the margins alone would put it.
    val font = writer.addBitmapFont(glyphs, mapOf("RC" to (-2).toShort()))
    val fontText = writer.addText("RC C! R")
    writer.drawBitmapFontTextRun(fontText, font, 0, -1, 12f, 50f, 1f)

    // 6. BITMAP_TEXT_MEASURE: an underline exactly as wide as that run.
    val runWidth = writer.bitmapTextMeasure(fontText, font, 0, 1f) // MEASURE_WIDTH
    writer.getRcPaint().setColor(0xFF9E9E9E.toInt()).setStyle(0).commit()
    writer.drawRect(12f, 66f, writer.floatExpression(runWidth, 12f, Rc.FloatExpression.ADD), 68f)

    // 7. The same font laid along the wave, each glyph rotated to the tangent.
    val wave = RemotePath()
    wave.moveTo(14f, 104f)
    wave.cubicTo(60f, 78f, 140f, 122f, 186f, 96f)
    writer.drawBitmapFontTextRunOnPath(fontText, font, wave, 0, -1, -7f, 1f)

    // 8. A particle system: twelve particles, each with an x, a y and a radius created from its
    //    own index, advanced once per frame by the loop's equations, and drawn by the loop body.
    val particleVars = floatArrayOf(0f, 0f, 0f) // filled with the ids the equations read
    val system = writer.createParticles(
        particleVars,
        arrayOf(
            // x = 16 + 15 * index
            floatArrayOf(Rc.FloatExpression.VAR1, 15f, Rc.FloatExpression.MUL, 16f, Rc.FloatExpression.ADD),
            // y = 152 + 9 * (index % 3)
            floatArrayOf(
                Rc.FloatExpression.VAR1, 3f, Rc.FloatExpression.MOD, 9f, Rc.FloatExpression.MUL,
                152f, Rc.FloatExpression.ADD,
            ),
            // radius = 2 + index % 4
            floatArrayOf(Rc.FloatExpression.VAR1, 4f, Rc.FloatExpression.MOD, 2f, Rc.FloatExpression.ADD),
        ),
        12,
    )
    val (px, py, pr) = listOf(particleVars[0], particleVars[1], particleVars[2])
    writer.getRcPaint().setColor(0xFF00897B.toInt()).setStyle(0).commit()
    writer.particlesLoop(
        system,
        floatArrayOf(), // never restarted
        arrayOf(
            floatArrayOf(px), // x unchanged
            floatArrayOf(py, 6f, Rc.FloatExpression.ADD), // y drops 6
            floatArrayOf(pr), // radius unchanged
        ),
    ) {
        writer.drawCircle(px, py, pr)
    }

    // 9. The particles past the middle of the canvas get a marker, through the comparison's
    //    single-equation form: x - 100 is above zero only for those.
    writer.getRcPaint().setColor(0xFFFDD835.toInt()).commit()
    writer.particlesComparison(
        system, 0.toShort(), 0f, 12f,
        floatArrayOf(px, 100f, Rc.FloatExpression.SUB),
        arrayOf(floatArrayOf(px), floatArrayOf(py), floatArrayOf(pr)),
    ) {
        writer.drawRect(
            writer.floatExpression(px, 2f, Rc.FloatExpression.SUB),
            writer.floatExpression(py, 12f, Rc.FloatExpression.SUB),
            writer.floatExpression(px, 2f, Rc.FloatExpression.ADD),
            writer.floatExpression(py, 8f, Rc.FloatExpression.SUB),
        )
    }

    // 10. A matrix expression: translate to (150, 40), then turn 20 degrees. Four corners are
    //     pushed through it and the transformed ids drive the lines of a rotated square.
    val transform = writer.matrixExpression(
        150f, 40f, MatrixOperations.TRANSLATE2,
        20f, MatrixOperations.ROT_Z,
    )
    fun corner(x: Float, y: Float): FloatArray {
        val out = floatArrayOf(0f, 0f) // filled with the ids of the transformed components
        writer.addMatrixMultiply(transform, floatArrayOf(x, y), out)
        return out
    }
    val corners = listOf(corner(-22f, -14f), corner(22f, -14f), corner(22f, 14f), corner(-22f, 14f))
    writer.getRcPaint().setColor(0xFF6D4C41.toInt()).setStyle(1).setStrokeWidth(2f).commit()
    for (k in corners.indices) {
        val from = corners[k]
        val to = corners[(k + 1) % corners.size]
        writer.drawLine(from[0], from[1], to[0], to[1])
    }

    val bytes = writer.encodeToByteArray()
    File("advanced.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to advanced.rc")
}

/**
 * Text laid along curves, plus the two value operations that read and branch on it:
 * `DRAW_TEXT_ON_PATH` over a wave and an arc, `DRAW_TEXT_ON_CIRCLE` outside and inside a circle,
 * `TEXT_MEASURE` sizing a bar to the width of a string, and `CONDITIONAL_OPERATIONS` drawing a
 * marker only when that measurement passes a threshold.
 */
private fun buildTextPathSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "textpath", platform)

    // 1. A wave: text follows the curve and rotates with its tangent.
    val wave = RemotePath()
    wave.moveTo(10f, 40f)
    wave.cubicTo(50f, 5f, 100f, 65f, 150f, 25f)
    val waveId = writer.addPathData(wave)
    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).setTextSize(13f).commit()
    writer.drawTextOnPath(writer.textCreateId("following a wave"), waveId, 4f, 0f)

    // 2. The same path with a positive vOffset, which pushes the glyphs off it.
    writer.getRcPaint().setColor(0xFFB0BEC5.toInt()).setTextSize(9f).commit()
    writer.drawTextOnPath(writer.textCreateId("offset below"), waveId, 8f, 14f)

    // 3. Around a circle, outside and inside, both centered on the start angle.
    writer.getRcPaint().setColor(0xFF43A047.toInt()).setTextSize(12f).commit()
    writer.drawTextOnCircle(
        writer.textCreateId("OUTSIDE THE RING"), 100f, 120f, 45f, 270f, 0f,
        DrawTextOnCircle.Alignment.CENTER, DrawTextOnCircle.Placement.OUTSIDE,
    )
    writer.getRcPaint().setColor(0xFFE53935.toInt()).setTextSize(10f).commit()
    writer.drawTextOnCircle(
        writer.textCreateId("inside the ring"), 100f, 120f, 32f, 90f, 0f,
        DrawTextOnCircle.Alignment.CENTER, DrawTextOnCircle.Placement.INSIDE,
    )

    // 4. TEXT_MEASURE: a bar exactly as wide as the measured string.
    val measured = writer.textCreateId("measure me")
    writer.getRcPaint().setColor(0xFF37474F.toInt()).setTextSize(12f).commit()
    writer.drawTextAnchored(measured, 12f, 182f, -1f, -1f, 0)
    val measuredWidth = writer.textMeasure(measured, 0) // MEASURE_WIDTH
    writer.getRcPaint().setColor(0xFFFB8C00.toInt()).commit()
    writer.drawRect(12f, 186f, writer.floatExpression(measuredWidth, 12f, Rc.FloatExpression.ADD), 192f)

    // 5. CONDITIONAL_OPERATIONS: the marker draws only because the bar is wider than 20.
    writer.conditionalOperations(4.toByte(), measuredWidth, 20f) // TYPE_GT
    writer.getRcPaint().setColor(0xFF8E24AA.toInt()).commit()
    writer.drawCircle(190f, 189f, 5f)
    writer.endConditionalOperations()

    val bytes = writer.encodeToByteArray()
    File("textpath.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to textpath.rc")
}

/**
 * Click actions: three buttons whose `onClick` action lists change document values, and one
 * that calls back into the host. The panel below them reads those values, so a tap visibly
 * changes what the next frame draws.
 */
private fun buildActionsSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "actions", platform)

    // Values the buttons write to. addFloatConstant/addInteger return the id to target.
    // addFloatConstant returns the NaN-tagged reference a modifier can take directly; the
    // plain id an action targets comes back through Utils.idFromNan.
    val boxWidth = writer.addFloatConstant(40f)
    val boxWidthId = androidx.compose.remote.core.operations.Utils.idFromNan(boxWidth)
    val labelId = writer.addText("tap a button")
    val counterId = writer.addInteger(0)

    fun button(label: String, color: Int, vararg actions: androidx.compose.remote.creation.actions.Action) {
        writer.startBox(RecordingModifier().width(52f).height(28f).background(color).onClick(*actions), 2, 2)
        val id = writer.addText(label)
        writer.startTextComponent(RecordingModifier(), id, 0xFFFFFFFF.toInt(), 11f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
        writer.endBox()
    }

    writer.startColumn(RecordingModifier().fillMaxSize().padding(12f).spacedBy(10f), 2, 4)

    writer.startRow(RecordingModifier().spacedBy(8f), 1, 2)
    button("Wide", 0xFF1E88E5.toInt(), ValueFloatChange(boxWidthId, 150f), ValueStringChange(labelId, "wide"))
    button("Narrow", 0xFF43A047.toInt(), ValueFloatChange(boxWidthId, 40f), ValueStringChange(labelId, "narrow"))
    writer.endRow()

    writer.startRow(RecordingModifier().spacedBy(8f), 1, 2)
    button("Count", 0xFFFB8C00.toInt(), ValueIntegerChange(counterId.toInt(), 7))
    button("Host", 0xFF8E24AA.toInt(), HostAction(42))
    writer.endRow()

    // The panel: its width is the float the buttons write, so a tap resizes it.
    writer.startBox(RecordingModifier().width(boxWidth).height(30f).background(0xFF37474F.toInt()), 1, 4)
    writer.endBox()

    // The label: its text is the string the buttons write.
    writer.startTextComponent(RecordingModifier(), labelId, 0xFF1A237E.toInt(), 14f, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
    writer.endTextComponent()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("actions.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to actions.rc")
}

/**
 * Time-driven float expressions, the first document that changes from frame to frame. Every
 * value comes from `RemoteComposeWriter.floatExpression`, so the bytes carry real
 * `FloatExpression` records with NaN-tagged operators and system-variable references.
 */
private fun buildAnimSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "anim", platform)
    // 1. A circle whose x oscillates: 100 + 60 * sin(2 * t).
    val circleX = writer.floatExpression(Rc.Time.CONTINUOUS_SEC, 2f, Rc.FloatExpression.MUL, Rc.FloatExpression.SIN, 60f, Rc.FloatExpression.MUL, 100f, Rc.FloatExpression.ADD)
    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).commit()
    writer.drawCircle(circleX, 40f, 16f)

    // 2. A static expression: radius = hypot(3, 4) * 4 = 20.
    val radius = writer.floatExpression(3f, 4f, Rc.FloatExpression.HYPOT, 4f, Rc.FloatExpression.MUL)
    writer.getRcPaint().setColor(0xFF43A047.toInt()).commit()
    writer.drawCircle(40f, 100f, radius)

    // 3. A stepping target (0 or 100, flipping every second) eased over half a second with the
    //    standard cubic curve: a real FloatAnimation attached to the expression.
    val target = floatArrayOf(Rc.Time.CONTINUOUS_SEC, Rc.FloatExpression.FLOOR, 2f, Rc.FloatExpression.MOD, 100f, Rc.FloatExpression.MUL)
    val animation = androidx.compose.remote.core.operations.utilities.easing.FloatAnimation.packToFloatArray(
        0.5f,
        androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD,
        null,
        Float.NaN,
        Float.NaN,
    )
    val slideX = writer.floatExpression(target, animation)
    val slideRight = writer.floatExpression(slideX, 40f, Rc.FloatExpression.ADD)
    writer.getRcPaint().setColor(0xFFE53935.toInt()).commit()
    writer.drawRect(slideX, 140f, slideRight, 170f)

    // 4. The elapsed seconds as text, so a screenshot shows which frame it is.
    val clockText = writer.createTextFromFloat(Rc.Time.CONTINUOUS_SEC, 2, 2, 0x1000)
    writer.getRcPaint().setColor(0xFF000000.toInt()).setTextSize(14f).commit()
    writer.drawTextAnchored(clockText, 100f, 195f, -1f, -1f, 0)

    val bytes = writer.encodeToByteArray()
    File("anim.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to anim.rc")
}

/**
 * Exercises every `PaintBundle` attribute the renderer decodes, through the official `RcPaint`
 * API, so `paint.rc` doubles as the fixture for `PaintFixtureTest` and as a visual cross-check
 * document. Each draw is placed on a 200x200 grid so a bad decode is visible, and several steps
 * deliberately rely on paint state being cumulative across `commit()` calls.
 */
private fun buildPaintSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(200, 200, "paint", platform)

    // 1. Stroke style, width, round cap and join. Red.
    writer.getRcPaint()
        .setColor(0xFFE53935.toInt())
        .setStyle(1)
        .setStrokeWidth(6f)
        .setStrokeCap(1)
        .setStrokeJoin(1)
        .commit()
    writer.drawRect(12f, 12f, 60f, 60f)

    // 2. Only the style changes back to fill; color and stroke settings must carry over.
    writer.getRcPaint().setStyle(0).commit()
    writer.drawCircle(90f, 36f, 20f)

    // 3. Alpha replaces the color's alpha channel: 50% blue over the red circle.
    writer.getRcPaint().setColor(0xFF1E88E5.toInt()).setAlpha(0.5f).commit()
    writer.drawRect(80f, 26f, 140f, 46f)

    // 4. Stroke width and square cap on a line; a fresh opaque color resets alpha.
    writer.getRcPaint().setColor(0xFF000000.toInt()).setStrokeWidth(4f).setStrokeCap(2).commit()
    writer.drawLine(150f, 20f, 190f, 60f)

    // 5. Text size 24 then 10 with a bold italic monospace typeface. panY = -1 anchors the
    //    text's bottom at y (DrawTextAnchored.getVerticalOffset), so these y values are baselines
    //    plus descent, not top edges.
    writer.getRcPaint().setTextSize(24f).commit()
    writer.drawTextAnchored("Big", 12f, 90f, -1f, -1f, 0)
    writer.getRcPaint().setTextSize(10f).setTypeface(RcPaint.FONT_TYPE_MONOSPACE, 700, true).commit()
    writer.drawTextAnchored("bold mono", 70f, 86f, -1f, -1f, 0)

    // 6. Linear gradient with explicit stops, clamp tile mode.
    writer.getRcPaint()
        .setLinearGradient(
            12f, 110f, 92f, 110f,
            intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            floatArrayOf(0f, 1f),
            0,
        )
        .commit()
    writer.drawRect(12f, 100f, 92f, 130f)

    // 7. Radial gradient without stops.
    writer.getRcPaint()
        .setRadialGradient(140f, 115f, 25f, intArrayOf(0xFFFFFFFF.toInt(), 0xFF43A047.toInt()), null, 0)
        .commit()
    writer.drawCircle(140f, 115f, 25f)

    // 8. Sweep gradient.
    writer.getRcPaint()
        .setSweepGradient(
            52f, 165f,
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt()),
            null,
        )
        .commit()
    writer.drawRect(12f, 140f, 92f, 190f)

    // 9. Clearing the shader and filling from a color-pool id.
    val magentaId = writer.addColor(0xFFD500F9.toInt())
    writer.getRcPaint().setShader(0).setColorId(magentaId).commit()
    writer.drawRect(110f, 140f, 190f, 158f)

    // 10. Fill-and-stroke with a bevel join.
    writer.getRcPaint().setStyle(2).setStrokeWidth(6f).setStrokeJoin(2).commit()
    writer.drawRect(120f, 170f, 180f, 188f)

    // 11. Paint set inside a component must not leak out of it: green inside the box, magenta
    //     fill-and-stroke restored after endBox.
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF43A047.toInt()).setStyle(0).commit()
    writer.drawRect(160f, 66f, 190f, 80f)
    writer.endBox()
    writer.drawCircle(150f, 86f, 6f)

    val bytes = writer.encodeToByteArray()
    File("paint.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to paint.rc")
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
 * A "dashboard card" built the way a real Compose document is: every component declares its
 * size through modifiers (`fillMaxSize`, `width`, `size`, `padding`) or is a text component the
 * player measures itself. Canvas draws only appear inside boxes that declare their own size,
 * since a component with nothing but draw calls has no intrinsic size on the real player. The
 * three stat cards have different-radius icons, so the row's CENTER cross-alignment visibly
 * staggers them.
 */
private fun buildShowcase() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(360, 420, "showcase", platform)

    val navy = 0xFF1A237E.toInt()
    val slate = 0xFF546E7A.toInt()

    fun text(s: String, color: Int, size: Float, modifier: RecordingModifier = RecordingModifier()) {
        val id = writer.addText(s)
        writer.startTextComponent(modifier, id, color, size, 0, 400f, "", 0.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
    }

    fun circleBox(radius: Float, color: Int) {
        writer.startBox(RecordingModifier().size(radius * 2f), 1, 4)
        writer.getRcPaint().setColor(color).commit()
        writer.drawCircle(radius, radius, radius)
        writer.endBox()
    }

    // Column: START horizontally, TOP vertically, 18px between sections, 20px page padding.
    writer.startColumn(RecordingModifier().fillMaxSize().padding(20f).spacedBy(18f), 1, 4)

    text("Dashboard", navy, 22f)

    // Stat cards: a full-width row, SPACE_EVENLY main axis, CENTER cross axis.
    writer.startRow(RecordingModifier().fillMaxWidth().height(120f), 7, 2)
    data class Stat(val value: String, val label: String, val radius: Float, val bg: Int, val accent: Int)
    val stats = listOf(
        Stat("128", "Users", 10f, 0xFFE3F2FD.toInt(), 0xFF1E88E5.toInt()),
        Stat("42", "Orders", 16f, 0xFFE8F5E9.toInt(), 0xFF43A047.toInt()),
        Stat("97%", "Uptime", 13f, 0xFFFFF3E0.toInt(), 0xFFFB8C00.toInt()),
    )
    for ((value, label, radius, bg, accent) in stats) {
        // background before padding: the fill covers the padded box. In this library a
        // padding modifier adds to the declared width (LayoutComponent.computeModifierDefinedWidth),
        // so width(76) + padding(10) is a 96px card; three of them leave 32px for SPACE_EVENLY.
        writer.startBox(RecordingModifier().width(76f).background(bg).padding(10f), 2, 4)
        writer.startColumn(RecordingModifier().spacedBy(6f), 2, 4) // children centered horizontally
        circleBox(radius, accent)
        text(value, navy, 18f)
        text(label, slate, 12f)
        writer.endColumn()
        writer.endBox()
    }
    writer.endRow()

    text("Activity", navy, 16f)

    // Avatars: START main axis with 10px gaps, CENTER cross axis.
    writer.startRow(RecordingModifier().spacedBy(10f), 1, 2)
    val avatars = listOf(10f to 0xFFE53935.toInt(), 16f to 0xFF8E24AA.toInt(), 12f to 0xFF00897B.toInt(), 8f to 0xFFFDD835.toInt())
    for ((radius, color) in avatars) circleBox(radius, color)
    writer.endRow()

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

    // padding(left=2, top=6, right=2, bottom=2): a real-bytes hex-diff of this same call
    // confirmed the field order is (left, top, right, bottom), not the "top/bottom/left/right"
    // an earlier pass guessed — left/top get a real inset here, so the purple child rect's top
    // edge should sit visibly further from the grey background's top edge than its left edge
    // sits from the background's left edge.
    writer.startBox(RecordingModifier().padding(2f, 6f, 2f, 2f).background(0xFF37474F.toInt()), 0, 0)
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

    // MODIFIER_SCROLL: verticalScroll(50f) decodes to [direction=0, positionExpression=50.0,
    // max=NaN, notchMax=NaN] — max and notchMax are NaN-tagged references the writer reserves
    // even in this simplest overload. It is a container: a TOUCH_EXPRESSION follows it and a
    // CONTAINER_END closes it, which is what drives the position it names.
    //
    // The window is given a size here, since a scrolling component with none is a window onto
    // nothing and clips its content away. The green square is taller than the window, so what
    // shows is the top of it. Scrolling something is demonstrated by coffee.rc, which has child
    // components to scroll rather than canvas draws.
    writer.startBox(RecordingModifier().width(15f).height(9f).verticalScroll(50f), 0, 0)
    writer.getRcPaint().setColor(0xFF33691E.toInt()).commit()
    writer.drawRect(0f, 0f, 15f, 15f)
    writer.endBox()

    // COLOR_CONSTANT + MODIFIER_BORDER's colorId-ref path: addColor(...) registers a real color
    // in the pool, then dynamicBorder(...) references it by id (colorRefFlag == 2 on the wire,
    // r/g/b/a all 0) instead of carrying literal color floats — the border should still render
    // in the real magenta color, proving colorPool resolution actually happened rather than the
    // border silently staying unrendered (this parser's prior behavior for this exact case).
    val dynamicBorderColorId = writer.addColor(0xFFD500F9.toInt())
    writer.startBox(RecordingModifier().dynamicBorder(2f, 4f, dynamicBorderColorId.toShort(), 0), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF37474F.toInt())
        .commit()
    writer.drawRect(123f, 2f, 138f, 12f)
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

    // MODIFIER_CLIP_RECT real-effect proof: neither clip opcode carries its own rect bounds on
    // the wire at all (real Compose always clips to this container's own measured box) — a
    // useful real effect is only possible when the container also declares an explicit
    // width()/height() smaller than its content. Here the amber child rect (30x20) is drawn
    // oversized relative to the 15x10 box; only its top-left 15x10 corner should be visible, the
    // rest clipped away.
    writer.startBox(RecordingModifier().width(15f).height(10f).clip(RectShape(0f, 0f, 8f, 8f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFFF8F00.toInt())
        .commit()
    writer.drawRect(181f, 15f, 211f, 35f)
    writer.endBox()

    // MODIFIER_ROUNDED_CLIP_RECT real-effect proof: same "oversized content clipped to a smaller
    // declared box" shape as the plain RectShape proof above, but with real corner rounding this
    // time — the fuchsia child rect (30x20) is drawn oversized relative to the declared 15x10
    // box, so its visible top-left 15x10 corner should show a real quarter-round cut at all four
    // corners (this parser's quadratic approximation of a circular arc), not RectShape's sharp
    // 90-degree corners.
    writer.startBox(RecordingModifier().width(15f).height(10f).clip(RoundedRectShape(4f, 4f, 4f, 4f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFE91E8F.toInt())
        .commit()
    writer.drawRect(145f, 170f, 175f, 190f)
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

    // MODIFIER_WIDTH_IN real-effect proof: a 30-wide child inside widthIn(5f, 15f) — its natural
    // width (30) exceeds max (15), so this parser should clip it down to exactly 15, the same
    // real "cut off the overflow" effect MODIFIER_CLIP_RECT gets, without a separate clip(...)
    // call — matching real Compose's widthIn/heightIn semantics.
    writer.startBox(RecordingModifier().widthIn(5f, 15f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF827717.toInt())
        .commit()
    writer.drawRect(95f, 90f, 125f, 100f)
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

    // MODIFIER_ZINDEX real paint-order proof: two Box children at the *identical* overlapping
    // (150,65)-(175,80) rect, inside a plain startBox (not a Column/Row) so they naturally
    // overlap rather than getting spaced apart. The red child is authored *first* but carries the
    // *higher* z-index (5 vs 1) — without real z-index reordering, the blue child (authored
    // second) would paint on top just from document order; with it, red should end up on top
    // instead, since arrangeChildren reorders sibling paint order by z-index after positioning.
    writer.startBox(RecordingModifier(), 0, 0)
    writer.startBox(RecordingModifier().then(ZIndexModifier(5f)), 0, 0)
    writer.getRcPaint().setColor(0xFFD32F2F.toInt()).commit()
    writer.drawRect(150f, 65f, 175f, 80f)
    writer.endBox()
    writer.startBox(RecordingModifier().then(ZIndexModifier(1f)), 0, 0)
    writer.getRcPaint().setColor(0xFF1976D2.toInt()).commit()
    writer.drawRect(150f, 65f, 175f, 80f)
    writer.endBox()
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

    // GraphicsLayerModifierOperation.SCALE_X=0/SCALE_Y=1: an 8x8 square scaled 2x should render as
    // a 16x16 square centered on the *original* square's own center (61-69 stays its vertical
    // midline at y=65) — proof this parser infers a real pivot from the container's own content
    // bounds, not just leaving the attribute byte-consumed like every attribute here used to be.
    val graphicsLayerScale = GraphicsLayerModifier()
    graphicsLayerScale.setFloatAttribute(0, 2f) // SCALE_X
    graphicsLayerScale.setFloatAttribute(1, 2f) // SCALE_Y
    writer.startBox(RecordingModifier().then(graphicsLayerScale), 0, 0)
    writer.getRcPaint()
        .setColor(0xFFFDD835.toInt())
        .commit()
    writer.drawRect(45f, 61f, 53f, 69f)
    writer.endBox()

    // GraphicsLayerModifierOperation.ROTATION_Z=4: a square rotated 45 degrees about its own
    // inferred center should read as a diamond, not a square translated/clipped off to one side.
    val graphicsLayerRotate = GraphicsLayerModifier()
    graphicsLayerRotate.setFloatAttribute(4, 45f) // ROTATION_Z
    writer.startBox(RecordingModifier().then(graphicsLayerRotate), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF1E88E5.toInt())
        .commit()
    writer.drawRect(60f, 61f, 76f, 69f)
    writer.endBox()

    // GraphicsLayerModifierOperation.TRANSFORM_ORIGIN_X/_Y=5/6: the same 45-degree rotation as
    // above, but with the pivot fraction explicitly set to (0f, 0f) — real Compose's
    // GraphicsLayerScope.transformOrigin standard semantics put that at this layer's own
    // top-left corner, not its center. A diamond whose top-left corner stays fixed while the rest
    // sweeps away from it (not a diamond centered the same way the plain ROTATION_Z square above
    // is) is the only way to tell this from that test by eye.
    val graphicsLayerRotateOrigin = GraphicsLayerModifier()
    graphicsLayerRotateOrigin.setFloatAttribute(5, 0f) // TRANSFORM_ORIGIN_X
    graphicsLayerRotateOrigin.setFloatAttribute(6, 0f) // TRANSFORM_ORIGIN_Y
    graphicsLayerRotateOrigin.setFloatAttribute(4, 45f) // ROTATION_Z
    writer.startBox(RecordingModifier().then(graphicsLayerRotateOrigin), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00ACC1.toInt())
        .commit()
    writer.drawRect(85f, 61f, 93f, 69f)
    writer.endBox()

    // GraphicsLayerModifierOperation.SHAPE=20/SHAPE_CIRCLE=2: a solid 16x16 square with a
    // SHAPE_CIRCLE-clipped graphics layer should render as a circle inscribed in that square —
    // corners visibly cut off against whatever's behind, the same real quarter-round-cut proof
    // MODIFIER_BACKGROUND's own shapeType=CIRCLE test uses, but reached through the reduced
    // GraphicsLayerModifier attribute API this time (no separate boolean "clip" flag exists in
    // this wire format — SHAPE itself is the only way to express clip intent here) instead of a
    // MODIFIER_BACKGROUND fill.
    val graphicsLayerShapeCircle = GraphicsLayerModifier()
    graphicsLayerShapeCircle.setIntAttribute(20, 2) // SHAPE = SHAPE_CIRCLE
    writer.startBox(RecordingModifier().then(graphicsLayerShapeCircle), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF5D4037.toInt())
        .commit()
    writer.drawRect(2f, 170f, 18f, 186f)
    writer.endBox()

    writer.startBox(RecordingModifier().then(WidthInModifier(1, 5f, 40f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF4527A0.toInt())
        .commit()
    writer.drawRect(38f, 28f, 53f, 38f)
    writer.endBox()

    // MODIFIER_DIMENSION_CONSTRAINTS real-effect proof: same "oversized content clipped to a
    // declared max" shape as OP_MODIFIER_WIDTH_IN's own proof, but reached through
    // WidthInModifier's 3-arg constructor (type=0/HORIZONTAL_CONSTRAINTS) — a different wire
    // opcode entirely (MODIFIER_DIMENSION_CONSTRAINTS, not MODIFIER_WIDTH_IN) that this parser
    // previously only byte-consumed. A 30-wide child inside a HORIZONTAL_CONSTRAINTS(5f, 15f)
    // constraint should clip down to exactly the declared max (15), the same real effect
    // MODIFIER_WIDTH_IN's own type gets.
    writer.startBox(RecordingModifier().then(WidthInModifier(0, 5f, 15f)), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00695C.toInt())
        .commit()
    writer.drawRect(116f, 112f, 146f, 122f)
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

    // MODIFIER_COLLAPSIBLE_PRIORITY real-effect proof: a startCollapsibleRow declaring an explicit
    // width(30f) — real Compose's own available-width constraint, known here only because it's
    // explicit — with 3 Box children each drawing an identical raw (72, 52)-(87, 62) 15x10 rect
    // (deliberately overlapping, same proof-of-real-arrangement pattern as the plain Row tests):
    // the first carries no collapsiblePriority modifier (Float.MAX_VALUE default — never
    // collapses), the second collapsiblePriority(0, 2f), the third collapsiblePriority(0, 1f) —
    // real CollapsibleRowLayout visits highest-priority-first (source-confirmed via javap), so at
    // 15+15=30 the first two exactly fill the declared width and the third (lowest priority) is
    // the one that collapses. A render showing only two 15-wide rects packed with no gap where a
    // third would have been (not three overlapping rects, and not a gap left for the hidden one)
    // proves this now really collapses by priority instead of staying byte-consumed only.
    writer.startCollapsibleRow(RecordingModifier().width(30f), 0, 0)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF00838F.toInt()).commit()
    writer.drawRect(72f, 52f, 87f, 62f)
    writer.endBox()
    writer.startBox(RecordingModifier().collapsiblePriority(0, 2f), 0, 0)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawRect(72f, 52f, 87f, 62f)
    writer.endBox()
    writer.startBox(RecordingModifier().collapsiblePriority(0, 1f), 0, 0)
    writer.getRcPaint().setColor(0xFFEF6C00.toInt()).commit()
    writer.drawRect(72f, 52f, 87f, 62f)
    writer.endBox()
    writer.endCollapsibleRow()

    writer.startFlow(RecordingModifier(), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00838F.toInt())
        .commit()
    writer.drawRect(2f, 41f, 17f, 51f)
    writer.endFlow()

    // LAYOUT_FLOW real wrapping proof: four Box children, each drawing its 8x8 rect at the
    // *identical* raw (65, 90)-(73, 98) document coordinates — deliberately overlapping, same
    // proof-of-real-arrangement pattern as the plain Column/Row tests — wrapped in
    // startFlow(spacedBy(3f), maxItemsInMainAxis=2). FlowLayout extends RowLayout (source-
    // confirmed via javap): with 4 children capped at 2 per line, a render showing two rows of
    // two side-by-side children each (not one packed row of four, and not four still-overlapping
    // squares) can only be this renderer's own real Flow wrapping at work.
    writer.startFlow(RecordingModifier().spacedBy(3f), 0, 0, 2, Int.MAX_VALUE)
    val flowColors = intArrayOf(0xFFD84315.toInt(), 0xFF6A1B9A.toInt(), 0xFF2E7D32.toInt(), 0xFF1565C0.toInt())
    for (color in flowColors) {
        writer.startBox(RecordingModifier(), 0, 0)
        writer.getRcPaint().setColor(color).commit()
        writer.drawRect(65f, 90f, 73f, 98f)
        writer.endBox()
    }
    writer.endFlow()

    // LAYOUT_FLOW maxLinesInCrossAxis real-effect proof: four Box children again, each drawing an
    // identical raw (170, 170)-(176, 176) rect, wrapped in startFlow(spacedBy(3f),
    // maxItemsInMainAxis=2, maxLinesInCrossAxis=1) this time — only the first row (2 children)
    // should be visible; the second row's 2 children should render nothing at all (javap-confirmed
    // real FlowLayout marks them Component.Visibility.GONE the moment a 3rd row would start),
    // proving this parser now really hides the overflow instead of wrapping it into an unbounded
    // number of visible rows.
    writer.startFlow(RecordingModifier().spacedBy(3f), 0, 0, 2, 1)
    val flowMaxLinesColors = intArrayOf(0xFFEF6C00.toInt(), 0xFF00695C.toInt(), 0xFFAD1457.toInt(), 0xFF283593.toInt())
    for (color in flowMaxLinesColors) {
        writer.startBox(RecordingModifier(), 0, 0)
        writer.getRcPaint().setColor(color).commit()
        writer.drawRect(170f, 170f, 176f, 176f)
        writer.endBox()
    }
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

    // LAYOUT_STATE real-effect proof: a startStateLayout wrapping 3 startBox/endBox children
    // (each drawing an identical raw (110, 80)-(125, 90) rect, deliberately overlapping) —
    // real StateLayout defaults currentLayoutIndex to 0 and hides every other child
    // (source-confirmed via javap: inflate() calls hideLayoutsOtherThan(0) immediately, before any
    // runtime state-change event this parser has no live state to evaluate). A render showing only
    // the first (pink) child, with the second (indigo) and third (lime) entirely absent — not
    // three overlapping rects — proves stateIndex now really drives this default-state visibility
    // instead of staying byte-consumed only.
    writer.startStateLayout(RecordingModifier(), 0)
    val stateColors = intArrayOf(0xFFE91E63.toInt(), 0xFF3F51B5.toInt(), 0xFFCDDC39.toInt())
    for (color in stateColors) {
        writer.startBox(RecordingModifier(), 0, 0)
        writer.getRcPaint().setColor(color).commit()
        writer.drawRect(110f, 80f, 125f, 90f)
        writer.endBox()
    }
    writer.endStateLayout()

    // MODIFIER_WIDTH's real-effect proof for a WEIGHT mode: a startRow declaring an explicit
    // width(60f) — real Compose's own available-width constraint, known here only because it's
    // explicit — with two Box children: a 10-wide fixed one, and one carrying
    // then(WidthModifier(Type.WEIGHT, 1f)) whose own natural content is a small 8-wide placeholder
    // rect. Real Row/Column weight semantics: the weighted child gets the *remaining* space (here
    // 60-10=50, the only weight so it gets all of it) instead of its own natural size — this
    // parser approximates that (no true measure pass) by stretching the weighted child's own
    // already-positioned content via a real Scale wrap pivoted at its own leading edge, so its
    // 8-wide placeholder should render 50 wide (from x=10 to x=60) rather than staying 8 wide
    // (x=10 to x=18) — proving `WEIGHT` now gets a real proportional-space-distribution effect
    // instead of staying byte-consumed only.
    writer.startRow(RecordingModifier().width(60f), 0, 0)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF00695C.toInt()).commit()
    writer.drawRect(110f, 90f, 120f, 98f)
    writer.endBox()
    writer.startBox(RecordingModifier().then(WidthModifier(DimensionModifierOperation.Type.WEIGHT, 1f)), 0, 0)
    writer.getRcPaint().setColor(0xFFAD1457.toInt()).commit()
    writer.drawRect(120f, 90f, 128f, 98f)
    writer.endBox()
    writer.endRow()

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

    // LAYOUT_IMAGE: real-bytes hex-diff of image(modifier, 111, 222, 0.5f) confirmed the wire
    // order is [componentId][animationId][bitmapId=111][scaleType=222][alpha=0.5] — bitmapId
    // (this call's 2nd argument) precedes scaleType (its 3rd) — the reverse of what an earlier
    // pass over this opcode assumed. This leaf carries no position/size of its own, so a real
    // render only happens with an explicit width()/height() on the same modifier (see
    // OP_CONTAINER_END's imageBitmapId handling); alpha=0.8 (not 1.0) also exercises the
    // SaveLayerAlpha wrap that path takes when the image isn't fully opaque.
    val imageBitmap = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (py in 0 until 4) {
        for (px in 0 until 4) {
            imageBitmap.setRGB(px, py, if ((px + py) % 2 == 0) 0xFF00BFA5.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    val imageBitmapId = writer.storeBitmap(imageBitmap)
    writer.image(RecordingModifier().width(16f).height(16f), imageBitmapId, RemoteComposeWriter.IMAGE_SCALE_FIT, 0.8f)

    // LAYOUT_IMAGE real scaleType=SCALE_FIT/SCALE_CROP proof: a solid-green 8x4 bitmap (2:1
    // aspect ratio) drawn into a 16x16 square box two ways. FIT preserves aspect ratio and shrinks
    // to fit entirely inside the box — letterboxed to a 16-wide x 8-tall strip vertically centered
    // in the box, so the box's own top/bottom margins should show the red background behind it,
    // not green. CROP preserves aspect ratio but grows to cover the whole box — the scaled image
    // ends up wider than the box (clipped back to it, the same auto-clip real Compose's own
    // Image/Modifier.paint applies whenever contentScale overflows the layout box), so unlike FIT
    // it should show solid green corner-to-corner with no red margin visible anywhere.
    val wideBitmap = java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (py in 0 until 4) {
        for (px in 0 until 8) {
            wideBitmap.setRGB(px, py, 0xFF2E7D32.toInt())
        }
    }
    val wideBitmapId = writer.storeBitmap(wideBitmap)
    writer.startBox(RecordingModifier().offset(110f, 56f), 0, 0)
    writer.image(RecordingModifier().width(16f).height(16f), wideBitmapId, RemoteComposeWriter.IMAGE_SCALE_FIT, 1f)
    writer.endBox()
    writer.startBox(RecordingModifier().offset(130f, 56f), 0, 0)
    writer.image(RecordingModifier().width(16f).height(16f), wideBitmapId, RemoteComposeWriter.IMAGE_SCALE_CROP, 1f)
    writer.endBox()

    // DATA_FLOAT + resolveFloat: addFloatConstant(...) registers a real value in a float pool
    // then returns a NaN-tagged *reference* to it, not the literal value — passing that reference
    // straight into padding(...) (as real documents can) means the parser must resolve it back to
    // 6.0 through the float pool, or this child would decode a NaN inset and disappear/render
    // garbage. The teal child should still shift right by exactly 6, the same as a literal
    // padding(6f, 0f, 0f, 0f) would, proving the resolution actually happened.
    val paddingRef = writer.addFloatConstant(6f)
    writer.startBox(RecordingModifier().padding(paddingRef, 0f, 0f, 0f), 0, 0)
    writer.getRcPaint()
        .setColor(0xFF00897B.toInt())
        .commit()
    writer.drawRect(40f, 90f, 55f, 100f)
    writer.endBox()

    // DATA_INT/DATA_BOOLEAN/DATA_LONG: the same real-value-pool pattern as DATA_FLOAT/
    // COLOR_CONSTANT, registering real values this parser doesn't yet resolve against anywhere
    // (ints have no spare NaN-like bit pattern to tag a reference into, so DATA_INT is always a
    // literal; DATA_LONG's value is read via the real SDK's own readLongNanId(), hinting long
    // fields elsewhere may support a similar tagged reference, unconfirmed and not modeled here).
    writer.addInteger(7)
    writer.addBoolean(true)
    writer.addLong(123456789012L)

    writer.performHaptic(4)
    // THEME brackets the operations belonging to one mode: everything between a mode and the
    // next UNSPECIFIED is skipped when painting the other. A light-only marker, then back to
    // operations that belong to both.
    writer.setTheme(Rc.Theme.LIGHT)
    writer.getRcPaint().setColor(0xFFFFC107.toInt()).commit()
    writer.drawRect(96f, 2f, 104f, 6f)
    writer.setTheme(Rc.Theme.UNSPECIFIED)
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

    // MATRIX_SKEW: skewX/skewY are direct shear factors (android.graphics.Matrix.setSkew
    // convention), not an angle, unlike ROTATION_Z — a skewX=0.5 square should read as a
    // parallelogram leaning right, its top edge shifted +0.5*height from its bottom edge.
    writer.save()
    writer.skew(0.5f, 0f)
    writer.getRcPaint().setColor(0xFFD84315.toInt()).commit()
    writer.drawRect(2f, 130f, 17f, 145f)
    writer.restore()

    // Three Box children, each drawn at the *same* raw (22,130)-(32,140) rect — deliberately
    // overlapping in the document's own absolute coordinates, same proof-of-real-arrangement
    // pattern as the plain startColumn test below, but for startCollapsibleColumn: real
    // arrangement was previously only wired up for LAYOUT_COLUMN/LAYOUT_ROW, not this shape-
    // identical opcode pair, so a render showing these stacked without overlap now proves that
    // gap is closed.
    writer.startCollapsibleColumn(RecordingModifier().spacedBy(3f), 0, 0)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFFC2185B.toInt()).commit()
    writer.drawRect(22f, 130f, 32f, 140f)
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF7B1FA2.toInt()).commit()
    writer.drawRect(22f, 130f, 32f, 140f)
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF303F9F.toInt()).commit()
    writer.drawRect(22f, 130f, 32f, 140f)
    writer.endBox()
    writer.endCollapsibleColumn()

    // DRAW_TEXT_RUN: unlike DRAW_TEXT_ON_CIRCLE, the real DrawText.paint() *is* implemented
    // (delegates to PaintContext.drawTextRun(...)), so this is real byte-coverage *and* a real
    // semantic effect — only characters [6, 11) of "Hello World" ("World") should render, not
    // the whole string, proving this parser actually applies the substring range rather than
    // just byte-consuming it.
    writer.getRcPaint().setColor(0xFF212121.toInt()).commit()
    writer.drawTextRun("Hello World", 6, 11, 0, 11, 36f, 136f, false)

    // DRAW_TEXT_ON_PATH: unlike DRAW_TEXT_ON_CIRCLE, the real DrawText.paint() *is* implemented
    // (delegates to PaintContext.drawTextOnPath(...)). A real-bytes hex-diff of
    // drawTextOnPath(textId, pathId, 111f, 222f) decoded to floats (222.0, 111.0) in that wire
    // order — apply()'s own bytecode writes its 4th argument (vOffset) before its 3rd (hOffset),
    // the reverse of the call's own argument order, so this parser reads vOffset first.
    val textOnPathPath = RemotePath()
    textOnPathPath.moveTo(105f, 195f)
    textOnPathPath.lineTo(125f, 195f)
    val textOnPathPathId = writer.addPathData(textOnPathPath)
    writer.getRcPaint().setColor(0xFF00695C.toInt()).commit()
    writer.drawTextOnPath(writer.textCreateId("Path"), textOnPathPathId, 0f, -4f)

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

    // DRAW_TEXT_ON_CIRCLE: the real DrawTextOnCircle.paint() itself throws
    // UnsupportedOperationException in this SDK version, so there is no real curved-text
    // algorithm to reverse-engineer — this parser decodes the full wire format (real byte
    // coverage) but only renders a straight-line approximation anchored at the point
    // (centerX + radius*cos(startAngle), centerY + radius*sin(startAngle)) = (100, 185 - 10) =
    // (100, 175), i.e. straight up from a center 10 units below it. Kept well clear of the
    // canvas's bottom edge (unlike an earlier, since-moved placement at y=183): platform default
    // font metrics differ enough between Desktop/Android/iOS that text anchored close to a hard
    // clip edge shows a real, expected amount of clipped-glyph difference across platforms, which
    // isn't this opcode's own byte-coverage or anchor-math concern.
    writer.getRcPaint().setColor(0xFF212121.toInt()).commit()
    val circleTextId = writer.textCreateId("Curved")
    writer.drawTextOnCircle(
        circleTextId, 100f, 185f, 10f, 270f, 0f,
        DrawTextOnCircle.Alignment.CENTER, DrawTextOnCircle.Placement.OUTSIDE,
    )

    // MODIFIER_BACKGROUND shapeType real-effect proof: a wide, short (40x20) box carrying a
    // shapeType=1 (CIRCLE) background — the real BackgroundModifierOperation.paint() draws an
    // oval inscribed in the box instead of a rect, so this parser should render it as a rounded
    // ellipse (corners visibly cut off) rather than a sharp-cornered rectangle — the same
    // DrawOval-vs-DrawRect choice MODIFIER_BORDER's shapeType already gets for its stroke. The
    // box's own content is two tiny 1x1 markers at opposite corners (not a rect that fills the
    // full 40x20 box, which would just paint over the inferred background and hide its shape
    // entirely): contentBounds() still spans the full (150,110)-(190,130) box from those two
    // markers alone, but the ellipse itself stays visible everywhere else inside it.
    writer.startBox(RecordingModifier().then(CircleBackgroundElement(0.6f, 0.2f, 0f, 1f)), 0, 0)
    writer.getRcPaint().setColor(0xFFFFFFFF.toInt()).commit()
    writer.drawRect(150f, 110f, 151f, 111f)
    writer.drawRect(189f, 129f, 190f, 130f)
    writer.endBox()

    // LAYOUT_IMAGE real scaleType=SCALE_NONE proof: the same 8x4 bitmap into a 16x16 box — NONE
    // never scales at all, just centers the bitmap at its own natural size, so the visible green
    // should be a narrow 8x4 patch in the middle (corners *and* top/bottom margins showing the
    // background) rather than FIT's wider 16x8 letterboxed strip.
    writer.startBox(RecordingModifier().offset(12f, 61f), 0, 0)
    writer.image(RecordingModifier().width(16f).height(16f), wideBitmapId, RemoteComposeWriter.IMAGE_SCALE_NONE, 1f)
    writer.endBox()

    // LAYOUT_IMAGE real scaleType=SCALE_INSIDE proof: the same 8x4 bitmap into a 4x8 box (smaller
    // than the bitmap's own 8-wide natural size) — INSIDE shrinks like FIT whenever natural size
    // doesn't already fit (unlike NONE, which would just overflow/clip without shrinking): here
    // that means a 4-wide x2-tall strip vertically centered in the box, not a 4x8 rect stretched
    // to fill it (the old stretch-to-fill default) or a 4x4 square (CROP's math).
    writer.startBox(RecordingModifier().offset(2f, 90f), 0, 0)
    writer.image(RecordingModifier().width(4f).height(8f), wideBitmapId, RemoteComposeWriter.IMAGE_SCALE_INSIDE, 1f)
    writer.endBox()

    // LAYOUT_BOX real horizontalPositioning/verticalPositioning=CENTER,CENTER proof: a startBox
    // declaring CENTER/CENTER alignment (2, 2) wrapping two startBox/endBox children that both
    // draw their own rect at the *identical* raw (0, 0)-(20, 20)/(0, 0)-(8, 8) top-left-anchored
    // position (the small one deliberately drawn at the same corner as the big one, not
    // pre-centered by the document itself) — real BoxLayout positions every child independently
    // within the box's own bounds (here, the big child's own 20x20 union), so the small 8x8 child
    // should end up centered at (6, 6)-(14, 6+8) relative to the box, not still tucked in its
    // documented top-left corner, proving horizontalPositioning/verticalPositioning now really
    // align each child instead of staying byte-consumed only.
    writer.startBox(RecordingModifier().offset(2f, 150f), 2, 2)
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFF5D4037.toInt()).commit()
    writer.drawRect(0f, 0f, 20f, 20f)
    writer.endBox()
    writer.startBox(RecordingModifier(), 0, 0)
    writer.getRcPaint().setColor(0xFFFFB300.toInt()).commit()
    writer.drawRect(0f, 0f, 8f, 8f)
    writer.endBox()
    writer.endBox()

    // DRAW_TEXT_ANCHORED real panX proof: the same "Centered" string drawn twice at the identical
    // x=100 anchor, once with panX=-1 (left-align: x should be the text's own left edge) and once
    // with panX=1 (right-align: x should be the text's own right edge) — real
    // DrawTextAnchored.getHorizontalOffset() (source-confirmed via javap) means these two renders
    // should differ by roughly the text's own full measured width, not sit at the identical
    // position the old (x always literal left edge, panX byte-consumed) behavior would have given
    // both, proving panX now really anchors text instead of staying byte-consumed only.
    writer.getRcPaint().setColor(0xFF1565C0.toInt()).commit()
    writer.drawTextAnchored("Centered", 100f, 160f, -1f, 0f, 0)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawTextAnchored("Centered", 100f, 180f, 1f, 0f, 0)

    // DRAW_TEXT_ANCHORED real panY proof: the same "Tall" string drawn twice at the identical
    // y=100 anchor, once with panY=-1 (top-align: y should be the text's own top edge) and once
    // with panY=1 (bottom-align: y should be the text's own bottom edge) — this renderer's own
    // topLeft-based drawText means these two renders should differ by roughly the text's own
    // full measured height, not sit at the identical position the old (y always literal top
    // edge, panY byte-consumed) behavior would have given both, proving panY now really anchors
    // text vertically instead of staying byte-consumed only. Kept well within the document's own
    // declared 200x200 bounds (unlike x/y, which can safely overlap earlier content since this is
    // drawn last) so neither render risks being clipped off the rendered canvas.
    writer.getRcPaint().setColor(0xFF2E7D32.toInt()).commit()
    writer.drawTextAnchored("Tall", 140f, 100f, -1f, -1f, 0)
    writer.getRcPaint().setColor(0xFFC62828.toInt()).commit()
    writer.drawTextAnchored("Tall", 170f, 100f, -1f, 1f, 0)

    // LAYOUT_TEXT real textAlign proof: the same "Hi" textId drawn via two separate
    // startTextComponent/endTextComponent leaves, each declaring the identical width(60f) box —
    // once with TEXT_ALIGN_LEFT(1) (should render at this leaf's own left edge, unshifted) and
    // once with TEXT_ALIGN_CENTER(3) (should shift right by half the declared box's own leftover
    // space) — real TextLayout.updateVariables()'s own packed textAlign field (source-confirmed
    // via javap) means these two should differ by roughly half the declared width minus the
    // text's own estimated width, not sit at the identical position the old (LAYOUT_TEXT
    // completely unhandled — this whole opcode would have thrown a parse exception) behavior
    // would have given neither, proving LAYOUT_TEXT is both really parsed *and* textAlign
    // really anchors it within a declared width instead of staying unsupported entirely.
    val helloTextId = writer.addText("Hi")
    writer.startTextComponent(
        RecordingModifier().width(60f).offset(20f, 150f),
        helloTextId, 0xFF00695C.toInt(), 16f, 0, 400f, "",
        0.toShort(), 1.toShort(), 1, 1,
    )
    writer.endTextComponent()
    writer.startTextComponent(
        RecordingModifier().width(60f).offset(100f, 150f),
        helloTextId, 0xFFAD1457.toInt(), 16f, 0, 400f, "",
        0.toShort(), 3.toShort(), 1, 1,
    )
    writer.endTextComponent()

    // DRAW_BITMAP_SCALED real source-sub-rect + SCALE_FIT proof: the same solid-green wideBitmap
    // (8x4, used above for LAYOUT_IMAGE's own FIT/CROP proof) sampled from only its own top *half*
    // (srcRect (0,0,8,2), an 8x2 region — 4:1 aspect, unlike the full bitmap's own 2:1) into a
    // 20x20 square dst box with SCALE_FIT — real ImageScaling math driven by this *declared*
    // source rect's own aspect (not the bitmap's full natural 8x4 size) should letterbox to a
    // 20-wide x 5-tall strip vertically centered in the box (unlike the full-bitmap 2:1 aspect,
    // which would letterbox to 20x10 — twice as tall), proving DRAW_BITMAP_SCALED's own source
    // sub-rect really drives the scaling math instead of just being byte-consumed, and that this
    // opcode (previously completely unhandled — would have thrown a parse exception) is now real.
    writer.drawScaledBitmap(
        wideBitmapId, 0f, 0f, 8f, 2f, 2f, 2f, 22f, 22f,
        RemoteComposeWriter.IMAGE_SCALE_FIT, 1f, "scaled",
    )

    // LOOP_START real static-unrolling proof: a single 6x6 rect authored *once* inside
    // startLoop(0, from=0f, step=1f, until=3f)/endLoop(), nested in a startRow so real Row
    // packing (already proven elsewhere in this document) arranges each unrolled copy as its own
    // independent sibling. Real LoopOperation.paint() (source-confirmed via javap) re-apply()s
    // this one authored body in a live for(i=from; i<until; i+=step) loop — since every bound
    // here is a literal (not a NaN-tagged variable reference), this parser can compute the same
    // iteration count (3) without any live expression evaluation, and should render three 6x6
    // rects side by side (x=0-6, 6-12, 12-18 within the row), not the single rect the old
    // (LOOP_START completely unhandled — would have thrown a parse exception) behavior could
    // never have rendered at all.
    writer.startRow(RecordingModifier().offset(2f, 170f), 0, 0)
    writer.startLoop(0, 0f, 1f, 3f)
    writer.getRcPaint().setColor(0xFF6D4C41.toInt()).commit()
    writer.drawRect(0f, 0f, 6f, 6f)
    writer.endLoop()
    writer.endRow()

    // TEXT_SUBTEXT real substring proof: writer.textSubtext(srcId, start=6f, len=-1f) on a
    // registered "Hello World" string should compute "World" (chars [6, end) — len=-1f means
    // "rest of the string", real TextSubtext.apply() source-confirmed via javap) and register it
    // as a *new* text-pool entry, drawn here via drawTextAnchored's own int-textId overload — a
    // real substring effect (not just byte-consumed), proven by comparing against the full
    // "Hello World" string drawn right below it: the computed substring should visibly read only
    // "World", not the full source string the old (TEXT_SUBTEXT completely unhandled — would have
    // thrown a parse exception) behavior could never have computed at all.
    val subtextSrcId = writer.addText("Hello World")
    val subtextId = writer.textSubtext(subtextSrcId, 6f, -1f)
    writer.getRcPaint().setColor(0xFF00838F.toInt()).commit()
    writer.drawTextAnchored(subtextId, 155f, 196f, -1f, 1f, 0)
    writer.getRcPaint().setColor(0xFF5D4037.toInt()).commit()
    writer.drawTextAnchored(subtextSrcId, 60f, 196f, -1f, 1f, 0)

    // TEXT_TRANSFORM real TEXT_CAPITALIZE proof: writer.textTransform(srcId, 0f, -1f,
    // TEXT_CAPITALIZE) on a registered "hello world" string should compute "Hello World" (both
    // words' own first letter capitalized, every other char untouched — real
    // TextTransform.apply()'s own capitalizeWords() bytecode, ported verbatim), registered as a
    // *new* text-pool entry and drawn here via drawTextAnchored's own int-textId overload — a real
    // transform effect (not just byte-consumed), proven by comparing against the untransformed
    // "hello world" drawn right below it.
    val transformSrcId = writer.addText("hello world")
    val transformId = writer.textTransform(transformSrcId, 0f, -1f, 4)
    writer.getRcPaint().setColor(0xFF4527A0.toInt()).commit()
    writer.drawTextAnchored(transformId, 2f, 12f, -1f, -1f, 0)
    writer.getRcPaint().setColor(0xFF00695C.toInt()).commit()
    writer.drawTextAnchored(transformSrcId, 2f, 30f, -1f, -1f, 0)

    // PATH_CREATE/PATH_ADD real incremental-path proof: a triangle built via
    // pathCreate(2f, 150f)/pathAppendLineTo(id, 18f, 150f)/pathAppendLineTo(id, 10f, 165f)/
    // pathAppendClose(id) — the *incremental*, multi-opcode path-building protocol (distinct from
    // the already-handled single-opcode DATA_PATH/drawPath test elsewhere in this codebase),
    // drawn via the same public drawPath(id) real DATA_PATH-built paths already use. Real
    // RemotePathBase array shape (2-word padding bug included) reused verbatim from the existing
    // decodePathArray helper — a real triangle shape (not the single MoveTo point the old
    // (PATH_CREATE/PATH_ADD completely unhandled — would have thrown a parse exception) behavior
    // could never have rendered at all) proves both opcodes now really build up this shared path
    // pool incrementally.
    val trianglePathId = writer.pathCreate(2f, 150f)
    writer.pathAppendLineTo(trianglePathId, 18f, 150f)
    writer.pathAppendLineTo(trianglePathId, 10f, 165f)
    writer.pathAppendClose(trianglePathId)
    writer.getRcPaint().setColor(0xFFC2185B.toInt()).commit()
    writer.drawPath(trianglePathId)

    // PATH_TWEEN real linear-interpolation proof: two structurally-identical triangles (same
    // MoveTo/LineTo/LineTo/Close sequence), one at y=[2,20] and one shifted straight down by 30 to
    // y=[32,50] — writer.pathTween(pathA, pathB, 0.5f) should compute a *new* path exactly halfway
    // between them (y=[17,35], real PathTween.paint() delegating to the same per-coordinate lerp
    // real android.graphics.Path.interpolate() itself performs), confirmed via the parsed opcode
    // dump against both original triangles drawn alongside it in different colors for comparison —
    // not the unresolved reference the old (PATH_TWEEN completely unhandled — would have thrown a
    // parse exception) behavior could never have computed at all.
    val tweenPathA = writer.pathCreate(140f, 2f)
    writer.pathAppendLineTo(tweenPathA, 158f, 2f)
    writer.pathAppendLineTo(tweenPathA, 149f, 20f)
    writer.pathAppendClose(tweenPathA)
    val tweenPathB = writer.pathCreate(140f, 32f)
    writer.pathAppendLineTo(tweenPathB, 158f, 32f)
    writer.pathAppendLineTo(tweenPathB, 149f, 50f)
    writer.pathAppendClose(tweenPathB)
    val tweenedPathId = writer.pathTween(tweenPathA, tweenPathB, 0.5f)
    writer.getRcPaint().setColor(0xFF9E9E9E.toInt()).commit()
    writer.drawPath(tweenPathA)
    writer.getRcPaint().setColor(0xFF616161.toInt()).commit()
    writer.drawPath(tweenPathB)
    writer.getRcPaint().setColor(0xFFFFC107.toInt()).commit()
    writer.drawPath(tweenedPathId)

    // DEBUG_MESSAGE real byte-alignment proof: real DebugMessage has no paint()/rendering effect
    // at all (a pure developer-tools log message, VariableSupport only, source-confirmed via
    // javap), so there's nothing to render — the only real thing to verify is that this parser
    // consumes exactly its own real [textId][floatValue][flags] fields and stays correctly
    // aligned for whatever follows, proven by a normal drawRect landing at its own exact
    // documented position right after it (not shifted by a byte-misaligned read).
    writer.addDebugMessage("debug", 1f, 0)
    writer.getRcPaint().setColor(0xFF1A237E.toInt()).commit()
    writer.drawRect(160f, 150f, 176f, 166f)

    // MATRIX_FROM_PATH real position-along-path proof: a horizontal 40-wide line, positioned at
    // its own fraction=0.5 midpoint with POSITION_MATRIX_FLAG only (no rotation) — a small rect
    // authored at this leaf's own local (0,0)-(6,6) origin should land at exactly the line's own
    // midpoint, not the document-authored position the old (MATRIX_FROM_PATH completely
    // unhandled — would have thrown a parse exception) behavior could never have computed at all.
    writer.startBox(RecordingModifier().offset(60f, 70f), 0, 0)
    val hLinePathId = writer.pathCreate(0f, 0f)
    writer.pathAppendLineTo(hLinePathId, 40f, 0f)
    writer.matrixFromPath(hLinePathId, 0.5f, 0f, 1) // POSITION_MATRIX_FLAG only
    writer.getRcPaint().setColor(0xFF00838F.toInt()).commit()
    writer.drawRect(0f, 0f, 6f, 6f)
    writer.endBox()

    // MATRIX_FROM_PATH real TANGENT_MATRIX_FLAG proof: a 45-degree diagonal line — a thin
    // horizontal bar authored at this leaf's own local origin should render *rotated* to follow
    // the line's own 45-degree tangent direction (a diamond-ish diagonal shape), not the flat
    // horizontal bar the position-only proof above renders, when both flags are set.
    writer.startBox(RecordingModifier().offset(60f, 90f), 0, 0)
    val diagLinePathId = writer.pathCreate(0f, 0f)
    writer.pathAppendLineTo(diagLinePathId, 28f, 28f)
    writer.matrixFromPath(diagLinePathId, 0.5f, 0f, 3) // POSITION_MATRIX_FLAG | TANGENT_MATRIX_FLAG
    writer.getRcPaint().setColor(0xFFD84315.toInt()).commit()
    writer.drawRect(-8f, -1f, 8f, 1f)
    writer.endBox()

    // DRAW_TWEEN_PATH real tween+trim proof: two structurally-identical 20x20 squares (one at
    // y=[0, 20], one shifted straight down by 30 to y=[30, 50]) — drawTweenPath(squareA, squareB,
    // 0.5f, 0f, 0.5f) should first tween them to a square at y=[15, 35] (the same real
    // per-coordinate lerp PATH_TWEEN already proved), then real-trim to only the first *half* of
    // that square's own 80-long perimeter (matching Android's own PathMeasure.getSegment()) — the
    // first two sides, an "L" shape from (0,15) to (20,15) to (20,35) that fills (this parser's
    // own drawTweenPath, like drawPath, always fills) as a right triangle rather than the full
    // square, proving both this opcode's tween *and* its own real arc-length trim, not the
    // untrimmed full square the old (DRAW_TWEEN_PATH completely unhandled — would have thrown a
    // parse exception) behavior could never have computed at all.
    writer.startBox(RecordingModifier().offset(2f, 60f), 0, 0)
    val squarePathA = writer.pathCreate(0f, 0f)
    writer.pathAppendLineTo(squarePathA, 20f, 0f)
    writer.pathAppendLineTo(squarePathA, 20f, 20f)
    writer.pathAppendLineTo(squarePathA, 0f, 20f)
    writer.pathAppendClose(squarePathA)
    val squarePathB = writer.pathCreate(0f, 30f)
    writer.pathAppendLineTo(squarePathB, 20f, 30f)
    writer.pathAppendLineTo(squarePathB, 20f, 50f)
    writer.pathAppendLineTo(squarePathB, 0f, 50f)
    writer.pathAppendClose(squarePathB)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawTweenPath(squarePathA, squarePathB, 0.5f, 0f, 0.5f)
    writer.endBox()

    // PATH_COMBINE real OP_INTERSECT proof: two overlapping 20x20 squares — one at (0,0)-(20,20),
    // one at (10,10)-(30,30) — pathCombine(squareC, squareD, OP_INTERSECT) should compute exactly
    // their real geometric overlap, a 10x10 square at (10,10)-(20,20) (hand-verifiable min/max
    // arithmetic), via this parser's own real Sutherland-Hodgman polygon-clipping algorithm —
    // confirmed via the parsed opcode dump, not the unresolved reference the old (PATH_COMBINE
    // completely unhandled — would have thrown a parse exception) behavior could never have
    // computed at all.
    writer.startBox(RecordingModifier().offset(30f, 60f), 0, 0)
    val squarePathC = writer.pathCreate(0f, 0f)
    writer.pathAppendLineTo(squarePathC, 20f, 0f)
    writer.pathAppendLineTo(squarePathC, 20f, 20f)
    writer.pathAppendLineTo(squarePathC, 0f, 20f)
    writer.pathAppendClose(squarePathC)
    val squarePathD = writer.pathCreate(10f, 10f)
    writer.pathAppendLineTo(squarePathD, 30f, 10f)
    writer.pathAppendLineTo(squarePathD, 30f, 30f)
    writer.pathAppendLineTo(squarePathD, 10f, 30f)
    writer.pathAppendClose(squarePathD)
    val intersectPathId = writer.pathCombine(squarePathC, squarePathD, 1.toByte()) // OP_INTERSECT
    writer.getRcPaint().setColor(0xFF2E7D32.toInt()).commit()
    writer.drawPath(intersectPathId)
    writer.endBox()

    // CANVAS_OPERATIONS real pass-through-container proof: real CanvasOperations writes no fields
    // at all (source-confirmed via javap) — just a plain container whose own real paint() applies
    // its children directly — so a rect drawn inside startCanvasOperations()/
    // endCanvasOperations() should render at its own exact documented position, not the parse
    // exception the old (CANVAS_OPERATIONS completely unhandled) behavior would have thrown.
    writer.startCanvasOperations()
    writer.getRcPaint().setColor(0xFFEF6C00.toInt()).commit()
    writer.drawRect(100f, 185f, 116f, 199f)
    writer.endCanvasOperations()

    // SKIP real conditional-forward-compatibility proof: a SKIP_IF_API_GREATER_THAN(2) block with
    // value=0 wraps a bright red rect — since this parser reports its own library API level as
    // Int.MAX_VALUE (see OP_SKIP's own KDoc), MAX_VALUE > 0 is true, so real Skip.read() itself
    // jumps the reader clean past this whole span unparsed, and that red rect should never render
    // at all (not the parse exception the old completely-unhandled behavior would have thrown for
    // *any* SKIP block, skipped or not). A second SKIP_IF_API_LESS_THAN(1) block with
    // value=Int.MAX_VALUE wraps a green rect right after — MAX_VALUE < MAX_VALUE is false, so this
    // one should render normally, proving the reader stays correctly aligned whether or not the
    // preceding block was actually skipped.
    val skip1 = writer.beginSkip(2.toShort(), 0) // SKIP_IF_API_GREATER_THAN, value=0 -> skip
    writer.getRcPaint().setColor(0xFFD50000.toInt()).commit()
    writer.drawRect(2f, 185f, 30f, 199f)
    writer.endSkip(skip1)
    val skip2 = writer.beginSkip(1.toShort(), Int.MAX_VALUE) // SKIP_IF_API_LESS_THAN -> no skip
    writer.getRcPaint().setColor(0xFF388E3C.toInt()).commit()
    writer.drawRect(35f, 185f, 63f, 199f)
    writer.endSkip(skip2)

    // REM real byte-alignment proof: real Rem has no paint()/apply() at all (a pure source
    // comment, VariableSupport not even implemented), so there's nothing to render — the only
    // real thing to verify is that this parser consumes exactly its own real
    // [length][UTF8 bytes] and stays correctly aligned for whatever follows, proven by a normal
    // drawRect landing at its own exact documented position right after it.
    writer.rem("this is a comment, not a real UI opcode")
    writer.getRcPaint().setColor(0xFF00695C.toInt()).commit()
    writer.drawRect(66f, 185f, 94f, 199f)

    // TEXT_LENGTH real computed-value proof: writer.textLength(srcId) on a registered "Hello"
    // string (5 chars) returns a NaN-tagged float directly usable as any other float field
    // elsewhere — used here as a width() value on a box wrapping an oversized (30x10) rect clipped
    // to its own bounds. Real TextLength.apply() (source-confirmed via javap) computes the real
    // string length and loads it into the exact same value pool this parser's own resolveFloat
    // already resolves NaN-tagged fields against, so this box's own real declared width should be
    // exactly 5 (not the unresolved NaN — or the parse exception the old completely-unhandled
    // behavior would have thrown for the whole document) — only a 5-wide sliver of the oversized
    // rect should be visible, not the full 30.
    val lengthSrcId = writer.addText("Hello")
    val lengthNan = writer.textLength(lengthSrcId)
    writer.startBox(RecordingModifier().offset(96f, 185f).width(lengthNan).height(10f).clip(RectShape(0f, 0f, 30f, 10f)), 0, 0)
    writer.getRcPaint().setColor(0xFF6D4C41.toInt()).commit()
    writer.drawRect(0f, 0f, 30f, 10f)
    writer.endBox()

    // ID_LIST/TEXT_LOOKUP real indexed-lookup proof: three registered strings ("Alpha", "Beta",
    // "Gamma") collected via writer.addList(intArrayOf(...)) (previously a completely unhandled
    // opcode) into a real ID_LIST, then writer.textLookup(1f, thatList) (also previously
    // completely unhandled) should resolve index 1 — real TextLookup.apply() (source-confirmed
    // via javap) looks up the id at that index within the real collection, then that id's own
    // real string — computing "Beta", not "Alpha"/"Gamma"/an unresolved reference, drawn here to
    // prove it directly.
    val lookupAlphaId = writer.addText("Alpha")
    val lookupBetaId = writer.addText("Beta")
    val lookupGammaId = writer.addText("Gamma")
    val lookupListNan = writer.addList(intArrayOf(lookupAlphaId, lookupBetaId, lookupGammaId))
    val lookedUpTextId = writer.textLookup(lookupListNan, 1f)
    writer.getRcPaint().setColor(0xFF4527A0.toInt()).commit()
    writer.drawTextAnchored(lookedUpTextId, 130f, 170f, -1f, -1f, 0)

    // OP_TEXT_LOOKUP_INT (opcode 153) — previously completely unhandled. Same real ID_LIST
    // (lookupListNan: Alpha/Beta/Gamma) as the TEXT_LOOKUP test above, but the index here comes
    // from a real registered int (writer.addInteger(2).toInt() recovers the plain int-pool id
    // from the "long NaN-tag" 2^32-offset return value), looked up via
    // RemoteContext.getInteger(mIndex) unconditionally at apply() time (source-confirmed via
    // javap on TextLookupInt, unlike TextLookup's NaN-conditional float index) — resolving index
    // 2 should draw "Gamma", not "Alpha"/"Beta"/an unresolved reference.
    val lookupIndexRefId = writer.addInteger(2).toInt()
    val lookedUpIntTextId = writer.textLookup(lookupListNan, lookupIndexRefId)
    writer.getRcPaint().setColor(0xFF00695C.toInt()).commit()
    writer.drawTextAnchored(lookedUpIntTextId, 130f, 190f, -1f, -1f, 0)

    // OP_TEXT_MERGE (opcode 136) — previously completely unhandled. Real TextMerge.apply()
    // (source-confirmed via javap) does a plain getText(srcId1) + getText(srcId2) concatenation
    // (no separator) and loadText()s the result into a newly allocated text-pool slot the real
    // writer.textMerge(srcId1, srcId2): Int itself returns — "Merged" + "Text" should compute
    // "MergedText" exactly, not "Merged"/"Text" individually or an unresolved reference.
    val mergeLeftId = writer.addText("Merged")
    val mergeRightId = writer.addText("Text")
    val mergedTextId = writer.textMerge(mergeLeftId, mergeRightId)
    writer.getRcPaint().setColor(0xFFD84315.toInt()).commit()
    writer.drawTextAnchored(mergedTextId, 5f, 8f, -1f, -1f, 0)

    // OP_COLOR_EXPRESSIONS (opcode 134) — previously completely unhandled. Same real colorPool
    // consumer path as the dynamicBorder test above (MODIFIER_BORDER's colorRefFlag == 2), but the
    // color id now comes from a real computed color expression instead of a literal DATA_COLOR:
    // mode 0 (COLOR_COLOR_INTERPOLATE) interpolates pure red -> pure blue at tween=0.5 — real
    // Utils.interpolateColor() does a gamma-2.2-corrected lerp (not naive linear RGB), so the
    // real midpoint is a brighter, more saturated purple than a naive 50/50 average would give —
    // and mode 4 (HSV_MODE) converts hue=1/3 (green), full saturation/value into RGB, which should
    // compute pure green (0xFF00FF00) exactly.
    val interpolatedColorId = writer.addColorExpression(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0.5f)
    writer.startBox(RecordingModifier().dynamicBorder(2f, 4f, interpolatedColorId, 0), 0, 0)
    writer.getRcPaint().setColor(0xFF37474F.toInt()).commit()
    writer.drawRect(143f, 2f, 158f, 12f)
    writer.endBox()

    val hsvColorId = writer.addColorExpression(1f / 3f, 1f, 1f)
    writer.startBox(RecordingModifier().dynamicBorder(2f, 4f, hsvColorId, 0), 0, 0)
    writer.getRcPaint().setColor(0xFF37474F.toInt()).commit()
    writer.drawRect(163f, 2f, 178f, 12f)
    writer.endBox()

    // OP_ID_LOOKUP (opcode 192) — previously completely unhandled. Real IdLookup.apply()
    // (source-confirmed via javap) does getCollectionsAccess().getId(dataSetId, index) (the id at
    // a literal index within an ID_LIST) then loadInteger(intId, thatId) — a plain intPool store,
    // despite the real class's misleadingly named mTextId field. This parser has no other real
    // consumer that treats an arbitrary retrieved id as meaningful on its own, so its real effect
    // is only observable by chaining into TEXT_LOOKUP_INT's own intPool-sourced index: an ID_LIST
    // of literal index values [0, 1, 2], ID_LOOKUP fetches the literal value "2" at index 2 into a
    // fresh intPool slot, then TEXT_LOOKUP_INT uses that same slot as its own index — resolving
    // the Alpha/Beta/Gamma ID_LIST's index 2, "Gamma", exactly as the direct TEXT_LOOKUP_INT test
    // above already proved, but this time via a real value computed at parse time rather than a
    // literal registered int.
    val indexValuesListNan = writer.addList(intArrayOf(0, 1, 2))
    val idLookupIntRefId = writer.idLookup(indexValuesListNan, 2f)
    val idLookupTextId = writer.textLookup(lookupListNan, idLookupIntRefId)
    writer.getRcPaint().setColor(0xFF6A1B9A.toInt()).commit()
    writer.drawTextAnchored(idLookupTextId, 5f, 45f, -1f, -1f, 0)

    // OP_INTEGER_EXPRESSION (opcode 144) — previously completely unhandled. Real
    // IntegerExpressionEvaluator is a genuine RPN stack machine over ~24 operators; this exercises
    // a real MOD: "17 MOD 3" should compute 2 (not 17, 3, or an unresolved reference), then that
    // computed (not merely registered) intPool value feeds TEXT_LOOKUP_INT's own index the same
    // way ID_LOOKUP's chained test above does, resolving the Alpha/Beta/Gamma ID_LIST's index 2,
    // "Gamma".
    val exprResultId = writer.integerExpression(17L, 3L, Rc.IntegerExpression.L_MOD).toInt()
    val exprTextId = writer.textLookup(lookupListNan, exprResultId)
    writer.getRcPaint().setColor(0xFF00838F.toInt()).commit()
    writer.drawTextAnchored(exprTextId, 5f, 65f, -1f, -1f, 0)

    // OP_TEXT_FROM_FLOAT (opcode 135) — previously completely unhandled. Real TextFromFloat.apply()
    // (source-confirmed via javap) branches on flags; FULL_FORMAT (0x1000) does a plain real
    // Float.toString(value) — 42.75 should compute "42.75" exactly, not "42"/"0.75"/an unresolved
    // reference. digitsBefore/digitsAfter are irrelevant on this path but still required params.
    val floatTextId = writer.createTextFromFloat(42.75f, 0, 0, 0x1000)
    writer.getRcPaint().setColor(0xFFAD1457.toInt()).commit()
    writer.drawTextAnchored(floatTextId, 5f, 85f, -1f, -1f, 0)

    // OP_ID_MAP (opcode 145) + OP_DATA_MAP_LOOKUP (opcode 154) — both previously completely
    // unhandled. Real DataMapIds.apply() (source-confirmed via javap) just registers a named
    // lookup table; real DataMapLookup.apply() looks a key up in it and resolves the matching
    // entry's real value by its own real type. Looking up "banana" in a real two-entry
    // (apple/banana) string-valued map should compute "Yellow Banana" exactly (not "Red Apple", an
    // empty string, or an unresolved reference).
    val fruitMapId = writer.addDataMap(
        RemoteComposeWriter.map("apple", "Red Apple"),
        RemoteComposeWriter.map("banana", "Yellow Banana"),
    )
    val mapLookupTextId = writer.mapLookup(fruitMapId, "banana")
    writer.getRcPaint().setColor(0xFF33691E.toInt()).commit()
    writer.drawTextAnchored(mapLookupTextId, 5f, 105f, -1f, -1f, 0)

    val bytes = writer.encodeToByteArray()
    File("sample.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to sample.rc")
}

/**
 * A card that comes and goes: `MODIFIER_VISIBILITY` reads an int the buttons write, and the
 * `ANIMATION_SPEC` beside it says the card fades rather than blinking in and out.
 */
private fun buildVisibilitySample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(300, 220, "visibility", platform)

    fun text(value: Int, color: Int, size: Float, weight: Float = 400f, modifier: RecordingModifier = RecordingModifier()) {
        writer.startTextComponent(modifier, value, color, size, 0, weight, "", 1.toShort(), 1.toShort(), 1, 1)
        writer.endTextComponent()
    }

    // The value the buttons write and the card reads. 1 is VISIBLE, 0 is GONE.
    val shown = writer.addInteger(1).toInt()

    writer.startColumn(RecordingModifier().fillMaxSize().background(0xFFF5F5F7.toInt()).padding(16f).spacedBy(10f), 1, 4)

    text(writer.addText("Visibility"), 0xFF1B1B1F.toInt(), 20f, 700f)

    // The card itself: visible or gone by the int, and fading either way over 400ms. The enter
    // and exit animations are FADE_IN and FADE_OUT, which are ordinals 0 and 1 on the wire.
    writer.startBox(
        RecordingModifier()
            .fillMaxWidth().height(64f)
            .visibility(shown)
            .animationSpec(
                1, 0.4f, androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD,
                0.4f, androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD,
                androidx.compose.remote.core.operations.layout.animation.AnimationSpec.ANIMATION.FADE_IN,
                androidx.compose.remote.core.operations.layout.animation.AnimationSpec.ANIMATION.FADE_OUT,
            )
            .clip(RoundedRectShape(12f, 12f, 12f, 12f))
            .background(0xFF6750A4.toInt()),
        1, 2,
    )
    text(writer.addText("Now you see me"), 0xFFFFFFFF.toInt(), 15f, 700f)
    writer.endBox()

    // Something below it, so that the card going away is visible as a change in the layout too.
    text(writer.addText("and this sits underneath"), 0xFF5F5A66.toInt(), 12f)

    writer.startRow(RecordingModifier().fillMaxWidth().spacedBy(8f), 1, 2)
    for ((label, value) in listOf("Hide" to 0, "Show" to 1)) {
        writer.startBox(
            RecordingModifier().width(120f).height(36f)
                .clip(RoundedRectShape(18f, 18f, 18f, 18f))
                .background(0xFFE8DEF8.toInt())
                .then(RippleElement())
                .onClick(ValueIntegerChange(shown, value)),
            1, 2,
        )
        text(writer.addText(label), 0xFF21005D.toInt(), 13f, 700f)
        writer.endBox()
    }
    writer.endRow()

    writer.endColumn()

    val bytes = writer.encodeToByteArray()
    File("visibility.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to visibility.rc")
}

/**
 * The same stepping target eased three ways, so the curves can be compared at a given moment:
 * the standard cubic, `EASE_OUT_BOUNCE` and `EASE_OUT_ELASTIC`. Each drives the right-hand edge
 * of a bar, so where the bar ends is what the curve says at that instant.
 */
private fun buildEasingSample() {
    val platform = JvmRcPlatformServices()
    val writer = RemoteComposeWriter(300, 200, "easing", platform)

    // 0 for a second then 100 for a second, which is the change each curve eases.
    val target = floatArrayOf(
        Rc.Time.CONTINUOUS_SEC, Rc.FloatExpression.FLOOR, 2f, Rc.FloatExpression.MOD,
        100f, Rc.FloatExpression.MUL,
    )
    // The spline is the one curve given its shape rather than named: the values are stepped
    // through evenly, so this one rises, falls back and rises again.
    val splineSpec = floatArrayOf(0f, 1f, 0.5f, 1f)
    val curves = listOf(
        Triple("standard", androidx.compose.remote.core.operations.utilities.easing.Easing.CUBIC_STANDARD, null),
        Triple("bounce", androidx.compose.remote.core.operations.utilities.easing.Easing.EASE_OUT_BOUNCE, null),
        Triple("elastic", androidx.compose.remote.core.operations.utilities.easing.Easing.EASE_OUT_ELASTIC, null),
        Triple("spline", androidx.compose.remote.core.operations.utilities.easing.Easing.SPLINE_CUSTOM, splineSpec),
    )
    val colors = listOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt())

    for ((index, curve) in curves.withIndex()) {
        val animation = androidx.compose.remote.core.operations.utilities.easing.FloatAnimation.packToFloatArray(
            1f, curve.second, curve.third, Float.NaN, Float.NaN,
        )
        val eased = writer.floatExpression(target, animation)
        val right = writer.floatExpression(eased, 40f, Rc.FloatExpression.ADD)
        val top = 20f + index * 40f
        writer.getRcPaint().setColor(colors[index]).commit()
        writer.drawRect(40f, top, right, top + 24f)
    }

    val bytes = writer.encodeToByteArray()
    File("easing.rc").writeBytes(bytes)
    println("wrote ${bytes.size} bytes to easing.rc")
}
