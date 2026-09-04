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
 * in a `startBox`/`endBox` carrying `padding()`/`background()` modifiers, a small green rect
 * wrapped in a `startBox`/`endBox` carrying a `visibility()` modifier, and a small orange rect
 * wrapped in a `startBox`/`endBox` carrying an `offset()` modifier, a small teal rect wrapped in a
 * `startBox`/`endBox` carrying a `border()` modifier, a small purple rect wrapped in a
 * `startBox`/`endBox` carrying a `clip(RectShape(...))` modifier, a small olive rect wrapped in
 * a `startBox`/`endBox` carrying a `clip(RoundedRectShape(...))` modifier, a small teal rect
 * wrapped in a `startBox`/`endBox` carrying an `onLongClick(HostAction(9))` modifier, a second row
 * of a brown/teal/red rect each wrapped in a `startBox`/`endBox` carrying an `onTouchDown`/
 * `onTouchUp`/`onTouchCancel(HostAction(9))` modifier respectively, an indigo/amber rect carrying a
 * `widthIn()`/`heightIn()` modifier respectively, a blue-grey rect carrying a
 * `collapsiblePriority()` modifier, a purple rect carrying an `alignByBaseline()` modifier, and a
 * dark-brown rect carrying a `then(ZIndexModifier(3f))` modifier, a blue rect carrying a
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
 * wrapped in a `startCustom`/`endCustom`, a `writer.image(...)` leaf component — not yet rendered
 * by this engine's `Image` opcode support, only byte-consumed — and three top-level document
 * metadata ops: `performHaptic(4)`, `setTheme(1)`, `setRootContentBehavior(1, 2, 3, 4)` — and a
 * brown rect wrapped in a `startBox`/`endBox` carrying an `animationSpec(3)` modifier, a cyan rect
 * drawn after a raw `writer.save()`/`writer.translate(130f, 41f)`/`writer.restore()` (no modifier
 * involved — the document author's own top-level matrix ops), an amber rect drawn after a raw
 * `writer.save()`/`writer.scale(2f, 2f, 145f, 46f)`/`writer.restore()`, a purple rect drawn after a
 * raw `writer.save()`/`writer.rotate(45f, 175f, 46f)`/`writer.restore()`, and a magenta rect drawn
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
 * overload (those always set src == dst) — not by anything in this codebase. Shared by every
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
            "7EB6KkM+AABAAAAAQ0cAAEEwAADW1sr////0/////wAAAAAAAAAAOkBAAABAQAAAQEAAAEBAAAA3AAAAAAAAAAAAAAAAAAAAAD729vc9+Pj5PyKioz+A" +
            "AAAAAAAAyf////MoAAAAAgAAAAT/ex+iKkI0AABAAAAAQnAAAEFAAADW1sr////y/////wAAAAAAAAAA0wAAAADJ////8SgAAAACAAAABP84jjwqQoIA" +
            "AEAAAABCoAAAQUAAANbWyv////D/////AAAAAAAAAADdQKAAAECgAADJ////7ygAAAACAAAABP//VyIqQqoAAEAAAABCyAAAQUAAANbWyv///+7/////" +
            "AAAAAAAAAABrAAAAAAAAAAAAAAAAAAAAAEAAAABAgAAAAAAAAAAAAAAAAAAAP4AAAAAAAADJ////7SgAAAACAAAABP8AlogqQtIAAEAAAABC8AAAQUAA" +
            "ANbWyv///+z/////AAAAAAAAAABsyf///+soAAAAAgAAAAT/XjWxKkL6AABAAAAAQwwAAEFAAADW1sr////q/////wAAAAAAAAAANkCAAABAgAAAQIAA" +
            "AECAAADJ////6SgAAAACAAAABP/AyjMqQxEAAEAAAABDIAAAQUAAANbWyv///+j/////AAAAAAAAAABTAAAAAdEAAAAJ1sn////nKAAAAAIAAAAE/wCD" +
            "jypDJQAAQAAAAEM0AABBQAAA1tbK////5v////8AAAAAAAAAANvRAAAACdbJ////5SgAAAACAAAABP+NbmMqQAAAAEFwAABBiAAAQcgAANbWyv///+T/" +
            "////AAAAAAAAAADc0QAAAAnWyf///+MoAAAAAgAAAAT/JqaaKkGgAABBcAAAQgwAAEHIAADW1sr////i/////wAAAAAAAAAA4dEAAAAJ1sn////hKAAA" +
            "AAIAAAAE/+9TUCpCGAAAQXAAAEJUAABByAAA1tbK////4P////8AAAAAAAAAAOdBIAAAQaAAAMn////fKAAAAAIAAAAE/3mGyypCYAAAQXAAAEKOAABB" +
            "yAAA1tbK////3v////8AAAAAAAAAAOhAQAAAQSAAAMn////dKAAAAAIAAAAE//+zACpClAAAQXAAAEKyAABByAAA1tbK////3P////8AAAAAAAAAAOsA" +
            "AAAAQAAAAMn////bKAAAAAIAAAAE/1RueipCuAAAQXAAAELWAABByAAA1tbK////2v////8AAAAAAAAAAO3/gAABAAAAAMn////ZKAAAAAIAAAAE/44k" +
            "qipC3AAAQXAAAEL6AABByAAA1tbK////2P////8AAAAAAAAAAN9AQAAAyf///9coAAAAAgAAAAT/PicjKkMAAABBcAAAQw8AAEHIAADW1sr////W////" +
            "/wAAAAAAAAAA5cn////VKAAAAAIAAAAE/xl20ipDEgAAQXAAAEMhAABByAAA1tbK////1P////8AAAAAAAAAAK7J////0ygAAAACAAAABP+tFFcqQyQA" +
            "AEFwAABDMwAAQcgAANbWyv///9L/////AAAAAAAAAADkAAAAAQAAAABEegAAQ/oAAEEAAABB8AAAyf///9EoAAAAAgAAAAT/gncXKkAAAABB4AAAQYgA" +
            "AEIYAADW1sr////Q/////wAAAAAAAAAA4AAAAAEAAAQLPwAAAMn////PKAAAAAIAAAAE/2obmipBoAAAQeAAAEIMAABCGAAA1tbK////zv////8AAAAA" +
            "AAAAAOAAAAACAAAEAEAAAAAAAAQBQAAAAMn////NKAAAAAIAAAAE//3YNSpCNAAAQnQAAEJUAABCigAA1tbK////zP////8AAAAAAAAAAOAAAAABAAAE" +
            "BEI0AADJ////yygAAAACAAAABP8eiOUqQnAAAEJ0AABCmAAAQooAANbWyv///8r/////AAAAAAAAAADzAUCgAABCIAAAyf///8koAAAAAgAAAAT/RSeg" +
            "KkIYAABB4AAAQlQAAEIYAADW1sr////I/////wAAAAAAAAAAO9QAAAADAAAAB9bJ////xygAAAACAAAABP8AaVwqQmAAAEHgAABCjgAAQhgAANbWyv//" +
            "/8b/////AAAAAAAAAAA73gAAAARAIAAA1sn////FKAAAAAIAAAAE/9hDFSpClAAAQeAAAEKyAABCGAAA1tbK////xP////8AAAAAAAAAADtmAAAAMwAA" +
            "AAJoadUAAAAFAAAAM9bJ////wygAAAACAAAABP8oNZMqQrgAAEHgAABC1gAAQhgAANbWyv///8L/////AAAAAAAAAAA72gAAAAAAAAAGAAAAAAAAACrW" +
            "yf///8EoAAAAAgAAAAT/M2keKkLcAABB4AAAQvoAAEIYAADW1sr////A/////wAAAAAAAAAAO+MAAAAHAAAACdbJ////vygAAAACAAAABP9tTEEqQwAA" +
            "AEHgAABDDwAAQhgAANbW6f///77/////AAAAAAAAAAAAAAAAyf///70oAAAAAgAAAAT/N0dPKkMSAABB4AAAQyEAAEIYAADW1ub///+8/////wAAAAAA" +
            "AAAAAAAAAMn///+7KAAAAAIAAAAE/4gOTypDJAAAQeAAAEMzAABCGAAA1tbw////uv////8AAAAAAAAAAAAAAAB/////f////8n///+5KAAAAAIAAAAE" +
            "/wCDjypAAAAAQiQAAEGIAABCTAAA1taw////uP////8AAAAAAAAAAMn///+3KAAAAAIAAAAE/+ZKGSpBoAAAQiQAAEIMAABCTAAA1tbI////tigAAAAC" +
            "AAAABP8aI34qQhgAAEIkAABCVAAAQkwAANbZ////tf////8AAAAAAAAAAAAAAADJ////tCgAAAACAAAABP+tFFcqQmAAAEIkAABCjgAAQkwAANbWzf//" +
            "/7P/////yf///7LP////sSgAAAACAAAABP9Viy8qQpQAAEIkAABCsgAAQkwAANbW1mYAAAA0AAAACG15Q3VzdG9tXf//////////AAAANAAAAADJ////" +
            "sCgAAAACAAAABP9ONC4qQrgAAEIkAABC1gAAQkwAANbW6v///6//////AAAAAwAAAAE/QAAA1rEAAAAEPwAAAAFBAAAAAQAAAAIAAAADAAAABMr///+u" +
            "/////wAAAAAAAAAADgAAAANDlgAAAAAAAUOWAAAAAAABAAAAAAAAAAHJ////rSgAAAACAAAABP9dQDcqQtwAAEIkAABC+gAAQkwAANbWgn9DAgAAQiQA" +
            "ACgAAAACAAAABP8ArMEqAAAAAAAAAABBcAAAQSAAAIOCfkAAAABAAAAAQxEAAEI4AAAoAAAAAgAAAAT/9X8XKkMRAABCJAAAQxgAAEI4AACDgoFCNAAA" +
            "Qy8AAEI4AAAoAAAAAgAAAAT/ahuaKkMoAABCJAAAQzcAAEJMAACDgidDPgAAQiQAAENNAABCTAAAKAAAAAIAAAAE/6oA/ypDOQAAQhAAAENSAABCYAAA" +
            "g8r///+s/////wAAAAAAAAAANwAAAAAAAAAAAAAAAAAAAAA/gAAAPt7e3wAAAAA/gAAAAAAAAMn///+rKAAAAAIAAAAE/xVlwCpAAAAAQnQAAEFAAABC" +
            "jgAAKAAAAAIAAAAE/y59Mi5CDAAAQpwAAEDAAADW1sz///+q/////wAAAAAAAAAAQEAAAMn///+pyv///6j/////AAAAAAAAAADJ////pygAAAACAAAA" +
            "BP/TLy8qQAAAAEK0AABBQAAAQsgAANbWyv///6b/////AAAAAAAAAADJ////pSgAAAACAAAABP8ZdtIqQAAAAEK0AABBQAAAQsgAANbWyv///6T/////" +
            "AAAAAAAAAADJ////oygAAAACAAAABP84jjwqQAAAAEK0AABBQAAAQsgAANbW1tbL////ov////8AAAAGAAAAAgAAAAAQAAAAAEKgAADJ////ocr///+g" +
            "/////wAAAAAAAAAAyf///58oAAAAAgAAAAT/xigoKkGwAABDFgAAQfAAAEMeAADW1sr///+e/////wAAAAAAAAAAyf///50oAAAAAgAAAAT/+aglKkGw" +
            "AABDFgAAQfAAAEMmAADW1sr///+c/////wAAAAAAAAAAyf///5soAAAAAgAAAAT/AIOPKkGwAABDFgAAQfAAAEMeAADW1tbWgnsAAAA1AAAADv+AAApC" +
            "0gAAQygAAP+AAAsAAAAAAAAAAEL6AABDFgAA/4AACwAAAAAAAAAAQvoAAEMoAAD/gAAPJgAAADUoAAAAAgAAAAT/ahuaKkLSAABDFgAAQwIAAEMqAACD",
    )
}
