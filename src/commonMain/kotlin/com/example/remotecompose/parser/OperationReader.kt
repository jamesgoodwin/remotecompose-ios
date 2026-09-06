package com.example.remotecompose.parser

import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.text.BitmapGlyph
import com.example.remotecompose.parser.Operation as Op

/**
 * Decodes a `.rc` byte stream into [Operation]s, one per record, with no evaluation: the
 * equivalent of `RemoteComposeBuffer.inflateFromBuffer` dispatching to each operation's `read`.
 *
 * Every record is one opcode byte followed by that opcode's fixed fields (see [Operations] and
 * each [Operation] class). An opcode not listed here is a hard failure, since the format has no
 * generic length prefix to skip by.
 */
internal object OperationReader {

    fun readAll(bytes: ByteArray): List<Op> {
        val reader = BufferReader(bytes)
        val out = mutableListOf<Op>()
        while (reader.hasRemaining()) {
            out += readOne(reader)
        }
        return out
    }

    private fun readOne(r: BufferReader): Op = when (val opId = r.readU8()) {
        Operations.HEADER -> Op.Header(
            majorVersion = r.readS32(), minorVersion = r.readS32(), patchVersion = r.readS32(),
            width = r.readS32(), height = r.readS32(), capabilities = r.readS64(),
        )
        Operations.DATA_TEXT -> {
            val id = r.readS32()
            val length = r.readS32()
            Op.TextData(id, r.readUtf8(length))
        }
        Operations.TEXT_SUBTEXT -> Op.TextSubtext(r.readS32(), r.readS32(), r.readFloat32(), r.readFloat32())
        Operations.TEXT_TRANSFORM -> Op.TextTransform(r.readS32(), r.readS32(), r.readFloat32(), r.readFloat32(), r.readS32())
        Operations.ROOT_CONTENT_DESCRIPTION -> Op.RootContentDescription(r.readS32())
        Operations.PAINT_VALUES -> {
            val wordCount = r.readS32()
            Op.PaintData(IntArray(wordCount) { r.readS32() })
        }
        Operations.DRAW_RECT -> Op.DrawRect(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.DRAW_CIRCLE -> Op.DrawCircle(r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.DRAW_ROUND_RECT -> Op.DrawRoundRect(
            r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(),
        )
        Operations.DRAW_TEXT_RUN -> Op.DrawTextRun(
            textId = r.readS32(), start = r.readS32(), end = r.readS32(),
            contextStart = r.readS32(), contextEnd = r.readS32(),
            x = r.readFloat32(), y = r.readFloat32(), rtl = r.readU8() != 0,
        )
        Operations.DRAW_TEXT_ANCHOR -> Op.DrawTextAnchored(
            r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readS32(),
        )
        Operations.DRAW_TEXT_ON_CIRCLE -> Op.DrawTextOnCircle(
            textId = r.readS32(), centerX = r.readFloat32(), centerY = r.readFloat32(), radius = r.readFloat32(),
            startAngleDegrees = r.readFloat32(), warpRadiusOffset = r.readFloat32(),
            alignment = r.readU8(), placement = r.readU8(),
        )
        Operations.DRAW_TEXT_ON_PATH -> Op.DrawTextOnPath(r.readS32(), r.readS32(), r.readFloat32(), r.readFloat32())
        Operations.DRAW_LINE -> Op.DrawLine(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.DRAW_OVAL -> Op.DrawOval(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.DRAW_ARC -> Op.DrawArc(
            r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(),
        )
        Operations.DRAW_SECTOR -> Op.DrawSector(
            r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(),
        )
        Operations.DATA_PATH -> {
            val pathId = r.readS32()
            val floatCount = r.readS32()
            Op.PathData(pathId, readPathArray(r, floatCount))
        }
        Operations.PATH_CREATE -> Op.PathCreate(r.readS32(), r.readFloat32(), r.readFloat32())
        Operations.PATH_ADD -> {
            val pathId = r.readS32()
            val floatCount = r.readS32()
            Op.PathAdd(pathId, readPathArray(r, floatCount))
        }
        Operations.PATH_TWEEN -> Op.PathTween(r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.MATRIX_FROM_PATH -> Op.MatrixFromPath(r.readS32(), r.readFloat32(), r.readFloat32(), r.readS32())
        Operations.DRAW_TWEEN_PATH -> Op.DrawTweenPath(r.readS32(), r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.CONDITIONAL_OPERATIONS -> Op.ConditionalOperations(r.readS8().toByte(), r.readFloat32(), r.readFloat32())
        Operations.PATH_COMBINE -> Op.PathCombine(r.readS32(), r.readS32(), r.readS32(), r.readS8())
        Operations.DRAW_PATH -> Op.DrawPath(r.readS32())
        Operations.CLIP_PATH -> Op.ClipPath(r.readS32())
        Operations.DATA_BITMAP -> {
            val bitmapId = r.readS32()
            val width = r.readS32()
            val height = r.readS32()
            val length = r.readS32()
            Op.BitmapData(bitmapId, width, height, r.readBytes(length))
        }
        Operations.DRAW_BITMAP -> Op.DrawBitmap(
            r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readS32(),
        )
        Operations.DRAW_BITMAP_INT -> Op.DrawBitmapInt(
            bitmapId = r.readS32(),
            srcLeft = r.readS32(), srcTop = r.readS32(), srcRight = r.readS32(), srcBottom = r.readS32(),
            dstLeft = r.readS32(), dstTop = r.readS32(), dstRight = r.readS32(), dstBottom = r.readS32(),
            descriptionId = r.readS32(),
        )
        Operations.DRAW_BITMAP_SCALED -> Op.DrawBitmapScaled(
            bitmapId = r.readS32(),
            srcLeft = r.readFloat32(), srcTop = r.readFloat32(), srcRight = r.readFloat32(), srcBottom = r.readFloat32(),
            dstLeft = r.readFloat32(), dstTop = r.readFloat32(), dstRight = r.readFloat32(), dstBottom = r.readFloat32(),
            scaleType = r.readS32(), scaleFactor = r.readFloat32(), descriptionId = r.readS32(),
        )
        Operations.CLICK_AREA -> Op.ClickArea(
            actionId = r.readS32(), contentDescriptionId = r.readS32(),
            left = r.readFloat32(), top = r.readFloat32(), right = r.readFloat32(), bottom = r.readFloat32(),
            metadataTextId = r.readS32(),
        )
        Operations.LAYOUT_COLUMN -> Op.LayoutColumn(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.LAYOUT_ROW -> Op.LayoutRow(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.LAYOUT_COLLAPSIBLE_COLUMN -> Op.LayoutCollapsibleColumn(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.LAYOUT_COLLAPSIBLE_ROW -> Op.LayoutCollapsibleRow(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.LAYOUT_FLOW -> Op.LayoutFlow(
            r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32(), r.readS32(), r.readS32(),
        )
        Operations.LAYOUT_BOX -> Op.LayoutBox(r.readS32(), r.readS32(), r.readS32(), r.readS32())
        Operations.LAYOUT_FIT_BOX -> Op.LayoutFitBox(r.readS32(), r.readS32(), r.readS32(), r.readS32())
        Operations.LAYOUT_TEXT -> Op.LayoutText(
            componentId = r.readS32(), animationId = r.readS32(), textId = r.readS32(), color = r.readS32(),
            fontSize = r.readFloat32(), fontStyle = r.readS32(), fontWeight = r.readFloat32(), fontFamilyId = r.readS32(),
            textAlign = r.readS32(), overflow = r.readS32(), maxLines = r.readS32(),
        )
        Operations.LAYOUT_ROOT -> Op.LayoutRoot(r.readS32())
        Operations.CANVAS_OPERATIONS -> Op.CanvasOperations
        Operations.SKIP -> {
            val conditionType = r.readS32()
            val value = r.readS32()
            val skipLength = r.readS32()
            // Reported API level: Int.MAX_VALUE, i.e. "assume the newest client".
            val ourApiLevel = Int.MAX_VALUE
            val skipped = when (conditionType) {
                1 -> ourApiLevel < value
                2 -> ourApiLevel > value
                3 -> ourApiLevel == value
                4 -> ourApiLevel != value
                else -> false // profile-based conditions: no profile concept here
            }
            if (skipped) r.seek(r.position + skipLength)
            Op.Skip(conditionType, value, skipLength, skipped)
        }
        Operations.REM -> {
            val length = r.readS32()
            Op.Rem(r.readUtf8(length))
        }
        Operations.TEXT_MEASURE -> Op.TextMeasure(r.readS32(), r.readS32(), r.readS32())
        Operations.TEXT_LENGTH -> Op.TextLength(r.readS32(), r.readS32())
        Operations.ID_LIST -> {
            val id = r.readS32()
            val count = r.readS32()
            Op.IdList(id, List(count) { r.readS32() })
        }
        Operations.TEXT_LOOKUP -> Op.TextLookup(r.readS32(), r.readS32(), r.readFloat32())
        Operations.TEXT_LOOKUP_INT -> Op.TextLookupInt(r.readS32(), r.readS32(), r.readS32())
        Operations.TEXT_MERGE -> Op.TextMerge(r.readS32(), r.readS32(), r.readS32())
        Operations.COLOR_EXPRESSIONS -> Op.ColorExpression(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readS32())
        Operations.ID_LOOKUP -> Op.IdLookup(r.readS32(), r.readS32(), r.readFloat32())
        Operations.INTEGER_EXPRESSION -> {
            val id = r.readS32()
            val mask = r.readS32()
            val count = r.readS32()
            Op.IntegerExpression(id, mask, IntArray(count) { r.readS32() })
        }
        Operations.TEXT_FROM_FLOAT -> Op.TextFromFloat(r.readS32(), r.readFloat32(), r.readS32(), r.readS32())
        Operations.ID_MAP -> {
            val mapId = r.readS32()
            val count = r.readS32()
            Op.DataMapIds(
                mapId,
                List(count) {
                    val nameLength = r.readS32()
                    val name = r.readUtf8(nameLength)
                    Op.DataMapEntry(name, type = r.readU8(), valueId = r.readS32())
                },
            )
        }
        Operations.DATA_MAP_LOOKUP -> Op.DataMapLookup(r.readS32(), r.readS32(), r.readS32())
        Operations.DATA_BITMAP_FONT -> {
            val id = r.readS32()
            val packed = r.readS32()
            val version = packed ushr 16
            val glyphCount = packed and 0xFFFF
            val glyphs = List(glyphCount) {
                BitmapGlyph(
                    chars = r.readUtf8(r.readS32()),
                    bitmapId = r.readS32(),
                    marginLeft = r.readS16(), marginTop = r.readS16(),
                    marginRight = r.readS16(), marginBottom = r.readS16(),
                    bitmapWidth = r.readS16(), bitmapHeight = r.readS16(),
                )
            }
            // Version 1 added the kerning table; version 0 documents stop after the glyphs.
            val kerning = if (version >= 1) {
                val pairCount = r.readU16()
                buildMap { repeat(pairCount) { put(r.readUtf8(r.readS32()), r.readS16()) } }
            } else {
                emptyMap()
            }
            Op.BitmapFontData(id, version, glyphs, kerning)
        }
        Operations.DRAW_BITMAP_FONT_TEXT_RUN -> {
            // The text id carries a flag in its top bit: set means a glyph spacing float follows.
            val tagged = r.readS32()
            val textId = tagged and 0x7FFFFFFF
            val glyphSpacing = if (tagged and GLYPH_SPACING_FLAG != 0) r.readFloat32() else 0f
            Op.DrawBitmapFontText(
                textId = textId, fontId = r.readS32(), start = r.readS32(), end = r.readS32(),
                x = r.readFloat32(), y = r.readFloat32(), glyphSpacing = glyphSpacing,
            )
        }
        Operations.DRAW_BITMAP_FONT_TEXT_ON_PATH -> {
            val tagged = r.readS32()
            val textId = tagged and 0x7FFFFFFF
            val glyphSpacing = if (tagged and GLYPH_SPACING_FLAG != 0) r.readFloat32() else 0f
            Op.DrawBitmapFontTextOnPath(
                textId = textId, fontId = r.readS32(), pathId = r.readS32(),
                start = r.readS32(), end = r.readS32(), yAdj = r.readFloat32(), glyphSpacing = glyphSpacing,
            )
        }
        Operations.BITMAP_TEXT_MEASURE -> {
            val tagged = r.readS32()
            val id = tagged and 0x7FFFFFFF
            val glyphSpacing = if (tagged and GLYPH_SPACING_FLAG != 0) r.readFloat32() else 0f
            Op.BitmapTextMeasure(id, textId = r.readS32(), fontId = r.readS32(), type = r.readS32(), glyphSpacing = glyphSpacing)
        }
        Operations.PATH_EXPRESSION -> {
            val id = r.readS32()
            val flags = r.readS32()
            val min = r.readFloat32()
            val max = r.readFloat32()
            val count = r.readFloat32()
            val lengthX = r.readS32()
            if (lengthX > 32) throw RemoteComposeParseException("Path expression $id has a $lengthX-entry x expression (max 32)")
            val expressionX = FloatArray(lengthX) { r.readFloat32() }
            val lengthY = r.readS32()
            if (lengthY > 32) throw RemoteComposeParseException("Path expression $id has a $lengthY-entry y expression (max 32)")
            Op.PathExpression(id, flags, min, max, count, expressionX, FloatArray(lengthY) { r.readFloat32() })
        }
        Operations.FLOAT_FUNCTION_DEFINE -> {
            val id = r.readS32()
            val count = r.readS32()
            if (count > 32) throw RemoteComposeParseException("Float function $id declares $count arguments (max 32)")
            Op.FloatFunctionDefine(id, List(count) { r.readS32() })
        }
        Operations.FLOAT_FUNCTION_CALL -> {
            val id = r.readS32()
            val count = r.readS32()
            if (count > 80) throw RemoteComposeParseException("Float function call $id passes $count arguments (max 80)")
            Op.FloatFunctionCall(id, List(count) { r.readFloat32() })
        }
        Operations.LOOP_START -> Op.LoopStart(r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.LAYOUT_STATE -> Op.LayoutState(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readS32())
        Operations.LAYOUT_CONTENT -> Op.LayoutContent(r.readS32())
        Operations.LAYOUT_CANVAS_CONTENT -> Op.LayoutCanvasContent(r.readS32())
        Operations.LAYOUT_CANVAS -> Op.LayoutCanvas(r.readS32(), r.readS32())
        Operations.LAYOUT_CUSTOM -> {
            val componentId = r.readS32()
            val animationId = r.readS32()
            val nameTextId = r.readS32()
            val propertyCount = r.readS32()
            Op.LayoutCustom(
                componentId, animationId, nameTextId,
                List(propertyCount) { Op.CustomProperty(type = r.readU16(), dataType = r.readU16(), value = r.readS32()) },
            )
        }
        Operations.LAYOUT_IMAGE -> Op.LayoutImage(r.readS32(), r.readS32(), r.readS32(), r.readS32(), r.readFloat32())
        Operations.HAPTIC_FEEDBACK -> Op.HapticFeedback(r.readS32())
        Operations.THEME -> Op.Theme(r.readS32())
        Operations.ROOT_CONTENT_BEHAVIOR -> Op.RootContentBehavior(r.readS32(), r.readS32(), r.readS32(), r.readS32())
        Operations.DEBUG_MESSAGE -> Op.DebugMessage(r.readS32(), r.readFloat32(), r.readS32())
        Operations.ANIMATION_SPEC -> Op.AnimationSpec(
            r.readS32(), r.readFloat32(), r.readS32(), r.readFloat32(), r.readS32(), r.readS32(), r.readS32(),
        )
        Operations.MODIFIER_WIDTH -> Op.ModifierWidth(r.readS32(), r.readFloat32())
        Operations.MODIFIER_HEIGHT -> Op.ModifierHeight(r.readS32(), r.readFloat32())
        Operations.CONTAINER_END -> Op.ContainerEnd
        Operations.MODIFIER_CLICK -> Op.ModifierClick
        Operations.HOST_ACTION -> Op.HostAction(r.readS32())
        Operations.MODIFIER_PADDING -> Op.ModifierPadding(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.MODIFIER_BACKGROUND -> Op.ModifierBackground(
            colorIdFlag = r.readS32(), colorId = r.readS32(), reserved1 = r.readS32(), reserved2 = r.readS32(),
            r = r.readFloat32(), g = r.readFloat32(), b = r.readFloat32(), a = r.readFloat32(),
            shapeType = r.readS32(),
        )
        Operations.MODIFIER_VISIBILITY -> Op.ModifierVisibility(r.readS32())
        Operations.MODIFIER_OFFSET -> Op.ModifierOffset(r.readFloat32(), r.readFloat32())
        Operations.MODIFIER_BORDER -> Op.ModifierBorder(
            colorRefFlag = r.readS32(), colorId = r.readS32(), legacyFlag = r.readS32(), reserved = r.readS32(),
            borderWidth = r.readFloat32(), roundedCorner = r.readFloat32(),
            r = r.readFloat32(), g = r.readFloat32(), b = r.readFloat32(), a = r.readFloat32(),
            shapeType = r.readS32(),
        )
        Operations.DATA_FLOAT -> Op.FloatConstant(r.readS32(), r.readFloat32())
        Operations.ANIMATED_FLOAT -> {
            val id = r.readS32()
            val packed = r.readS32()
            val expLength = packed and 0xFFFF
            val animLength = (packed shr 16) and 0xFFFF
            if (expLength > 32) throw RemoteComposeParseException("Float expression too long ($expLength)")
            val expression = FloatArray(expLength) { r.readFloat32() }
            val animation = if (animLength > 0) FloatArray(animLength) { r.readFloat32() } else null
            Op.FloatExpression(id, expression, animation)
        }
        Operations.DATA_INT -> Op.IntegerConstant(r.readS32(), r.readS32())
        Operations.DATA_BOOLEAN -> Op.BooleanConstant(r.readS32(), r.readU8() != 0)
        Operations.DATA_LONG -> Op.LongConstant(r.readS32(), r.readS64())
        Operations.COLOR_CONSTANT -> Op.ColorConstant(r.readS32(), r.readS32())
        Operations.MODIFIER_CLIP_RECT -> Op.ModifierClipRect
        Operations.MODIFIER_ROUNDED_CLIP_RECT -> Op.ModifierRoundedClipRect(
            r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(),
        )
        Operations.MODIFIER_MULTI_CLICK -> Op.ModifierMultiClick(r.readS32())
        Operations.MODIFIER_TOUCH_DOWN -> Op.ModifierTouchDown
        Operations.MODIFIER_TOUCH_UP -> Op.ModifierTouchUp
        Operations.MODIFIER_TOUCH_CANCEL -> Op.ModifierTouchCancel
        Operations.MODIFIER_WIDTH_IN -> Op.ModifierWidthIn(r.readFloat32(), r.readFloat32())
        Operations.MODIFIER_HEIGHT_IN -> Op.ModifierHeightIn(r.readFloat32(), r.readFloat32())
        Operations.MODIFIER_COLLAPSIBLE_PRIORITY -> Op.ModifierCollapsiblePriority(r.readS32(), r.readFloat32())
        Operations.MODIFIER_ALIGN_BY -> Op.ModifierAlignBy(r.readFloat32(), r.readS32())
        Operations.MODIFIER_ZINDEX -> Op.ModifierZIndex(r.readFloat32())
        Operations.MODIFIER_RIPPLE -> Op.ModifierRipple
        Operations.MODIFIER_DRAW_CONTENT -> Op.ModifierDrawContent
        Operations.MODIFIER_MARQUEE -> Op.ModifierMarquee(
            r.readS32(), r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32(),
        )
        Operations.MODIFIER_SCROLL -> Op.ModifierScroll(r.readS32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.TOUCH_EXPRESSION -> {
            val id = r.readS32()
            val defValue = r.readFloat32()
            val min = r.readFloat32()
            val max = r.readFloat32()
            val velocity = r.readFloat32()
            val flags = r.readS32()
            val srcExp = List(r.readS32()) { r.readFloat32() }
            val packed = r.readS32()
            val tapExp = List(packed and 0xFFFF) { r.readFloat32() }
            val tapExpFloats = List(r.readS32()) { r.readFloat32() }
            Op.TouchExpression(id, defValue, min, max, velocity, flags, srcExp, packed, tapExp, tapExpFloats)
        }
        Operations.MODIFIER_GRAPHICS_LAYER -> {
            val count = r.readS32()
            Op.ModifierGraphicsLayer(List(count) { Op.GraphicsLayerAttribute(tag = r.readS32(), rawValue = r.readS32()) })
        }
        Operations.MODIFIER_DIMENSION_CONSTRAINTS -> Op.ModifierDimensionConstraints(r.readS8(), r.readFloat32(), r.readFloat32())
        Operations.VALUE_INTEGER_CHANGE_ACTION -> Op.ValueIntegerChange(r.readS32(), r.readS32())
        Operations.VALUE_STRING_CHANGE_ACTION -> Op.ValueStringChange(r.readS32(), r.readS32())
        Operations.VALUE_FLOAT_CHANGE_ACTION -> Op.ValueFloatChange(r.readS32(), r.readFloat32())
        Operations.VALUE_INTEGER_EXPRESSION_CHANGE_ACTION -> Op.ValueIntegerExpressionChange(r.readS64(), r.readS64())
        Operations.VALUE_FLOAT_EXPRESSION_CHANGE_ACTION -> Op.ValueFloatExpressionChange(r.readS32(), r.readS32())
        Operations.MATRIX_SAVE -> Op.MatrixSave
        Operations.MATRIX_RESTORE -> Op.MatrixRestore
        Operations.MATRIX_TRANSLATE -> Op.MatrixTranslate(r.readFloat32(), r.readFloat32())
        Operations.MATRIX_SCALE -> Op.MatrixScale(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.MATRIX_ROTATE -> Op.MatrixRotate(r.readFloat32(), r.readFloat32(), r.readFloat32())
        Operations.MATRIX_SKEW -> Op.MatrixSkew(r.readFloat32(), r.readFloat32())
        Operations.CLIP_RECT -> Op.ClipRect(r.readFloat32(), r.readFloat32(), r.readFloat32(), r.readFloat32())
        else -> throw RemoteComposeParseException(
            "Opcode $opId at byte ${r.position - 1} is not handled by this reader",
        )
    }

    /**
     * `DrawBitmapFontText`, `DrawBitmapFontTextOnPath` and `BitmapTextMeasure` set the top bit of
     * their leading id when a glyph-spacing float follows it, and clear it from the id itself.
     */
    private const val GLYPH_SPACING_FLAG = Int.MIN_VALUE

    private const val PATH_CMD_MOVE = 10
    private const val PATH_CMD_LINE = 11
    private const val PATH_CMD_QUADRATIC = 12
    private const val PATH_CMD_CONIC = 13
    private const val PATH_CMD_CUBIC = 14
    private const val PATH_CMD_CLOSE = 15

    /** `0xFF800000`: the bit pattern every `Utils.asNan` command tag shares. */
    private const val NAN_TAG_MASK = -0x800000

    /**
     * Decodes a `RemotePathBase` flat command array of [floatCount] raw words. Each command is a
     * NaN-tagged word (`0xFF800000 | tag`) followed by its coordinates. `RemotePathBase.add(int,
     * float, float)` and its siblings advance the write cursor two slots too far before writing
     * coordinates (a bug the class itself annotates "THIS IS FLAW in the encoding"), so every
     * command except move and close has two junk words between its tag and its real arguments.
     * Conic ([PATH_CMD_CONIC]) has not been verified against writer output and is rejected.
     */
    fun readPathArray(reader: BufferReader, floatCount: Int): List<PathCommand> {
        val words = IntArray(floatCount) { reader.readS32() }
        val commands = mutableListOf<PathCommand>()
        var i = 0
        while (i < words.size) {
            val tagWord = words[i]
            if ((tagWord and NAN_TAG_MASK) != NAN_TAG_MASK) {
                throw RemoteComposeParseException("Expected a path command tag at float index $i, got a non-tag word")
            }
            when (val tag = tagWord and 0x7FFFFF) {
                PATH_CMD_MOVE -> {
                    commands += PathCommand.MoveTo(Float.fromBits(words[i + 1]), Float.fromBits(words[i + 2]))
                    i += 3
                }
                PATH_CMD_LINE -> {
                    commands += PathCommand.LineTo(Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]))
                    i += 5
                }
                PATH_CMD_QUADRATIC -> {
                    commands += PathCommand.QuadraticTo(
                        Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]),
                        Float.fromBits(words[i + 5]), Float.fromBits(words[i + 6]),
                    )
                    i += 7
                }
                PATH_CMD_CUBIC -> {
                    commands += PathCommand.CubicTo(
                        Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]),
                        Float.fromBits(words[i + 5]), Float.fromBits(words[i + 6]),
                        Float.fromBits(words[i + 7]), Float.fromBits(words[i + 8]),
                    )
                    i += 9
                }
                PATH_CMD_CLOSE -> {
                    commands += PathCommand.Close
                    i += 1
                }
                else -> throw RemoteComposeParseException(
                    "Path command tag $tag at float index $i is not supported (conic is unverified)",
                )
            }
        }
        return commands
    }
}
