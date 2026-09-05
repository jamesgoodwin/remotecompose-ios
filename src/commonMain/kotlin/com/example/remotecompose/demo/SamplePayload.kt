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
 * a `startBox`/`endBox` carrying a `clip(RoundedRectShape(...))` modifier, a small teal rect
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
 * `startFlow`/`endFlow`, a deep-orange rect wrapped in a `startFitBox`/`endFitBox`, and a dark-blue
 * rect wrapped in a `startRoot`/`endRoot`, and a magenta rect wrapped in a `startStateLayout`/
 * `endStateLayout`, an olive rect wrapped in a `startCanvas`/`endCanvas`, and a dark-brown rect
 * wrapped in a `startCustom`/`endCustom`, a `writer.image(RecordingModifier().width(16f)
 * .height(16f), bitmapId, IMAGE_SCALE_FIT, 0.8f)` leaf component drawing a small teal/white
 * checkerboard bitmap at 80% opacity into that explicit 16x16 box (this renderer has no measure
 * pass to size an image from its own bitmap/scaleType otherwise, so `LAYOUT_IMAGE` only renders
 * when an explicit `width()`/`height()` is present — a real-bytes hex-diff of this same call also
 * caught `bitmapId` and `scaleType` swapped from an earlier pass's assumed field order) — and
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
            "AEMgAABBQAAA1tbK////5P////8AAAAAAAAAAFMAAAAB0QAAAAnWyf///+MoAAAAAgAAAAT/AIOPKkMlAABAAAAAQzQAAEFAAADW1sr////i/////wAA" +
            "AAAAAAAA29EAAAAJ1sn////hKAAAAAIAAAAE/41uYypAAAAAQXAAAEGIAABByAAA1tbK////4P////8AAAAAAAAAANzRAAAACdbJ////3ygAAAACAAAA" +
            "BP8mppoqQaAAAEFwAABCDAAAQcgAANbWyv///97/////AAAAAAAAAADh0QAAAAnWyf///90oAAAAAgAAAAT/71NQKkIYAABBcAAAQlQAAEHIAADW1sr/" +
            "///c/////wAAAAAAAAAA50EgAABBoAAAyf///9soAAAAAgAAAAT/eYbLKkJgAABBcAAAQo4AAEHIAADW1sr////a/////wAAAAAAAAAA6EBAAABBIAAA" +
            "yf///9koAAAAAgAAAAT//7MAKkKUAABBcAAAQrIAAEHIAADW1sr////Y/////wAAAAAAAAAA6wAAAABAAAAAyf///9coAAAAAgAAAAT/VG56KkK4AABB" +
            "cAAAQtYAAEHIAADW1sr////W/////wAAAAAAAAAA7f+AAAEAAAAAyf///9UoAAAAAgAAAAT/jiSqKkLcAABBcAAAQvoAAEHIAADW1sr////U/////wAA" +
            "AAAAAAAA30BAAADJ////0ygAAAACAAAABP8+JyMqQwAAAEFwAABDDwAAQcgAANbWyv///9L/////AAAAAAAAAADJ////0cr////Q/////wAAAAAAAAAA" +
            "30CgAADJ////zygAAAACAAAABP/TLy8qQxYAAEKCAABDLwAAQqAAANbWyv///87/////AAAAAAAAAADfP4AAAMn////NKAAAAAIAAAAE/xl20ipDFgAA" +
            "QoIAAEMvAABCoAAA1tbW1sr////M/////wAAAAAAAAAA5cn////LKAAAAAIAAAAE/xl20ipDEgAAQXAAAEMhAABByAAA1tbK////yv////8AAAAAAAAA" +
            "AK7J////ySgAAAACAAAABP+tFFcqQyQAAEFwAABDMwAAQcgAANbWyv///8j/////AAAAAAAAAADkAAAAAQAAAABEegAAQ/oAAEEAAABB8AAAyf///8co" +
            "AAAAAgAAAAT/gncXKkAAAABB4AAAQYgAAEIYAADW1sr////G/////wAAAAAAAAAA4AAAAAEAAAQLPwAAAMn////FKAAAAAIAAAAE/2obmipBoAAAQeAA" +
            "AEIMAABCGAAA1tbK////xP////8AAAAAAAAAAOAAAAACAAAEAEAAAAAAAAQBQAAAAMn////DKAAAAAIAAAAE//3YNSpCNAAAQnQAAEJUAABCigAA1tbK" +
            "////wv////8AAAAAAAAAAOAAAAABAAAEBEI0AADJ////wSgAAAACAAAABP8eiOUqQnAAAEJ0AABCmAAAQooAANbWyv///8D/////AAAAAAAAAADzAUCg" +
            "AABCIAAAyf///78oAAAAAgAAAAT/RSegKkIYAABB4AAAQlQAAEIYAADW1sr///++/////wAAAAAAAAAAO9QAAAADAAAAB9bJ////vSgAAAACAAAABP8A" +
            "aVwqQmAAAEHgAABCjgAAQhgAANbWyv///7z/////AAAAAAAAAAA73gAAAARAIAAA1sn///+7KAAAAAIAAAAE/9hDFSpClAAAQeAAAEKyAABCGAAA1tbK" +
            "////uv////8AAAAAAAAAADtmAAAANgAAAAJoadUAAAAFAAAANtbJ////uSgAAAACAAAABP8oNZMqQrgAAEHgAABC1gAAQhgAANbWyv///7j/////AAAA" +
            "AAAAAAA72gAAAAAAAAAGAAAAAAAAACrWyf///7coAAAAAgAAAAT/M2keKkLcAABB4AAAQvoAAEIYAADW1sr///+2/////wAAAAAAAAAAO+MAAAAHAAAA" +
            "CdbJ////tSgAAAACAAAABP9tTEEqQwAAAEHgAABDDwAAQhgAANbW6f///7T/////AAAAAAAAAAAAAAAAyf///7MoAAAAAgAAAAT/N0dPKkMSAABB4AAA" +
            "QyEAAEIYAADW1ub///+y/////wAAAAAAAAAAAAAAAMn///+xKAAAAAIAAAAE/4gOTypDJAAAQeAAAEMzAABCGAAA1tbw////sP////8AAAAAAAAAAAAA" +
            "AAB/////f////8n///+vKAAAAAIAAAAE/wCDjypAAAAAQiQAAEGIAABCTAAA1taw////rv////8AAAAAAAAAAMn///+tKAAAAAIAAAAE/+ZKGSpBoAAA" +
            "QiQAAEIMAABCTAAA1tbI////rCgAAAACAAAABP8aI34qQhgAAEIkAABCVAAAQkwAANbZ////q/////8AAAAAAAAAAAAAAADJ////qigAAAACAAAABP+t" +
            "FFcqQmAAAEIkAABCjgAAQkwAANbWzf///6n/////yf///6jP////pygAAAACAAAABP9Viy8qQpQAAEIkAABCsgAAQkwAANbW1mYAAAA3AAAACG15Q3Vz" +
            "dG9tXf//////////AAAANwAAAADJ////pigAAAACAAAABP9ONC4qQrgAAEIkAABC1gAAQkwAANbWZQAAADgAAAAEAAAABAAAAFCJUE5HDQoaCgAAAA1J" +
            "SERSAAAABAAAAAQIBgAAAKnxnn4AAAAXSURBVHheY2DYv/Q/CMBpFA6QZiCoAgC6AjL51qQNdQAAAABJRU5ErkJggur///+l/////wAAADgAAAAEP0zM" +
            "zRAAAAAAQYAAAEMAAAAAQYAAANaxAAAABD8AAAABQQAAAAEAAAACAAAAAwAAAATK////pP////8AAAAAAAAAAA4AAAADQ5YAAAAAAAFDlgAAAAAAAQAA" +
            "AAAAAAAByf///6MoAAAAAgAAAAT/XUA3KkLcAABCJAAAQvoAAEJMAADW1oJ/QwIAAEIkAAAoAAAAAgAAAAT/AKzBKgAAAAAAAAAAQXAAAEEgAACDgn5A" +
            "AAAAQAAAAEMRAABCOAAAKAAAAAIAAAAE//V/FypDEQAAQiQAAEMYAABCOAAAg4KBQjQAAEMvAABCOAAAKAAAAAIAAAAE/2obmipDKAAAQiQAAEM3AABC" +
            "TAAAg4InQz4AAEIkAABDTQAAQkwAACgAAAACAAAABP+qAP8qQzkAAEIQAABDUgAAQmAAAIOCgD8AAAAAAAAAKAAAAAIAAAAE/9hDFSpAAAAAQwIAAEGI" +
            "AABDEQAAg+n///+i/////wAAAAAAAAAAQEAAAMn///+hyv///6D/////AAAAAAAAAADJ////nygAAAACAAAABP/CGFsqQbAAAEMCAABCAAAAQwwAANbW" +
            "yv///57/////AAAAAAAAAADJ////nSgAAAACAAAABP97H6IqQbAAAEMCAABCAAAAQwwAANbWyv///5z/////AAAAAAAAAADJ////mygAAAACAAAABP8w" +
            "P58qQbAAAEMCAABCAAAAQwwAANbW1tYoAAAAAgAAAAT/ISEhZgAAADkAAAALSGVsbG8gV29ybGQrAAAAOQAAAAYAAAALAAAAAAAAAAtCEAAAQwgAAAB7" +
            "AAAAOgAAAAj/gAAKQtIAAENDAAD/gAALAAAAAAAAAABC+gAAQ0MAACgAAAACAAAABP8AaVxmAAAAOwAAAARQYXRoNQAAADsAAAA6wIAAAAAAAADK////" +
            "mv////8AAAAAAAAAADcAAAAAAAAAAAAAAAAAAAAAP4AAAD7e3t8AAAAAP4AAAAAAAADJ////mSgAAAACAAAABP8VZcAqQAAAAEJ0AABBQAAAQo4AACgA" +
            "AAACAAAABP8ufTIuQgwAAEKcAABAwAAA1tbM////mP////8AAAAAAAAAAEBAAADJ////l8r///+W/////wAAAAAAAAAAyf///5UoAAAAAgAAAAT/0y8v" +
            "KkAAAABCtAAAQUAAAELIAADW1sr///+U/////wAAAAAAAAAAyf///5MoAAAAAgAAAAT/GXbSKkAAAABCtAAAQUAAAELIAADW1sr///+S/////wAAAAAA" +
            "AAAAyf///5EoAAAAAgAAAAT/OI48KkAAAABCtAAAQUAAAELIAADW1tbWy////5D/////AAAABgAAAAIAAAAAEAAAAABCoAAAyf///4/K////jv////8A" +
            "AAAAAAAAAMn///+NKAAAAAIAAAAE/8YoKCpBsAAAQxYAAEHwAABDHgAA1tbK////jP////8AAAAAAAAAAMn///+LKAAAAAIAAAAE//moJSpBsAAAQxYA" +
            "AEHwAABDJgAA1tbK////iv////8AAAAAAAAAAMn///+JKAAAAAIAAAAE/wCDjypBsAAAQxYAAEHwAABDHgAA1tbW1oJ7AAAAPAAAAA7/gAAKQtIAAEMo" +
            "AAD/gAALAAAAAAAAAABC+gAAQxYAAP+AAAsAAAAAAAAAAEL6AABDKAAA/4AADyYAAAA8KAAAAAIAAAAE/2obmipC0gAAQxYAAEMCAABDKgAAgygAAAAC" +
            "AAAABP8hISFmAAAAPQAAAAZDdXJ2ZWQ5AAAAPULIAABDOQAAQSAAAEOHAAAAAAAAAQA=",
    )
}
