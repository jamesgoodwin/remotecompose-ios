package com.example.remotecompose.demo

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 of `tools/rc-writer/sample.rc` — a real `.rc` payload produced by the official
 * `androidx.compose.remote:remote-creation-jvm:1.0.0-alpha18` writer (a red `drawRect`, a blue
 * `drawCircle`, a green `drawRoundRect`, a `drawTextAnchored`, a purple `drawLine`, an orange
 * `drawOval`, a cyan `drawArc`, a pink `drawSector`, an indigo `drawPath` triangle, an 8x8
 * checkerboard `drawBitmap`, an `addClickArea` tap target over the red rect, a teal `drawPath`
 * combining a quadratic and a cubic Bézier segment, a yellow rect + brown circle wrapped in a
 * `startColumn`/`endColumn`, a teal rect + pink circle wrapped in a `startRow`/`endRow`, an olive
 * rect wrapped in a `startBox`/`endBox`, a dark-brown rect wrapped in a `startBox`/`endBox`
 * carrying explicit `width()`/`height()` modifiers, a small pink rect wrapped in a
 * `startBox`/`endBox` carrying an `onClick(HostAction(9))` modifier, a small purple rect wrapped
 * in a `startBox`/`endBox` carrying `padding(2f, 6f, 2f, 2f)`/`background(0xFF37474F)` modifiers —
 * a real-bytes hex-diff of this same call confirmed the field order is `(left, top, right,
 * bottom)`, not the `top/bottom/left/right` an earlier pass guessed; `left`/`top` really inset
 * the purple child (visibly further from the grey background's top edge than its left edge,
 * proving the order), and all four expand the inferred grey background back out to the full
 * un-padded box rather than just matching the inset child's own bounds — a small green rect
 * wrapped in a `startBox`/`endBox` carrying a `visibility()` modifier, and a small orange rect
 * wrapped in a `startBox`/`endBox` carrying an `offset()` modifier, a small teal rect wrapped in a
 * `startBox`/`endBox` carrying a `border(2f, 4f, 0xFF000000, 0)` modifier — a real stroked black
 * rounded-rect outline around the teal child's own inferred bounds, not just a byte-consumed
 * field — a dark-slate rect wrapped in a `startBox`/`endBox` carrying a
 * `dynamicBorder(2f, 4f, colorId, 0)` modifier, where `colorId` comes from
 * `writer.addColor(0xFFD500F9)` — the border's real magenta color is resolved from
 * `COLOR_CONSTANT`'s color pool by id (`r`/`g`/`b`/`a` are all `0` on the wire for this path),
 * proving `MODIFIER_BORDER`'s color-pool-reference case now actually resolves instead of staying
 * unrendered — a small purple rect wrapped in a
 * `startBox`/`endBox` carrying a `clip(RectShape(...))` modifier, a small olive rect wrapped in
 * a `startBox`/`endBox` carrying a `clip(RoundedRectShape(...))` modifier, an amber rect drawn
 * oversized (30x20) inside a `startBox`/`endBox` carrying `width(15f)`/`height(10f)`/
 * `clip(RectShape(...))` modifiers — only its top-left 15x10 corner should be visible, the rest
 * clipped away, since neither clip opcode carries its own rect bounds on the wire at all (real
 * Compose always clips to the container's own *measured* box); a real effect is only possible
 * here because the same container also declares an explicit size smaller than its content, a
 * small teal rect
 * wrapped in a `startBox`/`endBox` carrying an `onLongClick(HostAction(9))` modifier, a second row
 * of a brown/teal/red rect each wrapped in a `startBox`/`endBox` carrying an `onTouchDown`/
 * `onTouchUp`/`onTouchCancel(HostAction(9))` modifier respectively, an indigo/amber rect carrying a
 * `widthIn()`/`heightIn()` modifier respectively, a blue-grey rect carrying a
 * `collapsiblePriority()` modifier, a purple rect carrying an `alignByBaseline()` modifier, and a
 * dark-brown rect carrying a `then(ZIndexModifier(3f))` modifier, a red/blue pair of
 * `startBox`/`endBox` children — both drawing the *identical* overlapping `(150, 65)-(175, 80)`
 * rect, wrapped in a plain (non-Column/Row) `startBox`/`endBox` — where the red child is authored
 * *first* but carries the *higher* z-index (`ZIndexModifier(5f)` vs `ZIndexModifier(1f)`); it
 * should still paint on top of the blue child authored after it, proving `MODIFIER_ZINDEX` now
 * reorders sibling *paint* order for real instead of staying byte-consumed only, a dark-green
 * rect wrapped in a `startBox`/`endBox` carrying a `verticalScroll(50f)` modifier — every public
 * call path to it also emits a `TOUCH_EXPRESSION` record right after (a length-prefixed,
 * otherwise self-describing touch-gesture expression tree); both represent a live interaction
 * this parser has no runtime state or expression evaluator to give real effect to, so this child
 * renders at its own plain position, unaffected — a blue rect carrying a
 * `then(RippleModifier())` modifier, a magenta rect carrying a `drawContent()` modifier, and an
 * olive rect carrying a `then(MarqueeModifier(...))` modifier, and a purple rect carrying a
 * `then(GraphicsLayerModifier().apply { setFloatAttribute(ALPHA, 0.5f) })` modifier, a yellow rect
 * carrying a `then(GraphicsLayerModifier().apply { setFloatAttribute(SCALE_X, 2f);
 * setFloatAttribute(SCALE_Y, 2f) })` modifier that should render as a 16x16 square doubled about
 * its own *inferred* center (this renderer has no measure pass, so — the same approximation
 * `background()` already makes — that center comes from the container's own content bounds, not
 * document-authored placement), and a blue rect carrying a
 * `then(GraphicsLayerModifier().apply { setFloatAttribute(ROTATION_Z, 45f) })` modifier that
 * should render as a rotated bar, not an axis-aligned rect quietly ignoring the attribute, and a
 * dark-indigo rect carrying a `then(WidthInModifier(type, min, max))` modifier, and a teal rect
 * carrying an `onClick(ValueIntegerChange(3, 7))` modifier, a burnt-orange rect carrying an
 * `onClick(ValueFloatChange(4, 2.5f))` modifier, and a dark-blue rect carrying an
 * `onClick(ValueStringChange(5, hi))` modifier, and a dark-olive rect carrying an
 * `onClick(ValueIntegerExpressionChange(6L, 42L))` modifier, and a brown rect carrying an
 * `onClick(ValueFloatExpressionChange(7, 9))` modifier, and a dark-blue-grey/dark-pink pair of
 * boxes wrapped in a `startCollapsibleColumn`/`endCollapsibleColumn` and a
 * `startCollapsibleRow`/`endCollapsibleRow` respectively, and a teal rect wrapped in a
 * `startFlow`/`endFlow`, an orange/purple/green/blue quartet of `startBox`/`endBox` children —
 * each drawing its rect at the *identical* raw `(65, 90)-(73, 98)` document coordinates — wrapped
 * in a `startFlow(RecordingModifier().spacedBy(3f), 0, 0, 2, Int.MAX_VALUE)`/`endFlow` (2 items
 * per line): a render showing two rows of two side-by-side children each (not one packed row of
 * four, and not four still-overlapping squares) proves `LAYOUT_FLOW` now really wraps — the real
 * `FlowLayout` class extends `RowLayout` (source-confirmed via javap), so its main axis is always
 * horizontal — instead of staying [OP_LAYOUT_ROW]'s plain single-line arrangement, a deep-orange
 * rect wrapped in a `startFitBox`/`endFitBox`, and a dark-blue
 * rect wrapped in a `startRoot`/`endRoot`, and a magenta rect wrapped in a `startStateLayout`/
 * `endStateLayout`, an olive rect wrapped in a `startCanvas`/`endCanvas`, and a dark-brown rect
 * wrapped in a `startCustom`/`endCustom`, a `writer.image(RecordingModifier().width(16f)
 * .height(16f), bitmapId, IMAGE_SCALE_FIT, 0.8f)` leaf component drawing a small teal/white
 * checkerboard bitmap at 80% opacity into that explicit 16x16 box (this renderer has no measure
 * pass to size an image from its own bitmap/scaleType otherwise, so `LAYOUT_IMAGE` only renders
 * when an explicit `width()`/`height()` is present — a real-bytes hex-diff of this same call also
 * caught `bitmapId` and `scaleType` swapped from an earlier pass's assumed field order) — and
 * a teal rect wrapped in a `startBox`/`endBox` carrying a
 * `padding(writer.addFloatConstant(6f), 0f, 0f, 0f)` modifier — the modifier is given a
 * *NaN-tagged reference* to `6.0` (what `addFloatConstant` actually returns), not the literal
 * value itself, so the child rendering shifted right by exactly 6 (not disappearing into a `NaN`
 * translate) proves `resolveFloat` really resolves a `DATA_FLOAT`-registered reference wherever a
 * document passes one, not just literal floats, and `writer.addInteger(7)`/
 * `writer.addBoolean(true)`/`writer.addLong(123456789012L)` registering real values in their own
 * pools (the same real-value-pool pattern as `DATA_FLOAT`/`COLOR_CONSTANT`, not yet resolved
 * against anywhere) — and
 * three top-level document metadata ops: `performHaptic(4)`, `setTheme(1)`,
 * `setRootContentBehavior(1, 2, 3, 4)` — and a
 * brown rect wrapped in a `startBox`/`endBox` carrying an `animationSpec(3)` modifier, a cyan rect
 * drawn after a raw `writer.save()`/`writer.translate(130f, 41f)`/`writer.restore()` (no modifier
 * involved — the document author's own top-level matrix ops), an amber rect drawn after a raw
 * `writer.save()`/`writer.scale(2f, 2f, 145f, 46f)`/`writer.restore()`, a purple rect drawn after a
 * raw `writer.save()`/`writer.rotate(45f, 175f, 46f)`/`writer.restore()`, a burnt-orange rect
 * drawn after a raw `writer.save()`/`writer.skew(0.5f, 0f)`/`writer.restore()` that should read
 * as a parallelogram leaning right (`skewX`/`skewY` are direct shear factors, the
 * `android.graphics.Matrix.setSkew(kx, ky)` convention, not an angle like `ROTATION_Z`), a
 * pink/purple/indigo trio of `startBox`/`endBox` children — each drawing its rect at the
 * *identical* raw `(22, 130)-(32, 140)` document coordinates — wrapped in a
 * `startCollapsibleColumn(RecordingModifier().spacedBy(3f), 0, 0)`/`endCollapsibleColumn`, so a
 * render showing them stacked without overlap proves real Column arrangement now covers this
 * shape-identical opcode pair too, not only plain `LAYOUT_COLUMN`/`LAYOUT_ROW`, the word "World"
 * drawn via `writer.drawTextRun("Hello World", 6, 11, 0, 11, 36f, 136f, false)` — unlike
 * `DRAW_TEXT_ON_CIRCLE`, the real `DrawText.paint()` *is* implemented, so only characters
 * `[6, 11)` of the pool string rendering (not the whole "Hello World") is a real semantic effect,
 * not just a byte-consumed field, the word "Path" drawn via `writer.drawTextOnPath(textId,
 * pathId, 0f, -4f)` against a two-point path from `(105, 195)` — a real-bytes hex-diff of this
 * same call caught `vOffset` written *before* `hOffset` on the wire, the reverse of the call's
 * own argument order; there's no real glyph-by-glyph path-following to reverse-engineer at this
 * renderer's level, so it renders as a straight line anchored at the path's own first point
 * shifted by `(hOffset, vOffset)`, not curving along the path — and a
 * magenta rect drawn
 * oversized then clipped by a raw `writer.save()`/`writer.clipRect(190f, 41f, 205f, 51f)`/
 * `writer.restore()`, and a blue rect plus a separate green circle wrapped in a
 * `startBox`/`endBox` carrying a `background(0xFFFF6F00)` modifier — with a gap between the two
 * shapes deliberately left unpainted by either, so an orange background rect inferred from their
 * combined bounding box (this renderer has no measure/layout pass, so real content-position
 * knowledge is the closest available substitute) is visually distinguishable from either shape's
 * own fill, and three red/blue/green `startBox`/`endBox` children — each drawing its rect at the
 * *identical* raw `(2, 90)-(12, 100)` document coordinates, i.e. not pre-spaced by the document
 * author at all — wrapped in a `startColumn(RecordingModifier().spacedBy(3f), 0, 0)`/`endColumn`,
 * so a render showing them stacked without overlap can only be this renderer's own real Column
 * child-arrangement at work, and a `startRow(RecordingModifier().width(80f), 6, 2)`/`endRow`
 * (`RowLayout.SPACE_BETWEEN`/`.CENTER`) wrapping a short crimson, a tall amber, and a short teal
 * `startBox`/`endBox` child — all three drawn at the *identical* raw `(22, 150)` top-left, two of
 * them the same height — so real horizontal spread (using the row's declared 80f width as the
 * space to distribute) and real vertical centering against the tallest child are both this
 * renderer's own alignment-mode handling, not document-authored placement, and a purple triangular
 * `drawRect` clipped by a raw `writer.save()`/`writer.addClipPath(pathId)`/`writer.restore()` (the
 * clip path itself registered via `writer.addPathData(RemotePath)`, not drawn on its own) — only
 * the triangle inside the clip path should paint, the rest of the oversized rect should not, and a
 * small square cropped from just the top-left (red) pixel of a 2x2 four-quadrant test bitmap via
 * `writer.getBuffer().drawBitmap(id, 0, 0, srcLeft, srcTop, srcRight, srcBottom, dstLeft, dstTop,
 * dstRight, dstBottom, 0)` — the only public call path that exercises `DRAW_BITMAP_INT`'s real
 * source-rect cropping, not exposed by any `RemoteComposeWriter.drawBitmap(...)` convenience
 * overload (those always set src == dst), and the word "Curved" drawn via
 * `writer.drawTextOnCircle(textId, 100f, 185f, 10f, 270f, 0f, Alignment.CENTER,
 * Placement.OUTSIDE)` — the real `DrawTextOnCircle.paint()` itself throws
 * `UnsupportedOperationException` in this SDK version, so there is no real curved-text algorithm
 * to reverse-engineer; this parser fully decodes the wire format but renders only a straight-line
 * approximation anchored where `startAngle` points on the circle (here, straight up from a center
 * 10 units below it) — not by anything in this codebase. Shared by every
 * platform demo entry point (iOS, Android) so they
 * render byte-identical input — the point of the cross-platform comparison is to catch *rendering*
 * differences, not to accidentally compare two different payloads.
 */
@OptIn(ExperimentalEncodingApi::class)
val SAMPLE_RC_BYTES: ByteArray by lazy {
    Base64.decode(
            "AAAAAAEAAAABAAAAAAAAAMgAAADIAAAAAAAAAABmAAAAKgAAAARkZW1vZwAAACooAAAAAgAAAAT/5Tk1KkGgAABBoAAAQzQAAEM0AAAoAAAAAgAAAAT/" +
            "HojlLkJwAABDDAAAQfAAACgAAAACAAAABP9DoEczQtwAAELcAABDPgAAQz4AAEFAAABBQAAAKAAAAAIAAAAE/wAAAGYAAAArAAAAAkhphQAAACtCyAAA" +
            "QaAAAAAAAAAAAAAAAAAAACgAAAACAAAABP+OJKovQSAAAENDAABDPgAAQ0MAACgAAAACAAAABP/7jAA4QwwAAEHwAABDQwAAQnAAACgAAAACAAAABP8A" +
            "rMGYQAAAAEAAAABCIAAAQiAAAAAAAABCtAAAKAAAAAIAAAAE/9gbYDRDDAAAQwwAAENGAABDRgAAQ0gAAELIAAAoAAAAAgAAAAT/OUmrewAAACwAAAAO" +
            "/4AACkMbAABDMgAA/4AACwAAAAAAAAAAQ0YAAEMyAAD/gAALAAAAAAAAAABDMAAAQ0cAAP+AAA98AAAALGUAAAAtAAAACAAAAAgAAABWiVBORw0KGgoA" +
            "AAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAHUlEQVR4XmP4DwSKioogCivNgE0QmWbAJjjkTAAAa5Crwb4olnUAAAAASUVORK5CYIJmAAAALgAAAAdj" +
            "aGVja2VyLAAAAC1CjAAAQoIAAELcAABC0gAAAAAALmUAAAAvAAAAAgAAAAIAAABUiVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAG0lE" +
            "QVR4XmN4amn633mB+38GuY6n///eMP0PAFK0Cg/xwFIOAAAAAElFTkSuQmCCQgAAAC8AAAAAAAAAAAAAAAEAAAABAAAAlgAAAAIAAAC+AAAAKgAAAABm" +
            "AAAAMAAAAAp0YXAgdGFyZ2V0ZgAAADEAAAAaaHR0cHM6Ly9leGFtcGxlLmNvbS90YXBwZWRAAAAABwAAADBBoAAAQaAAAEM0AABDNAAAAAAAMSgAAAAC" +
            "AAAABP8Ag497AAAAMgAAABT/gAAKQAAAAEMWAAD/gAAMAAAAAAAAAABBkAAAQxYAAEGQAABDKgAA/4AADgAAAAAAAAAAQZAAAEM5AABBIAAAQ0MAAEAA" +
            "AABDQwAA/4AAD3wAAAAyzP////7/////AAAAAAAAAAAAAAAAyf////0oAAAAAgAAAAT//dg1KkKqAABDFgAAQtIAAEMlAAAoAAAAAgAAAAT/bUxBLkK+" +
            "AABDLwAAQQAAANbWy/////z/////AAAAAAAAAAAAAAAAyf////soAAAAAgAAAAT/AGlcKkLmAABDFgAAQwIAAEMlAAAoAAAAAgAAAAT/8GKSLkMMAABD" +
            "HQAAQOAAANbWyv////r/////AAAAAAAAAADJ////+SgAAAACAAAABP+enSQqQxYAAEMUAABDJQAAQyMAANbWyv////j/////AAAAAAAAAAAQAAAAAEGg" +
            "AABDAAAAAEEgAADJ////9ygAAAACAAAABP9dQDcqQyoAAEMUAABDPgAAQyMAANbWyv////b/////AAAAAAAAAAA70QAAAAnWyf////UoAAAAAgAAAAT/" +
            "7EB6KkM+AABAAAAAQ0cAAEEwAADW1sr////0/////wAAAAAAAAAAOkAAAABAwAAAQAAAAEAAAAA3AAAAAAAAAAAAAAAAAAAAAD5c3N0+jo6PPp6enz+A" +
            "AAAAAAAAyf////MoAAAAAgAAAAT/ex+iKkI0AABAAAAAQnAAAEFAAADW1sr////y/////wAAAAAAAAAA0wAAAADJ////8SgAAAACAAAABP84jjwqQoIA" +
            "AEAAAABCoAAAQUAAANbWyv////D/////AAAAAAAAAADdQKAAAECgAADJ////7ygAAAACAAAABP//VyIqQqoAAEAAAABCyAAAQUAAANbWyv///+7/////" +
            "AAAAAAAAAABrAAAAAAAAAAAAAAAAAAAAAEAAAABAgAAAAAAAAAAAAAAAAAAAP4AAAAAAAADJ////7SgAAAACAAAABP8AlogqQtIAAEAAAABC8AAAQUAA" +
            "ANbWyv///+z/////AAAAAAAAAABs4gAAAABCSAAA/4AAM/+AADSdAAgAAAAAAAAAAAAA/4AAMwAAAAAAAAADAAAAA/+AAA6/gAAA/7EAAwAAAAAAAAAA" +
            "1sn////rKAAAAAIAAAAE/zNpHipDMgAAQoIAAENBAABCoAAA1taKAAAANf/VAPnK////6v////8AAAAAAAAAAGsAAAACAAAANQAAAAAAAAAAQAAAAECA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAMn////pKAAAAAIAAAAE/zdHTypC9gAAQAAAAEMKAABBQAAA1tbK////6P////8AAAAAAAAAAGzJ////5ygAAAAC" +
            "AAAABP9eNbEqQvoAAEAAAABDDAAAQUAAANbWyv///+b/////AAAAAAAAAAA2QIAAAECAAABAgAAAQIAAAMn////lKAAAAAIAAAAE/8DKMypDEQAAQAAA" +
            "AEMgAABBQAAA1tbK////5P////8AAAAAAAAAABAAAAAAQXAAAEMAAAAAQSAAAGzJ////4ygAAAACAAAABP//jwAqQzUAAEFwAABDUwAAQgwAANbWyv//" +
            "/+L/////AAAAAAAAAABTAAAAAdEAAAAJ1sn////hKAAAAAIAAAAE/wCDjypDJQAAQAAAAEM0AABBQAAA1tbK////4P////8AAAAAAAAAANvRAAAACdbJ" +
            "////3ygAAAACAAAABP+NbmMqQAAAAEFwAABBiAAAQcgAANbWyv///97/////AAAAAAAAAADc0QAAAAnWyf///90oAAAAAgAAAAT/JqaaKkGgAABBcAAA" +
            "QgwAAEHIAADW1sr////c/////wAAAAAAAAAA4dEAAAAJ1sn////bKAAAAAIAAAAE/+9TUCpCGAAAQXAAAEJUAABByAAA1tbK////2v////8AAAAAAAAA" +
            "AOdBIAAAQaAAAMn////ZKAAAAAIAAAAE/3mGyypCYAAAQXAAAEKOAABByAAA1tbK////2P////8AAAAAAAAAAOhAQAAAQSAAAMn////XKAAAAAIAAAAE" +
            "//+zACpClAAAQXAAAEKyAABByAAA1tbK////1v////8AAAAAAAAAAOsAAAAAQAAAAMn////VKAAAAAIAAAAE/1RueipCuAAAQXAAAELWAABByAAA1tbK" +
            "////1P////8AAAAAAAAAAO3/gAABAAAAAMn////TKAAAAAIAAAAE/44kqipC3AAAQXAAAEL6AABByAAA1tbK////0v////8AAAAAAAAAAN9AQAAAyf//" +
            "/9EoAAAAAgAAAAT/PicjKkMAAABBcAAAQw8AAEHIAADW1sr////Q/////wAAAAAAAAAAyf///8/K////zv////8AAAAAAAAAAN9AoAAAyf///80oAAAA" +
            "AgAAAAT/0y8vKkMWAABCggAAQy8AAEKgAADW1sr////M/////wAAAAAAAAAA3z+AAADJ////yygAAAACAAAABP8ZdtIqQxYAAEKCAABDLwAAQqAAANbW" +
            "1tbK////yv////8AAAAAAAAAAOXJ////ySgAAAACAAAABP8ZdtIqQxIAAEFwAABDIQAAQcgAANbWyv///8j/////AAAAAAAAAACuyf///8coAAAAAgAA" +
            "AAT/rRRXKkMkAABBcAAAQzMAAEHIAADW1sr////G/////wAAAAAAAAAA5AAAAAEAAAAARHoAAEP6AABBAAAAQfAAAMn////FKAAAAAIAAAAE/4J3FypA" +
            "AAAAQeAAAEGIAABCGAAA1tbK////xP////8AAAAAAAAAAOAAAAABAAAECz8AAADJ////wygAAAACAAAABP9qG5oqQaAAAEHgAABCDAAAQhgAANbWyv//" +
            "/8L/////AAAAAAAAAADgAAAAAgAABABAAAAAAAAEAUAAAADJ////wSgAAAACAAAABP/92DUqQjQAAEJ0AABCVAAAQooAANbWyv///8D/////AAAAAAAA" +
            "AADgAAAAAQAABARCNAAAyf///78oAAAAAgAAAAT/HojlKkJwAABCdAAAQpgAAEKKAADW1sr///++/////wAAAAAAAAAA8wFAoAAAQiAAAMn///+9KAAA" +
            "AAIAAAAE/0UnoCpCGAAAQeAAAEJUAABCGAAA1tbK////vP////8AAAAAAAAAADvUAAAAAwAAAAfWyf///7soAAAAAgAAAAT/AGlcKkJgAABB4AAAQo4A" +
            "AEIYAADW1sr///+6/////wAAAAAAAAAAO94AAAAEQCAAANbJ////uSgAAAACAAAABP/YQxUqQpQAAEHgAABCsgAAQhgAANbWyv///7j/////AAAAAAAA" +
            "AAA7ZgAAADYAAAACaGnVAAAABQAAADbWyf///7coAAAAAgAAAAT/KDWTKkK4AABB4AAAQtYAAEIYAADW1sr///+2/////wAAAAAAAAAAO9oAAAAAAAAA" +
            "BgAAAAAAAAAq1sn///+1KAAAAAIAAAAE/zNpHipC3AAAQeAAAEL6AABCGAAA1tbK////tP////8AAAAAAAAAADvjAAAABwAAAAnWyf///7MoAAAAAgAA" +
            "AAT/bUxBKkMAAABB4AAAQw8AAEIYAADW1un///+y/////wAAAAAAAAAAAAAAAMn///+xKAAAAAIAAAAE/zdHTypDEgAAQeAAAEMhAABCGAAA1tbm////" +
            "sP////8AAAAAAAAAAAAAAADJ////rygAAAACAAAABP+IDk8qQyQAAEHgAABDMwAAQhgAANbW8P///67/////AAAAAAAAAAAAAAAAf////3/////J////" +
            "rSgAAAACAAAABP8Ag48qQAAAAEIkAABBiAAAQkwAANbW8P///6z/////AAAAAAAAAABAQAAAAAAAAn/////J////q8r///+q/////wAAAAAAAAAAyf//" +
            "/6koAAAAAgAAAAT/2EMVKkKCAABCtAAAQpIAAELEAADW1sr///+o/////wAAAAAAAAAAyf///6coAAAAAgAAAAT/ahuaKkKCAABCtAAAQpIAAELEAADW" +
            "1sr///+m/////wAAAAAAAAAAyf///6UoAAAAAgAAAAT/Ln0yKkKCAABCtAAAQpIAAELEAADW1sr///+k/////wAAAAAAAAAAyf///6MoAAAAAgAAAAT/" +
            "FWXAKkKCAABCtAAAQpIAAELEAADW1tbWsP///6L/////AAAAAAAAAADJ////oSgAAAACAAAABP/mShkqQaAAAEIkAABCDAAAQkwAANbWyP///6AoAAAA" +
            "AgAAAAT/GiN+KkIYAABCJAAAQlQAAEJMAADW2f///5//////AAAAAAAAAAAAAAAAyf///54oAAAAAgAAAAT/rRRXKkJgAABCJAAAQo4AAEJMAADW1s3/" +
            "//+d/////8n///+cz////5soAAAAAgAAAAT/VYsvKkKUAABCJAAAQrIAAEJMAADW1tZmAAAANwAAAAhteUN1c3RvbV3//////////wAAADcAAAAAyf//" +
            "/5ooAAAAAgAAAAT/TjQuKkK4AABCJAAAQtYAAEJMAADW1mUAAAA4AAAABAAAAAQAAABQiVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAA" +
            "F0lEQVR4XmNg2L/0PwjAaRQOkGYgqAIAugIy+dakDXUAAAAASUVORK5CYILq////mf////8AAAA4AAAABD9MzM0QAAAAAEGAAABDAAAAAEGAAADWUAAA" +
            "ADlAwAAAyv///5j/////AAAAAAAAAAA6/4AAOQAAAAAAAAAAAAAAAMn///+XKAAAAAIAAAAE/wCJeypCIAAAQrQAAEJcAABCyAAA1taMAAAAOgAAAAeP" +
            "AAAAOwGUAAAAPAAAABy+mRoUsQAAAAQ/AAAAAUEAAAABAAAAAgAAAAMAAAAEyv///5b/////AAAAAAAAAAAOAAAAA0OWAAAAAAABQ5YAAAAAAAEAAAAA" +
            "AAAAAcn///+VKAAAAAIAAAAE/11ANypC3AAAQiQAAEL6AABCTAAA1taCf0MCAABCJAAAKAAAAAIAAAAE/wCswSoAAAAAAAAAAEFwAABBIAAAg4J+QAAA" +
            "AEAAAABDEQAAQjgAACgAAAACAAAABP/1fxcqQxEAAEIkAABDGAAAQjgAAIOCgUI0AABDLwAAQjgAACgAAAACAAAABP9qG5oqQygAAEIkAABDNwAAQkwA" +
            "AIOCJ0M+AABCJAAAQ00AAEJMAAAoAAAAAgAAAAT/qgD/KkM5AABCEAAAQ1IAAEJgAACDgoA/AAAAAAAAACgAAAACAAAABP/YQxUqQAAAAEMCAABBiAAA" +
            "QxEAAIPp////lP////8AAAAAAAAAAEBAAADJ////k8r///+S/////wAAAAAAAAAAyf///5EoAAAAAgAAAAT/whhbKkGwAABDAgAAQgAAAEMMAADW1sr/" +
            "//+Q/////wAAAAAAAAAAyf///48oAAAAAgAAAAT/ex+iKkGwAABDAgAAQgAAAEMMAADW1sr///+O/////wAAAAAAAAAAyf///40oAAAAAgAAAAT/MD+f" +
            "KkGwAABDAgAAQgAAAEMMAADW1tbWKAAAAAIAAAAE/yEhIWYAAAA9AAAAC0hlbGxvIFdvcmxkKwAAAD0AAAAGAAAACwAAAAAAAAALQhAAAEMIAAAAewAA" +
            "AD4AAAAI/4AACkLSAABDQwAA/4AACwAAAAAAAAAAQvoAAENDAAAoAAAAAgAAAAT/AGlcZgAAAD8AAAAEUGF0aDUAAAA/AAAAPsCAAAAAAAAAyv///4z/" +
            "////AAAAAAAAAAA3AAAAAAAAAAAAAAAAAAAAAD+AAAA+3t7fAAAAAD+AAAAAAAAAyf///4soAAAAAgAAAAT/FWXAKkAAAABCdAAAQUAAAEKOAAAoAAAA" +
            "AgAAAAT/Ln0yLkIMAABCnAAAQMAAANbWzP///4r/////AAAAAAAAAABAQAAAyf///4nK////iP////8AAAAAAAAAAMn///+HKAAAAAIAAAAE/9MvLypA" +
            "AAAAQrQAAEFAAABCyAAA1tbK////hv////8AAAAAAAAAAMn///+FKAAAAAIAAAAE/xl20ipAAAAAQrQAAEFAAABCyAAA1tbK////hP////8AAAAAAAAA" +
            "AMn///+DKAAAAAIAAAAE/ziOPCpAAAAAQrQAAEFAAABCyAAA1tbW1sv///+C/////wAAAAYAAAACAAAAABAAAAAAQqAAAMn///+Byv///4D/////AAAA" +
            "AAAAAADJ////fygAAAACAAAABP/GKCgqQbAAAEMWAABB8AAAQx4AANbWyv///37/////AAAAAAAAAADJ////fSgAAAACAAAABP/5qCUqQbAAAEMWAABB" +
            "8AAAQyYAANbWyv///3z/////AAAAAAAAAADJ////eygAAAACAAAABP8Ag48qQbAAAEMWAABB8AAAQx4AANbW1taCewAAAEAAAAAO/4AACkLSAABDKAAA" +
            "/4AACwAAAAAAAAAAQvoAAEMWAAD/gAALAAAAAAAAAABC+gAAQygAAP+AAA8mAAAAQCgAAAACAAAABP9qG5oqQtIAAEMWAABDAgAAQyoAAIMoAAAAAgAA" +
            "AAT/ISEhZgAAAEEAAAAGQ3VydmVkOQAAAEFCyAAAQzkAAEEgAABDhwAAAAAAAAEA",
    )
}
