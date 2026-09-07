package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.model.RemoteDocument
import com.example.remotecompose.geometry.Matrix4
import com.example.remotecompose.geometry.MatrixExpressionEvaluator
import com.example.remotecompose.geometry.PathGenerator
import com.example.remotecompose.geometry.PathGeometry
import com.example.remotecompose.layout.Dimension
import com.example.remotecompose.layout.DimensionType
import com.example.remotecompose.layout.LayoutEngine
import com.example.remotecompose.layout.LayoutNode
import com.example.remotecompose.layout.LayoutTreeBuilder
import com.example.remotecompose.layout.Modifier
import com.example.remotecompose.layout.Visibility
import com.example.remotecompose.parser.Operation as Op
import com.example.remotecompose.runtime.ActionTrigger
import com.example.remotecompose.runtime.DocumentAction
import com.example.remotecompose.runtime.FloatExpressionEvaluator
import com.example.remotecompose.runtime.HitRegion
import com.example.remotecompose.runtime.ParticleSystem
import com.example.remotecompose.runtime.RemoteContext
import com.example.remotecompose.runtime.TimeSnapshot
import com.example.remotecompose.text.BitmapFont
import com.example.remotecompose.text.EstimatedTextMetrics
import com.example.remotecompose.text.FloatFormat
import com.example.remotecompose.text.GlyphPlacement
import com.example.remotecompose.text.TextAnchoring
import com.example.remotecompose.text.TextMetricsProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parses the `androidx.compose.remote` wire format (`remote-core` 1.0.0-alpha18) as produced by
 * `RemoteComposeWriter`, into a flat [Opcode] list for
 * [com.example.remotecompose.engine.OpcodeExecutor].
 *
 * Format facts, from `WireBuffer` and each operation's `read()` in the published jar:
 * - Every record starts with one opcode byte (`Operations.<NAME>`) and has a fixed per-opcode
 *   field layout; there is no generic length prefix, so an opcode this parser does not handle
 *   is a hard failure ([RemoteComposeParseException]) rather than a skip.
 * - All integers and floats are big-endian; floats are raw IEEE-754 bits. Lengths and counts are
 *   4-byte ints. There are no variable-length integers.
 * - A float field may be a NaN-tagged reference into the float pool (`Utils.asNan(id)`); see
 *   `resolveFloat` inside [build].
 *
 * Two phases: [OperationReader] decodes the bytes into one [Operation] per record with no
 * evaluation, then [build] walks those operations once, evaluating every value-producing one
 * into pools and flattening layout containers into draw opcodes with a bounding-box heuristic.
 * The second phase is the known architectural gap versus the real player's per-frame
 * `RemoteContext` and measure/layout pass; `docs/PLAN.md` steps 5 and 6 replace it.
 */
object RemoteComposeParser {

    /**
     * Decodes [bytes] into a live [RemoteComposeDocument] whose [RemoteComposeDocument.frame]
     * produces the flattened opcodes for a moment in time.
     *
     * @param textMetrics Measures text for the layout heuristics. Pass
     *   [com.example.remotecompose.engine.ComposeTextMetrics] for real font metrics; the default
     *   is a font-free estimate, adequate for tests and headless use.
     * @throws RemoteComposeParseException on an unhandled opcode or a truncated record.
     */
    fun load(bytes: ByteArray, textMetrics: TextMetricsProvider = EstimatedTextMetrics): RemoteComposeDocument {
        val operations = OperationReader.readAll(bytes)
        val header = operations.filterIsInstance<Op.Header>().firstOrNull()?.let {
            Header(it.majorVersion, it.minorVersion, it.patchVersion, it.width, it.height, it.capabilities)
        } ?: Header(0, 0, 0, 0, 0, 0L)
        val context = RemoteContext()
        // The root component is measured against the window; until a host says otherwise the
        // window is the document's own declared size.
        context.windowWidth = header.width.toFloat()
        context.windowHeight = header.height.toFloat()
        return RemoteComposeDocument(header, operations, context, textMetrics)
    }

    /** A static snapshot: [load] followed by the first frame at time zero. */
    fun parse(bytes: ByteArray, textMetrics: TextMetricsProvider = EstimatedTextMetrics): RemoteDocument =
        load(bytes, textMetrics).frame(0L)

    /** Hit regions of the tree built by the most recent [build]; read by [RemoteComposeDocument]. */
    internal var hitRegions: List<HitRegion> = emptyList()
        private set

    /** Where the components that ripple when pressed ended up, from the same build. */
    internal var rippleTargets: List<LayoutEngine.RippleTarget> = emptyList()
        private set

    /**
     * Evaluates [operations] against [context] and flattens the result into draw opcodes; see the
     * class KDoc. Constants are applied only the first time (`context.inflated`), so a later
     * change to a pool value persists across frames; everything else is re-applied every frame.
     */
    internal fun build(operations: List<Op>, context: RemoteContext, textMetrics: TextMetricsProvider): List<Opcode> {
        hitRegions = emptyList()
        rippleTargets = emptyList()
        // Ids generated for a pattern's declarations start again each frame: the walk is the
        // same every time, so the same declaration keeps the same id rather than the document
        // growing a new one per frame.
        context.resetGeneratedIds()
        // Cumulative paint, exactly as the real player's PaintContext keeps it: each PAINT_VALUES
        // bundle is a delta applied on top of the previous state, and every draw opcode captures
        // a snapshot of it. Saved on scope push and restored on CONTAINER_END, mirroring
        // Component.paint()'s savePaint()/restorePaint() around each component's own painting.
        val paint = PaintState()
        // `CoreDocument.paint` sets the document's theme to UNSPECIFIED before every walk, so a
        // document that ends inside a mode does not start the next frame there.
        var documentTheme = RemoteContext.THEME_UNSPECIFIED
        val textPool = context.texts
        val pathPool = context.paths
        val idListPool = context.idLists
        val bitmapPool = context.bitmaps
        val colorPool = context.colors
        val floatPool = context.floats
        val intPool = context.ints
        val booleanPool = context.booleans
        val longPool = context.longs
        val dataMapPool = context.dataMaps
        val opcodes = mutableListOf<Opcode>()

        /** A wire float that may be a NaN-tagged id, resolved through the context (see [RemoteContext.resolveFloat]). */
        fun resolveFloat(raw: Float): Float = context.resolveFloat(raw)

        // Every operation that carries an expression resolves its pool variables the same way
        // FloatExpression.updateVariables does, leaving operators — the caller variable slots
        // among them — for the evaluator.
        fun resolveExpression(expression: FloatArray) = FloatArray(expression.size) { k ->
            val v = expression[k]
            if (FloatExpressionEvaluator.isVariable(v)) resolveFloat(v) else v
        }

        // Shared by Op.TextSubtext/Op.TextTransform (real TextSubtext/TextTransform.apply()'s
        // own identical [start, start+len) / [start, end) — when len == -1f — String.substring()
        // logic, source-confirmed via javap on both). null (real byte-consumed-only fallback) when
        // either the source text-pool entry or start/len (already resolveFloat-resolved by the
        // caller) is unresolved.
        fun substringOf(src: String?, start: Float, len: Float): String? {
            if (src == null || start.isNaN() || len.isNaN()) return null
            val startIdx = start.toInt().coerceIn(0, src.length)
            val endIdx = if (len == -1f) src.length else (startIdx + len.toInt()).coerceIn(startIdx, src.length)
            return src.substring(startIdx, endIdx)
        }

        // DrawBitmapFontText.paint()'s own start/end handling: an end past the string (which
        // includes the -1 the writer sends for "all of it") runs to the string's end.
        fun runOf(text: String, start: Int, end: Int): String {
            val from = start.coerceIn(0, text.length)
            val to = if (end < 0 || end > text.length) text.length else end.coerceAtLeast(from)
            return text.substring(from, to)
        }

        // A single stateless "add this draw" routine: inside a component the opcode becomes part
        // of that component's canvas content and is painted after layout; outside, it is a
        // top-level draw appended directly.
        val engine = LayoutEngine(context, textMetrics)
        val tree = LayoutTreeBuilder(context, engine)
        val trailing = mutableListOf<Opcode>()
        fun emit(op: Opcode) {
            if (!tree.emit(op)) opcodes += op
        }
        fun deferRestore() {
            if (!tree.addCleanup(Opcode.MatrixRestore)) trailing += Opcode.MatrixRestore
        }
        val scopeCache = HashMap<List<Op>, Map<Int, Int>>()
        fun scopesOf(list: List<Op>): Map<Int, Int> = scopeCache.getOrPut(list) { matchScopes(list) }
        // FloatFunctionDefine only names a body; a call runs it. Collected before the walk so a
        // call is not order-dependent on where its define sits in the stream.
        val functions = HashMap<Int, Op.FloatFunctionDefine>()
        val functionBodies = HashMap<Int, IntRange>()
        for ((i, op) in operations.withIndex()) {
            if (op is Op.FloatFunctionDefine) {
                functions[op.id] = op
                functionBodies[op.id] = (i + 1) until (scopesOf(operations)[i] ?: operations.size)
            }
        }
        // Guards FloatFunctionDefine's "Recursion not allowed".
        val executing = HashSet<Int>()
        // Patterns, by id, and the block arguments of the call being expanded. A pattern may be
        // defined after it is called, so they are collected before the walk starts.
        val patterns = operations.filterIsInstance<Op.PatternDefine>().associateBy { it.id }
        val activeCalls = ArrayDeque<ActiveCall>()
        var expansionDepth = 0

        // What `PatternForEach` has bound its local item to while its body is being walked.
        val itemBindings = HashMap<Int, Int>()

        /**
         * An id as the entry currently being expanded would name it.
         *
         * A for-each body names its local item, and every expansion means a different entry.
         * Where a value is read as the body is walked, [RemoteContext.aliasId] has already put
         * the entry's value under the local item's id and this changes nothing. Where an id is
         * instead kept for later — a text component resolves its string once the whole tree is
         * laid out, long after the loop has moved on — the entry's own id has to be what is
         * kept, which is what this returns.
         */
        fun itemId(id: Int): Int = itemBindings[id] ?: id
        // A particle loop's restart creates the particle again, from its system's own equations.
        val particleCreators = operations.filterIsInstance<Op.ParticlesCreate>().associate { it.id to it.equations }
        // ConditionalOperations TYPE_CHANGED compares against the previous frame's operands.
        val conditionalPrevious = context.conditionalPrevious

        // A macro's body is a list of its own, so the walk names the list it is walking; every
        // range below is an index into `ops`, which is `operations` outside a macro.
        fun walk(ops: List<Op>, from: Int, to: Int) {
            val scopeEnds = scopesOf(ops)
            var i = from
            while (i < to) {
                val op = ops[i]
                // CoreDocument.paint()'s own filter: an operation between a `Theme` naming a mode
                // and the next one naming none belongs to that mode, and is skipped in the other.
                // A `Theme` always runs, or the document could never leave the mode it entered.
                if (documentTheme != RemoteContext.THEME_UNSPECIFIED &&
                    documentTheme != context.paintTheme &&
                    op !is Op.Theme
                ) {
                    // Past its end if it opens one, so that skipping a component does not leave
                    // its `ContainerEnd` to close something else. The library only ever filters
                    // the top level, where nothing is open; this holds either way.
                    i = (scopeEnds[i]?.plus(1)) ?: (i + 1)
                    continue
                }
                if (context.inflated && op.isConstant()) {
                    i++
                    continue
                }
                when (op) {
                    is Op.Theme -> documentTheme = op.theme

                    is Op.ColorAttribute -> {
                        // ColorAttribute.paint(): one component of a colour, as a float. HSV is
                        // Utils.getHue/getSaturation/getBrightness, with hue over 360 so that it
                        // lands in the same 0..1 range as everything else.
                        colorPool[op.colorId]?.let { colour ->
                            val r = colour.red
                            val g = colour.green
                            val b = colour.blue
                            val high = max(r, max(g, b))
                            val low = min(r, min(g, b))
                            val span = high - low
                            val value = when (op.type and 0xFF) {
                                0 -> { // HUE
                                    val degrees = when {
                                        span == 0f -> 0f
                                        high == r -> ((g - b) / span + 6f) * 60f
                                        high == g -> ((b - r) / span + 2f) * 60f
                                        else -> ((r - g) / span + 4f) * 60f
                                    }
                                    (if (degrees >= 360f) degrees - 360f else degrees) / 360f
                                }
                                1 -> if (high == 0f) 0f else span / high // SATURATION
                                2 -> high // BRIGHTNESS
                                3 -> r
                                4 -> g
                                5 -> b
                                6 -> colour.alpha
                                else -> Float.NaN
                            }
                            if (!value.isNaN()) floatPool[op.id] = value
                        }
                    }

                    is Op.ColorTheme -> {
                        // ColorTheme.setTheme(): the value for the mode being painted.
                        val argb = if (context.paintTheme == RemoteContext.THEME_LIGHT) op.lightMode else op.darkMode
                        colorPool[op.id] = Color(argb)
                    }

                    // Decoded by OperationReader so the stream stays aligned, but with no effect in
                    // this evaluator: their semantics are runtime state, actions or animation, which
                    // need the per-frame context of docs/PLAN.md step 5.

                    is Op.AnimationSpec -> {
                        // The spec is written among a component's modifiers, so the component it
                        // belongs to is the one open rather than one it names by id.
                        context.animationSpecs[op.animationId] = op
                        tree.current?.let { node ->
                            node.motionDuration = resolveFloat(op.motionDuration).takeUnless { it.isNaN() } ?: 0f
                            node.motionEasing = op.motionEasingType
                            node.visibilityDuration =
                                resolveFloat(op.visibilityDuration).takeUnless { it.isNaN() } ?: 0f
                            node.visibilityEasing = op.visibilityEasingType
                            node.enterAnimation = op.enterAnimation
                            node.exitAnimation = op.exitAnimation
                        }
                    }

                    is Op.Skip, is Op.Rem, is Op.RootContentDescription, is Op.DebugMessage,
                    is Op.HapticFeedback, is Op.RootContentBehavior,
                    is Op.ModifierAlignBy, is Op.ModifierMarquee, is Op.ModifierDrawContent -> Unit

                    is Op.ModifierRipple -> tree.current?.modifiers?.add(Modifier.Ripple())

                    // Action-list entries: collected onto the enclosing component's trigger, and
                    // run on gesture rather than while evaluating (each operation's runAction).
                    is Op.HostAction -> tree.addAction(DocumentAction.Host(op.actionId))

                    // `runAction(actionId, getText(metadataId))`: the same action with a string.
                    is Op.HostMetadataAction ->
                        tree.addAction(DocumentAction.Host(op.actionId, textPool[op.metadataId] ?: ""))

                    is Op.HostNamedAction ->
                        tree.addAction(DocumentAction.HostNamed(op.textId, op.type, op.valueId))
                    is Op.ValueFloatChange -> tree.addAction(DocumentAction.SetFloat(op.valueId, op.value))
                    // `NamedVariable.apply`: loadVariableName(name, id, type). The value itself
                    // is written by whatever record follows; this only says what it is called.
                    is Op.NamedVariable -> context.loadVariableName(op.name, op.id, op.type)

                    is Op.ValueIntegerChange -> tree.addAction(DocumentAction.SetInteger(op.valueId, op.value))
                    is Op.ValueStringChange -> tree.addAction(DocumentAction.SetText(op.valueId, op.stringId))
                    is Op.ValueFloatExpressionChange ->
                        tree.addAction(DocumentAction.SetFloatFromExpression(op.valueId, op.value))
                    is Op.ValueIntegerExpressionChange ->
                        tree.addAction(DocumentAction.SetIntegerFromExpression(op.valueId.toInt(), op.value.toInt()))

                    // TouchExpression: a float driven by the pointer while it is down. Evaluated
                    // like any float expression, with the drag delta applied on top (mode 0 of
                    // TouchExpression.apply); the pointer position arrives as ID_TOUCH_POS_X/_Y.
                    is Op.TouchExpression -> context.applyTouchExpression(op)

                    is Op.Header -> Unit // captured by load()

                    is Op.FloatExpression -> context.applyFloatExpression(op)

                    is Op.TextData -> {
                        val id = op.id
                        textPool[id] = op.text
                    }

                    is Op.TextSubtext -> {
                        val textId = op.textId
                        val srcId = op.srcId
                        val start = resolveFloat(op.start)
                        val len = resolveFloat(op.len)
                        substringOf(textPool[srcId], start, len)?.let { textPool[textId] = it }
                    }

                    is Op.TextTransform -> {
                        val textId = op.textId
                        val srcId = op.srcId
                        val start = resolveFloat(op.start)
                        val len = resolveFloat(op.len)
                        val operation = op.operation
                        substringOf(textPool[srcId], start, len)?.let { sub ->
                            textPool[textId] = when (operation) {
                                1 -> sub.lowercase()
                                2 -> sub.uppercase()
                                3 -> sub.trim()
                                // capitalizeWords: title-cases the first char of every word, copying
                                // every other char through unchanged — ported char-for-char from the
                                // real capitalizeWords() bytecode's own atStartOfWord-flag loop.
                                4 -> buildString {
                                    var atStartOfWord = true
                                    for (c in sub) {
                                        when {
                                            c.isWhitespace() -> { atStartOfWord = true; append(c) }
                                            atStartOfWord -> { append(c.uppercaseChar()); atStartOfWord = false }
                                            else -> append(c)
                                        }
                                    }
                                }
                                // capitalizeFirstWord: title-cases only the first non-whitespace char
                                // of the whole string — ported from the real capitalizeFirstWord()
                                // bytecode.
                                5 -> {
                                    val i = sub.indexOfFirst { !it.isWhitespace() }
                                    if (i == -1) sub else sub.substring(0, i) + sub[i].uppercaseChar() + sub.substring(i + 1)
                                }
                                else -> sub
                            }
                        }
                    }

                    is Op.PaintData -> {
                        val words = op.words
                        PaintBundleDecoder.apply(
                            words = words,
                            state = paint,
                            resolveFloat = ::resolveFloat,
                            colorById = { colorPool[it] },
                            textById = { textPool[it] },
                        )
                    }

                    is Op.DrawRect -> {
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        emit(Opcode.DrawRect(
                            left, top, right, bottom,
                            paint.snapshot(),
                        ))
                    }

                    is Op.DrawCircle -> {
                        val centerX = resolveFloat(op.centerX)
                        val centerY = resolveFloat(op.centerY)
                        val radius = resolveFloat(op.radius)
                        emit(Opcode.DrawCircle(
                            centerX, centerY, radius,
                            paint.snapshot(),
                        ))
                    }

                    is Op.DrawRoundRect -> {
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        val radiusX = resolveFloat(op.radiusX)
                        val radiusY = resolveFloat(op.radiusY)
                        emit(Opcode.DrawRoundRect(
                            left, top, right, bottom, radiusX, radiusY,
                            paint.snapshot(),
                        ))
                    }

                    is Op.DrawTextRun -> {
                        val textId = op.textId
                        val start = op.start
                        val end = op.end
                        val x = resolveFloat(op.x)
                        val y = resolveFloat(op.y)
                        emit(Opcode.DrawText(
                            stringIndex = textId,
                            x = x,
                            y = y,
                            paint = paint.snapshot(),
                            substringStart = start,
                            substringEnd = end,
                        ))
                    }

                    is Op.DrawTextAnchored -> {
                        val textId = op.textId
                        val x = resolveFloat(op.x)
                        val y = resolveFloat(op.y)
                        val panX = resolveFloat(op.panX)
                        val panY = resolveFloat(op.panY)
                        emit(Opcode.DrawText(
                            stringIndex = textId,
                            x = x,
                            y = y,
                            paint = paint.snapshot(),
                            panX = panX,
                            panY = panY,
                            baselineRelative = op.flags and 8 != 0, // DrawTextAnchored.BASELINE_RELATIVE
                        ))
                    }

                    is Op.DrawTextOnCircle -> {
                        val glyphs = GlyphPlacement.onCircle(
                            text = textPool[op.textId] ?: "",
                            stringIndex = op.textId,
                            centerX = resolveFloat(op.centerX),
                            centerY = resolveFloat(op.centerY),
                            radius = resolveFloat(op.radius),
                            startAngleDegrees = resolveFloat(op.startAngleDegrees),
                            warpRadiusOffset = resolveFloat(op.warpRadiusOffset),
                            alignment = op.alignment,
                            placement = op.placement,
                            paint = paint.snapshot(),
                            metrics = textMetrics,
                        )
                        for (glyph in glyphs) emit(glyph)
                    }

                    is Op.DrawTextOnPath -> {
                        // Wire order is vOffset then hOffset, the reverse of the call's arguments.
                        val commands = pathPool[op.pathId]
                        if (commands != null) {
                            val glyphs = GlyphPlacement.onPath(
                                text = textPool[op.textId] ?: "",
                                stringIndex = op.textId,
                                commands = commands,
                                hOffset = resolveFloat(op.hOffset),
                                vOffset = resolveFloat(op.vOffset),
                                paint = paint.snapshot(),
                                metrics = textMetrics,
                            )
                            for (glyph in glyphs) emit(glyph)
                        }
                    }

                    is Op.DrawLine -> {
                        val x1 = resolveFloat(op.x1)
                        val y1 = resolveFloat(op.y1)
                        val x2 = resolveFloat(op.x2)
                        val y2 = resolveFloat(op.y2)
                        emit(Opcode.DrawLine(
                            x1, y1, x2, y2,
                            paint.snapshot().copy(style = PaintStyleKind.STROKE),
                        ))
                    }

                    is Op.DrawOval -> {
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        emit(Opcode.DrawOval(
                            left, top, right, bottom,
                            paint.snapshot(),
                        ))
                    }

                    is Op.DrawArc, is Op.DrawSector -> {
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        val startAngle = resolveFloat(op.startAngle)
                        val sweepAngle = resolveFloat(op.sweepAngle)
                        emit(Opcode.DrawArc(
                            left, top, right, bottom, startAngle, sweepAngle,
                            useCenter = op is Op.DrawSector,
                            paint = paint.snapshot(),
                        ))
                    }

                    is Op.PathData -> {
                        val pathId = op.pathId
                        pathPool[pathId] = op.commands
                    }

                    is Op.PathCreate -> {
                        val pathId = op.pathId
                        val startX = resolveFloat(op.startX)
                        val startY = resolveFloat(op.startY)
                        pathPool[pathId] = listOf(PathCommand.MoveTo(startX, startY))
                    }

                    is Op.PathAdd -> {
                        val pathId = op.pathId
                        val appended = op.commands
                        pathPool[pathId] = (pathPool[pathId] ?: emptyList()) + appended
                    }

                    is Op.PathTween -> {
                        val outId = op.outId
                        val pathId1 = op.pathId1
                        val pathId2 = op.pathId2
                        val tween = resolveFloat(op.tween)
                        lerpPath(pathPool[pathId1], pathPool[pathId2], tween)?.let { pathPool[outId] = it }
                    }

                    is Op.MatrixFromPath -> {
                        val pathId = op.pathId
                        val fraction = resolveFloat(op.fraction)
                        val vOffset = resolveFloat(op.vOffset)
                        val flags = op.flags
                        val posTan = pathPool[pathId]?.let { PathGeometry.pointAtFraction(it, fraction) }
                        if (posTan != null) {
                            val px = posTan.x; val py = posTan.y
                            val tx = posTan.tangentX; val ty = posTan.tangentY
                            val len = sqrt(tx * tx + ty * ty).takeIf { it > 0f } ?: 1f
                            val perpX = -ty / len * vOffset
                            val perpY = tx / len * vOffset
                            emit(Opcode.MatrixSave)
                            emit(Opcode.Translate(px + perpX, py + perpY))
                            if (flags and 2 != 0) { // TANGENT_MATRIX_FLAG
                                emit(Opcode.Rotate(atan2(ty, tx) * 180f / PI.toFloat(), 0f, 0f))
                            }
                            deferRestore()
                        }
                    }

                    is Op.DrawTweenPath -> {
                        val path1Id = op.path1Id
                        val path2Id = op.path2Id
                        val tween = resolveFloat(op.tween)
                        val start = resolveFloat(op.start)
                        val stop = resolveFloat(op.stop)
                        val lerped = lerpPath(pathPool[path1Id], pathPool[path2Id], tween)
                        if (lerped != null) {
                            val trimmed = trimPath(lerped, start, stop)
                            if (trimmed.isNotEmpty()) {
                                emit(Opcode.DrawPath(trimmed, paint.snapshot()))
                            }
                        }
                    }

                    is Op.PathCombine -> {
                        val outId = op.outId
                        val pathId1 = op.pathId1
                        val pathId2 = op.pathId2
                        val operation = op.operation
                        val path1 = pathPool[pathId1]
                        val path2 = pathPool[pathId2]
                        if (operation == 1 && path1 != null && path2 != null) { // OP_INTERSECT only
                            val subject = PathGeometry.flatten(path1).map { floatArrayOf(it[0], it[1]) }
                            val clip = PathGeometry.flatten(path2).map { floatArrayOf(it[0], it[1]) }
                            val result = sutherlandHodgmanIntersect(subject, clip)
                            if (result.size >= 3) {
                                pathPool[outId] = listOf(PathCommand.MoveTo(result[0][0], result[0][1])) +
                                    result.drop(1).map { PathCommand.LineTo(it[0], it[1]) } +
                                    listOf(PathCommand.Close)
                            }
                        }
                    }

                    is Op.DrawPath -> {
                        val pathId = op.pathId
                        val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                            "DrawPath references path id $pathId which no prior DataPath defined",
                        )
                        emit(Opcode.DrawPath(commands, paint.snapshot()))
                    }

                    is Op.ClipPath -> {
                        val pathId = op.pathId
                        val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                            "ClipPath references path id $pathId which no prior DataPath defined",
                        )
                        emit(Opcode.ClipPath(commands))
                    }

                    is Op.BitmapData -> {
                        val bitmapId = op.bitmapId
                        bitmapPool[bitmapId] = op.bytes
                        context.bitmapSizes[bitmapId] = op.width to op.height
                    }

                    is Op.DrawBitmap -> {
                        val bitmapId = op.bitmapId
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        emit(Opcode.DrawBitmap(bitmapId, left, top, right, bottom))
                    }

                    is Op.DrawBitmapInt -> {
                        val bitmapId = op.bitmapId
                        val srcLeft = op.srcLeft.toFloat()
                        val srcTop = op.srcTop.toFloat()
                        val srcRight = op.srcRight.toFloat()
                        val srcBottom = op.srcBottom.toFloat()
                        val dstLeft = op.dstLeft.toFloat()
                        val dstTop = op.dstTop.toFloat()
                        val dstRight = op.dstRight.toFloat()
                        val dstBottom = op.dstBottom.toFloat()
                        emit(Opcode.DrawBitmap(
                            bitmapId, dstLeft, dstTop, dstRight, dstBottom,
                            srcLeft, srcTop, srcRight, srcBottom,
                        ))
                    }

                    is Op.DrawBitmapScaled -> {
                        val bitmapId = op.bitmapId
                        val srcLeft = resolveFloat(op.srcLeft)
                        val srcTop = resolveFloat(op.srcTop)
                        val srcRight = resolveFloat(op.srcRight)
                        val srcBottom = resolveFloat(op.srcBottom)
                        val dstLeft = resolveFloat(op.dstLeft)
                        val dstTop = resolveFloat(op.dstTop)
                        val dstRight = resolveFloat(op.dstRight)
                        val dstBottom = resolveFloat(op.dstBottom)
                        val scaleType = op.scaleType
                        val srcWidth = (srcRight - srcLeft).toInt()
                        val srcHeight = (srcBottom - srcTop).toInt()
                        val realScaleTypes = scaleType == 0 || scaleType == 1 || scaleType == 4 || scaleType == 5
                        val dst = if (realScaleTypes) {
                            LayoutEngine.imageScaleDstRect(scaleType, srcWidth, srcHeight, dstLeft, dstTop, dstRight, dstBottom)
                        } else {
                            floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
                        }
                        emit(Opcode.MatrixSave)
                        emit(Opcode.ClipRect(dstLeft, dstTop, dstRight, dstBottom))
                        emit(Opcode.DrawBitmap(
                            bitmapId, dst[0], dst[1], dst[2], dst[3],
                            srcLeft, srcTop, srcRight, srcBottom,
                        ))
                        emit(Opcode.MatrixRestore)
                    }

                    is Op.ClickArea -> {
                        val actionId = op.actionId
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        val metadataTextId = op.metadataTextId
                        emit(Opcode.ActionClick(actionId, metadataTextId, left, top, right, bottom))
                    }

                    is Op.TextAttribute -> {
                        // TextAttribute.paint(): the same measurement TextMeasure takes, plus
                        // TEXT_LENGTH. The high byte is the monospace and max-height measuring
                        // flags, which this renderer does not apply.
                        val text = textPool[op.textId] ?: ""
                        val measured = textMetrics.measure(text, paint.snapshot())
                        val value = when (op.type and 0xFF) {
                            0 -> measured.width
                            1 -> measured.height
                            2 -> 0f
                            3 -> measured.width
                            4 -> -measured.ascent
                            5 -> measured.descent
                            6 -> text.length.toFloat()
                            else -> Float.NaN
                        }
                        if (!value.isNaN()) floatPool[op.id] = value
                    }

                    is Op.TimeAttribute -> {
                        // TimeAttribute.paint(): the moment is the long at timeId when the
                        // document names one, and now otherwise. Types 0..2 measure from now and
                        // 3..5 from the long at args[0]; the rest read a part off the moment.
                        //
                        // The real operation calls `wakeIn` so the host redraws when the value
                        // would next change. This renderer redraws whenever the document asks,
                        // so asking is the whole of it.
                        val now = context.frameTimeMillis
                        val moment = context.longs[op.timeId] ?: now
                        val snapshot = TimeSnapshot(moment)
                        val type = op.type and 0xFF
                        val from = when (type) {
                            3, 4, 5 -> context.longs[op.args.firstOrNull() ?: -1] ?: now
                            else -> now
                        }
                        val delta = moment - from
                        val value = when (type) {
                            0, 3 -> { context.needsRepaint = true; delta * 0.001f }
                            1, 4 -> { context.needsRepaint = true; (delta * 0.001 / 60.0).toFloat() }
                            2, 5 -> (delta * 0.001 / 3600.0).toFloat()
                            6 -> snapshot.second.toFloat()
                            7 -> snapshot.minute.toFloat()
                            8 -> snapshot.hour.toFloat()
                            9 -> snapshot.dayOfMonth.toFloat()
                            10 -> (snapshot.month - 1).toFloat()
                            11 -> (snapshot.dayOfWeek - 1).toFloat()
                            12 -> snapshot.year.toFloat()
                            14 -> {
                                context.needsRepaint = true
                                (snapshot.millis - context.documentLoadTime) * 0.001f
                            }
                            15 -> snapshot.dayOfYear.toFloat()
                            else -> Float.NaN
                        }
                        if (!value.isNaN()) floatPool[op.id] = value
                    }

                    is Op.ImageAttribute -> {
                        // ImageAttribute.paint(): IMAGE_WIDTH(0) or IMAGE_HEIGHT(1), which
                        // DATA_BITMAP states beside the bytes.
                        context.bitmapSizes[op.imageId]?.let { (width, height) ->
                            val value = when (op.type and 0xFF) {
                                0 -> width.toFloat()
                                1 -> height.toFloat()
                                else -> Float.NaN
                            }
                            if (!value.isNaN()) floatPool[op.id] = value
                        }
                    }

                    is Op.TextMeasure -> {
                        // TextMeasure: measures the text with the current paint and stores one
                        // component of its bounds. Types are WIDTH(0), HEIGHT(1) and the four
                        // bounds edges LEFT(2)/RIGHT(3)/TOP(4)/BOTTOM(5), with the bounds box
                        // being [0, -ascent, width, descent] as elsewhere in this renderer.
                        val measured = textMetrics.measure(textPool[op.textId] ?: "", paint.snapshot())
                        val value = when (op.type and 0xFF) {
                            0 -> measured.width
                            1 -> measured.height
                            2 -> 0f
                            3 -> measured.width
                            4 -> -measured.ascent
                            5 -> measured.descent
                            else -> Float.NaN
                        }
                        if (!value.isNaN()) floatPool[op.id] = value
                    }

                    is Op.TextLength -> {
                        val lengthId = op.lengthId
                        val textId = op.textId
                        floatPool[lengthId] = (textPool[textId]?.length ?: 0).toFloat()
                    }

                    is Op.IdList -> {
                        val id = op.id
                        idListPool[id] = op.ids
                    }

                    is Op.TextLookup -> {
                        val textId = op.textId
                        val dataSetId = op.dataSetId
                        val index = resolveFloat(op.index)
                        if (!index.isNaN()) {
                            idListPool[dataSetId]?.getOrNull(index.toInt())?.let { srcId ->
                                textPool[srcId]?.let { textPool[textId] = it }
                            }
                        }
                    }

                    is Op.TextLookupInt -> {
                        val textId = op.textId
                        val dataSetId = op.dataSetId
                        val indexRefId = op.indexRefId
                        intPool[indexRefId]?.let { index ->
                            idListPool[dataSetId]?.getOrNull(index)?.let { srcId ->
                                textPool[srcId]?.let { textPool[textId] = it }
                            }
                        }
                    }

                    is Op.TextMerge -> {
                        val textId = op.textId
                        val srcId1 = op.srcId1
                        val srcId2 = op.srcId2
                        val left = textPool[srcId1] ?: ""
                        val right = textPool[srcId2] ?: ""
                        textPool[textId] = left + right
                    }

                    is Op.ColorExpression -> {
                        val id = op.id
                        val modeAlpha = op.modeAlpha
                        val mode = modeAlpha and 0xFF
                        val alpha = (modeAlpha ushr 16) and 0xFF
                        val word1 = op.word1
                        val word2 = op.word2
                        val word3 = op.word3
                        val computed: Int? = when (mode) {
                            0, 1, 2, 3 -> {
                                val c1 = if (mode and 1 != 0) colorPool[word1]?.toArgb() else word1
                                val c2 = if (mode and 2 != 0) colorPool[word2]?.toArgb() else word2
                                val tween = resolveFloat(Float.fromBits(word3))
                                if (c1 != null && c2 != null && !tween.isNaN()) {
                                    interpolateColorArgb(c1, c2, tween)
                                } else {
                                    null
                                }
                            }

                            4 -> {
                                val hue = resolveFloat(Float.fromBits(word1))
                                val sat = resolveFloat(Float.fromBits(word2))
                                val value = resolveFloat(Float.fromBits(word3))
                                if (!hue.isNaN() && !sat.isNaN() && !value.isNaN()) {
                                    (alpha shl 24) or (hsvToRgbArgb(hue, sat, value) and 0xFFFFFF)
                                } else {
                                    null
                                }
                            }

                            else -> null // ARGB_MODE(5)/IDARGB_MODE(6) — left unresolved
                        }
                        if (computed != null) colorPool[id] = Color(computed)
                    }

                    is Op.IdLookup -> {
                        val intId = op.intId
                        val dataSetId = op.dataSetId
                        val index = resolveFloat(op.index)
                        if (!index.isNaN()) {
                            idListPool[dataSetId]?.getOrNull(index.toInt())?.let { intPool[intId] = it }
                        }
                    }

                    is Op.IntegerExpression -> {
                        val id = op.id
                        val mask = op.mask
                        val values = op.values
                        val count = values.size
                        val resolved = IntArray(count)
                        val isOperator = BooleanArray(count)
                        var allResolved = true
                        for (i in 0 until count) {
                            val bitSet = (mask ushr i) and 1 != 0
                            val v = values[i]
                            if (bitSet && v < 65536) {
                                val iv = intPool[v]
                                if (iv != null) resolved[i] = iv else allResolved = false
                            } else {
                                resolved[i] = v
                                isOperator[i] = bitSet && v >= 65536
                            }
                        }
                        if (allResolved) {
                            val stack = IntArray(count)
                            var sp = -1
                            var valid = true
                            for (i in 0 until count) {
                                if (isOperator[i]) {
                                    val newSp = evalIntegerOp(stack, sp, resolved[i])
                                    if (newSp == null) {
                                        valid = false
                                        break
                                    }
                                    sp = newSp
                                } else {
                                    sp++
                                    stack[sp] = resolved[i]
                                }
                            }
                            if (valid && sp >= 0) intPool[id] = stack[sp]
                        }
                    }

                    is Op.TextFromFloat -> {
                        // TextFromFloat.apply(): FULL_FORMAT prints the float as it is, and
                        // otherwise the flags word picks the padding, grouping, separator pair
                        // and negative style that StringUtils formats with. The digits word packs
                        // the count before the point in its high half and after it in its low.
                        val value = resolveFloat(op.value)
                        if (!value.isNaN()) {
                            val flags = op.flags
                            val before = (op.digits shr 16) and 0xFFFF
                            val after = op.digits and 0xFFFF
                            val afterPad = when (flags and 3) {
                                1 -> FloatFormat.NO_PAD
                                3 -> '0'
                                else -> ' '
                            }
                            val prePad = when (flags and 12) {
                                4 -> FloatFormat.NO_PAD
                                12 -> '0'
                                else -> ' '
                            }
                            textPool[op.textId] = when {
                                flags and 0x1000 != 0 -> value.toString()
                                flags and 0x400 != 0 -> FloatFormat.formatLegacy(value, before, after, prePad, afterPad)
                                else -> FloatFormat.format(
                                    value, before, after, prePad, afterPad,
                                    separator = (flags shr 6) and 3, grouping = (flags shr 4) and 3,
                                    options = (flags shr 8) and 3,
                                )
                            }
                        }
                    }

                    is Op.DataMapIds -> {
                        val mapId = op.mapId
                        val entries = op.entries
                        dataMapPool[mapId] = entries
                    }

                    is Op.DataMapLookup -> {
                        val id = op.id
                        val dataMapId = op.dataMapId
                        val stringId = op.stringId
                        val key = textPool[stringId]
                        val entry = dataMapPool[dataMapId]?.firstOrNull { it.name == key }
                        if (entry != null) {
                            val (_, type, valueId) = entry
                            when (type) {
                                0 -> textPool[valueId]?.let { textPool[id] = it }
                                1 -> intPool[valueId]?.let { intPool[id] = it }
                                2 -> floatPool[valueId]?.let { floatPool[id] = it }
                                3 -> longPool[valueId]?.let { intPool[id] = it.toInt() }
                                4 -> booleanPool[valueId]?.let { intPool[id] = if (it) 1 else 0 }
                            }
                        }
                    }

                    is Op.FloatConstant -> {
                        val id = op.id
                        val value = op.value
                        floatPool[id] = value
                    }

                    is Op.IntegerConstant -> {
                        val id = op.id
                        val value = op.value
                        intPool[id] = value
                    }

                    is Op.BooleanConstant -> {
                        val id = op.id
                        val value = op.value
                        booleanPool[id] = value
                    }

                    is Op.LongConstant -> {
                        val id = op.id
                        val value = op.value
                        longPool[id] = value
                    }

                    is Op.ColorConstant -> {
                        val colorId = op.colorId
                        val colorArgb = op.colorArgb
                        colorPool[colorId] = Color(colorArgb)
                    }

                    is Op.MatrixSave -> emit(Opcode.MatrixSave)

                    is Op.MatrixRestore -> emit(Opcode.MatrixRestore)

                    is Op.MatrixTranslate -> {
                        val dx = resolveFloat(op.dx)
                        val dy = resolveFloat(op.dy)
                        emit(Opcode.Translate(dx, dy))
                    }

                    is Op.MatrixScale -> {
                        val sx = resolveFloat(op.sx)
                        val sy = resolveFloat(op.sy)
                        val pivotX = resolveFloat(op.pivotX)
                        val pivotY = resolveFloat(op.pivotY)
                        emit(Opcode.Scale(sx, sy, pivotX, pivotY))
                    }

                    is Op.MatrixRotate -> {
                        val degrees = resolveFloat(op.degrees)
                        val pivotX = resolveFloat(op.pivotX)
                        val pivotY = resolveFloat(op.pivotY)
                        emit(Opcode.Rotate(degrees, pivotX, pivotY))
                    }

                    is Op.MatrixSkew -> {
                        val skewX = resolveFloat(op.skewX)
                        val skewY = resolveFloat(op.skewY)
                        emit(Opcode.Skew(skewX, skewY))
                    }

                    is Op.ClipRect -> {
                        val left = resolveFloat(op.left)
                        val top = resolveFloat(op.top)
                        val right = resolveFloat(op.right)
                        val bottom = resolveFloat(op.bottom)
                        emit(Opcode.ClipRect(left, top, right, bottom))
                    }

                    is Op.LayoutRoot -> tree.openNode(LayoutNode(LayoutNode.Kind.ROOT), paint)

                    is Op.LayoutColumn, is Op.LayoutRow, is Op.LayoutCollapsibleColumn,
                    is Op.LayoutCollapsibleRow, is Op.LayoutFlow -> {
                        val kind = when (op) {
                            is Op.LayoutColumn -> LayoutNode.Kind.COLUMN
                            is Op.LayoutRow -> LayoutNode.Kind.ROW
                            is Op.LayoutCollapsibleColumn -> LayoutNode.Kind.COLLAPSIBLE_COLUMN
                            is Op.LayoutCollapsibleRow -> LayoutNode.Kind.COLLAPSIBLE_ROW
                            else -> LayoutNode.Kind.FLOW
                        }
                        val node = LayoutNode(kind)
                        node.horizontalPositioning = op.horizontalPositioning
                        node.verticalPositioning = op.verticalPositioning
                        node.spacedBy = resolveFloat(op.spacedBy).takeUnless { it.isNaN() } ?: 0f
                        node.componentId = op.componentId
                        node.animationId = op.animationId
                        if (op is Op.LayoutFlow) {
                            node.maxItemsInMainAxis = op.maxItemsInMainAxis.takeIf { it > 0 } ?: Int.MAX_VALUE
                            node.maxLines = op.maxLinesInCrossAxis.takeIf { it > 0 } ?: Int.MAX_VALUE
                        }
                        tree.openNode(node, paint)
                    }

                    is Op.LayoutBox, is Op.LayoutFitBox -> {
                        val node = LayoutNode(if (op is Op.LayoutBox) LayoutNode.Kind.BOX else LayoutNode.Kind.FIT_BOX)
                        node.horizontalPositioning = op.horizontalPositioning
                        node.verticalPositioning = op.verticalPositioning
                        node.componentId = op.componentId
                        node.animationId = op.animationId
                        tree.openNode(node, paint)
                    }

                    is Op.LayoutText -> {
                        // TextLayout.paintingComponent builds its own paint: size, weight, italic, color.
                        val node = LayoutNode(LayoutNode.Kind.TEXT)
                        node.textId = itemId(op.textId)
                        val fontSize = resolveFloat(op.fontSize).takeUnless { it.isNaN() || it <= 0f } ?: DEFAULT_LAYOUT_TEXT_SIZE
                        val weight = resolveFloat(op.fontWeight).takeUnless { it.isNaN() || it <= 0f }?.toInt() ?: 400
                        // TextLayout.isDynamicColorEnabled: the flags in the top half of the
                        // alignment word say whether the colour field is an ARGB or the id of
                        // one, which is how a text follows a themed colour.
                        val dynamicColor = ((op.textAlign ushr 16) and 1) == 1
                        val color = if (dynamicColor) colorPool[op.color] ?: Color.Transparent else Color(op.color)
                        node.textPaint = PaintStyle(
                            color, PaintStyleKind.FILL,
                            textSize = fontSize, fontWeight = weight, fontItalic = op.fontStyle == 1,
                        )
                        node.textAlign = op.textAlign and 0xFFFF
                        node.componentId = op.componentId
                        node.animationId = op.animationId
                        tree.openNode(node, paint)
                    }

                    is Op.LayoutImage -> {
                        val node = LayoutNode(LayoutNode.Kind.IMAGE)
                        node.bitmapId = itemId(op.bitmapId)
                        node.imageScaleType = op.scaleType
                        node.imageAlpha = resolveFloat(op.alpha).takeUnless { it.isNaN() } ?: 1f
                        tree.openNode(node, paint)
                    }

                    is Op.LayoutCanvas -> tree.openNode(LayoutNode(LayoutNode.Kind.CANVAS), paint)

                    is Op.LayoutCustom -> tree.openNode(LayoutNode(LayoutNode.Kind.CUSTOM), paint)

                    is Op.LayoutState -> {
                        val node = LayoutNode(LayoutNode.Kind.STATE)
                        node.horizontalPositioning = op.horizontalPositioning
                        node.verticalPositioning = op.verticalPositioning
                        node.stateIndexId = op.stateIndex
                        tree.openNode(node, paint)
                    }

                    is Op.LayoutContent, is Op.LayoutCanvasContent, is Op.CanvasOperations -> tree.openContent(paint)

                    is Op.ModifierClick, is Op.ModifierMultiClick -> tree.openActionList(ActionTrigger.CLICK, paint)
                    is Op.ModifierTouchDown -> tree.openActionList(ActionTrigger.TOUCH_DOWN, paint)
                    is Op.ModifierTouchUp -> tree.openActionList(ActionTrigger.TOUCH_UP, paint)
                    is Op.ModifierTouchCancel -> tree.openActionList(ActionTrigger.TOUCH_CANCEL, paint)

                    is Op.ContainerEnd -> tree.close(paint)?.let { opcodes.addAll(it) }

                    is Op.ConditionalOperations -> {
                        // ConditionalOperations: its block runs only when the comparison holds.
                        // TYPE_EQ(0)/NEQ(1)/LT(2)/LTE(3)/GT(4)/GTE(5), plus CHANGED(6) which the
                        // real operation tracks across frames.
                        val end = scopeEnds[i] ?: to
                        val a = resolveFloat(op.varA)
                        val b = resolveFloat(op.varB)
                        val previous = conditionalPrevious[i]
                        val holds = when (op.type.toInt()) {
                            0 -> a == b
                            1 -> a != b
                            2 -> a < b
                            3 -> a <= b
                            4 -> a > b
                            5 -> a >= b
                            6 -> previous != null && (previous[0] != a || previous[1] != b)
                            else -> false
                        }
                        conditionalPrevious[i] = floatArrayOf(a, b)
                        if (holds) walk(ops, i + 1, end)
                        i = end // the block's own CONTAINER_END
                    }

                    is Op.BitmapFontData -> {
                        context.bitmapFonts[op.id] = BitmapFont(op.glyphs, op.kerning)
                    }

                    is Op.DrawBitmapFontText -> {
                        // DrawBitmapFontText.paint(): walk the run and draw each glyph's bitmap
                        // where the advance loop puts it, relative to the run's origin.
                        val text = textPool[op.textId]
                        val font = context.bitmapFonts[op.fontId]
                        if (text != null && font != null) {
                            val x = resolveFloat(op.x)
                            val y = resolveFloat(op.y)
                            for (placement in font.layout(runOf(text, op.start, op.end), resolveFloat(op.glyphSpacing)).placements) {
                                emit(Opcode.DrawBitmap(
                                    placement.glyph.bitmapId,
                                    x + placement.left, y + placement.top,
                                    x + placement.right, y + placement.bottom,
                                ))
                            }
                        }
                    }

                    is Op.DrawBitmapTextAnchored -> {
                        // DrawBitmapTextAnchored.paint(): the run is laid out as usual, then
                        // shifted so that (x, y) lands where panX/panY say inside its bounds.
                        //
                        //   dx = -width * (1 + panX) / 2 - left
                        //   dy = -height * (1 - panY) / 2 - top
                        //
                        // which is `getHorizontalOffset`/`getVerticalOffset` with the box they
                        // measure against being zero-sized, as it is for a bare draw.
                        val text = textPool[op.textId]
                        val font = context.bitmapFonts[op.fontId]
                        if (text != null && font != null) {
                            val from = resolveFloat(op.start).toInt()
                            val to = resolveFloat(op.end).toInt()
                            val run = font.layout(runOf(text, from, to), resolveFloat(op.glyphSpacing))
                            val placements = run.placements
                            val left = placements.minOfOrNull { it.left } ?: 0f
                            val top = placements.minOfOrNull { it.top } ?: 0f
                            val right = placements.maxOfOrNull { it.right } ?: 0f
                            val bottom = placements.maxOfOrNull { it.bottom } ?: 0f
                            val panX = resolveFloat(op.panX)
                            val panY = resolveFloat(op.panY)
                            val dx = -(right - left) * (1f + panX) / 2f - left
                            val dy = -(bottom - top) * (1f - panY) / 2f - top
                            val x = resolveFloat(op.x) + dx
                            val y = resolveFloat(op.y) + dy
                            for (placement in placements) {
                                emit(Opcode.DrawBitmap(
                                    placement.glyph.bitmapId,
                                    x + placement.left, y + placement.top,
                                    x + placement.right, y + placement.bottom,
                                ))
                            }
                        }
                    }

                    is Op.DrawBitmapFontTextOnPath -> {
                        // DrawBitmapFontTextOnPath.paint(): each glyph is centred on the point a
                        // fraction of the way along the path and rotated to the tangent there,
                        // the fraction being its own centre over the run's total width.
                        val text = textPool[op.textId]
                        val font = context.bitmapFonts[op.fontId]
                        val path = pathPool[op.pathId]
                        if (text != null && font != null && path != null) {
                            val run = runOf(text, op.start, op.end)
                            val spacing = resolveFloat(op.glyphSpacing)
                            val total = font.measureWidth(run)
                            val yAdj = resolveFloat(op.yAdj)
                            if (total > 0f) {
                                for (placement in font.layout(run, spacing).placements) {
                                    val halfWidth = placement.glyph.bitmapWidth / 2f
                                    val point = PathGeometry.pointAtFraction(path, (placement.left + halfWidth) / total)
                                        ?: continue
                                    emit(Opcode.MatrixSave)
                                    emit(Opcode.Translate(point.x, point.y))
                                    emit(Opcode.Rotate(
                                        atan2(point.tangentY, point.tangentX) * 180f / PI.toFloat(), 0f, 0f,
                                    ))
                                    emit(Opcode.DrawBitmap(
                                        placement.glyph.bitmapId,
                                        -halfWidth, yAdj + placement.glyph.marginTop,
                                        halfWidth, yAdj + placement.glyph.bitmapHeight + placement.glyph.marginTop,
                                    ))
                                    emit(Opcode.MatrixRestore)
                                }
                            }
                        }
                    }

                    is Op.BitmapTextMeasure -> {
                        // BitmapTextMeasure.measure(): the run's bounds from the same advance
                        // loop. The monospace and max-height flags are not applied.
                        val text = textPool[op.textId]
                        val font = context.bitmapFonts[op.fontId]
                        if (text != null && font != null) {
                            val spacing = resolveFloat(op.glyphSpacing)
                            val run = font.layout(text, spacing)
                            val placements = run.placements
                            val top = placements.minOfOrNull { it.glyph.marginTop.toFloat() } ?: 0f
                            val bottom = placements.maxOfOrNull {
                                (it.glyph.bitmapHeight + it.glyph.marginTop + it.glyph.marginBottom).toFloat()
                            } ?: 0f
                            val value = when (op.type and 0xFF) {
                                0 -> run.width // MEASURE_WIDTH, from a left edge of zero
                                1 -> bottom - top // MEASURE_HEIGHT
                                2 -> 0f // MEASURE_LEFT
                                3 -> run.width // MEASURE_RIGHT
                                4 -> top // MEASURE_TOP
                                5 -> bottom // MEASURE_BOTTOM
                                else -> Float.NaN
                            }
                            context.loadFloat(op.id, value)
                        }
                    }

                    is Op.PathExpression -> {
                        // PathExpression.apply(): sample the two expressions into the path pool.
                        val count = resolveFloat(op.count)
                        val min = resolveFloat(op.min)
                        val max = resolveFloat(op.max)
                        if (!count.isNaN() && count >= 1f && !min.isNaN() && !max.isNaN()) {
                            val x = resolveExpression(op.expressionX)
                            val y = resolveExpression(op.expressionY)
                            val kind = PathGenerator.kindOf(op.flags)
                            val loop = (op.flags and 1) == 1
                            val commands = if ((op.flags and 8) == 8) {
                                PathGenerator.generatePolar(x, y, min, max, count.toInt(), kind, loop, context)
                            } else {
                                PathGenerator.generate(x, y, min, max, count.toInt(), kind, loop, context)
                            }
                            // An unsupported operator anywhere in either expression samples to
                            // NaN; storing that would draw nothing but hide the reason.
                            if (PathGenerator.isFinite(commands)) pathPool[op.id] = commands else pathPool.remove(op.id)
                        }
                    }

                    is Op.ShaderData -> {
                        // Decoded and kept, never painted: see the operation's KDoc.
                        context.shaders[op.id] = op
                    }

                    is Op.MatrixConstant -> {
                        // MatrixConstant.apply(): the values, each of which may be a float id.
                        context.matrices[op.id] = resolveExpression(op.values)
                    }

                    is Op.MatrixExpression -> {
                        // MatrixExpression.apply(): run the matrix machine and store the result.
                        // The matrix operators live outside the float operator range, so they
                        // have to be spared the pool lookup by hand.
                        val resolved = FloatArray(op.expression.size) { k ->
                            val v = op.expression[k]
                            if (!MatrixExpressionEvaluator.isOperator(v) && FloatExpressionEvaluator.isVariable(v)) {
                                resolveFloat(v)
                            } else {
                                v
                            }
                        }
                        val matrix = MatrixExpressionEvaluator.eval(resolved)
                        if (matrix != null) context.matrices[op.id] = matrix.values.copyOf()
                    }

                    is Op.MatrixVectorMath -> {
                        // MatrixVectorMath.apply(): transform the inputs and store each component
                        // of the result under its own float id.
                        val values = context.matrices[op.matrixId]
                        if (values != null) {
                            val matrix = Matrix4()
                            matrix.copyFrom(values)
                            val inputs = resolveExpression(op.inputs)
                            val outputs = FloatArray(op.outputs.size)
                            if (op.type == 0) {
                                matrix.transformPoint(inputs, outputs)
                            } else {
                                matrix.transformPerspective(inputs, outputs)
                            }
                            for ((k, id) in op.outputs.withIndex()) context.loadFloat(id, outputs[k])
                        }
                    }

                    is Op.ParticlesCreate -> {
                        // ParticlesCreate.paint(): every particle is created from its equations,
                        // which see its own index. The real operation does this each frame too,
                        // so a system only moves through time in its equations or its loop.
                        val system = ParticleSystem(op.varIds, op.particleCount)
                        val equations = op.equations.map { resolveExpression(it) }
                        for (p in 0 until system.count) system.initialize(p, equations)
                        context.particles[op.id] = system
                    }

                    is Op.ParticlesLoop -> {
                        // ParticlesLoop.paint(): advance each particle, then run the block once
                        // with that particle's variables in the pool.
                        val end = scopeEnds[i] ?: to
                        val system = context.particles[op.id]
                        val create = particleCreators[op.id]
                        if (system != null) {
                            for (p in 0 until system.count) {
                                system.load(context, p)
                                // Resolved against the particle as it stands, so the equations
                                // advance it together rather than in sequence.
                                val equations = op.equations.map { resolveExpression(it) }
                                val restart = resolveExpression(op.restart)
                                for (v in system.values[p].indices) {
                                    val equation = equations.getOrNull(v) ?: continue
                                    val value = FloatExpressionEvaluator.eval(equation, collections = context)
                                    system.values[p][v] = value
                                    context.loadFloat(system.varIds[v], value)
                                }
                                if (restart.isNotEmpty() &&
                                    FloatExpressionEvaluator.eval(restart, collections = context) > 0f && create != null
                                ) {
                                    system.initialize(p, create.map { resolveExpression(it) })
                                    system.load(context, p)
                                }
                                walk(ops, i + 1, end)
                            }
                            context.needsRepaint = true
                        }
                        i = end // the loop's own CONTAINER_END
                    }

                    is Op.ParticlesCompare -> {
                        // ParticlesCompare.condition1Body(): the block runs for the particles in
                        // range whose expression comes out above zero, after equations1 has
                        // advanced them. The two-body form is decoded but not run.
                        val end = scopeEnds[i] ?: to
                        val system = context.particles[op.id]
                        if (system != null && op.equations2.isEmpty()) {
                            val min = resolveFloat(op.min)
                            val max = resolveFloat(op.max)
                            val from = if (min.isNaN() || min <= 0f) 0 else min.toInt()
                            val to = if (max.isNaN() || max >= system.count) system.count else max.toInt()
                            var matched = false
                            for (p in from until to) {
                                system.load(context, p)
                                val test = resolveExpression(op.expression)
                                if (FloatExpressionEvaluator.eval(test, collections = context) <= 0f) continue
                                matched = true
                                val equations = op.equations1.map { resolveExpression(it) }
                                for (v in system.values[p].indices) {
                                    val equation = equations.getOrNull(v) ?: continue
                                    val value = FloatExpressionEvaluator.eval(equation, collections = context)
                                    system.values[p][v] = value
                                    context.loadFloat(system.varIds[v], value)
                                }
                                walk(ops, i + 1, end)
                            }
                            if (matched) context.needsRepaint = true
                        }
                        i = end // the block's own CONTAINER_END
                    }

                    is Op.FloatFunctionDefine -> i = scopeEnds[i] ?: to

                    is Op.FloatFunctionCall -> {
                        // FloatFunctionCall.paint(): each argument is written into the function's
                        // matching argument id, then the body's value operations run.
                        val function = functions[op.id]
                        val body = functionBodies[op.id]
                        if (function != null && body != null && executing.add(op.id)) {
                            for ((k, arg) in op.args.withIndex()) {
                                val target = function.argIds.getOrNull(k) ?: break
                                context.loadFloat(target, resolveFloat(arg))
                            }
                            walk(operations, body.first, body.last + 1)
                            executing.remove(op.id)
                        }
                    }

                    is Op.FloatListData -> {
                        // DataListFloat.apply(): registered as it was read. Entries written as
                        // ids stay ids, which is what the library hands back too.
                        context.floatLists[op.id] = op.values.copyOf()
                    }

                    is Op.DynamicFloatList -> {
                        // DataDynamicListFloat.updateVariables(): a new list of zeros whenever
                        // its length changes, so that what UpdateDynamicFloatList wrote survives
                        // a frame in which the length did not.
                        val length = resolveFloat(op.length)
                        if (!length.isNaN() && length >= 0f && length <= MAX_LIST_LENGTH) {
                            val size = length.toInt()
                            if (context.floatLists[op.id]?.size != size) context.floatLists[op.id] = FloatArray(size)
                        }
                    }

                    is Op.UpdateDynamicFloatList -> {
                        val list = context.floatLists[op.arrayId]
                        val index = resolveFloat(op.index)
                        val value = resolveFloat(op.value)
                        if (list != null && !index.isNaN() && index >= 0f && index < list.size) {
                            list[index.toInt()] = value
                        }
                    }

                    is Op.PatternDefine -> i = scopeEnds[i] ?: to

                    is Op.PatternCall -> {
                        // PatternInflation.materialize(): bind the arguments to the pattern's
                        // parameters, collect the blocks this call supplies, and walk the body.
                        val end = scopeEnds[i] ?: to
                        val pattern = patterns[op.id]
                        if (pattern != null && expansionDepth < MAX_EXPANSION_DEPTH) {
                            val outerBindings = HashMap(itemBindings)
                            for ((k, paramId) in pattern.paramIds.withIndex()) {
                                val argument = op.argIds.getOrNull(k) ?: break
                                // The argument is named in the caller's terms, which inside
                                // another pattern may itself be a binding.
                                val bound = itemId(argument)
                                context.aliasId(paramId, bound)
                                itemBindings[paramId] = bound
                            }
                            activeCalls.addLast(ActiveCall(collectBlocks(ops, i + 1, end, scopeEnds), outerBindings))
                            expansionDepth++
                            walk(pattern.body, 0, pattern.body.size)
                            expansionDepth--
                            activeCalls.removeLast()
                            itemBindings.clear()
                            itemBindings.putAll(outerBindings)
                        }
                        i = end // the call's own CONTAINER_END
                    }

                    is Op.PatternArgument -> {
                        // The block the call supplied for this slot, or nothing. A block was
                        // written where the call is, so it is walked as the caller: with the
                        // caller's bindings, and with the caller's own slots to fill rather than
                        // this pattern's, which is what makes a pattern that passes a block on
                        // to another one work.
                        val call = activeCalls.lastOrNull()
                        val block = call?.blocks?.get(op.paramIndex)
                        if (call != null && block != null) {
                            val insideBindings = HashMap(itemBindings)
                            itemBindings.clear()
                            itemBindings.putAll(call.callerBindings)
                            activeCalls.removeLast()
                            expansionDepth--
                            walk(block.first, block.second, block.third)
                            expansionDepth++
                            activeCalls.addLast(call)
                            itemBindings.clear()
                            itemBindings.putAll(insideBindings)
                        }
                    }

                    // A block is walked where the pattern's body asks for it, not where it sits.
                    is Op.PatternBlock -> i = scopeEnds[i] ?: to

                    is Op.PatternForEach -> {
                        // PatternForEach.materialize(): once per entry of the list, with the
                        // local item standing for that entry.
                        val end = scopeEnds[i] ?: to
                        val entries = idListPool[op.collectionId]
                        if (entries != null) {
                            for (entryId in entries.take(MAX_LOOP_ITERATIONS)) {
                                context.aliasId(op.localItemId, entryId)
                                itemBindings[op.localItemId] = entryId
                                walk(ops, i + 1, end)
                            }
                            itemBindings.remove(op.localItemId)
                        }
                        i = end // the block's own CONTAINER_END
                    }

                    is Op.LoopStart -> {
                        // LoopOperation: re-apply the body once per index with the loop variable set.
                        val end = scopeEnds[i] ?: to
                        val from = resolveFloat(op.from)
                        val step = resolveFloat(op.step)
                        val until = resolveFloat(op.until)
                        if (!from.isNaN() && !step.isNaN() && !until.isNaN() && step > 0f) {
                            var value = from
                            var iterations = 0
                            while (value < until && iterations < MAX_LOOP_ITERATIONS) {
                                context.loadFloat(op.indexVariableId, value)
                                walk(ops, i + 1, end)
                                value += step
                                iterations++
                            }
                        }
                        i = end // the loop's own CONTAINER_END
                    }

                    is Op.ModifierWidth, is Op.ModifierHeight -> tree.current?.let { node ->
                        val dimension = Dimension(DimensionType.fromWire(op.mode), resolveFloat(op.value))
                        val previous = if (op is Op.ModifierWidth) node.widthDimension else node.heightDimension
                        dimension.rangeMin = previous.rangeMin
                        dimension.rangeMax = previous.rangeMax
                        if (op is Op.ModifierWidth) node.widthDimension = dimension else node.heightDimension = dimension
                    }

                    is Op.ModifierWidthIn, is Op.ModifierHeightIn -> tree.current?.let { node ->
                        val dimension = if (op is Op.ModifierWidthIn) node.widthDimension else node.heightDimension
                        dimension.rangeMin = resolveFloat(op.min)
                        dimension.rangeMax = resolveFloat(op.max)
                    }

                    is Op.ModifierDimensionConstraints -> tree.current?.let { node ->
                        val dimension = when (op.type) {
                            0, 2 -> node.widthDimension // HORIZONTAL, REQUIRED_HORIZONTAL
                            else -> node.heightDimension
                        }
                        dimension.rangeMin = resolveFloat(op.min)
                        dimension.rangeMax = resolveFloat(op.max)
                    }

                    is Op.ModifierPadding -> tree.current?.modifiers?.add(
                        Modifier.Padding(
                            resolveFloat(op.left), resolveFloat(op.top), resolveFloat(op.right), resolveFloat(op.bottom),
                        ),
                    )

                    is Op.ModifierBackground -> {
                        val color = if (op.colorIdFlag != 0) colorPool[op.colorId] else null
                        tree.current?.modifiers?.add(Modifier.Background(color ?: Color(op.r, op.g, op.b, op.a), op.shapeType))
                    }

                    is Op.ModifierBorder -> {
                        val color = if (op.colorRefFlag == 2) colorPool[op.colorId] else Color(op.r, op.g, op.b, op.a)
                        if (color != null) {
                            tree.current?.modifiers?.add(
                                Modifier.Border(color, resolveFloat(op.borderWidth), resolveFloat(op.roundedCorner), op.shapeType),
                            )
                        }
                    }

                    is Op.ModifierVisibility -> tree.current?.let { node ->
                        // ComponentVisibilityOperation reads an int variable; a bare Visibility value
                        // that is not a known id is taken literally.
                        val raw = intPool[op.visibility] ?: op.visibility
                        node.visibility = when (raw and 0xF) {
                            Visibility.VISIBLE -> Visibility.VISIBLE
                            Visibility.INVISIBLE -> Visibility.INVISIBLE
                            else -> Visibility.GONE
                        }
                    }

                    is Op.ModifierOffset -> tree.current?.modifiers?.add(Modifier.Offset(resolveFloat(op.x), resolveFloat(op.y)))

                    is Op.ModifierScroll -> {
                        // The position is a NaN-tagged reference to the float the modifier's own
                        // touch expression writes, so the id is what matters rather than a value.
                        tree.current?.modifiers?.add(
                            Modifier.Scroll(
                                op.direction,
                                FloatExpressionEvaluator.idOf(op.positionExpression),
                                FloatExpressionEvaluator.idOf(op.max),
                                FloatExpressionEvaluator.idOf(op.notchMax),
                            ),
                        )
                        // The modifier is a container holding that touch expression: opening a
                        // frame keeps the component current while it is walked, and lets the
                        // ContainerEnd after it close this rather than the component.
                        tree.openContent(paint)
                    }

                    is Op.ModifierClipRect -> tree.current?.modifiers?.add(Modifier.ClipRect())

                    is Op.ModifierRoundedClipRect -> tree.current?.modifiers?.add(
                        Modifier.RoundedClipRect(
                            resolveFloat(op.topStart), resolveFloat(op.topEnd),
                            resolveFloat(op.bottomStart), resolveFloat(op.bottomEnd),
                        ),
                    )

                    is Op.ModifierZIndex -> tree.current?.modifiers?.add(Modifier.ZIndex(resolveFloat(op.zIndex)))

                    is Op.ModifierCollapsiblePriority -> tree.current?.modifiers?.add(
                        Modifier.CollapsiblePriority(op.orientation, resolveFloat(op.priority)),
                    )

                    is Op.ModifierGraphicsLayer -> tree.current?.modifiers?.add(
                        Modifier.GraphicsLayer(op.attributes.associate { it.tag to it.rawValue }),
                    )
                }
                // Inside a pattern, what the body just declared belongs to this expansion alone:
                // it keeps the value under an id of its own, so that an id kept for later — a
                // text component resolves its string long after the call has returned — reads
                // this copy rather than whichever call ran last.
                if (expansionDepth > 0) {
                    declaredIdOf(op)?.let { declared ->
                        val fresh = context.nextGeneratedId()
                        context.aliasId(fresh, declared)
                        itemBindings[declared] = fresh
                    }
                }
                i++
            }
        }
        walk(operations, 0, operations.size)
        opcodes += tree.flush(paint)
        hitRegions = tree.hitRegions
        rippleTargets = tree.rippleTargets
        opcodes += trailing
        context.inflated = true
        return opcodes
    }

    private const val DEFAULT_LAYOUT_TEXT_SIZE = 16f
    /**
     * The id an operation defines, for the operations that define one.
     *
     * `RemapContext.declareId` is the library's version of this: inside a pattern it gives every
     * declaration a fresh id, so that two calls of the same pattern do not write to one slot.
     * Component ids are left out on purpose — this renderer keys nothing on them, so renaming
     * them would change nothing — as is anything a pattern body cannot declare.
     */
    private fun declaredIdOf(op: Op): Int? = when (op) {
        is Op.TextData -> op.id
        is Op.FloatConstant -> op.id
        is Op.ColorConstant -> op.colorId
        is Op.IdList -> op.id
        is Op.FloatListData -> op.id
        is Op.DynamicFloatList -> op.id
        is Op.DataMapIds -> op.mapId
        is Op.PathCreate -> op.pathId
        is Op.TextSubtext -> op.textId
        is Op.TextTransform -> op.textId
        is Op.TextLength -> op.lengthId
        is Op.TextMeasure -> op.id
        is Op.TextLookup -> op.textId
        is Op.TextLookupInt -> op.textId
        is Op.TextMerge -> op.textId
        is Op.TextFromFloat -> op.textId
        is Op.ColorExpression -> op.id
        is Op.IntegerExpression -> op.id
        is Op.FloatExpression -> op.id
        is Op.ShaderData -> op.id
        else -> null
    }

    /** A `PatternCall` being expanded: the blocks it passed, and the scope it was written in. */
    private class ActiveCall(
        val blocks: Map<Int, Triple<List<Op>, Int, Int>>,
        val callerBindings: Map<Int, Int>,
    )

    /** `ExpansionContext.MAX_EXPANSION_DEPTH`: how deep patterns may call one another. */
    private const val MAX_EXPANSION_DEPTH = 64

    /**
     * `ExpansionContext.recordBlocks`: the blocks a call supplies, by the slot each fills. A
     * block is kept as the range it occupies rather than copied out, since it is walked in place.
     */
    private fun collectBlocks(
        ops: List<Op>,
        from: Int,
        to: Int,
        scopeEnds: Map<Int, Int>,
    ): Map<Int, Triple<List<Op>, Int, Int>> {
        val blocks = HashMap<Int, Triple<List<Op>, Int, Int>>()
        var i = from
        while (i < to) {
            val op = ops[i]
            if (op is Op.PatternBlock) {
                val end = scopeEnds[i] ?: to
                blocks[op.paramIndex] = Triple(ops, i + 1, end)
                i = end + 1
            } else {
                i++
            }
        }
        return blocks
    }

    /** `DataDynamicListFloat`'s own cap on how long a list it will allocate. */
    private const val MAX_LIST_LENGTH = 2000f

    private const val MAX_LOOP_ITERATIONS = 1000

    /**
     * Index of the `CONTAINER_END` closing each scope-opening operation, so a loop body can be
     * walked repeatedly without re-scanning for its end.
     */
    private fun matchScopes(operations: List<Op>): Map<Int, Int> {
        val ends = HashMap<Int, Int>()
        val open = ArrayDeque<Int>()
        for ((i, op) in operations.withIndex()) {
            when (op) {
                is Op.LayoutRoot, is Op.LayoutColumn, is Op.LayoutRow, is Op.LayoutCollapsibleColumn,
                is Op.LayoutCollapsibleRow, is Op.LayoutFlow, is Op.LayoutBox, is Op.LayoutFitBox,
                is Op.LayoutText, is Op.LayoutImage, is Op.LayoutCanvas, is Op.LayoutCustom, is Op.LayoutState,
                is Op.LayoutContent, is Op.LayoutCanvasContent, is Op.CanvasOperations, is Op.LoopStart, is Op.ConditionalOperations,
                is Op.ModifierClick, is Op.ModifierMultiClick, is Op.ModifierTouchDown, is Op.ModifierTouchUp,
                is Op.ModifierTouchCancel, is Op.ModifierScroll, is Op.FloatFunctionDefine, is Op.ParticlesLoop,
                is Op.ParticlesCompare, is Op.PatternForEach, is Op.PatternDefine, is Op.PatternCall,
                is Op.PatternBlock -> open.addLast(i)
                is Op.ContainerEnd -> open.removeLastOrNull()?.let { ends[it] = i }
                else -> Unit
            }
        }
        return ends
    }

    /**
     * Operations whose only effect is to define a pool value once. They are skipped after the
     * first pass so that later writes to the same id (an animation, an action) are not undone
     * every frame; `PathCreate`/`PathAdd` are included because re-applying them would append
     * segments again.
     */
    private fun Op.isConstant(): Boolean = when (this) {
        is Op.TextData, is Op.FloatConstant, is Op.IntegerConstant, is Op.BooleanConstant,
        is Op.LongConstant, is Op.ColorConstant, is Op.BitmapData, is Op.PathData,
        is Op.PathCreate, is Op.PathAdd, is Op.IdList, is Op.DataMapIds, is Op.BitmapFontData, is Op.ShaderData,
        is Op.FloatListData -> true
        else -> false
    }

    // Op.PathTween's real semantic (matching real android.graphics.Path.interpolate()'s own
    // contract): both paths need the identical command sequence — same count, same kind at every
    // index — to linearly interpolate; null (this parser's own honest "leave unresolved" fallback)
    // otherwise, the same way real Path.canInterpolate() refuses a structural mismatch.
    private fun lerpPath(a: List<PathCommand>?, b: List<PathCommand>?, t: Float): List<PathCommand>? {
        if (a == null || b == null || a.size != b.size) return null
        fun lerp(x: Float, y: Float) = x + (y - x) * t
        return a.zip(b).map { (ca, cb) ->
            when {
                ca is PathCommand.MoveTo && cb is PathCommand.MoveTo ->
                    PathCommand.MoveTo(lerp(ca.x, cb.x), lerp(ca.y, cb.y))
                ca is PathCommand.LineTo && cb is PathCommand.LineTo ->
                    PathCommand.LineTo(lerp(ca.x, cb.x), lerp(ca.y, cb.y))
                ca is PathCommand.QuadraticTo && cb is PathCommand.QuadraticTo ->
                    PathCommand.QuadraticTo(
                        lerp(ca.x1, cb.x1), lerp(ca.y1, cb.y1),
                        lerp(ca.x2, cb.x2), lerp(ca.y2, cb.y2),
                    )
                ca is PathCommand.CubicTo && cb is PathCommand.CubicTo ->
                    PathCommand.CubicTo(
                        lerp(ca.x1, cb.x1), lerp(ca.y1, cb.y1),
                        lerp(ca.x2, cb.x2), lerp(ca.y2, cb.y2),
                        lerp(ca.x3, cb.x3), lerp(ca.y3, cb.y3),
                    )
                ca is PathCommand.Close && cb is PathCommand.Close -> PathCommand.Close
                else -> return null
            }
        }
    }

    // Op.ColorExpression's real gamma-2.2-corrected color interpolation (source-confirmed via
    // javap on the real Utils.interpolateColor()) — NOT a naive linear RGB lerp: each channel is
    // decoded to linear light (channel/255)^2.2, lerped there, then re-encoded ^(1/2.2) before
    // clamping back to a byte. `tween` of exactly 0f/NaN or 1f short-circuits to the input color
    // unchanged (matching the real method's own guard).
    private fun interpolateColorArgb(color1: Int, color2: Int, tween: Float): Int {
        if (tween.isNaN() || tween == 0f) return color1
        if (tween == 1f) return color2
        fun channel(c: Int, shift: Int): Float = ((c ushr shift) and 0xFF) / 255f
        fun gamma(v: Float): Float = v.toDouble().pow(2.2).toFloat()
        val a1 = channel(color1, 24); val r1 = gamma(channel(color1, 16))
        val g1 = gamma(channel(color1, 8)); val b1 = gamma(channel(color1, 0))
        val a2 = channel(color2, 24); val r2 = gamma(channel(color2, 16))
        val g2 = gamma(channel(color2, 8)); val b2 = gamma(channel(color2, 0))
        val aOut = (a1 + tween * (a2 - a1))
        val rOut = (r1 + tween * (r2 - r1)).toDouble().pow(1.0 / 2.2).toFloat()
        val gOut = (g1 + tween * (g2 - g1)).toDouble().pow(1.0 / 2.2).toFloat()
        val bOut = (b1 + tween * (b2 - b1)).toDouble().pow(1.0 / 2.2).toFloat()
        fun toByte(v: Float): Int = (v * 255f).toInt().coerceIn(0, 255)
        return (toByte(aOut) shl 24) or (toByte(rOut) shl 16) or (toByte(gOut) shl 8) or toByte(bOut)
    }

    // Op.ColorExpression's real HSV-to-RGB conversion (source-confirmed via javap on the real
    // Utils.hsvToRgb()) — `hue` a 0f..1f wheel fraction (not degrees). Returns a fully opaque
    // (0xFF alpha) ARGB int; the caller overwrites the alpha byte with the real op's own alpha
    // field. Matches the real method's own edge case: `hue` of exactly 1f (segment index 6, one
    // past the last hexagon wedge) returns plain transparent-black `0`, not a wrapped-around
    // segment 0.
    private fun hsvToRgbArgb(hue: Float, sat: Float, value: Float): Int {
        val hh = hue * 6f
        val i = hh.toInt()
        val f = hh - i
        val p = (0.5f + 255f * value * (1f - sat)).toInt()
        val q = (0.5f + 255f * value * (1f - f * sat)).toInt()
        val t = (0.5f + 255f * value * (1f - (1f - f) * sat)).toInt()
        val v = (0.5f + 255f * value).toInt()
        return when (i) {
            0 -> -0x1000000 or (v shl 16) or (t shl 8) or p
            1 -> -0x1000000 or (q shl 16) or (v shl 8) or p
            2 -> -0x1000000 or (p shl 16) or (v shl 8) or t
            3 -> -0x1000000 or (p shl 16) or (q shl 8) or v
            4 -> -0x1000000 or (t shl 16) or (p shl 8) or v
            5 -> -0x1000000 or (v shl 16) or (p shl 8) or q
            else -> 0
        }
    }

    // Op.IntegerExpression's real RPN stack-machine step (source-confirmed via javap on the real
    // IntegerExpressionEvaluator.opEval()): binary ops pop stack[sp-1]/stack[sp], push 1 result at
    // sp-1 (returns sp-1); unary ops rewrite stack[sp] in place (returns sp unchanged);
    // CLAMP/IFELSE/MAD pop 3 (stack[sp-2..sp]), push 1 result at sp-2 (returns sp-2). Returns null
    // for an out-of-bounds pop (malformed expression) or VAR1/VAR2/VAR3 (only meaningful inside a
    // loop/foreach evaluation context this parser doesn't implement).
    private fun evalIntegerOp(stack: IntArray, sp: Int, op: Int): Int? {
        fun binary(f: (Int, Int) -> Int): Int? {
            if (sp < 1) return null
            stack[sp - 1] = f(stack[sp - 1], stack[sp])
            return sp - 1
        }

        fun unary(f: (Int) -> Int): Int? {
            if (sp < 0) return null
            stack[sp] = f(stack[sp])
            return sp
        }
        return when (op) {
            65537 -> binary { a, b -> a + b } // I_ADD
            65538 -> binary { a, b -> a - b } // I_SUB
            65539 -> binary { a, b -> a * b } // I_MUL
            65540 -> binary { a, b -> if (b == 0) 0 else a / b } // I_DIV
            65541 -> binary { a, b -> if (b == 0) 0 else a % b } // I_MOD
            65542 -> binary { a, b -> a shl b } // I_SHL
            65543 -> binary { a, b -> a shr b } // I_SHR
            65544 -> binary { a, b -> a ushr b } // I_USHR
            65545 -> binary { a, b -> a or b } // I_OR
            65546 -> binary { a, b -> a and b } // I_AND
            65547 -> binary { a, b -> a xor b } // I_XOR
            65548 -> binary { a, b -> (a xor (b shr 31)) - (b shr 31) } // I_COPY_SIGN
            65549 -> binary { a, b -> min(a, b) } // I_MIN
            65550 -> binary { a, b -> max(a, b) } // I_MAX
            65551 -> unary { a -> -a } // I_NEG
            65552 -> unary { a -> abs(a) } // I_ABS
            65553 -> unary { a -> a + 1 } // I_INCR
            65554 -> unary { a -> a - 1 } // I_DECR
            65555 -> unary { a -> a.inv() } // I_NOT
            65556 -> unary { a -> (a shr 31) or (-a ushr 31) } // I_SIGN
            65557 -> { // I_CLAMP: min(max(value, lo), hi)
                if (sp < 2) null else {
                    stack[sp - 2] = min(max(stack[sp - 2], stack[sp - 1]), stack[sp])
                    sp - 2
                }
            }

            65558 -> { // I_IFELSE: condition > 0 ? thenVal : elseVal
                if (sp < 2) null else {
                    stack[sp - 2] = if (stack[sp - 2] > 0) stack[sp - 1] else stack[sp]
                    sp - 2
                }
            }

            65559 -> { // I_MAD: a*b + c (a=top, b=second, c=third)
                if (sp < 2) null else {
                    stack[sp - 2] = stack[sp] * stack[sp - 1] + stack[sp - 2]
                    sp - 2
                }
            }

            else -> null // I_VAR1/I_VAR2/I_VAR3 (only meaningful inside a loop context) or unknown
        }
    }

    // Op.DrawTweenPath's real start/stop trim (matching Android's own well-documented
    // PathMeasure.getSegment()): keeps only the [start, stop) fraction of the path's own total
    // arc length, rebuilt as a polyline (MoveTo + LineTo per flattened vertex, the same
    // curve-flattening [PathGeometry.flatten] already performs elsewhere) — start == 0f && stop ==
    // 1f (no real trim) returns the original commands unchanged, so untrimmed callers keep their
    // own exact Quadratic/CubicTo curves instead of an unnecessarily-flattened approximation.
    private fun trimPath(commands: List<PathCommand>, start: Float, stop: Float): List<PathCommand> {
        if (start <= 0f && stop >= 1f) return commands
        val segments = PathGeometry.flatten(commands)
        val lengths = segments.map { sqrt((it[2] - it[0]) * (it[2] - it[0]) + (it[3] - it[1]) * (it[3] - it[1])) }
        val totalLength = lengths.sum()
        if (totalLength <= 0f) return commands
        val startDist = start.coerceIn(0f, 1f) * totalLength
        val stopDist = stop.coerceIn(0f, 1f) * totalLength
        val vertices = mutableListOf<FloatArray>()
        var accumulated = 0f
        for (i in segments.indices) {
            val seg = segments[i]
            val segStart = accumulated
            val segEnd = accumulated + lengths[i]
            // segStart < stopDist (strict): a segment that starts exactly at the stop boundary
            // contributes zero real length inside [start, stop) and must be excluded, or its own
            // tEnd == 0 vertex would duplicate the previous segment's own already-added endpoint.
            if (segEnd >= startDist && segStart < stopDist) {
                val tStart = if (lengths[i] > 0f) ((startDist - segStart) / lengths[i]).coerceIn(0f, 1f) else 0f
                val tEnd = if (lengths[i] > 0f) ((stopDist - segStart) / lengths[i]).coerceIn(0f, 1f) else 1f
                if (vertices.isEmpty()) {
                    vertices += floatArrayOf(seg[0] + (seg[2] - seg[0]) * tStart, seg[1] + (seg[3] - seg[1]) * tStart)
                }
                vertices += floatArrayOf(seg[0] + (seg[2] - seg[0]) * tEnd, seg[1] + (seg[3] - seg[1]) * tEnd)
            }
            accumulated = segEnd
        }
        if (vertices.size < 2) return emptyList()
        return listOf(PathCommand.MoveTo(vertices[0][0], vertices[0][1])) +
            vertices.drop(1).map { PathCommand.LineTo(it[0], it[1]) }
    }

    // Op.PathCombine's OP_INTERSECT: the textbook Sutherland-Hodgman polygon-clipping algorithm
    // (real, well-known computational geometry — not a guess), correct for any simple subject
    // polygon clipped against a *convex* clip polygon. [ensureCcw] normalizes winding first since
    // the algorithm's own "inside" test assumes a consistent (counter-clockwise) orientation —
    // real callers may author either winding.
    private fun ensureCcw(poly: List<FloatArray>): List<FloatArray> {
        var area = 0f
        for (i in poly.indices) {
            val p1 = poly[i]
            val p2 = poly[(i + 1) % poly.size]
            area += p1[0] * p2[1] - p2[0] * p1[1]
        }
        return if (area < 0f) poly.reversed() else poly
    }

    private fun sutherlandHodgmanIntersect(subject: List<FloatArray>, clip: List<FloatArray>): List<FloatArray> {
        if (subject.size < 3 || clip.size < 3) return emptyList()
        fun isInside(p: FloatArray, a: FloatArray, b: FloatArray) =
            (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0f
        fun intersection(p1: FloatArray, p2: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
            val denom = (p1[0] - p2[0]) * (a[1] - b[1]) - (p1[1] - p2[1]) * (a[0] - b[0])
            if (denom == 0f) return p2
            val t = ((p1[0] - a[0]) * (a[1] - b[1]) - (p1[1] - a[1]) * (a[0] - b[0])) / denom
            return floatArrayOf(p1[0] + t * (p2[0] - p1[0]), p1[1] + t * (p2[1] - p1[1]))
        }
        val clipCcw = ensureCcw(clip)
        var output = ensureCcw(subject)
        for (i in clipCcw.indices) {
            if (output.isEmpty()) break
            val a = clipCcw[i]
            val b = clipCcw[(i + 1) % clipCcw.size]
            val input = output
            val next = mutableListOf<FloatArray>()
            for (j in input.indices) {
                val current = input[j]
                val prev = input[(j - 1 + input.size) % input.size]
                val currentInside = isInside(current, a, b)
                val prevInside = isInside(prev, a, b)
                if (currentInside) {
                    if (!prevInside) next += intersection(prev, current, a, b)
                    next += current
                } else if (prevInside) {
                    next += intersection(prev, current, a, b)
                }
            }
            output = next
        }
        return output
    }
}
