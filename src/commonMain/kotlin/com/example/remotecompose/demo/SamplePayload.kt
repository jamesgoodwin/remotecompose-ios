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
 * fuchsia rect drawn oversized (30x20) inside a `startBox`/`endBox` carrying the same
 * `width(15f)`/`height(10f)`/`clip(RoundedRectShape(4f, 4f, 4f, 4f))` shape this time — its
 * visible top-left 15x10 corner should show a real quarter-round cut at all four corners (a
 * quadratic-Bézier approximation of a circular arc, since this renderer has no dedicated
 * round-rect clip primitive), not `RectShape`'s sharp 90-degree corners, a small teal rect
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
 * should render as a rotated bar, not an axis-aligned rect quietly ignoring the attribute, a
 * cyan rect carrying the same `ROTATION_Z, 45f` but also
 * `setFloatAttribute(TRANSFORM_ORIGIN_X, 0f); setFloatAttribute(TRANSFORM_ORIGIN_Y, 0f)` —
 * standard Compose `GraphicsLayerScope.transformOrigin` semantics put that pivot fraction at this
 * layer's own top-left corner instead of its default center, so this diamond's top-left corner
 * should stay fixed in place while the rest sweeps down-right from it, unlike the plain
 * `ROTATION_Z` diamond above (which stays centered on its own square), proving
 * `TRANSFORM_ORIGIN_X`/`_Y` now really move the pivot instead of staying byte-consumed only, and a
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
 * horizontal — instead of staying `LAYOUT_ROW`'s plain single-line arrangement, another
 * orange/teal/pink/indigo quartet of `startBox`/`endBox` children — each drawing its rect at the
 * *identical* raw `(170, 170)-(176, 176)` document coordinates — wrapped in a
 * `startFlow(RecordingModifier().spacedBy(3f), 0, 0, 2, 1)`/`endFlow` (2 items per line, only 1
 * line): a render showing only the first row's two children, with the second row's two entirely
 * absent (not a third and fourth square appearing anywhere) proves `maxLinesInCrossAxis` now
 * really hides the overflow — javap on the real `FlowLayout`'s own measure logic shows it marks
 * an overflowing child `Component.Visibility.GONE` the moment its row would exceed that count,
 * the same real effect `MODIFIER_VISIBILITY` already gives that value — an indigo/amber
 * rect carrying a `widthIn(5f, 15f)` modifier — its 30-wide natural content should clip down to
 * exactly the declared max (15), the same real "cut off the overflow" effect `MODIFIER_CLIP_RECT`
 * gets, without a separate `clip(...)` call — matching real Compose's `widthIn`/`heightIn`
 * semantics, a deep-orange
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
 * 10 units below it), and a brown-orange 40x20 `startBox`/`endBox` carrying a real
 * `RemoteComposeWriter.addModifierBackground(r,g,b,a,1)` call (shapeType=1/CIRCLE — javap-confirmed
 * on the real `BackgroundModifierOperation.paint()`'s own `mShapeType`-gated `drawRect`/
 * `drawCircle` dispatch; `RecordingModifier.background(...)`'s public fluent API only ever emits
 * shapeType 0, so this one is reached via a small custom `RecordingModifier.Element` calling that
 * real writer method directly) whose only content is two 1x1 marker rects at opposite corners of
 * the box (not a rect that fills it, which would just paint over the inferred background and hide
 * its shape) — the background renders as a real oval inscribed in the box, corners visibly cut off
 * against the green round-rect behind it, rather than a sharp-cornered rectangle — the same
 * shapeType-gated shape choice `MODIFIER_BORDER`'s stroke already gets. Shared by every
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
            "/+L/////AAAAAAAAAAAQAAAAAEFwAABDAAAAAEEgAAA2QIAAAECAAABAgAAAQIAAAMn////hKAAAAAIAAAAE/+kejypDEQAAQyoAAEMvAABDPgAA1tbK" +
            "////4P////8AAAAAAAAAAFMAAAAB0QAAAAnWyf///98oAAAAAgAAAAT/AIOPKkMlAABAAAAAQzQAAEFAAADW1sr////e/////wAAAAAAAAAA29EAAAAJ" +
            "1sn////dKAAAAAIAAAAE/41uYypAAAAAQXAAAEGIAABByAAA1tbK////3P////8AAAAAAAAAANzRAAAACdbJ////2ygAAAACAAAABP8mppoqQaAAAEFw" +
            "AABCDAAAQcgAANbWyv///9r/////AAAAAAAAAADh0QAAAAnWyf///9koAAAAAgAAAAT/71NQKkIYAABBcAAAQlQAAEHIAADW1sr////Y/////wAAAAAA" +
            "AAAA50EgAABBoAAAyf///9coAAAAAgAAAAT/eYbLKkJgAABBcAAAQo4AAEHIAADW1sr////W/////wAAAAAAAAAA6EBAAABBIAAAyf///9UoAAAAAgAA" +
            "AAT//7MAKkKUAABBcAAAQrIAAEHIAADW1sr////U/////wAAAAAAAAAA50CgAABBcAAAyf///9MoAAAAAgAAAAT/gncXKkK+AABCtAAAQvoAAELIAADW" +
            "1sr////S/////wAAAAAAAAAA6wAAAABAAAAAyf///9EoAAAAAgAAAAT/VG56KkK4AABBcAAAQtYAAEHIAADW1sr////Q/////wAAAAAAAAAA7f+AAAEA" +
            "AAAAyf///88oAAAAAgAAAAT/jiSqKkLcAABBcAAAQvoAAEHIAADW1sr////O/////wAAAAAAAAAA30BAAADJ////zSgAAAACAAAABP8+JyMqQwAAAEFw" +
            "AABDDwAAQcgAANbWyv///8z/////AAAAAAAAAADJ////y8r////K/////wAAAAAAAAAA30CgAADJ////ySgAAAACAAAABP/TLy8qQxYAAEKCAABDLwAA" +
            "QqAAANbWyv///8j/////AAAAAAAAAADfP4AAAMn////HKAAAAAIAAAAE/xl20ipDFgAAQoIAAEMvAABCoAAA1tbW1sr////G/////wAAAAAAAAAA5cn/" +
            "///FKAAAAAIAAAAE/xl20ipDEgAAQXAAAEMhAABByAAA1tbK////xP////8AAAAAAAAAAK7J////wygAAAACAAAABP+tFFcqQyQAAEFwAABDMwAAQcgA" +
            "ANbWyv///8L/////AAAAAAAAAADkAAAAAQAAAABEegAAQ/oAAEEAAABB8AAAyf///8EoAAAAAgAAAAT/gncXKkAAAABB4AAAQYgAAEIYAADW1sr////A" +
            "/////wAAAAAAAAAA4AAAAAEAAAQLPwAAAMn///+/KAAAAAIAAAAE/2obmipBoAAAQeAAAEIMAABCGAAA1tbK////vv////8AAAAAAAAAAOAAAAACAAAE" +
            "AEAAAAAAAAQBQAAAAMn///+9KAAAAAIAAAAE//3YNSpCNAAAQnQAAEJUAABCigAA1tbK////vP////8AAAAAAAAAAOAAAAABAAAEBEI0AADJ////uygA" +
            "AAACAAAABP8eiOUqQnAAAEJ0AABCmAAAQooAANbWyv///7r/////AAAAAAAAAADgAAAAAwAABARCNAAAAAAEBQAAAAAAAAQGAAAAAMn///+5KAAAAAIA" +
            "AAAE/wCswSpCqgAAQnQAAEK6AABCigAA1tbK////uP////8AAAAAAAAAAPMBQKAAAEIgAADJ////tygAAAACAAAABP9FJ6AqQhgAAEHgAABCVAAAQhgA" +
            "ANbWyv///7b/////AAAAAAAAAAA71AAAAAMAAAAH1sn///+1KAAAAAIAAAAE/wBpXCpCYAAAQeAAAEKOAABCGAAA1tbK////tP////8AAAAAAAAAADve" +
            "AAAABEAgAADWyf///7MoAAAAAgAAAAT/2EMVKkKUAABB4AAAQrIAAEIYAADW1sr///+y/////wAAAAAAAAAAO2YAAAA2AAAAAmhp1QAAAAUAAAA21sn/" +
            "//+xKAAAAAIAAAAE/yg1kypCuAAAQeAAAELWAABCGAAA1tbK////sP////8AAAAAAAAAADvaAAAAAAAAAAYAAAAAAAAAKtbJ////rygAAAACAAAABP8z" +
            "aR4qQtwAAEHgAABC+gAAQhgAANbWyv///67/////AAAAAAAAAAA74wAAAAcAAAAJ1sn///+tKAAAAAIAAAAE/21MQSpDAAAAQeAAAEMPAABCGAAA1tbp" +
            "////rP////8AAAAAAAAAAAAAAADJ////qygAAAACAAAABP83R08qQxIAAEHgAABDIQAAQhgAANbW5v///6r/////AAAAAAAAAAAAAAAAyf///6koAAAA" +
            "AgAAAAT/iA5PKkMkAABB4AAAQzMAAEIYAADW1vD///+o/////wAAAAAAAAAAAAAAAH////9/////yf///6coAAAAAgAAAAT/AIOPKkAAAABCJAAAQYgA" +
            "AEJMAADW1vD///+m/////wAAAAAAAAAAQEAAAAAAAAJ/////yf///6XK////pP////8AAAAAAAAAAMn///+jKAAAAAIAAAAE/9hDFSpCggAAQrQAAEKS" +
            "AABCxAAA1tbK////ov////8AAAAAAAAAAMn///+hKAAAAAIAAAAE/2obmipCggAAQrQAAEKSAABCxAAA1tbK////oP////8AAAAAAAAAAMn///+fKAAA" +
            "AAIAAAAE/y59MipCggAAQrQAAEKSAABCxAAA1tbK////nv////8AAAAAAAAAAMn///+dKAAAAAIAAAAE/xVlwCpCggAAQrQAAEKSAABCxAAA1tbW1vD/" +
            "//+c/////wAAAAAAAAAAQEAAAAAAAAIAAAAByf///5vK////mv////8AAAAAAAAAAMn///+ZKAAAAAIAAAAE/+9sACpDKgAAQyoAAEMwAABDMAAA1tbK" +
            "////mP////8AAAAAAAAAAMn///+XKAAAAAIAAAAE/wBpXCpDKgAAQyoAAEMwAABDMAAA1tbK////lv////8AAAAAAAAAAMn///+VKAAAAAIAAAAE/60U" +
            "VypDKgAAQyoAAEMwAABDMAAA1tbK////lP////8AAAAAAAAAAMn///+TKAAAAAIAAAAE/yg1kypDKgAAQyoAAEMwAABDMAAA1tbW1rD///+S/////wAA" +
            "AAAAAAAAyf///5EoAAAAAgAAAAT/5koZKkGgAABCJAAAQgwAAEJMAADW1sj///+QKAAAAAIAAAAE/xojfipCGAAAQiQAAEJUAABCTAAA1tn///+P////" +
            "/wAAAAAAAAAAAAAAAMn///+OKAAAAAIAAAAE/60UVypCYAAAQiQAAEKOAABCTAAA1tbN////jf/////J////jM////+LKAAAAAIAAAAE/1WLLypClAAA" +
            "QiQAAEKyAABCTAAA1tbWZgAAADcAAAAIbXlDdXN0b21d//////////8AAAA3AAAAAMn///+KKAAAAAIAAAAE/040LipCuAAAQiQAAELWAABCTAAA1tZl" +
            "AAAAOAAAAAQAAAAEAAAAUIlQTkcNChoKAAAADUlIRFIAAAAEAAAABAgGAAAAqfGefgAAABdJREFUeF5jYNi/9D8IwGkUDpBmIKgCALoCMvnWpA11AAAA" +
            "AElFTkSuQmCC6v///4n/////AAAAOAAAAAQ/TMzNEAAAAABBgAAAQwAAAABBgAAA1lAAAAA5QMAAAMr///+I/////wAAAAAAAAAAOv+AADkAAAAAAAAA" +
            "AAAAAADJ////hygAAAACAAAABP8AiXsqQiAAAEK0AABCXAAAQsgAANbWjAAAADoAAAAHjwAAADsBlAAAADwAAAAcvpkaFLEAAAAEPwAAAAFBAAAAAQAA" +
            "AAIAAAADAAAABMr///+G/////wAAAAAAAAAADgAAAANDlgAAAAAAAUOWAAAAAAABAAAAAAAAAAHJ////hSgAAAACAAAABP9dQDcqQtwAAEIkAABC+gAA" +
            "QkwAANbWgn9DAgAAQiQAACgAAAACAAAABP8ArMEqAAAAAAAAAABBcAAAQSAAAIOCfkAAAABAAAAAQxEAAEI4AAAoAAAAAgAAAAT/9X8XKkMRAABCJAAA" +
            "QxgAAEI4AACDgoFCNAAAQy8AAEI4AAAoAAAAAgAAAAT/ahuaKkMoAABCJAAAQzcAAEJMAACDgidDPgAAQiQAAENNAABCTAAAKAAAAAIAAAAE/6oA/ypD" +
            "OQAAQhAAAENSAABCYAAAg4KAPwAAAAAAAAAoAAAAAgAAAAT/2EMVKkAAAABDAgAAQYgAAEMRAACD6f///4T/////AAAAAAAAAABAQAAAyf///4PK////" +
            "gv////8AAAAAAAAAAMn///+BKAAAAAIAAAAE/8IYWypBsAAAQwIAAEIAAABDDAAA1tbK////gP////8AAAAAAAAAAMn///9/KAAAAAIAAAAE/3sfoipB" +
            "sAAAQwIAAEIAAABDDAAA1tbK////fv////8AAAAAAAAAAMn///99KAAAAAIAAAAE/zA/nypBsAAAQwIAAEIAAABDDAAA1tbW1igAAAACAAAABP8hISFm" +
            "AAAAPQAAAAtIZWxsbyBXb3JsZCsAAAA9AAAABgAAAAsAAAAAAAAAC0IQAABDCAAAAHsAAAA+AAAACP+AAApC0gAAQ0MAAP+AAAsAAAAAAAAAAEL6AABD" +
            "QwAAKAAAAAIAAAAE/wBpXGYAAAA/AAAABFBhdGg1AAAAPwAAAD7AgAAAAAAAAMr///98/////wAAAAAAAAAANwAAAAAAAAAAAAAAAAAAAAA/gAAAPt7e" +
            "3wAAAAA/gAAAAAAAAMn///97KAAAAAIAAAAE/xVlwCpAAAAAQnQAAEFAAABCjgAAKAAAAAIAAAAE/y59Mi5CDAAAQpwAAEDAAADW1sz///96/////wAA" +
            "AAAAAAAAQEAAAMn///95yv///3j/////AAAAAAAAAADJ////dygAAAACAAAABP/TLy8qQAAAAEK0AABBQAAAQsgAANbWyv///3b/////AAAAAAAAAADJ" +
            "////dSgAAAACAAAABP8ZdtIqQAAAAEK0AABBQAAAQsgAANbWyv///3T/////AAAAAAAAAADJ////cygAAAACAAAABP84jjwqQAAAAEK0AABBQAAAQsgA" +
            "ANbW1tbL////cv////8AAAAGAAAAAgAAAAAQAAAAAEKgAADJ////ccr///9w/////wAAAAAAAAAAyf///28oAAAAAgAAAAT/xigoKkGwAABDFgAAQfAA" +
            "AEMeAADW1sr///9u/////wAAAAAAAAAAyf///20oAAAAAgAAAAT/+aglKkGwAABDFgAAQfAAAEMmAADW1sr///9s/////wAAAAAAAAAAyf///2soAAAA" +
            "AgAAAAT/AIOPKkGwAABDFgAAQfAAAEMeAADW1tbWgnsAAABAAAAADv+AAApC0gAAQygAAP+AAAsAAAAAAAAAAEL6AABDFgAA/4AACwAAAAAAAAAAQvoA" +
            "AEMoAAD/gAAPJgAAAEAoAAAAAgAAAAT/ahuaKkLSAABDFgAAQwIAAEMqAACDKAAAAAIAAAAE/yEhIWYAAABBAAAABkN1cnZlZDkAAABBQsgAAEM5AABB" +
            "IAAAQ4cAAAAAAAABAMr///9q/////wAAAAAAAAAANwAAAAAAAAAAAAAAAAAAAAA/GZmaPkzMzQAAAAA/gAAAAAAAAcn///9pKAAAAAIAAAAE/////ypD" +
            "FgAAQtwAAEMXAABC3gAAKkM9AABDAQAAQz4AAEMCAADW1g==",
    )
}
