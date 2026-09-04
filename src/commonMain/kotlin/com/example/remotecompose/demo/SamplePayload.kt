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
 * wrapped in a `startBox`/`endBox` carrying an `onLongClick(HostAction(9))` modifier, and a second
 * row of a brown/teal/red rect each wrapped in a `startBox`/`endBox` carrying an `onTouchDown`/
 * `onTouchUp`/`onTouchCancel(HostAction(9))` modifier respectively), not by anything in this
 * codebase. Shared by every platform demo entry point (iOS, Android) so they render byte-identical
 * input — the point of the cross-platform comparison is to catch *rendering* differences, not to
 * accidentally compare two different payloads.
 */
@OptIn(ExperimentalEncodingApi::class)
val SAMPLE_RC_BYTES: ByteArray by lazy {
    Base64.decode(
        "AAAAAAEAAAABAAAAAAAAAMgAAADIAAAAAAAAAABmAAAAKgAAAARkZW1vZwAAACooAAAAAgAAAAT/5Tk1KkGgAABBoAAAQzQAAEM0AAAoAAAA" +
            "AgAAAAT/HojlLkJwAABDDAAAQfAAACgAAAACAAAABP9DoEczQtwAAELcAABDPgAAQz4AAEFAAABBQAAAKAAAAAIAAAAE/wAAAGYAAAArAAAA" +
            "AkhphQAAACtCyAAAQaAAAAAAAAAAAAAAAAAAACgAAAACAAAABP+OJKovQSAAAENDAABDPgAAQ0MAACgAAAACAAAABP/7jAA4QwwAAEHwAABD" +
            "QwAAQnAAACgAAAACAAAABP8ArMGYQAAAAEAAAABCIAAAQiAAAAAAAABCtAAAKAAAAAIAAAAE/9gbYDRDDAAAQwwAAENGAABDRgAAQ0gAAELI" +
            "AAAoAAAAAgAAAAT/OUmrewAAACwAAAAO/4AACkMbAABDMgAA/4AACwAAAAAAAAAAQ0YAAEMyAAD/gAALAAAAAAAAAABDMAAAQ0cAAP+AAA98" +
            "AAAALGUAAAAtAAAACAAAAAgAAABWiVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAHUlEQVR4XmP4DwSKioogCivNgE0QmWbA" +
            "JjjkTAAAa5Crwb4olnUAAAAASUVORK5CYIJmAAAALgAAAAdjaGVja2VyLAAAAC1CjAAAQoIAAELcAABC0gAAAAAALmYAAAAvAAAACnRhcCB0" +
            "YXJnZXRmAAAAMAAAABpodHRwczovL2V4YW1wbGUuY29tL3RhcHBlZEAAAAAHAAAAL0GgAABBoAAAQzQAAEM0AAAAAAAwKAAAAAIAAAAE/wCD" +
            "j3sAAAAxAAAAFP+AAApAAAAAQxYAAP+AAAwAAAAAAAAAAEGQAABDFgAAQZAAAEMqAAD/gAAOAAAAAAAAAABBkAAAQzkAAEEgAABDQwAAQAAA" +
            "AENDAAD/gAAPfAAAADHM/////v////8AAAAAAAAAAAAAAADJ/////SgAAAACAAAABP/92DUqQqoAAEMWAABC0gAAQyUAACgAAAACAAAABP9t" +
            "TEEuQr4AAEMvAABBAAAA1tbL/////P////8AAAAAAAAAAAAAAADJ////+ygAAAACAAAABP8AaVwqQuYAAEMWAABDAgAAQyUAACgAAAACAAAA" +
            "BP/wYpIuQwwAAEMdAABA4AAA1tbK////+v////8AAAAAAAAAAMn////5KAAAAAIAAAAE/56dJCpDFgAAQxQAAEMlAABDIwAA1tbK////+P//" +
            "//8AAAAAAAAAABAAAAAAQaAAAEMAAAAAQSAAAMn////3KAAAAAIAAAAE/11ANypDKgAAQxQAAEM+AABDIwAA1tbK////9v////8AAAAAAAAA" +
            "ADvRAAAACdbJ////9SgAAAACAAAABP/sQHoqQz4AAEAAAABDRwAAQTAAANbWyv////T/////AAAAAAAAAAA6QEAAAEBAAABAQAAAQEAAADcA" +
            "AAAAAAAAAAAAAAAAAAAAPvb29z34+Pk/IqKjP4AAAAAAAADJ////8ygAAAACAAAABP97H6IqQjQAAEAAAABCcAAAQUAAANbWyv////L/////" +
            "AAAAAAAAAADTAAAAAMn////xKAAAAAIAAAAE/ziOPCpCggAAQAAAAEKgAABBQAAA1tbK////8P////8AAAAAAAAAAN1AoAAAQKAAAMn////v" +
            "KAAAAAIAAAAE//9XIipCqgAAQAAAAELIAABBQAAA1tbK////7v////8AAAAAAAAAAGsAAAAAAAAAAAAAAAAAAAAAQAAAAECAAAAAAAAAAAAA" +
            "AAAAAAA/gAAAAAAAAMn////tKAAAAAIAAAAE/wCWiCpC0gAAQAAAAELwAABBQAAA1tbK////7P////8AAAAAAAAAAGzJ////6ygAAAACAAAA" +
            "BP9eNbEqQvoAAEAAAABDDAAAQUAAANbWyv///+r/////AAAAAAAAAAA2QIAAAECAAABAgAAAQIAAAMn////pKAAAAAIAAAAE/8DKMypDEQAA" +
            "QAAAAEMgAABBQAAA1tbK////6P////8AAAAAAAAAAFMAAAAB0QAAAAnWyf///+coAAAAAgAAAAT/AIOPKkMlAABAAAAAQzQAAEFAAADW1sr/" +
            "///m/////wAAAAAAAAAA29EAAAAJ1sn////lKAAAAAIAAAAE/41uYypAAAAAQXAAAEGIAABByAAA1tbK////5P////8AAAAAAAAAANzRAAAA" +
            "CdbJ////4ygAAAACAAAABP8mppoqQaAAAEFwAABCDAAAQcgAANbWyv///+L/////AAAAAAAAAADh0QAAAAnWyf///+EoAAAAAgAAAAT/71NQ" +
            "KkIYAABBcAAAQlQAAEHIAADW1g=="
    )
}
