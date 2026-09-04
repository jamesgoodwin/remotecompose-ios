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
 * `then(GraphicsLayerModifier().apply { setFloatAttribute(ALPHA, 0.5f) })` modifier, and a
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
 * own fill), not by anything in this codebase. Shared by every platform demo entry point
 * (iOS, Android) so they
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
            "aGVja2VyLAAAAC1CjAAAQoIAAELcAABC0gAAAAAALmYAAAAvAAAACnRhcCB0YXJnZXRmAAAAMAAAABpodHRwczovL2V4YW1wbGUuY29tL3RhcHBlZEAA" +
            "AAAHAAAAL0GgAABBoAAAQzQAAEM0AAAAAAAwKAAAAAIAAAAE/wCDj3sAAAAxAAAAFP+AAApAAAAAQxYAAP+AAAwAAAAAAAAAAEGQAABDFgAAQZAAAEMq" +
            "AAD/gAAOAAAAAAAAAABBkAAAQzkAAEEgAABDQwAAQAAAAENDAAD/gAAPfAAAADHM/////v////8AAAAAAAAAAAAAAADJ/////SgAAAACAAAABP/92DUq" +
            "QqoAAEMWAABC0gAAQyUAACgAAAACAAAABP9tTEEuQr4AAEMvAABBAAAA1tbL/////P////8AAAAAAAAAAAAAAADJ////+ygAAAACAAAABP8AaVwqQuYA" +
            "AEMWAABDAgAAQyUAACgAAAACAAAABP/wYpIuQwwAAEMdAABA4AAA1tbK////+v////8AAAAAAAAAAMn////5KAAAAAIAAAAE/56dJCpDFgAAQxQAAEMl" +
            "AABDIwAA1tbK////+P////8AAAAAAAAAABAAAAAAQaAAAEMAAAAAQSAAAMn////3KAAAAAIAAAAE/11ANypDKgAAQxQAAEM+AABDIwAA1tbK////9v//" +
            "//8AAAAAAAAAADvRAAAACdbJ////9SgAAAACAAAABP/sQHoqQz4AAEAAAABDRwAAQTAAANbWyv////T/////AAAAAAAAAAA6QEAAAEBAAABAQAAAQEAA" +
            "ADcAAAAAAAAAAAAAAAAAAAAAPvb29z34+Pk/IqKjP4AAAAAAAADJ////8ygAAAACAAAABP97H6IqQjQAAEAAAABCcAAAQUAAANbWyv////L/////AAAA" +
            "AAAAAADTAAAAAMn////xKAAAAAIAAAAE/ziOPCpCggAAQAAAAEKgAABBQAAA1tbK////8P////8AAAAAAAAAAN1AoAAAQKAAAMn////vKAAAAAIAAAAE" +
            "//9XIipCqgAAQAAAAELIAABBQAAA1tbK////7v////8AAAAAAAAAAGsAAAAAAAAAAAAAAAAAAAAAQAAAAECAAAAAAAAAAAAAAAAAAAA/gAAAAAAAAMn/" +
            "///tKAAAAAIAAAAE/wCWiCpC0gAAQAAAAELwAABBQAAA1tbK////7P////8AAAAAAAAAAGzJ////6ygAAAACAAAABP9eNbEqQvoAAEAAAABDDAAAQUAA" +
            "ANbWyv///+r/////AAAAAAAAAAA2QIAAAECAAABAgAAAQIAAAMn////pKAAAAAIAAAAE/8DKMypDEQAAQAAAAEMgAABBQAAA1tbK////6P////8AAAAA" +
            "AAAAAFMAAAAB0QAAAAnWyf///+coAAAAAgAAAAT/AIOPKkMlAABAAAAAQzQAAEFAAADW1sr////m/////wAAAAAAAAAA29EAAAAJ1sn////lKAAAAAIA" +
            "AAAE/41uYypAAAAAQXAAAEGIAABByAAA1tbK////5P////8AAAAAAAAAANzRAAAACdbJ////4ygAAAACAAAABP8mppoqQaAAAEFwAABCDAAAQcgAANbW" +
            "yv///+L/////AAAAAAAAAADh0QAAAAnWyf///+EoAAAAAgAAAAT/71NQKkIYAABBcAAAQlQAAEHIAADW1sr////g/////wAAAAAAAAAA50EgAABBoAAA" +
            "yf///98oAAAAAgAAAAT/eYbLKkJgAABBcAAAQo4AAEHIAADW1sr////e/////wAAAAAAAAAA6EBAAABBIAAAyf///90oAAAAAgAAAAT//7MAKkKUAABB" +
            "cAAAQrIAAEHIAADW1sr////c/////wAAAAAAAAAA6wAAAABAAAAAyf///9soAAAAAgAAAAT/VG56KkK4AABBcAAAQtYAAEHIAADW1sr////a/////wAA" +
            "AAAAAAAA7f+AAAEAAAAAyf///9koAAAAAgAAAAT/jiSqKkLcAABBcAAAQvoAAEHIAADW1sr////Y/////wAAAAAAAAAA30BAAADJ////1ygAAAACAAAA" +
            "BP8+JyMqQwAAAEFwAABDDwAAQcgAANbWyv///9b/////AAAAAAAAAADlyf///9UoAAAAAgAAAAT/GXbSKkMSAABBcAAAQyEAAEHIAADW1sr////U////" +
            "/wAAAAAAAAAArsn////TKAAAAAIAAAAE/60UVypDJAAAQXAAAEMzAABByAAA1tbK////0v////8AAAAAAAAAAOQAAAABAAAAAER6AABD+gAAQQAAAEHw" +
            "AADJ////0SgAAAACAAAABP+CdxcqQAAAAEHgAABBiAAAQhgAANbWyv///9D/////AAAAAAAAAADgAAAAAQAABAs/AAAAyf///88oAAAAAgAAAAT/ahua" +
            "KkGgAABB4AAAQgwAAEIYAADW1sr////O/////wAAAAAAAAAA8wFAoAAAQiAAAMn////NKAAAAAIAAAAE/0UnoCpCGAAAQeAAAEJUAABCGAAA1tbK////" +
            "zP////8AAAAAAAAAADvUAAAAAwAAAAfWyf///8soAAAAAgAAAAT/AGlcKkJgAABB4AAAQo4AAEIYAADW1sr////K/////wAAAAAAAAAAO94AAAAEQCAA" +
            "ANbJ////ySgAAAACAAAABP/YQxUqQpQAAEHgAABCsgAAQhgAANbWyv///8j/////AAAAAAAAAAA7ZgAAADIAAAACaGnVAAAABQAAADLWyf///8coAAAA" +
            "AgAAAAT/KDWTKkK4AABB4AAAQtYAAEIYAADW1sr////G/////wAAAAAAAAAAO9oAAAAAAAAABgAAAAAAAAAq1sn////FKAAAAAIAAAAE/zNpHipC3AAA" +
            "QeAAAEL6AABCGAAA1tbK////xP////8AAAAAAAAAADvjAAAABwAAAAnWyf///8MoAAAAAgAAAAT/bUxBKkMAAABB4AAAQw8AAEIYAADW1un////C////" +
            "/wAAAAAAAAAAAAAAAMn////BKAAAAAIAAAAE/zdHTypDEgAAQeAAAEMhAABCGAAA1tbm////wP////8AAAAAAAAAAAAAAADJ////vygAAAACAAAABP+I" +
            "Dk8qQyQAAEHgAABDMwAAQhgAANbW8P///77/////AAAAAAAAAAAAAAAAf////3/////J////vSgAAAACAAAABP8Ag48qQAAAAEIkAABBiAAAQkwAANbW" +
            "sP///7z/////AAAAAAAAAADJ////uygAAAACAAAABP/mShkqQaAAAEIkAABCDAAAQkwAANbWyP///7ooAAAAAgAAAAT/GiN+KkIYAABCJAAAQlQAAEJM" +
            "AADW2f///7n/////AAAAAAAAAAAAAAAAyf///7goAAAAAgAAAAT/rRRXKkJgAABCJAAAQo4AAEJMAADW1s3///+3/////8n///+2z////7UoAAAAAgAA" +
            "AAT/VYsvKkKUAABCJAAAQrIAAEJMAADW1tZmAAAAMwAAAAhteUN1c3RvbV3//////////wAAADMAAAAAyf///7QoAAAAAgAAAAT/TjQuKkK4AABCJAAA" +
            "QtYAAEJMAADW1ur///+z/////wAAAAMAAAABP0AAANaxAAAABD8AAAABQQAAAAEAAAACAAAAAwAAAATK////sv////8AAAAAAAAAAA4AAAADQ5YAAAAA" +
            "AAFDlgAAAAAAAQAAAAAAAAAByf///7EoAAAAAgAAAAT/XUA3KkLcAABCJAAAQvoAAEJMAADW1oJ/QwIAAEIkAAAoAAAAAgAAAAT/AKzBKgAAAAAAAAAA" +
            "QXAAAEEgAACDgn5AAAAAQAAAAEMRAABCOAAAKAAAAAIAAAAE//V/FypDEQAAQiQAAEMYAABCOAAAg4KBQjQAAEMvAABCOAAAKAAAAAIAAAAE/2obmipD" +
            "KAAAQiQAAEM3AABCTAAAg4InQz4AAEIkAABDTQAAQkwAACgAAAACAAAABP+qAP8qQzkAAEIQAABDUgAAQmAAAIPK////sP////8AAAAAAAAAADcAAAAA" +
            "AAAAAAAAAAAAAAAAP4AAAD7e3t8AAAAAP4AAAAAAAADJ////rygAAAACAAAABP8VZcAqQAAAAEJ0AABBQAAAQo4AACgAAAACAAAABP8ufTIuQgwAAEKc" +
            "AABAwAAA1tY="
    )
}
