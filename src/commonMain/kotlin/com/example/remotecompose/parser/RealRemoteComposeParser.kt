package com.example.remotecompose.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.remotecompose.model.Header
import com.example.remotecompose.model.Opcode
import com.example.remotecompose.model.PaintStyle
import com.example.remotecompose.model.PaintStyleKind
import com.example.remotecompose.model.PathCommand
import com.example.remotecompose.model.RemoteDocument
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
 * Parses the **real** `androidx.compose.remote` wire format (v1.0.0-alpha18), as produced by the
 * official `androidx.compose.remote:remote-creation-jvm` writer — not the placeholder format
 * [RemoteComposeParser] was built against.
 *
 * This is deliberately narrow: it understands exactly the real opcodes a minimal
 * `RemoteComposeWriter(width, height, contentDescription, platform)` document produces when built
 * from `getRcPaint().setColor(...).commit()` plus `drawRect`/`drawCircle`/`drawRoundRect`/
 * `drawTextAnchored` calls, reverse-engineered by hex-dumping actual output from the official
 * writer (see the opcode constants below and `tools/rc-writer`, which generates the payloads this
 * was verified against). Real documents in general use 100+ opcodes across versioned "profiles"
 * (`androidx.compose.remote.core.Operations`), each with its own fixed field layout dispatched by
 * a per-opcode `CompanionOperation` — there is no generic length-prefix that would let a reader
 * skip an opcode it doesn't recognize (unlike the skippable framing [RemoteComposeParser] assumes).
 * An opcode outside this subset is therefore a hard parse failure here, not a graceful skip.
 *
 * Confirmed against real output from `androidx.compose.remote:remote-core` /
 * `remote-creation-jvm:1.0.0-alpha18`:
 * - All integers/floats are big-endian, IEEE-754 raw bits for floats — this part matches what
 *   [BufferReader] already assumed for the placeholder format, so its primitive readers are reused
 *   as-is.
 * - There is **no LEB128 varint** anywhere; lengths and counts are fixed 4-byte big-endian ints
 *   (`WireBuffer.writeInt`), unlike [RemoteComposeParser]'s varint-based framing.
 * - Every operation record starts with a single opcode byte (`Operations.<NAME>`), with **no**
 *   generic length prefix — the reader must know each opcode's exact shape.
 *
 * Once resolved into an [Opcode] list, rendering is identical to the placeholder path — this
 * parser feeds the exact same [com.example.remotecompose.engine.OpcodeExecutor] /
 * [com.example.remotecompose.ui.RemoteComposeCanvas], since those only depend on the abstract
 * [Opcode] model, not on which wire format produced it.
 */
object RealRemoteComposeParser {

    /** `Operations.HEADER` — document metadata, written in "flat" (non-map) form by this writer version. */
    private const val OP_HEADER = 0

    /** `Operations.DATA_TEXT` — a `(id, UTF-8 string)` entry in the document's text pool. */
    private const val OP_DATA_TEXT = 102

    /** `Operations.ROOT_CONTENT_DESCRIPTION` — a single int reference into the text pool. */
    private const val OP_ROOT_CONTENT_DESCRIPTION = 103

    /**
     * `Operations.TEXT_SUBTEXT` — `RemoteComposeWriter.textSubtext(srcTextId, start, len)` writes
     * `[textId:i32][srcId1:i32][start:f32(NaN-taggable)][len:f32(NaN-taggable, -1f means "rest of
     * the string")]` (source-confirmed via javap on the real `TextSubtext.read()`/`apply()`).
     * `textId` (unlike every other opcode's own leading id, always a *reference*) is a *newly*
     * allocated text-pool slot the real writer method itself returns for later callers to draw —
     * this op is what actually computes and registers that slot's real string content
     * (`srcId1`'s own pool entry, sliced `[start, start+len)`, or `[start, end)` when `len == -1`)
     * rather than just reserving it. Since [textPool] is a plain mutable map already written once
     * per [OP_DATA_TEXT] entry, this op writes into it exactly the same way — a real substring
     * effect (not just byte-consumed) for every later `DRAW_TEXT_ANCHORED`/etc. that references
     * `textId`, as long as `start`/`len` are literal (not `NaN`-tagged live variable references
     * this parser can't evaluate; the substring is simply skipped then, leaving `textId`
     * unresolved the same honest way an unresolved reference anywhere else in this parser is).
     */
    private const val OP_TEXT_SUBTEXT = 182

    /**
     * `Operations.TEXT_TRANSFORM` — `RemoteComposeWriter.textTransform(srcTextId, start, len,
     * operation)` writes `[textId:i32][srcId1:i32][start:f32(NaN-taggable)][len:f32(NaN-taggable,
     * -1f means "rest of the string")][operation:i32]` (source-confirmed via javap) — the exact
     * same "new text-pool slot computed from a real substring" shape [OP_TEXT_SUBTEXT] has, plus
     * one trailing `operation` applied to that substring afterward: `TEXT_TO_LOWERCASE`(`1`)/
     * `TEXT_TO_UPPERCASE`(`2`) (`String.lowercase()`/`.uppercase()`), `TEXT_TRIM`(`3`)
     * (`String.trim()`), `TEXT_CAPITALIZE`(`4`) (title-cases the first letter of *every* word,
     * leaving the rest of each word's own case untouched — not a full per-word lowercase-then-
     * capitalize), and `TEXT_UPPERCASE_FIRST_CHAR`(`5`) (title-cases only the first non-whitespace
     * character of the whole string) — both real `capitalizeWords()`/`capitalizeFirstWord()`
     * algorithms ported verbatim from the real `TextTransform.apply()`'s own bytecode, not
     * guessed (real `Character.toTitleCase()` approximated here as `uppercaseChar()` — identical
     * for ordinary Latin text, only differing for a handful of rare Unicode digraphs Kotlin's
     * stdlib has no direct titlecase mapping for). Real for the same reason [OP_TEXT_SUBTEXT] is:
     * as long as `start`/`len` are literal.
     */
    private const val OP_TEXT_TRANSFORM = 199

    /**
     * A paint-property bundle (observed opcode id 40; the real symbolic `Operations` name wasn't
     * confirmed against source, only its wire shape). Framed as
     * `[wordCount:i32][wordCount × i32]`, i.e. self-describing by word count rather than a fixed
     * shape — the one real opcode here that *is* generically skippable. Observed as
     * `(tag=4, argbColor)` for a single `setColor(...).commit()` call; this parser only extracts
     * the last word as a color, which holds for that single-property case.
     */
    private const val OP_PAINT_BUNDLE = 40

    /** `Operations.DRAW_RECT` — `[left,top,right,bottom]` as four raw floats, no length prefix. */
    private const val OP_DRAW_RECT = 42

    /** `Operations.DRAW_CIRCLE` — `[centerX,centerY,radius]` as three raw floats. */
    private const val OP_DRAW_CIRCLE = 46

    /** `Operations.DRAW_ROUND_RECT` — `[left,top,right,bottom,radiusX,radiusY]` as six raw floats. */
    private const val OP_DRAW_ROUND_RECT = 51

    /**
     * The real op `drawTextAnchored(text, x, y, panX, panY, flags)` writes: a `DATA_TEXT` entry
     * for the string, then this opcode (observed id 133) referencing it. Payload:
     * `[textId:i32][x:f32][y:f32][panX:f32][panY:f32][flags:i32]` — `x`/`y`/`panX` are each a
     * `readNanId`-tagged [floatPool] reference on the real `DrawTextAnchored` (javap-confirmed,
     * same as [OP_MODIFIER_PADDING]'s floats), now resolved through [resolveFloat]. `panX` gets a
     * real effect too (source-confirmed via javap on the real `DrawTextAnchored
     * .getHorizontalOffset()`): a `-1f..1f` fraction of the text's own measured width describing
     * which point of it `x` anchors, modeled by [Opcode.DrawText.panX] — see its own KDoc for the
     * formula (and the one real, honest simplification it makes: this renderer has no access to
     * the text's own left-side bearing, so that term is approximated as `0`). `panY`/`flags` are
     * still read to stay aligned with the stream but not modeled — `panY`'s real formula also
     * depends on the `ANCHOR_MONOSPACE_MEASURE`/`BASELINE_RELATIVE` flag bits and the text's own
     * ascent/descent, real font metrics this renderer can't confidently reproduce the way `panX`'s
     * simpler, purely width-based formula could be.
     */
    private const val OP_DRAW_TEXT_ANCHORED = 133

    /**
     * `Operations.DRAW_TEXT_RUN` — `RemoteComposeWriter.drawTextRun(text, start, end,
     * contextStart, contextEnd, x, y, rtl)` writes `[textId:i32][start:i32][end:i32]
     * [contextStart:i32][contextEnd:i32][x:f32][y:f32][rtl:byte]` (source-confirmed via javap:
     * `apply()`'s int/int/int/int/int/float/float/boolean parameter order matches the call's own
     * argument order exactly, unlike `DRAW_BITMAP_INT`/`LAYOUT_IMAGE`'s field-order surprises).
     * Unlike `DRAW_TEXT_ON_CIRCLE`, this op's real `DrawText.paint()` *is* implemented (delegates
     * to `PaintContext.drawTextRun(...)`) — real byte-coverage *and* a real semantic effect: only
     * the `[start, end)` substring of the referenced text-pool entry is drawn (modeled via
     * [Opcode.DrawText]'s `substringStart`/`substringEnd`, resolved at render time so the pool
     * entry itself stays shared rather than duplicated per opcode). `contextStart`/`contextEnd`
     * (a wider range used only for bidi/shaping context around the drawn substring) and `rtl` are
     * read to stay aligned but not modeled — this renderer has no bidi reordering to give them.
     */
    private const val OP_DRAW_TEXT_RUN = 43

    /**
     * `Operations.DRAW_TEXT_ON_CIRCLE` — `RemoteComposeWriter.drawTextOnCircle(textId, centerX,
     * centerY, radius, startAngle, warpRadiusOffset, alignment, placement)` writes
     * `[textId:i32][centerX:f32][centerY:f32][radius:f32][startAngleDegrees:f32]
     * [warpRadiusOffset:f32][alignment:byte][placement:byte]` (source-confirmed via javap on the
     * real `DrawTextOnCircle` operation class: `alignment`/`placement` are plain enum ordinals —
     * `Alignment` = `START`(0)/`CENTER`(1)/`END`(2), `Placement` = `OUTSIDE`(0)/`INSIDE`(1) — not
     * NaN-tagged variable references like the two enum fields' *values* would suggest from their
     * neighboring float fields' `readNanId()` reads elsewhere in that class's real `read()`).
     * There is no real curved-text algorithm to reverse-engineer here: the real
     * `DrawTextOnCircle.paint()` itself unconditionally throws
     * `UnsupportedOperationException("DrawTextOnCircle is not supported")` in this SDK version
     * (1.0.0-alpha18) — so this parser fully decodes the wire format (real byte-coverage) but
     * renders only a straight-line approximation reusing [Opcode.DrawText], anchored at the
     * circle position `startAngleDegrees` points to, ignoring `alignment`/`placement`/
     * `warpRadiusOffset` (which only affect how letters would bend along the arc).
     */
    private const val OP_DRAW_TEXT_ON_CIRCLE = 57

    /**
     * `Operations.DRAW_TEXT_ON_PATH` — `RemoteComposeWriter.drawTextOnPath(textId, pathId,
     * hOffset, vOffset)` writes `[textId:i32][pathId:i32][vOffset:f32][hOffset:f32]` — a
     * real-bytes hex-diff of `drawTextOnPath(textId, pathId, 111f, 222f)` decoded to floats
     * `(222.0, 111.0)` in that wire order: `DrawTextOnPath.apply()`'s own bytecode writes its 4th
     * argument (`vOffset`) *before* its 3rd (`hOffset`), the reverse of the call's own argument
     * order — another field-order surprise in the same family as `DRAW_BITMAP_INT`/
     * `LAYOUT_IMAGE`'s. Unlike `DRAW_TEXT_ON_CIRCLE`, the real `DrawText.paint()` *is* implemented
     * here (delegates to `PaintContext.drawTextOnPath(...)`) — but there's still no real
     * glyph-by-glyph path-following to reverse-engineer at this renderer's level, so this parser
     * fully decodes the wire format (real byte-coverage) but renders only a straight-line
     * approximation reusing [Opcode.DrawText], anchored at the referenced path's own first point
     * (its leading `MoveTo`) shifted by `(hOffset, vOffset)`.
     */
    private const val OP_DRAW_TEXT_ON_PATH = 53

    /** `Operations.DRAW_LINE` — `[x1,y1,x2,y2]` as four raw floats. */
    private const val OP_DRAW_LINE = 47

    /** `Operations.DRAW_OVAL` — `[left,top,right,bottom]` as four raw floats (bounds of the ellipse). */
    private const val OP_DRAW_OVAL = 56

    /**
     * `Operations.DRAW_ARC` — `[left,top,right,bottom,startAngle,sweepAngle]` as six raw floats,
     * an open arc (no line back to center — `Opcode.DrawArc.useCenter = false`).
     */
    private const val OP_DRAW_ARC = 152

    /**
     * `Operations.DRAW_SECTOR` — same six-float shape as [OP_DRAW_ARC], but a closed pie slice
     * (`Opcode.DrawArc.useCenter = true`). The real format distinguishes arc-vs-sector by opcode
     * id, not by a flag in the payload.
     */
    private const val OP_DRAW_SECTOR = 52

    /**
     * `Operations.DATA_PATH` — defines a reusable path resource: `[pathId:i32][floatCount:i32]`
     * followed by `floatCount` raw i32 words forming `RemotePathBase`'s flat, NaN-tagged command
     * array (see `androidx.compose.remote.core.RemotePathBase` — [Utils.asNan]-style sentinels,
     * not plain floats, mark where each command starts).
     *
     * Confirmed against real output from `moveTo`/`lineTo`/`close()`, then separately
     * `moveTo`/`quadTo`/`cubicTo`/`close()`, paths: the encoder has a documented bug
     * (`RemotePathBase.add(int,float,float)`: "THIS IS FLAW in the encoding TODO FIX ON
     * VERSIONING") that advances the write cursor 2 slots too many before writing a command's
     * real coordinates, leaving 2 zeroed/garbage floats between a command's tag and its actual
     * arguments for every non-[PATH_CMD_MOVE]/[PATH_CMD_CLOSE] command — this reader has to
     * reproduce that exact padding to stay aligned, not just skip it as a curiosity. Per-command
     * stride (tag + padding + real args) is verified for [PATH_CMD_MOVE]/[PATH_CMD_LINE]/
     * [PATH_CMD_QUADRATIC]/[PATH_CMD_CUBIC]/[PATH_CMD_CLOSE]; only [PATH_CMD_CONIC] remains
     * unverified — its 2-word padding is the same bug applied to a same-shaped `add()` overload,
     * but that specific overload hasn't been exercised against real bytes, so it's still a hard
     * parse failure here rather than an unverified extrapolation.
     */
    private const val OP_DATA_PATH = 123

    /** `Operations.DRAW_PATH` — `[pathId:i32]`, referencing a [OP_DATA_PATH] resource. */
    private const val OP_DRAW_PATH = 124

    /**
     * `Operations.CLIP_PATH` — `RemoteComposeBuffer.addClipPath(pathId)` writes just `[pathId:i32]`
     * (5 bytes total, confirmed against real output: nothing but the opcode byte and one int
     * before the next opcode) — the real op's `ClipPath.apply(WireBuffer, Int)` only ever takes
     * the id; a region-op (replace/intersect/union/etc, packed into the *read*-side int's high
     * byte) is never written by this simple `addClipPath(int)` call path, so it always decodes as
     * 0/REPLACE here — modeled as an intersect via the same [Opcode.ClipPath] the executor already
     * implements (Compose's own `DrawTransform.clipPath` has no separate replace mode to honor
     * anyway). References an id already registered by a prior [OP_DATA_PATH].
     */
    private const val OP_CLIP_PATH = 38

    /**
     * `Operations.PATH_CREATE` — `RemoteComposeWriter.pathCreate(startX, startY)` writes
     * `[pathId:i32][startX:f32(NaN-taggable)][startY:f32(NaN-taggable)]` (source-confirmed via
     * javap on the real `PathCreate.read()`) — unlike [OP_DATA_PATH]'s single self-contained
     * array, this starts a path *incrementally*: just the initial `MoveTo`, with every further
     * segment appended by a later [OP_PATH_ADD] referencing this same `pathId`. Real byte-coverage
     * *and* a real semantic effect together with [OP_PATH_ADD] — see its own KDoc.
     */
    private const val OP_PATH_CREATE = 159

    /**
     * `Operations.PATH_ADD` — `RemoteComposeWriter.pathAppend(pathId, vararg floats)` (or its
     * `pathAppendLineTo`/`QuadTo`/`CubicTo`/`MoveTo`/`Close` convenience wrappers) writes
     * `[pathId:i32][floatCount:i32]` followed by `floatCount` raw i32 words — the exact same
     * `RemotePathBase` flat NaN-tagged command array [OP_DATA_PATH]'s own [decodePathArray]
     * already decodes (same padding bug, same tag set), confirmed identical via javap on the real
     * `pathAppendLineTo`/`QuadTo`/`CubicTo`/`MoveTo`/`Close` convenience methods' own bytecode
     * (each builds exactly the array shape [decodePathArray] expects). Real semantic effect: this
     * op *appends* the decoded commands to [OP_PATH_CREATE]'s already-started path (rather than
     * replacing it, the way [OP_DATA_PATH] does for a brand new one) — real support for the
     * *incremental*, multi-opcode path-building protocol real `pathCreate()`/`pathAppend*()...`/
     * (no explicit close call) leaves as a genuinely separate wire shape from [OP_DATA_PATH]'s own
     * single-opcode array, even though both ultimately populate the same [pathPool] any
     * [OP_DRAW_PATH]/[OP_CLIP_PATH] can reference by id.
     */
    private const val OP_PATH_ADD = 160

    /**
     * `Operations.PATH_TWEEN` — `RemoteComposeWriter.pathTween(pathId1, pathId2, tween)` writes
     * `[outId:i32][pathId1:i32][pathId2:i32][tween:f32(NaN-taggable)]` (source-confirmed via javap
     * on the real `PathTween.read()`/`write()`) — `outId` (like [OP_TEXT_SUBTEXT]/
     * [OP_TEXT_TRANSFORM]'s own leading id) is a *newly* allocated [pathPool] slot the real writer
     * method itself returns. Real `paint()` just delegates to an abstract
     * `PaintContext.tweenPath(outId, pathId1, pathId2, tween)` with no further algorithm to
     * decompile — matching real `android.graphics.Path.interpolate()`'s own well-documented
     * contract (source: Android's own Path API docs, not this SDK), this parser reproduces that
     * exact semantic instead: both paths must have the *identical* command sequence (same count,
     * same `MoveTo`/`LineTo`/`QuadraticTo`/`CubicTo`/`Close` kind at every index — real
     * `Path.canInterpolate()`'s own requirement), linearly interpolating every coordinate pair by
     * `tween` (`0f` = `pathId1`, `1f` = `pathId2`); a structural mismatch leaves `outId`
     * unresolved, the same honest fallback every other unresolvable reference in this parser
     * already gets, rather than guessing at a mismatched-shape blend real Android itself refuses
     * to attempt either.
     */
    private const val OP_PATH_TWEEN = 158

    /**
     * `Operations.MATRIX_FROM_PATH` — `RemoteComposeWriter.matrixFromPath(pathId, fraction,
     * vOffset, flags)` writes `[pathId:i32][fraction:f32(NaN-taggable)][vOffset:f32(NaN-taggable)]
     * [flags:i32]` (source-confirmed via javap on the real `MatrixFromPath.read()`/`write()`).
     * Real `paint()` delegates to an abstract `PaintContext.matrixFromPath(...)` with no further
     * algorithm in this SDK to decompile, but the real semantic (matching Android's own
     * well-documented `PathMeasure.getPosTan()`) is unambiguous: `fraction` (`0f..1f`) is a
     * position along the path's own total arc length, `vOffset` a perpendicular offset from it,
     * and `flags` (`POSITION_MATRIX_FLAG=1`/`TANGENT_MATRIX_FLAG=2`) select whether the resulting
     * transform includes the position and/or a rotation matching the path's own tangent direction
     * there. Like [OP_CLIP_PATH], this op has no children/container shape of its own — the real
     * transform just persists as an ambient effect on whatever draws after it until this frame's
     * own [OP_CONTAINER_END] undoes it (the same `attachToTopScope`-queued [Opcode.MatrixRestore]
     * mechanism [OP_MODIFIER_OFFSET] already established). Arc length/position/tangent are
     * computed here via [pointAndTangentAlongPath] by flattening `QuadraticTo`/`CubicTo` segments
     * into short line samples (a standard, real curve-length technique — not a guess at the real
     * SDK's own specific subdivision granularity, which isn't decompilable from an abstract call).
     */
    private const val OP_MATRIX_FROM_PATH = 181

    /**
     * `Operations.DRAW_TWEEN_PATH` — `RemoteComposeWriter.drawTweenPath(path1Id, path2Id, tween,
     * start, stop)` writes `[path1Id:i32][path2Id:i32][tween:f32(NaN-taggable)]
     * [start:f32(NaN-taggable)][stop:f32(NaN-taggable)]` (source-confirmed via javap on the real
     * `DrawTweenPath.read()`/`write()`) — the same real per-coordinate lerp [OP_PATH_TWEEN] already
     * gives via [lerpPath], drawn directly in one opcode instead of registering a new [pathPool]
     * entry first. `start`/`stop` real-trim to only that `[start, stop)` fraction of the tweened
     * path's own total arc length (matching Android's own well-documented
     * `PathMeasure.getSegment()`) via [trimPath] — the same curve-flattening technique
     * [OP_MATRIX_FROM_PATH] already established, since a trimmed *portion* of a curve isn't
     * expressible as a shorter `QuadraticTo`/`CubicTo` without real curve-splitting math. A
     * structural tween mismatch (see [lerpPath]'s own gate) draws nothing, the same honest
     * fallback every other unresolvable reference in this parser already gets.
     */
    private const val OP_DRAW_TWEEN_PATH = 125

    /**
     * `Operations.PATH_COMBINE` — `RemoteComposeWriter.pathCombine(pathId1, pathId2, operation)`
     * writes `[outId:i32][pathId1:i32][pathId2:i32][operation:i8]` (source-confirmed via javap on
     * the real `PathCombine.read()`/`write()` — `operation` is a single byte, unlike every other
     * id/enum field in this format, confirmed by the real `readByte()` call, not assumed) —
     * `OP_DIFFERENCE=0`/`OP_INTERSECT=1`/`OP_REVERSE_DIFFERENCE=2`/`OP_UNION=3`/`OP_XOR=4`. Real
     * `paint()` delegates to an abstract `PaintContext.combinePath(...)` with no algorithm in this
     * SDK to decompile; general polygon boolean ops (union/difference/xor of arbitrary, possibly
     * concave, possibly curved paths) need real computational-geometry machinery well beyond this
     * parser's own scope. Only `OP_INTERSECT` gets a real effect here, via the textbook
     * Sutherland-Hodgman polygon-clipping algorithm [sutherlandHodgmanIntersect] (correct for any
     * simple subject polygon clipped against a *convex* clip polygon — real, not a guess, but a
     * scoped-down real algorithm rather than a full general-path intersection) — every other
     * operation, and any `OP_INTERSECT` whose clip path isn't convex, honestly leaves the result
     * unresolved rather than guessing at a general boolean-op result this algorithm can't
     * correctly produce.
     */
    private const val OP_PATH_COMBINE = 175

    /**
     * `Operations.CANVAS_OPERATIONS` — `startCanvasOperations()`/`endCanvasOperations()` write no
     * fields at all (source-confirmed via javap on the real `CanvasOperations.read()`/`apply()` —
     * `apply()` writes only the opcode header), just this container's own child opcodes directly
     * (no `LAYOUT_CONTENT` marker — real `CanvasOperations` isn't a `Component`/`LayoutManager`,
     * just a plain `Container`, the same shape [OP_LAYOUT_ROOT] already has), closed by a single
     * [OP_CONTAINER_END] (`endCanvasOperations()` calls `addContainerEnd()` directly, confirmed via
     * javap). Real `paint()` (source-confirmed via javap) just iterates and applies its own
     * children in order — a genuine pass-through content container, so this parser's own generic
     * scope mechanism already renders its children for real with no special handling needed at
     * all, the same as any other plain container here.
     */
    private const val OP_CANVAS_OPERATIONS = 173

    /**
     * `Operations.SKIP` — `RemoteComposeWriter.beginSkip(conditionType, value)`/`endSkip(token)`
     * writes `[conditionType:i32(as a short)][value:i32][skipLength:i32]` (source-confirmed via
     * javap on the real `Skip.read()`) followed by `skipLength` raw bytes of whatever content the
     * writer wrapped — a forward-compatibility mechanism letting a document include content only
     * some client versions understand: real `read()` itself (not `paint()`/`apply()` — this is a
     * *parse-time* wire-level jump, confirmed via javap on `WireBuffer.setIndex(getIndex() +
     * skipLength)` running inline inside `read()`) advances the reader past that whole span
     * unparsed whenever `needsToSkip()` (checked against this *client's* own reported library API
     * level/profile) is true, so a parser that doesn't understand what's inside never even
     * attempts to decode it. `SKIP_IF_API_LESS_THAN(1)`/`GREATER_THAN(2)`/`EQUAL_TO(3)`/
     * `NOT_EQUAL_TO(4)` are given a real, principled effect here: this parser reports its own
     * library API level as `Int.MAX_VALUE` (an honest "assume the newest, most capable client"
     * stance — the real numeric level a genuine `alpha18` client reports isn't recoverable from
     * this SDK's own compiled classes, so a document-specific exact threshold can't be matched,
     * but greater-than/less-than-style compatibility gating — the mechanism's own actual purpose —
     * still resolves correctly under this assumption). `SKIP_IF_PROFILE_INCLUDES(5)`/
     * `EXCLUDES(6)` have no such principled default (this parser has no real "profile" concept at
     * all) and are treated as "never skip" — the same safe, inclusive default every other
     * unresolvable condition in this parser already gets.
     */
    private const val OP_SKIP = 241

    /**
     * `Operations.REM` — `RemoteComposeWriter.rem(text)` writes `[length:i32][UTF8 bytes...]`
     * (source-confirmed via javap on the real `Rem.read()`/`write()` — `WireBuffer.writeUTF8()`/
     * `readUTF8(maxLength)` delegate to the shared `writeBuffer`/`readBuffer` self-length-prefixed
     * blob helper, the same length-then-bytes shape [OP_DATA_TEXT] already uses, just without a
     * leading pool id since a `REM` is never referenced elsewhere). Real `Rem` has no
     * `paint()`/`apply()` at all (not even a `PaintOperation`) — a plain author-facing source
     * comment, honestly byte-consumed only, the same no-visual-effect category
     * [OP_DEBUG_MESSAGE] already established.
     */
    private const val OP_REM = 185

    /**
     * `Operations.TEXT_LENGTH` — `RemoteComposeWriter.textLength(textId): Float` writes
     * `[lengthId:i32][textId:i32]` (source-confirmed via javap on the real `TextLength.read()`/
     * `write()`) — `lengthId` (like [OP_TEXT_SUBTEXT]/[OP_TEXT_TRANSFORM]/[OP_PATH_TWEEN]'s own
     * leading id) is a *newly* allocated slot, but unlike those, the real writer method itself
     * *returns* a NaN-tagged float directly referencing it (`Utils.asNan(lengthId)`, confirmed via
     * javap), meant to be passed straight into another float field elsewhere (a `width()`, an
     * `x`/`y`, anything [resolveFloat] resolves) rather than drawn on its own. Real
     * `apply(RemoteContext)` (source-confirmed via javap) computes `textId`'s own pool string's
     * real `.length` and `loadFloat(lengthId, length)`s it — the exact same [floatPool] map
     * [resolveFloat] already reads from, so this needs no new resolution machinery at all: once
     * this op populates `floatPool[lengthId]`, every other already-real NaN-tagged field
     * anywhere in this parser automatically picks up the real computed length wherever a document
     * references it.
     */
    private const val OP_TEXT_LENGTH = 156

    /**
     * `Operations.ID_LIST` — `RemoteComposeWriter.addList(intArray): Float` writes
     * `[id:declareId][count:i32][count × i32]` (source-confirmed via javap on the real
     * `DataListIds.read()`/`write()`) — a plain static list of ids (usually other pool
     * references, e.g. text-pool ids), the real writer method itself returning a NaN-tagged float
     * referencing it. Real [OP_TEXT_LOOKUP] is the one real consumer this parser gives an actual
     * effect to.
     */
    private const val OP_ID_LIST = 146

    /**
     * `Operations.TEXT_LOOKUP` — `RemoteComposeWriter.textLookup(dataSet, index): Int` writes
     * `[textId:declareId][dataSetId:readId][index:f32(NaN-taggable)]` (source-confirmed via javap
     * on the real `TextLookup.read()`/`write()`) — `textId` (like every other "new pool slot"
     * opcode this session) is a *newly* allocated text-pool slot the real writer method returns.
     * Real `apply()` (source-confirmed via javap) resolves `getCollectionsAccess().getId(dataSetId,
     * index)` — the id at `index` within the [OP_ID_LIST] collection `dataSetId` references — then
     * `getText()`s *that* id and registers the result at `textId`: a real indexed text-lookup,
     * implemented here as `idListPool[dataSetId]?.getOrNull(index)` then a [textPool] lookup, both
     * pools this parser already maintains. Real only when `index` is literal (not a `NaN`-tagged
     * live variable reference this parser can't evaluate); left unresolved otherwise, the same
     * honest fallback every other unresolvable reference in this parser already gets.
     */
    private const val OP_TEXT_LOOKUP = 151

    /**
     * `Operations.TEXT_LOOKUP_INT` — `RemoteComposeWriter.textLookup(dataSet, indexRefId): Int`
     * writes `[textId:declareId][dataSetId:readId][index:readId]` (source-confirmed via javap on
     * the real `TextLookupInt.read()`/`write()`) — the int-indexed sibling of [OP_TEXT_LOOKUP]:
     * unlike its NaN-taggable float `index`, this one's `index` is *always* an [intPool] reference
     * (real `updateVariables()` unconditionally calls `RemoteContext.getInteger(mIndex)`, no
     * literal-vs-reference branch the way the float variant's `Float.isNaN()` check has), so a
     * real effect here needs a real [OP_DATA_INT] entry already registered at that id — the same
     * `idListPool`/[textPool] lookup [OP_TEXT_LOOKUP] already performs, just with the index itself
     * coming from [intPool] instead of a literal.
     */
    private const val OP_TEXT_LOOKUP_INT = 153

    /**
     * `Operations.TEXT_MERGE` — `RemoteComposeWriter.textMerge(srcId1, srcId2): Int` writes
     * `[textId:declareId][srcId1:readId][srcId2:readId]` (source-confirmed via javap on the real
     * `TextMerge.read()`/`write()`) — `textId` (like every other "new pool slot" opcode this
     * session) is a *newly* allocated text-pool slot the real writer method returns. Real
     * `apply()` (source-confirmed via javap) does `getText(srcId1) + getText(srcId2)` (plain
     * string concatenation, no separator) and `loadText(textId, merged)`s the result — implemented
     * here as a direct [textPool] concatenation, the same pool [OP_TEXT_LOOKUP]/[OP_TEXT_LENGTH]
     * already read and write.
     */
    private const val OP_TEXT_MERGE = 136

    /**
     * `Operations.COLOR_EXPRESSIONS` — `RemoteComposeWriter.addColorExpression(...): Short`, 7
     * overloads sharing one wire shape (source-confirmed via javap on the real
     * `ColorExpression.read()`/`write()`/`apply()`): `[id:declareId][modeAlpha:i32][word1:i32]
     * [word2:i32][word3:i32]`, where `mode = modeAlpha and 0xFF` and `alpha = (modeAlpha ushr 16)
     * and 0xFF`. Real only for the two modes this parser can fully resolve: mode `0`
     * (`COLOR_COLOR_INTERPOLATE`, `addColorExpression(Int, Int, Float)`) — `word1`/`word2` are
     * literal ARGB ints, `word3` a NaN-taggable tween, real `Utils.interpolateColor()` doing a
     * gamma-2.2-corrected per-channel lerp (not naive linear RGB) — and mode `4` (`HSV_MODE`,
     * `addColorExpression(Float, Float, Float)`) — `word1`/`word2`/`word3` are float-bit-encoded
     * hue/sat/value (hue as a `0f..1f` wheel fraction, not degrees), real `Utils.hsvToRgb()`
     * doing the standard hexagon conversion, alpha always `255` since this writer overload has no
     * alpha parameter. Modes `1`-`3` (`ID_COLOR`/`COLOR_ID`/`ID_ID_INTERPOLATE`, where `word1`
     * and/or `word2` are [colorPool] id references instead of literal ARGB — real `apply()`
     * resolves them via `RemoteContext.getColor(id)`) are implemented too, since they reuse the
     * exact same [colorPool] this parser already maintains. Modes `5`/`6` (`ARGB_MODE`/
     * `IDARGB_MODE`, direct float-channel colors with a NaN-taggable alpha) are left unresolved —
     * same honest fallback every other unresolvable case in this parser already gets. Result
     * stored in [colorPool], the same pool [OP_MODIFIER_BORDER]'s `colorRefFlag == 2` path (via
     * `RecordingModifier.dynamicBorder(width, radius, colorId: Short, shapeType)`) already
     * consumes — giving this a real, visually verifiable effect: a border rendered in the
     * computed color.
     */
    private const val OP_COLOR_EXPRESSIONS = 134

    /**
     * `Operations.ID_LOOKUP` — `RemoteComposeWriter.idLookup(dataSet, index): Int` writes
     * `[intId:readId][dataSetId:readId][index:f32(NaN-taggable)]` (source-confirmed via javap on
     * the real `IdLookup.read()`/`write()`/`apply()`) — despite its field being misleadingly named
     * `mTextId`, real `apply()` does `getCollectionsAccess().getId(dataSetId, index)` (the id at
     * `index` within the [OP_ID_LIST] collection `dataSetId` references, the exact same
     * `idListPool[dataSetId]?.getOrNull(index)` [OP_TEXT_LOOKUP] already performs) then
     * `loadInteger(intId, thatId)` — a plain *integer* pool store via [intPool], not [textPool].
     * Real only when `index` is literal (not a `NaN`-tagged live variable reference this parser
     * can't evaluate). This parser has no consumer that treats an arbitrary retrieved id as
     * anything meaningful on its own, so its real effect is only observable by chaining into
     * [OP_TEXT_LOOKUP_INT]'s own [intPool]-sourced `index` — retrieving a literal index value from
     * an `ID_LIST` via `ID_LOOKUP`, then feeding that same `intPool` slot into `TEXT_LOOKUP_INT` as
     * its index, the same "real effect only provable by feeding it into an existing real consumer"
     * pattern [OP_DATA_INT] itself already relies on.
     */
    private const val OP_ID_LOOKUP = 192

    /**
     * `Operations.INTEGER_EXPRESSION` — `RemoteComposeWriter.integerExpression(vararg Long): Long`
     * writes `[id:declareId][mask:i32][count:i32][count × i32]` (source-confirmed via javap on the
     * real `IntegerExpression.read()`/`write()`) — a real RPN integer calculator over
     * `IntegerExpressionEvaluator`'s ~24 operators (source-confirmed via javap on
     * `IntegerExpressionEvaluator.opEval()`), each array slot either a literal int, an [intPool]
     * id reference (`mask` bit set *and* value `< 65536`, real `isId()`'s own exact condition — a
     * writer-level "long NaN-tag" value `>= 4294967296`), or an operator code (`mask` bit set *and*
     * value `>= 65536`, `Rc.IntegerExpression.L_*` constants like `L_ADD = 4295032833`, i.e.
     * `4294967296 + 65537`). Real `apply()`'s evaluator is a simple stack machine: literal/
     * resolved-id slots push, operator slots pop N operands (2 for the binary ops, 1 for the
     * unary ops, 3 for `CLAMP`/`IFELSE`/`MAD`) and push 1 result — implemented here faithfully
     * for every operator except `VAR1`/`VAR2`/`VAR3` (`I_VAR1`/`I_VAR2`/an implied third), which
     * only have real meaning inside a loop/foreach evaluation context (fed via `eval()`'s own
     * `vars` parameter) this parser doesn't implement — left unresolved, the same honest fallback
     * every other unresolvable case here already gets. Real `apply()` stores the final stack top
     * into [intPool] via `loadInteger()` — reusing the exact same pool [OP_ID_LOOKUP]/
     * [OP_TEXT_LOOKUP_INT] already read and write.
     */
    private const val OP_INTEGER_EXPRESSION = 144

    /**
     * `Operations.TEXT_FROM_FLOAT` — `RemoteComposeWriter.createTextFromFloat(value, digitsBefore,
     * digitsAfter, flags): Int` writes `[textId:declareId][value:f32(NaN-taggable)]
     * [(digitsBefore:u16 shl 16) or digitsAfter:u16][flags:i32]` (source-confirmed via javap on
     * the real `TextFromFloat.read()`/`write()`/`apply()`). Real `apply()` branches three ways on
     * `flags`: `FULL_FORMAT` (`0x1000`) does a plain `Float.toString(value)`; `LEGACY_MODE`
     * (`0x400`) and the default path both call increasingly involved `StringUtils.floatToString()`
     * overloads with padding/grouping/separator/sign options this parser doesn't replicate. Real
     * only for the `FULL_FORMAT` path — implemented as a direct Kotlin `Float.toString()`, which
     * targets the same shortest-round-trip algorithm family as the real JVM one closely enough for
     * plain values; the default/legacy padded-and-grouped formats are left unresolved, the same
     * honest fallback every other unresolvable case in this parser already gets.
     */
    private const val OP_TEXT_FROM_FLOAT = 135

    // RemotePathBase command tags (source-confirmed values), NaN-encoded via Utils.asNan(tag) —
    // i.e. an IEEE-754 float bit pattern with sign=1, exponent=0xFF, mantissa=tag.
    private const val PATH_CMD_MOVE = 10
    private const val PATH_CMD_LINE = 11
    private const val PATH_CMD_QUADRATIC = 12
    private const val PATH_CMD_CONIC = 13
    private const val PATH_CMD_CUBIC = 14
    private const val PATH_CMD_CLOSE = 15

    /** Mask isolating sign+exponent; a path-array word is a command tag iff these bits are all set. */
    private const val NAN_TAG_MASK = -0x800000 // 0xFF800000 as a 32-bit Int

    /**
     * `Operations.DATA_BITMAP` — defines a reusable image resource:
     * `[bitmapId:i32][width:i32][height:i32][pngByteLength:i32][pngBytes...]`. The image bytes
     * are a real, directly-decodable encoded image (confirmed via magic bytes `\x89PNG\r\n\x1a\n`)
     * — not a raw pixel dump — so they can be handed straight to [BitmapPool] unchanged, the same
     * as [RemoteComposeParser]'s placeholder bitmap pool.
     */
    private const val OP_DATA_BITMAP = 101

    /**
     * `Operations.DRAW_BITMAP` — `[bitmapId:i32][left:f32][top:f32][right:f32][bottom:f32]
     * [contentDescriptionTextId:i32]`. The trailing text-pool reference (the `drawBitmap(...,
     * contentDescription)` string) is read to stay aligned but not used, the same as
     * [OP_ROOT_CONTENT_DESCRIPTION]'s.
     */
    private const val OP_DRAW_BITMAP = 44

    /**
     * `Operations.DRAW_BITMAP_INT` — `RemoteComposeWriter.drawBitmap(bitmap, width, height, desc)`
     * writes `[bitmapId:i32][srcLeft:i32][srcTop:i32][srcRight:i32][srcBottom:i32][dstLeft:i32]
     * [dstTop:i32][dstRight:i32][dstBottom:i32][contentDescriptionTextId:i32]` — 10 raw ints,
     * the same trailing content-description reference [OP_DRAW_BITMAP] has, read to stay aligned
     * but not used. (An earlier pass over this opcode concluded `mContentDescId` was never
     * serialized, based on miscounting a 9-int decode of `drawBitmap(image, 40, 30, "desc")"` — a
     * real-bytes hex-diff against `writer.getBuffer().drawBitmap(id, 0, 0, 0, 0, 1, 1, 150, 2,
     * 190, 42, 0)` (real source-rect cropping, via the only public call path that exposes it)
     * showed a 10th trailing zero consumed by the *next* opcode's header when left unread, proving
     * the field is in fact always written.) That convenience `drawBitmap(bitmap, width, height,
     * desc)` overload always sets src == dst, using the given width/height for both — real
     * source-rect cropping exists in the wire format even though that overload doesn't exercise
     * it, so [Opcode.DrawBitmap]'s src fields are modeled generally.
     */
    private const val OP_DRAW_BITMAP_INT = 66

    /**
     * `Operations.DRAW_BITMAP_SCALED` — writes `[imageId:i32][srcLeft:f32(NaN-taggable)]
     * [srcTop:f32(NaN-taggable)][srcRight:f32(NaN-taggable)][srcBottom:f32(NaN-taggable)]
     * [dstLeft:f32(NaN-taggable)][dstTop:f32(NaN-taggable)][dstRight:f32(NaN-taggable)]
     * [dstBottom:f32(NaN-taggable)][scaleType:i32][scaleFactor:f32(NaN-taggable)]
     * [contentDescriptionId:i32]` (source-confirmed via javap on the real
     * `DrawBitmapScaled.read()`/`paint()` — its own `documentation()` call independently confirms
     * every field name/order too). Unlike [OP_LAYOUT_IMAGE] (which always samples a bitmap's own
     * *natural* PNG size), this op declares an explicit *source* sub-rect to sample — real
     * `ImageScaling.setup()`'s scaling math (already ported once for [OP_LAYOUT_IMAGE] as
     * [imageScaleDstRect]) is identical in shape, just fed this source rect's own declared
     * width/height instead of a bitmap's intrinsic size, so that same helper is reused verbatim
     * here for `SCALE_NONE`(`0`)/`SCALE_INSIDE`(`1`)/`SCALE_FIT`(`4`)/`SCALE_CROP`(`5`); real
     * `paint()` *always* clips to the declared destination rect regardless of scale type (unlike
     * [OP_LAYOUT_IMAGE]'s conditional clip), so this always wraps its `Opcode.DrawBitmap` in a
     * `ClipRect`. `SCALE_FILL_WIDTH`(`2`)/`SCALE_FILL_HEIGHT`(`3`)/`SCALE_FIXED_SCALE`(`7`, an
     * explicit `scaleFactor` zoom this parser doesn't model) fall back to the same stretch-to-fill
     * behavior every other unhandled scale type already falls back to elsewhere in this codebase.
     */
    private const val OP_DRAW_BITMAP_SCALED = 149

    /**
     * `Operations.CLICK_AREA` — `addClickArea(actionId, contentDescription, left, top, right,
     * bottom, metadata)` writes `[actionId:i32][contentDescriptionTextId:i32][left:f32][top:f32]
     * [right:f32][bottom:f32][metadataTextId:i32]`. `metadata` is exactly the target-URL string
     * [Opcode.ActionClick.targetUrlStringIndex] expects; `contentDescription` is read to stay
     * aligned but not modeled, same pattern as [OP_DRAW_BITMAP]'s.
     */
    private const val OP_CLICK_AREA = 64

    /**
     * `Operations.LAYOUT_COLUMN` — `startColumn(modifier, horizontalPositioning,
     * verticalPositioning)` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]`.
     *
     * Most layout container opcodes ([OP_LAYOUT_BOX]/[OP_LAYOUT_FLOW]/etc, [OP_LAYOUT_CONTENT]/
     * [OP_CONTAINER_END]) are still treated as **pass-through scope markers**: exactly the right
     * bytes are consumed to stay aligned, but no repositioning happens — correct only for a
     * document whose children already carry final, non-overlapping absolute coordinates.
     * [OP_LAYOUT_COLUMN] and [OP_LAYOUT_ROW] are the exception: this renderer still has no real
     * measure pass (nothing here computes a child's size before it's drawn), but once a child's
     * own content has been parsed, its *drawn* extent is known after the fact — enough to really
     * stack children in document order along the container's axis, `spacedBy` apart, rather than
     * trusting the document's own absolute placement for them. See `arrangeChildren` (in
     * [RealRemoteComposeParser.parse]) for the mechanism and its honest limits (no real alignment
     * modes, no reserved-but-empty space, first child's own position is the anchor).
     */
    private const val OP_LAYOUT_COLUMN = 204

    /** `Operations.LAYOUT_ROW` — identical shape to [OP_LAYOUT_COLUMN]'s (source-confirmed: same `apply()` structure). */
    private const val OP_LAYOUT_ROW = 203

    /**
     * `Operations.LAYOUT_COLLAPSIBLE_COLUMN`/`LAYOUT_COLLAPSIBLE_ROW` — `startCollapsibleColumn`/
     * `startCollapsibleRow` write the exact same `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]` shape as
     * [OP_LAYOUT_COLUMN]/[OP_LAYOUT_ROW] (confirmed byte-for-byte against real output), just under
     * different opcode numbers, and are closed the same way (a `LAYOUT_CONTENT` children marker,
     * then two [OP_CONTAINER_END]s).
     */
    private const val OP_LAYOUT_COLLAPSIBLE_COLUMN = 233
    private const val OP_LAYOUT_COLLAPSIBLE_ROW = 230

    /**
     * `Operations.LAYOUT_FLOW` — `startFlow` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][spacedBy:f32]
     * [maxItemsInMainAxis:i32][maxLinesInCrossAxis:i32]` — [OP_LAYOUT_COLUMN]'s shape plus two
     * trailing ints, confirmed via real output (`maxItemsInMainAxis`/`maxLinesInCrossAxis` default
     * to `Int.MAX_VALUE` when unset). Closed the same way as the other layout containers (a
     * `LAYOUT_CONTENT` children marker, then two [OP_CONTAINER_END]s). Gets real wrapping (not
     * just [OP_LAYOUT_ROW]'s single-line arrangement): the real `FlowLayout` class extends
     * `RowLayout` (source-confirmed via javap), so the main axis is always horizontal, wrapping to
     * a new line after `maxItemsInMainAxis` children — see `arrangeChildren`'s
     * `flowMaxItemsPerLine` handling. `maxLinesInCrossAxis` also gets a real effect: javap on the
     * real `FlowLayout`'s own measure logic shows it marks a child `Component.Visibility.GONE`
     * (no space reserved, the same real effect [OP_MODIFIER_VISIBILITY] already gives that value)
     * the moment its row index would reach `maxLinesInCrossAxis`, instead of adding another row —
     * see `arrangeChildren`'s `flowMaxLines` handling.
     */
    private const val OP_LAYOUT_FLOW = 240

    /**
     * `Operations.LAYOUT_FIT_BOX` — `startFitBox` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32]`, the exact same 4-int shape as
     * [OP_LAYOUT_BOX] (no `spacedBy`, confirmed against real output), closed the same way as the
     * other layout containers.
     */
    private const val OP_LAYOUT_FIT_BOX = 176

    /**
     * `Operations.LAYOUT_ROOT` — `startRoot()`/`endRoot()` write only `[componentId:i32]` (no
     * `LAYOUT_CONTENT` children marker, unlike every other container here — children follow
     * directly), closed by a single [OP_CONTAINER_END] (not two, confirmed against real output —
     * `endRoot()` calls `addContainerEnd()` exactly once).
     */
    private const val OP_LAYOUT_ROOT = 200

    /**
     * `Operations.LOOP_START` — writes `[indexVariableId:i32][from:f32(NaN-taggable)]
     * [step:f32(NaN-taggable)][until:f32(NaN-taggable)]` (source-confirmed via javap on the real
     * `LoopOperation.read()`/`apply()`), then this loop's own body opcodes directly (no
     * `LAYOUT_CONTENT` marker — real `LoopOperation` isn't a `Component`/`LayoutManager` at all,
     * just a plain `Container`, closed by a single [OP_CONTAINER_END] the same way [OP_LAYOUT_ROOT]
     * is). Real `paint()` (source-confirmed via javap) re-`apply()`s this one authored copy of the
     * body in a live `for (i = from; i < until; i += step)` loop, writing `i` into
     * `indexVariableId` each iteration for the body's own expressions to read — a live interaction
     * this parser has no expression evaluator for, so `indexVariableId` is read only to stay
     * aligned. When `from`/`step`/`until` are all literal (not `NaN`-tagged variable references)
     * and describe a real, non-empty range, though, the iteration *count* itself needs no live
     * expression evaluation at all — just arithmetic — so this frame's own [OP_CONTAINER_END]
     * literally clones this loop's one authored body that many times instead of leaving it
     * rendered exactly once, a real "unrolled loop" structural effect (not just newly byte-
     * consumed) whenever an enclosing [OP_LAYOUT_COLUMN]/[OP_LAYOUT_ROW]'s sequential packing
     * then arranges each clone as its own independent sibling. Any `NaN`-tagged bound falls back
     * to rendering the body exactly once — the same honest "real effect only when statically
     * known" gate every other no-measure-pass/no-expression-evaluator effect here already has.
     */
    private const val OP_LOOP_START = 215

    /**
     * `Operations.LAYOUT_STATE` — `startStateLayout` writes `[componentId:i32][animationId:i32]
     * [horizontalPositioning:i32][verticalPositioning:i32][stateIndex:i32]` (5 ints, confirmed
     * against real output), closed the same way as most other layout containers (a
     * `LAYOUT_CONTENT` children marker, then two [OP_CONTAINER_END]s). `stateIndex` is a
     * remote-variable id driving *runtime* state changes this parser has no live state/expression
     * system to evaluate — but the real `StateLayout` class (javap-confirmed) has a well-defined,
     * fully static *default* render even so: `currentLayoutIndex` starts at `0` and `inflate()`
     * immediately calls `hideLayoutsOtherThan(0)`, hiding every child but the first
     * (`Component.Visibility.GONE`) before any state-change event ever fires — see
     * `arrangeChildren`'s `isStateLayout` handling for the real (not faked) initial-render effect
     * this parser now gives it.
     */
    private const val OP_LAYOUT_STATE = 217

    /**
     * `Operations.LAYOUT_CANVAS` — `startCanvas` writes only `[componentId:i32][animationId:i32]`
     * (2 ints, no positioning/spacing — confirmed against real output), followed by a
     * `LAYOUT_CONTENT` marker, then `Operations.LAYOUT_CANVAS_CONTENT` (opcode 207,
     * [OP_LAYOUT_CANVAS_CONTENT] below) wrapping a single auto-assigned `[componentId:i32]`.
     * `endCanvas()` closes with **three** [OP_CONTAINER_END]s (one per nested scope), not the
     * usual two.
     */
    private const val OP_LAYOUT_CANVAS = 205
    private const val OP_LAYOUT_CANVAS_CONTENT = 207

    /**
     * `Operations.LAYOUT_CUSTOM` — `startCustom(modifier, name, properties)` first registers
     * `name` via a normal `DATA_TEXT` op, then writes `[componentId:i32][animationId:i32]
     * [nameTextId:i32][propertyCount:i32]` followed by `propertyCount` entries of `[type:i16]
     * [dataType:i16][value:4 bytes]` (int or float depending on `dataType`). Confirmed via
     * `startCustom(RecordingModifier(), "myCustom", emptyList())`: componentId decodes as literal
     * `-1` (not auto-numbered like other containers — `Custom.apply()` uses the modifier's raw
     * `getComponentId()` directly, bypassing the usual auto-assign-next-negative-id helper).
     */
    private const val OP_LAYOUT_CUSTOM = 93

    /**
     * `Operations.LAYOUT_IMAGE` — `writer.image(modifier, bitmapId, scaleType, alpha)` writes
     * `[componentId:i32][animationId:i32][bitmapId:i32][scaleType:i32][alpha:f32]`. Real-bytes
     * hex-diff of `image(modifier, 111, 222, 0.5f)` decoded to exactly
     * `[componentId, -1, 111, 222, 0.5]` — `bitmapId` (the call's 2nd argument) precedes
     * `scaleType` (its 3rd), the reverse of what an earlier pass over this opcode assumed (that
     * pass's own `image(modifier, 3, 1, 0.75f)` test call happened to leave the swap
     * undetectable, since neither `3` nor `1` stood out as identifiably "the bitmap id" or "the
     * scale type" on inspection). A leaf component — no `LAYOUT_CONTENT` children marker — closed
     * by a single [OP_CONTAINER_END]; unlike every other draw opcode, it carries no position/size
     * of its own, so real rendering (see `OP_CONTAINER_END`'s `imageBitmapId` handling) depends on
     * an explicit `MODIFIER_WIDTH`/`MODIFIER_HEIGHT` on the same modifier to know what rect to
     * draw into — this renderer has no measure pass to size it from the bitmap's own dimensions
     * or `scaleType` otherwise, so an image with no explicit size stays byte-consumed only, same
     * as before this parser attempted real rendering. `scaleType` itself now gets a real effect
     * for `SCALE_NONE`(`0`)/`SCALE_INSIDE`(`1`)/`SCALE_FIT`(`4`)/`SCALE_CROP`(`5`) (javap-confirmed
     * constants and algorithm, on the real `ImageScaling.adjustDrawToType()`): the bitmap's own
     * *natural* pixel size — read straight out of its raw PNG bytes' `IHDR` chunk (`bitmapPool`'s
     * bytes are always a real PNG, so this is exact, not a heuristic) rather than needing any
     * platform image-decoding this common-code parser doesn't have — is compared against this
     * leaf's own explicit declared box to draw at natural size centered (`NONE`, clipped to the box
     * if it overflows — harmless no-op otherwise), at natural size centered *or* letterboxed like
     * `FIT` when it doesn't already fit both dimensions (`INSIDE` — never scales *up*, unlike
     * `FIT`), letterboxed (`FIT`, centered, preserving aspect ratio, both axes fitting inside the
     * box), or overscanned (`CROP`, centered, preserving aspect ratio, clipped to the box since one
     * axis overflows it — real Compose's own `Image`/`Modifier.paint` clips automatically whenever
     * a mismatched `contentScale` makes the painted size larger than the layout box). Every other
     * `scaleType` value (`FILL_WIDTH`/`FILL_HEIGHT`/`SCALE_FIXED_SCALE`) still falls back to the
     * same stretch-to-fill-the-box behavior this parser has always used (identical to what
     * `SCALE_FILL_BOUNDS`(`6`) itself really means) — a real effect for four of the eight scale
     * types, not full parity with all of them.
     */
    private const val OP_LAYOUT_IMAGE = 234

    /**
     * `Operations.LAYOUT_TEXT` — `RemoteComposeWriter.startTextComponent(modifier, textId, color,
     * fontSize, fontStyle, fontWeight, fontFamilyName, flags, textAlign, overflow, maxLines)`
     * writes `[componentId:i32][animationId:i32][textId:i32][color:i32][fontSize:f32(NaN-taggable)]
     * [fontStyle:i32][fontWeight:f32(NaN-taggable)][fontFamilyId:i32][textAlign:i32(packed — see
     * below)][overflow:i32][maxLines:i32]` (source-confirmed via javap on the real
     * `TextLayout.read()`/`write()`), followed by this component's own modifiers, a
     * [OP_LAYOUT_CONTENT] marker, *no* children (real `TextLayout` is a leaf, like
     * [OP_LAYOUT_IMAGE] but — unlike it — still framed by a real content marker, confirmed via
     * `RemoteComposeWriter.startTextComponent()`'s own bytecode calling the same
     * `addContentStart()` every container calls), then two [OP_CONTAINER_END]s (this leaf's own
     * empty content frame, then its outer modifier-carrying frame) — the same double-close shape
     * [OP_LAYOUT_COLLAPSIBLE_COLUMN]/[OP_LAYOUT_ROW] already get. `textAlign`'s wire value is
     * itself packed (javap-confirmed on `TextLayout.updateVariables()`'s own `getFlagsFromTextAlign`
     * helper): the low 16 bits are the real `TEXT_ALIGN_LEFT(1)`/`RIGHT(2)`/`CENTER(3)`/
     * `JUSTIFY(4)`/`START(5)`/`END(6)` value, the high 16 bits a separate flags word (only
     * `FLAG_IS_DYNAMIC_COLOR` is ever set there — a dynamic-color-pool-reference feature this
     * parser doesn't resolve, so `color` is trusted as a literal packed ARGB always). Renders as a
     * plain [Opcode.DrawText] (real `fontSize`/`color`, `fontStyle`/`fontWeight`/`fontFamilyId`/
     * `overflow`/`maxLines` byte-consumed only — no italic/bold/font-family/wrapping/ellipsis
     * support exists anywhere in this renderer) at this leaf's own local origin, mirroring
     * [OP_LAYOUT_IMAGE]'s "no measure pass, so only real when this component declares its own
     * size" honesty: `textAlign` only gets a real horizontal-alignment effect when a
     * `MODIFIER_WIDTH`/`MODIFIER_DIMENSION_CONSTRAINTS` on the *same* component gave it an explicit
     * width to align within (via the same `crossAxisOffset` helper [OP_LAYOUT_BOX] reuses, mapping
     * `TEXT_ALIGN_LEFT`/`START`/`JUSTIFY` to `POS_START`, `CENTER` to `POS_CENTER`,
     * `RIGHT`/`END` to `POS_END` — `JUSTIFY`'s real multi-line-stretching effect needs a wrapping
     * algorithm this parser doesn't have, so it falls back to left, the same honest simplification
     * `MODIFIER_DIMENSION_CONSTRAINTS`'s own undeclared-size gate already documents elsewhere);
     * with no declared width, `textAlign` is real byte-coverage only, same as every other field
     * here.
     */
    private const val OP_LAYOUT_TEXT = 208

    /**
     * `Operations.HAPTIC_FEEDBACK`/`THEME`/`ROOT_CONTENT_BEHAVIOR` — top-level document metadata
     * ops (not nested in any container). `performHaptic(id)` writes `[id:i32]`; `setTheme(theme)`
     * writes `[theme:i32]`; `setRootContentBehavior(a, b, c, d)` writes `[a:i32][b:i32][c:i32]
     * [d:i32]`. Confirmed via real output for all three.
     */
    private const val OP_HAPTIC_FEEDBACK = 177
    private const val OP_THEME = 63
    private const val OP_ROOT_CONTENT_BEHAVIOR = 65

    /**
     * `Operations.DEBUG_MESSAGE` — `RemoteComposeWriter.addDebugMessage(...)` writes
     * `[textId:i32][floatValue:f32(NaN-taggable)][flags:i32]` (source-confirmed via javap on the
     * real `DebugMessage.read()`). Real `DebugMessage` only implements `VariableSupport` (no
     * `paint()`/`PaintOperation` at all) — a pure developer-tools log message with no rendering
     * effect of any kind to reproduce, honestly byte-consumed only, the same as
     * [OP_HAPTIC_FEEDBACK]/[OP_ROOT_CONTENT_BEHAVIOR]'s own no-visual-effect metadata.
     */
    private const val OP_DEBUG_MESSAGE = 179

    /**
     * `Operations.ANIMATION_SPEC` — `RecordingModifier.animationSpec(animationId)` writes
     * `[animationId:i32][motionDuration:f32][motionEasingType:i32][visibilityDuration:f32]
     * [visibilityEasingType:i32][enterAnimation:i32][exitAnimation:i32]` (7 fields, the last two
     * being `AnimationSpec.ANIMATION` enum ordinals). Confirmed via `animationSpec(3)`'s
     * hardcoded defaults decoding to exactly `[3, 300.0, 1, 300.0, 1, 0, 1]`.
     */
    private const val OP_ANIMATION_SPEC = 14

    /**
     * `Operations.LAYOUT_BOX` — `[componentId:i32][animationId:i32][horizontalPositioning:i32]
     * [verticalPositioning:i32]`, i.e. [OP_LAYOUT_COLUMN]'s shape minus the trailing `spacedBy`
     * float (source-confirmed: `BoxLayout.apply()` has no spacing concept, boxes stack children
     * rather than distributing them along an axis). `horizontalPositioning`/`verticalPositioning`
     * get a real effect too now (javap-confirmed on the real `BoxLayout.internalLayoutMeasure()`):
     * real `Box(contentAlignment = ...)` positions *every* child independently within the box's
     * own bounds using the exact same `START`/`CENTER`/`END`(horizontal) and `TOP`/`CENTER`/
     * `BOTTOM`(vertical) ordinals — and even the same fallback-to-zero-offset behavior for any
     * other value — [OP_LAYOUT_COLUMN]/[OP_LAYOUT_ROW]'s own cross-axis alignment already uses, so
     * `arrangeChildren`'s existing `crossAxisOffset` helper is reused verbatim for *both* axes here
     * (a `POS_START`/`POS_TOP` value — including the raw `0` many existing calls in this codebase
     * pass for "no override" — offsets identically under it, so this is behavior-preserving for
     * every already-existing Box test). Unlike Column/Row's sequential main-axis stacking, this is
     * a single per-child pass with no packing at all — see `arrangeChildren`'s own Box-alignment
     * block.
     */
    private const val OP_LAYOUT_BOX = 202

    /**
     * `Operations.LAYOUT_CONTENT` — `[componentId:i32]`, marking the start of a container's
     * children (a `LAYOUT_COLUMN`/`LAYOUT_ROW`/`LAYOUT_BOX`'s body). See [OP_LAYOUT_COLUMN]'s KDoc
     * for why this parser only consumes it rather than modeling it.
     */
    private const val OP_LAYOUT_CONTENT = 201

    /**
     * `Operations.CONTAINER_END` — no payload. `startColumn`/`endColumn` emits this **twice**
     * (closing [OP_LAYOUT_CONTENT]'s children block, then [OP_LAYOUT_COLUMN]'s own scope) —
     * confirmed against real output, not assumed from the single `endColumn()` call site.
     */
    private const val OP_CONTAINER_END = 214

    /**
     * `Operations.MODIFIER_WIDTH` — `RecordingModifier.width(float)` writes `[mode:i32][value:f32]`
     * (mode observed as 0/[DIMENSION_MODE_EXACT] for a fixed-size `width(float)`) — `value` is
     * actually a `readNanId`-tagged [floatPool] reference on the real `WidthModifierOperation`
     * (javap-confirmed, same as [OP_MODIFIER_PADDING]'s floats), now resolved through
     * [resolveFloat]. Written immediately after its component's own layout op (e.g.
     * [OP_LAYOUT_BOX]) and before [OP_LAYOUT_CONTENT]. When the mode is a real target size, the
     * value is captured onto the current container's [ScopeFrame] as [ScopeFrame.explicitWidthPx]
     * — used by `arrangeChildren` (a LAYOUT_COLUMN/LAYOUT_ROW's real child arrangement) as the
     * container's known main/cross-axis extent. `mode`'s other sizing *strategies* — `FILL`(`1`)/
     * `WRAP`(`2`)/`INTRINSIC_MIN`(`4`)/`INTRINSIC_MAX`(`5`)/`FILL_PARENT_MAX_WIDTH`(`7`)/
     * `FILL_PARENT_MAX_HEIGHT`(`8`) — still have no equivalent this parser can give real effect to
     * without a genuine measure pass. `WEIGHT`(`3`) is the one exception: unlike the others, its
     * `value` is a real, usable weight number (not a sizing strategy with no numeric meaning of its
     * own) — see [ScopeFrame.widthWeight]/[ScopeFrame.heightWeight] and `arrangeChildren`'s real
     * proportional-space-distribution effect for it, the same real `Modifier.weight()` concept
     * real Compose's own `Row`/`Column` give a weighted child.
     */
    private const val OP_MODIFIER_WIDTH = 16

    /** `Operations.MODIFIER_HEIGHT` — same `[mode:i32][value:f32]` shape as [OP_MODIFIER_WIDTH]. */
    private const val OP_MODIFIER_HEIGHT = 67

    /**
     * `Operations.MODIFIER_CLICK` — `RecordingModifier.onClick(vararg Action)` writes **no
     * payload of its own**; it's purely a marker opening a nested action list (one or more
     * [OP_HOST_ACTION]-shaped ops, or other action kinds this parser doesn't decode) that runs
     * when the component is tapped, closed by a generic [OP_CONTAINER_END] — the same closing
     * opcode the layout containers use, reused here for a third kind of scope.
     *
     * A real component-level click handler like this is architecturally the same problem as
     * [OP_LAYOUT_COLUMN]'s dynamic arrangement: knowing *which pixels* trigger it requires the
     * measure/layout pass this renderer doesn't have, unlike [OP_CLICK_AREA]'s explicit rect. So
     * this is consumed as a pass-through, not wired into [com.example.remotecompose.model.Opcode.ActionClick].
     */
    private const val OP_MODIFIER_CLICK = 59

    /** `Operations.HOST_ACTION` — `HostAction(actionId)` writes `[actionId:i32]`. */
    private const val OP_HOST_ACTION = 209

    /**
     * `Operations.MODIFIER_PADDING` — `RecordingModifier.padding(float)` writes four raw floats
     * (all equal to the single value passed, for the one-arg overload). For the four-arg
     * overload the order is `[left:f32][top:f32][right:f32][bottom:f32]` — source-confirmed via
     * javap on the real `PaddingModifier`/`PaddingModifierOperation` classes: both the
     * creation-side constructor and the wire `apply()` carry the fields straight through in
     * `(left, top, right, bottom)` order with no reordering at either hop (an earlier pass over
     * this opcode left the order "unconfirmed" and guessed wrong — `top/bottom/left/right`).
     * All four get a real semantic effect (see the handler below): `left`/`top` translate this
     * container's children inward (the same mechanism [OP_MODIFIER_OFFSET] uses); all four
     * expand a sibling `MODIFIER_BACKGROUND`'s inferred rect back out, so the background covers
     * this container's full un-padded box instead of just the inset children `contentBounds()`
     * alone would measure. Each field is also resolved through `resolveFloat` first, so a document
     * that passes a `RemoteComposeWriter.addFloatConstant(...)` reference here (rather than a
     * literal) still gets a real, correct inset instead of a `NaN` one — see [OP_DATA_FLOAT].
     */
    private const val OP_MODIFIER_PADDING = 58

    /**
     * `Operations.MODIFIER_BACKGROUND` — `RecordingModifier.background(Int)`/`background(FFFF)`
     * write **4 raw ints, then 4 raw floats, then 1 raw int** (36 bytes total, source-confirmed
     * via javap on the real `BackgroundModifierOperation`: its `write()` always calls a 9-arg
     * `apply(WireBuffer,IIIIFFFFI)`, never `IIII` + 4 rounding floats as an earlier pass through
     * this same opcode guessed — the four leading values previously described as "presumably
     * per-corner radii" are actually `[colorIdFlag][colorId][0][0]` (colorIdFlag/colorId used only
     * by the separate `backgroundId(...)` dynamic-color path this parser doesn't need to resolve,
     * since [OP_COLOR_CONSTANT]'s pool only matters when those two ints are non-zero). The middle
     * four floats are the color as normalized `0f..1f` channels (not a packed ARGB int — confirmed:
     * `0xFF7B1FA2` decoded here as `[0.4824, 0.1216, 0.6353, 1.0]`, exactly `R/255, G/255, B/255,
     * A/255`) — reading them as the 5th-8th field in the byte stream happens to be correct under
     * both the old and new understanding, which is why the resulting color was never actually
     * wrong despite the mislabeled fields around it. The trailing int is a real `shapeType`
     * (`0`=RECTANGLE, `1`=CIRCLE — the same two values [OP_MODIFIER_BORDER]'s shapeType uses,
     * javap-confirmed via `BackgroundModifierOperation.paint()`'s own `mShapeType`-gated
     * `drawRect`/`drawCircle` dispatch) that this parser now gives the same real effect: a `1`
     * background draws as [Opcode.DrawOval] instead of [Opcode.DrawRect] at `OP_CONTAINER_END`,
     * the same shapeType-gated shape choice [OP_MODIFIER_BORDER] already makes for its stroke.
     * `RecordingModifier.background(...)`'s public fluent API only ever emits shapeType `0`
     * (`SolidBackgroundModifier.write()` hardcodes it); reaching shapeType `1` on the wire needs
     * `RemoteComposeWriter.addModifierBackground(r,g,b,a,1)` called directly (still a real,
     * genuine writer method — just not one `RecordingModifier` exposes a chainable wrapper for).
     */
    private const val OP_MODIFIER_BACKGROUND = 55

    /** `Operations.MODIFIER_VISIBILITY` — `RecordingModifier.visibility(int)` writes a single raw int. */
    private const val OP_MODIFIER_VISIBILITY = 211

    /** `Operations.MODIFIER_OFFSET` — `RecordingModifier.offset(x, y)` writes `[x:f32][y:f32]`. */
    private const val OP_MODIFIER_OFFSET = 221

    /**
     * `Operations.DATA_FLOAT` — `RemoteComposeWriter.addFloatConstant(value)` writes
     * `[id:i32][value:f32]` (source-confirmed via javap on the real `FloatConstant` class).
     * Registers a real value into [floatPool], the same real-value-pool pattern
     * [OP_COLOR_CONSTANT] already established. `addFloatConstant(...)` doesn't return `value`
     * itself but a *NaN-tagged reference* to it (`Float.fromBits(id or -8388608)`, i.e. a NaN or
     * -Infinity bit pattern with `id` packed into the low 22 mantissa bits) — the same
     * `idFromNan(rawBits and 0x3FFFFF)` scheme this real SDK uses throughout for any field this
     * parser might otherwise read as a plain literal float. See `resolveFloat` (defined alongside
     * [floatPool]): [OP_MODIFIER_PADDING]'s fields resolve against it now, so a document passing
     * one of these references there gets its real registered value instead of silently decoding
     * as `NaN` and corrupting the translate it drives. Other still-literal-only fields (e.g.
     * `DRAW_TEXT_ON_CIRCLE`'s `warpRadiusOffset`) can adopt the same helper as they're revisited.
     */
    private const val OP_DATA_FLOAT = 80

    /**
     * `Operations.DATA_INT` — `RemoteComposeWriter.addInteger(value)` writes `[id:i32][value:i32]`
     * (source-confirmed via javap on the real `IntegerConstant` class: `value` is written with a
     * plain `writeInt`, not the `readNanId()`-style reference [OP_DATA_FLOAT]'s value gets — ints
     * have no spare NaN-like bit pattern to tag a reference into, so this is always a literal).
     * Registers into [intPool], the same real-value-pool pattern [OP_COLOR_CONSTANT]/
     * [OP_DATA_FLOAT] already established; not yet resolved against by anything.
     */
    private const val OP_DATA_INT = 140

    /**
     * `Operations.DATA_BOOLEAN` — `RemoteComposeWriter.addBoolean(value)` writes `[id:i32]
     * [value:byte]` (source-confirmed via javap on the real `BooleanConstant` class). Registers
     * into [booleanPool], the same real-value-pool pattern as [OP_DATA_INT].
     */
    private const val OP_DATA_BOOLEAN = 143

    /**
     * `Operations.DATA_LONG` — `RemoteComposeWriter.addLong(value)` writes `[id:i32][value:i64]`
     * (source-confirmed via javap on the real `LongConstant` class). `value` *is* read via
     * `readLongNanId()` on the real side, hinting long fields elsewhere may support a similar
     * tagged-reference scheme to [OP_DATA_FLOAT]'s — unconfirmed and not modeled here; this parser
     * always reads it as a literal 64-bit value into [longPool], the same real-value-pool pattern
     * as [OP_DATA_INT].
     */
    private const val OP_DATA_LONG = 148

    /**
     * `Operations.COLOR_CONSTANT` — `RemoteComposeWriter.addColor(argb)` writes
     * `[colorId:i32][colorArgb:i32]` (source-confirmed via javap: a plain packed-ARGB int, the
     * same convention [Opcode.DrawText.colorArgb] already uses). Registers a real color into
     * [colorPool] so a modifier that references it by id — so far: [OP_MODIFIER_BORDER]'s
     * `RecordingModifier.dynamicBorder(...)` path — resolves to the real color instead of staying
     * unrendered.
     */
    private const val OP_COLOR_CONSTANT = 138

    /**
     * `Operations.MODIFIER_BORDER` — `RecordingModifier.border(width, roundedCorner, color,
     * shapeType)` writes 4 raw ints then 6 raw floats then 1 raw int (44 bytes), confirmed via
     * `border(2f, 4f, 0xFF000000, 0)`: `[0, 0, 0, 0][2.0, 4.0, 0.0, 0.0, 0.0, 1.0][0]` — a
     * colorId-ref flag/id/legacy-flag/reserved int quad (source-confirmed via javap on the real
     * `BorderModifierOperation`: the 4th int is always a literal `0`, not merely unused). The flag
     * is `2` when the color instead comes from `RecordingModifier.dynamicBorder(...)`'s
     * color-pool reference (`r`/`g`/`b`/`a` all `0` on the wire in that case, source-confirmed via
     * `RemoteComposeBuffer.addModifierDynamicBorder`) — resolved against [OP_COLOR_CONSTANT]'s
     * [colorPool] now that it exists; a reference to an id this document never registered still
     * stays unrendered. Otherwise `r`/`g`/`b`/`a` are the color as normalized floats (same
     * normalized-channel convention as [OP_MODIFIER_BACKGROUND]). A trailing shapeType int
     * (`0`=RECTANGLE, `1`=CIRCLE, javap-confirmed) picks the stroked shape (see
     * `OP_CONTAINER_END`'s `borderColor` handling) drawn around the same
     * `contentBounds()`-inferred, padding-expanded box [OP_MODIFIER_BACKGROUND] already uses.
     */
    private const val OP_MODIFIER_BORDER = 107

    /**
     * `Operations.MODIFIER_CLIP_RECT` — `RecordingModifier.clip(RectShape(...))` writes only the
     * opcode tag, no payload — confirmed: the very next byte is the following opcode
     * (`LAYOUT_CONTENT`'s `0xc9`), with nothing in between. Real Compose always clips to this
     * container's own *measured* box, which this parser doesn't have — but when the same
     * container also carries an explicit `MODIFIER_WIDTH`/`MODIFIER_HEIGHT` smaller than its
     * natural content, clipping to *that* declared box (see `OP_CONTAINER_END`'s `hasClipRect`
     * handling) is both real and useful: it cuts off the overflow the same way real Compose
     * would, instead of staying a no-op.
     */
    private const val OP_MODIFIER_CLIP_RECT = 108

    /**
     * `Operations.MODIFIER_ROUNDED_CLIP_RECT` — `RecordingModifier.clip(RoundedRectShape(topStart,
     * topEnd, bottomStart, bottomEnd))` writes those 4 raw floats, confirmed via
     * `RoundedRectShape(4f, 4f, 4f, 4f)` decoding to exactly `[4.0, 4.0, 4.0, 4.0]` — each is
     * actually a `readNanId`-tagged [floatPool] reference on the real
     * `RoundedClipRectModifierOperation` (it's built on the same `DrawBase4` base every other
     * NaN-tagged 4-float operation here uses, javap-confirmed), so each is resolved through
     * [resolveFloat] the same way [OP_MODIFIER_PADDING]'s four floats already are. Gets the same
     * explicit-size-gated real clip [OP_MODIFIER_CLIP_RECT] does, but as a real rounded rect
     * instead of a sharp-cornered one: [Opcode.ClipPath] (already used elsewhere for
     * `writer.addClipPath(...)`) is built from a quadratic-corner rounded-rect approximation of
     * this frame's own clip box (see `OP_CONTAINER_END`'s clip-wrap handling) rather than staying
     * a no-op — the field order (`topStart`/`topEnd`/`bottomStart`/`bottomEnd`, source-confirmed
     * via javap: an LTR top-left/top-right/bottom-left/bottom-right, same as real Compose's
     * `RoundedCornerShape`) is trusted from the already-confirmed `RoundedRectShape` constructor,
     * not re-derived from this quadratic approximation.
     */
    private const val OP_MODIFIER_ROUNDED_CLIP_RECT = 54

    /**
     * `Operations.MODIFIER_MULTI_CLICK` — `RecordingModifier.onLongClick`/`onDoubleClick` write a
     * single raw int (the click-type discriminant: long=1, double=2 — confirmed via
     * `onLongClick(HostAction(9))` decoding to `[1]`) before opening the same nested
     * action-list-closed-by-[OP_CONTAINER_END] shape as [OP_MODIFIER_CLICK].
     */
    private const val OP_MODIFIER_MULTI_CLICK = 83

    /**
     * `Operations.MODIFIER_TOUCH_DOWN`/`_UP`/`_CANCEL` — `RecordingModifier.onTouchDown`/
     * `onTouchUp`/`onTouchCancel` each write only the opcode tag (no payload) before opening the
     * same nested action-list-closed-by-[OP_CONTAINER_END] shape as [OP_MODIFIER_CLICK], confirmed
     * via `onTouchDown/Up/Cancel(HostAction(9))` each decoding to the opcode immediately followed
     * by [OP_HOST_ACTION]'s `[9]` then [OP_CONTAINER_END].
     */
    private const val OP_MODIFIER_TOUCH_DOWN = 219
    private const val OP_MODIFIER_TOUCH_UP = 220
    private const val OP_MODIFIER_TOUCH_CANCEL = 225

    /**
     * `Operations.MODIFIER_WIDTH_IN`/`MODIFIER_HEIGHT_IN` — `RecordingModifier.widthIn(min, max)`/
     * `heightIn(min, max)` write `[min:f32][max:f32]`, the same two-float shape as
     * [OP_MODIFIER_WIDTH]/[OP_MODIFIER_HEIGHT] (which instead carry a leading mode int), confirmed
     * via `widthIn(10f, 20f)` decoding to exactly `[10.0, 20.0]`. Gets a real effect at
     * `OP_CONTAINER_END` (see `ScopeFrame.widthInMin`'s KDoc): resolved against this frame's own
     * inferred content bounds — narrower than `min` raises the effective declared size (visible
     * to a parent Row/Column's arrangement the same way an explicit `width()`/`height()` would
     * be), wider than `max` clips the overflow (implying `MODIFIER_CLIP_RECT`'s effect without a
     * separate `clip(...)` call, matching real Compose).
     */
    private const val OP_MODIFIER_WIDTH_IN = 231
    private const val OP_MODIFIER_HEIGHT_IN = 232

    /**
     * `Operations.MODIFIER_COLLAPSIBLE_PRIORITY` — `RecordingModifier.collapsiblePriority(orientation,
     * priority)` writes `[orientation:i32][priority:f32]`, confirmed via `collapsiblePriority(0, 2f)`
     * decoding to exactly `[0, 2.0]` — `priority` is actually a `readNanId`-tagged [floatPool]
     * reference on the real `CollapsiblePriorityModifierOperation` (javap-confirmed), resolved
     * through [resolveFloat] the same way [OP_MODIFIER_PADDING]'s floats already are. Gets a real
     * effect: stashed on this modifier's own frame, read when it registers into its parent
     * `LAYOUT_COLLAPSIBLE_COLUMN`/`ROW`'s [ScopeFrame.childCollapsiblePriorities] — see
     * `arrangeChildren`'s `isCollapsible` handling. `orientation` (`HORIZONTAL=0`/`VERTICAL=1`,
     * javap-confirmed on the real `CollapsiblePriority` class) must match the parent's own main
     * axis for this priority to apply at all (real Compose's own filter, source-confirmed via
     * javap on `CollapsiblePriority.getPriority`) — a priority declared for the wrong orientation
     * is silently ignored, same as real Compose.
     */
    private const val OP_MODIFIER_COLLAPSIBLE_PRIORITY = 235

    /**
     * `Operations.MODIFIER_ALIGN_BY` — `RecordingModifier.alignByBaseline()` writes
     * `[line:f32][flag:i32]` (line is a NaN-tagged baseline-kind constant, not a plain coordinate;
     * flag observed `0`), confirmed via `alignByBaseline()` decoding to a NaN-payload float
     * followed by `[0]`.
     */
    private const val OP_MODIFIER_ALIGN_BY = 237

    /**
     * `Operations.MODIFIER_ZINDEX` — the (not directly exposed on `RecordingModifier`, reached via
     * `.then(ZIndexModifier(value))`) z-index modifier writes a single raw float, confirmed via
     * `ZIndexModifier(3f)` decoding to exactly `[3.0]`. Gets a real semantic effect for siblings
     * inside a `LAYOUT_COLUMN`/`LAYOUT_ROW`: [ScopeFrame.zIndex] is carried into the parent's
     * [ScopeFrame.childZIndices] at [OP_CONTAINER_END], and `arrangeChildren` reorders sibling
     * *paint* order (not position) by it afterward, so a higher z-index child draws on top of
     * lower ones that would otherwise cover it, without changing where either one sits.
     */
    private const val OP_MODIFIER_ZINDEX = 223

    /**
     * `Operations.MODIFIER_RIPPLE` — reached via `.then(RippleModifier())` (also not a direct
     * `RecordingModifier` method) — writes only the opcode tag, no payload, confirmed: the very
     * next byte is [OP_LAYOUT_CONTENT]'s `0xc9`.
     */
    private const val OP_MODIFIER_RIPPLE = 229

    /**
     * `Operations.MODIFIER_DRAW_CONTENT` — `RecordingModifier.drawContent()`/`drawWithContent()`
     * write only the opcode tag, no payload, confirmed: the very next byte is
     * [OP_LAYOUT_CONTENT]'s `0xc9`.
     */
    private const val OP_MODIFIER_DRAW_CONTENT = 174

    /**
     * `Operations.MODIFIER_MARQUEE` — reached via `.then(MarqueeModifier(iterations, animationMode,
     * repeatDelayMillis, initialDelayMillis, spacing, velocity))` (not a direct method either) —
     * writes `[iterations:i32][animationMode:i32][repeatDelay:f32][initialDelay:f32][spacing:f32]
     * [velocity:f32]`, confirmed via `MarqueeModifier(1, 0, 1000f, 500f, 8f, 30f)` decoding to
     * exactly `[1, 0, 1000.0, 500.0, 8.0, 30.0]`.
     */
    private const val OP_MODIFIER_MARQUEE = 228

    /**
     * `Operations.MODIFIER_SCROLL` — reached via `RecordingModifier.verticalScroll(max)`/
     * `horizontalScroll(max)` — writes `[direction:i32][positionExpression:f32][max:f32]
     * [notchMax:f32]` (source-confirmed via javap on the real `ScrollModifierOperation`: field
     * order matches `apply()`'s own parameter order exactly, no reordering). `direction` is
     * `0`=VERTICAL/`1`=HORIZONTAL. Real-bytes hex-diff of `verticalScroll(50f)` decoded to exactly
     * `[0, 50.0, NaN, NaN]` — `max`/`notchMax` are NaN-tagged variable references (reserved via
     * the real writer's `reserveFloatVariable()`) even in this simplest convenience overload.
     * There's no real scroll effect to give this: the real `ScrollModifierOperation.paint()`
     * resolves its actual scroll offset from `RemoteContext.getFloat(idFromNan(positionExpression))`
     * — a live touch/interaction-driven runtime variable this parser has no state or expression
     * system to evaluate — so this is byte-coverage only, the same "no interactive runtime to
     * drive it" gap as [OP_MODIFIER_MARQUEE]'s animation and [OP_MODIFIER_GRAPHICS_LAYER]'s
     * shadow/blur attributes.
     */
    private const val OP_MODIFIER_SCROLL = 226

    /**
     * `Operations.TOUCH_EXPRESSION` — an unavoidable companion `RemoteComposeWriter
     * .addModifierScroll(...)` itself emits alongside [OP_MODIFIER_SCROLL] (every creation-side
     * `ScrollModifier.write()` branch calls a `RemoteComposeWriter.addModifierScroll(...)`
     * overload, and all of them set up this touch-driven expression alongside it — there is no
     * public call path to `verticalScroll`/`horizontalScroll` that skips it). Source-confirmed via
     * javap on the real `TouchExpression` class's `apply()`: a length-prefixed, otherwise
     * self-describing record —
     * `[id:i32][defValue:f32][min:f32][max:f32][velocity:f32][flags:i32]
     * [srcExpLength:i32][srcExp: srcExpLength floats][packed:i32 = (tag << 16) | tapExpLength]
     * [tapExp: tapExpLength floats][tapExpFloatsLength:i32][tapExpFloats: tapExpFloatsLength
     * floats]` — an embedded little expression-tree language driving a touch/gesture-animated
     * value. Fully decodable (every array is length-prefixed) but, like [OP_MODIFIER_SCROLL]
     * itself, represents a live interaction this parser has no runtime state or expression
     * evaluator to give real effect to — byte-coverage only.
     */
    private const val OP_TOUCH_EXPRESSION = 157

    /**
     * `Operations.MODIFIER_GRAPHICS_LAYER` — reached via `.then(GraphicsLayerModifier().apply {
     * setFloatAttribute(key, value) })` (a `HashMap<Int, Any>` of attributes, not a direct method)
     * — writes `[count:i32]` then `count` entries of `[tag:i32][value:4 bytes]`, where `tag` is the
     * attribute key OR'd with `0x400` for a float value (else a plain int). Confirmed via
     * `setFloatAttribute(11 /* ALPHA */, 0.5f)` decoding to `[1, [0x40B, 0.5]]`.
     */
    private const val OP_MODIFIER_GRAPHICS_LAYER = 224

    /**
     * `GraphicsLayerModifierOperation.ALPHA` (`= 11`) OR'd with the float-value tag bit
     * (`0x400`) — the tag [OP_MODIFIER_GRAPHICS_LAYER] uses to mark an entry as the layer's
     * opacity, the one attribute this parser gives real effect to (see [Opcode.SaveLayerAlpha]).
     */
    private const val GRAPHICS_LAYER_ALPHA_TAG = 11 or 0x400

    // The rest of GraphicsLayerModifierOperation's float-valued attribute tags this parser now
    // also gives real effect to — source-confirmed (javap on the real
    // GraphicsLayerModifierOperation class) as SCALE_X=0, SCALE_Y=1, ROTATION_Z=4,
    // TRANSLATION_X=7, TRANSLATION_Y=8, each OR'd with the same `0x400` float-value tag bit as
    // [GRAPHICS_LAYER_ALPHA_TAG]. Unlike ALPHA (whose effect — a compositing layer — needs no
    // pivot), scale/rotation are applied about this container's own inferred content-bounds
    // center (this renderer has no measure pass to get a real layout box from, the same
    // approximation [OP_MODIFIER_BACKGROUND] already makes) — so, unlike every other modifier's
    // immediate emission, they're deferred to this container's own `OP_CONTAINER_END`, once that
    // bounding box is known; see [ScopeFrame.glScaleX] etc. and their use in `OP_CONTAINER_END`.
    private const val GRAPHICS_LAYER_SCALE_X_TAG = 0 or 0x400
    private const val GRAPHICS_LAYER_SCALE_Y_TAG = 1 or 0x400
    private const val GRAPHICS_LAYER_ROTATION_Z_TAG = 4 or 0x400
    private const val GRAPHICS_LAYER_TRANSLATION_X_TAG = 7 or 0x400
    private const val GRAPHICS_LAYER_TRANSLATION_Y_TAG = 8 or 0x400

    // `GraphicsLayerModifierOperation.TRANSFORM_ORIGIN_X`/`_Y` (`= 5`/`= 6`, source-confirmed via
    // javap), each OR'd with the same `0x400` float-value tag bit. Standard Compose
    // `GraphicsLayerScope.transformOrigin` semantics (not something specific to remote-compose's
    // own wire format): a fraction of this layer's own size along each axis, defaulting to `0.5f`
    // (`TransformOrigin.Center`) when unset — generalizes the scale/rotation pivot [ScopeFrame
    // .glScaleX] etc. already compute from a hardcoded content-bounds *center* into a pivot at
    // `left + originX * width`, `top + originY * height` instead, so an explicit origin away from
    // center (e.g. `(0f, 0f)` for the top-left corner) really moves where a rotation/scale pivots
    // around, while an unset origin keeps the exact center-pivot behavior every existing
    // scale/rotation test already relies on.
    private const val GRAPHICS_LAYER_TRANSFORM_ORIGIN_X_TAG = 5 or 0x400
    private const val GRAPHICS_LAYER_TRANSFORM_ORIGIN_Y_TAG = 6 or 0x400

    // `GraphicsLayerModifierOperation.SHAPE`/`SHAPE_RADIUS` (`= 20`/`= 21`, source-confirmed via
    // javap) — SHAPE is int-valued (no `0x400` float bit: `0`=SHAPE_RECT, `1`=SHAPE_ROUND_RECT,
    // `2`=SHAPE_CIRCLE, same constants javap-confirmed on the same class), SHAPE_RADIUS is
    // float-valued. Standard Compose `GraphicsLayerScope.shape`/`.clip` semantics: a non-rect
    // shape clips this layer's own rendered content to it. This reduced wire API exposes no
    // separate boolean "clip" flag the way real Compose's full API does — SHAPE is the only way
    // this format can express clip intent at all, so a non-RECT value is trusted to mean "clip to
    // this shape" unconditionally. Applied at OP_CONTAINER_END as an innermost ClipPath wrap
    // (nested *inside* the SCALE/ROTATION_Z/TRANSLATION transform-wrap above, since real Compose
    // clips a layer's own local content before transforming the whole clipped result) built from
    // the same quadratic-corner [roundedRectPath] approximation [OP_MODIFIER_ROUNDED_CLIP_RECT]
    // uses — all 4 corners at `SHAPE_RADIUS` for SHAPE_ROUND_RECT, at half this box's smaller
    // dimension (an approximately-circular rounded rect, `roundedRectPath` already clamps radii to
    // that same maximum) for SHAPE_CIRCLE.
    private const val GRAPHICS_LAYER_SHAPE_TAG = 20
    private const val GRAPHICS_LAYER_SHAPE_RADIUS_TAG = 21 or 0x400

    // `RowLayout`/`ColumnLayout`'s shared positioning-mode ordinals (source-confirmed identical in
    // both classes) — the raw ints `horizontalPositioning`/`verticalPositioning` carry. TOP/BOTTOM
    // are only meaningful on Row's verticalPositioning (cross axis); START/END are only meaningful
    // on Column's horizontalPositioning (cross axis); CENTER and the three SPACE_* modes apply to
    // either axis. See arrangeChildren's KDoc for how each is actually honored.
    private const val POS_START = 1
    private const val POS_CENTER = 2
    private const val POS_END = 3
    private const val POS_TOP = 4
    private const val POS_BOTTOM = 5
    private const val POS_SPACE_BETWEEN = 6
    private const val POS_SPACE_EVENLY = 7
    private const val POS_SPACE_AROUND = 8

    // `DimensionModifierOperation.Type` ordinals for the two modes where MODIFIER_WIDTH/HEIGHT's
    // value is a real target size in this renderer's own coordinate units, rather than a sizing
    // *strategy* (FILL/WRAP/WEIGHT/INTRINSIC_*) this parser has no layout pass to resolve.
    private const val DIMENSION_MODE_EXACT = 0
    private const val DIMENSION_MODE_WEIGHT = 3
    private const val DIMENSION_MODE_EXACT_DP = 6

    /**
     * `Operations.MODIFIER_DIMENSION_CONSTRAINTS` — reached via `.then(WidthInModifier(type, min,
     * max))`'s 3-arg constructor (the public 2-arg `widthIn(min, max)` always takes the
     * [OP_MODIFIER_WIDTH_IN] path instead) — writes `[type:byte][min:f32][max:f32]`, a single raw
     * **byte** rather than the usual `i32`, confirmed via `WidthInModifier(1, 5f, 40f)` decoding to
     * exactly `[1, 5.0, 40.0]` in a 9-byte payload (1+4+4, not 1+4+4+3 padding). `min`/`max` are
     * each a `readNanId`-tagged [floatPool] reference on the real
     * `DimensionConstraintsModifierOperation` (javap-confirmed, same as [OP_MODIFIER_PADDING]'s
     * floats), now resolved through [resolveFloat]. `type` (`0`=`HORIZONTAL_CONSTRAINTS`,
     * `1`=`VERTICAL_CONSTRAINTS`, `2`=`REQUIRED_HORIZONTAL_CONSTRAINTS`,
     * `3`=`REQUIRED_VERTICAL_CONSTRAINTS`, javap-confirmed constants) picks which axis this
     * constrains — exactly [OP_MODIFIER_WIDTH_IN]'s own shape for `0`/`2`, [OP_MODIFIER_HEIGHT_IN]'s
     * for `1`/`3` (the `REQUIRED_*` variants only differ in real Compose's constraint-propagation
     * semantics — whether this overrides an *incoming* measure constraint rather than merely
     * narrowing it — a distinction this parser's post-hoc, no-measure-pass clamp against inferred
     * content bounds has no way to tell apart from the plain variant, so both get the identical
     * real effect) — see [ScopeFrame.widthInMin]/[heightInMin]'s KDoc, now populated by this opcode
     * too instead of staying byte-consumed only.
     */
    private const val OP_MODIFIER_DIMENSION_CONSTRAINTS = 243

    /**
     * `Operations.VALUE_INTEGER_CHANGE_ACTION` — `ValueIntegerChange(valueId, value)`, an `Action`
     * subtype usable anywhere [OP_HOST_ACTION] is (inside `onClick`/`onLongClick`/etc's nested
     * action list) — writes `[valueId:i32][value:i32]`, confirmed via `ValueIntegerChange(3, 7)`
     * decoding to exactly `[3, 7]`.
     */
    private const val OP_VALUE_INTEGER_CHANGE = 212

    /**
     * `Operations.VALUE_STRING_CHANGE_ACTION` — `ValueStringChange(valueId, string)` first
     * registers the string via a normal [OP_DATA_TEXT] op (the same text pool `DrawTextAnchored`
     * uses), then writes `[valueId:i32][stringId:i32]` referencing it. Confirmed via
     * `ValueStringChange(5, "hi")` decoding to a `DATA_TEXT` registration followed by `[5, <that
     * id>]`.
     */
    private const val OP_VALUE_STRING_CHANGE = 213

    /**
     * `Operations.VALUE_FLOAT_CHANGE_ACTION` — `ValueFloatChange(valueId, value)` writes
     * `[valueId:i32][value:f32]`, confirmed via `ValueFloatChange(4, 2.5f)` decoding to exactly
     * `[4, 2.5]`.
     */
    private const val OP_VALUE_FLOAT_CHANGE = 222

    /**
     * `Operations.VALUE_INTEGER_EXPRESSION_CHANGE_ACTION` — `ValueIntegerExpressionChange(valueId,
     * value)` writes `[valueId:i64][value:i64]` — the first action using 64-bit fields instead of
     * 32-bit. Confirmed via `ValueIntegerExpressionChange(6L, 42L)` decoding to exactly `[6, 42]`.
     */
    private const val OP_VALUE_INTEGER_EXPRESSION_CHANGE = 218

    /**
     * `Operations.VALUE_FLOAT_EXPRESSION_CHANGE_ACTION` — `ValueFloatExpressionChange(valueId,
     * value)` writes `[valueId:i32][value:i32]` (both plain ints, despite the name — `value` here
     * is an expression/id reference, not a float bit pattern). Confirmed via
     * `ValueFloatExpressionChange(7, 9)` decoding to exactly `[7, 9]`.
     */
    private const val OP_VALUE_FLOAT_EXPRESSION_CHANGE = 227

    /**
     * `Operations.MATRIX_SAVE` / `MATRIX_RESTORE` — bare opcode bytes, no payload (confirmed via
     * `writer.save()`/`writer.restore()` decoding to a single byte each, `82`/`83` hex). Unlike
     * [OP_MODIFIER_OFFSET]'s scope-attached synthetic save/restore pair, these are top-level draw
     * ops the document author placed directly in the stream, so they map straight onto the
     * existing [Opcode.MatrixSave]/[Opcode.MatrixRestore] with no scope-stack bookkeeping needed.
     */
    private const val OP_MATRIX_SAVE = 130
    private const val OP_MATRIX_RESTORE = 131

    /** `Operations.MATRIX_TRANSLATE` — `[dx:f32][dy:f32]`, confirmed via `writer.translate(130f,41f)`. */
    private const val OP_MATRIX_TRANSLATE = 127

    /**
     * `Operations.MATRIX_SCALE` — `[scaleX:f32][scaleY:f32][pivotX:f32][pivotY:f32]`, confirmed
     * via `writer.scale(2f,2f,145f,46f)` decoding to exactly those four floats in that order.
     */
    private const val OP_MATRIX_SCALE = 126

    /**
     * `Operations.MATRIX_ROTATE` — `[degrees:f32][pivotX:f32][pivotY:f32]`, confirmed via
     * `writer.rotate(45f,175f,46f)` decoding to exactly those three floats in that order.
     */
    private const val OP_MATRIX_ROTATE = 129

    /**
     * `Operations.MATRIX_SKEW` — `writer.skew(skewX, skewY)` writes `[skewX:f32][skewY:f32]`, the
     * same raw top-level document-author matrix op as [OP_MATRIX_TRANSLATE]/[OP_MATRIX_SCALE]/
     * [OP_MATRIX_ROTATE] (its own real `MatrixSkew.write()` just delegates straight to a shared
     * `DrawBase2` two-float writer with no pivot field — unlike scale/rotate, this real op has no
     * pivot concept at all). `skewX`/`skewY` are direct shear factors (`x' = x + skewX*y`,
     * `y' = skewY*x + y`), the same convention `android.graphics.Matrix.setSkew(kx, ky)` uses, not
     * an angle — `DrawTextOnCircle`'s `startAngle` is the only field this real SDK's own
     * documentation ever calls out as being "in degrees"; this one carries no such note.
     */
    private const val OP_MATRIX_SKEW = 128

    /**
     * `Operations.CLIP_RECT` — the top-level draw-context clip (distinct from
     * [OP_MODIFIER_CLIP_RECT]'s modifier-attached one): `[left:f32][top:f32][right:f32][bottom:f32]`,
     * confirmed via `writer.clipRect(190f,41f,205f,51f)` decoding to exactly those four floats.
     */
    private const val OP_CLIP_RECT = 39

    /**
     * `drawTextAnchored` carries no font-size parameter — real font sizing comes from a text style
     * this minimal parser doesn't yet decode — so text is drawn at a fixed, reasonable default.
     */
    private const val DEFAULT_TEXT_SIZE_SP = 16f

    /**
     * Parses [bytes] as a real `.rc` document containing only the opcode subset documented above.
     *
     * @throws RemoteComposeParseException if an opcode outside that subset is encountered, or if
     *   the buffer runs out mid-record.
     */
    fun parse(bytes: ByteArray): RemoteDocument {
        val reader = BufferReader(bytes)

        var width = 0
        var height = 0
        var currentColor = Color.Black
        val textPool = mutableMapOf<Int, String>()
        val pathPool = mutableMapOf<Int, List<PathCommand>>()
        val idListPool = mutableMapOf<Int, List<Int>>()
        val bitmapPool = mutableMapOf<Int, ByteArray>()
        val colorPool = mutableMapOf<Int, Color>()
        val floatPool = mutableMapOf<Int, Float>()
        val intPool = mutableMapOf<Int, Int>()
        val booleanPool = mutableMapOf<Int, Boolean>()
        val longPool = mutableMapOf<Int, Long>()
        val opcodes = mutableListOf<Opcode>()

        /**
         * Resolves a raw wire float that may be a NaN-tagged [floatPool] reference (the
         * `Utils.asNan(id)`/`idFromNan(value)` scheme — see [OP_DATA_FLOAT]'s KDoc) rather than a
         * literal value: real Compose lets a document write `RemoteComposeWriter
         * .addFloatConstant(value)`'s returned reference anywhere a plain float field is expected,
         * so a field this parser previously always read as a literal would decode as `NaN` (and
         * corrupt whatever math used it — e.g. a `Translate` by `NaN` renders nothing at all)
         * whenever a document actually exercises that path. Falls back to the raw value itself
         * when it isn't NaN, or when the referenced id was never registered by a prior
         * [OP_DATA_FLOAT].
         */
        fun resolveFloat(raw: Float): Float {
            if (!raw.isNaN()) return raw
            val id = raw.toRawBits() and 0x3FFFFF
            return floatPool[id] ?: raw
        }

        // Shared by OP_TEXT_SUBTEXT/OP_TEXT_TRANSFORM (real TextSubtext/TextTransform.apply()'s
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

        // Every real container/action-list scope (LAYOUT_BOX/COLUMN/ROW/etc's own scope, the
        // LAYOUT_CONTENT children scope, LAYOUT_CANVAS_CONTENT, and MODIFIER_CLICK/MULTI_CLICK/
        // TOUCH_*'s nested action lists) is closed by exactly one generic CONTAINER_END, and these
        // scopes nest strictly LIFO in the real byte stream. This stack lets a modifier that needs
        // real semantic effect (so far: MODIFIER_OFFSET, MODIFIER_VISIBILITY, MODIFIER_BACKGROUND)
        // push cleanup [Opcode]s — or, for MODIFIER_BACKGROUND, a pending fill color — onto its
        // *container's own* scope (always the top of this stack at the point the modifier is
        // parsed, since any nested action-list scope from an earlier modifier in the same list is
        // always fully opened-and-closed before the next modifier is written) so they fire/resolve
        // when that scope's matching CONTAINER_END is reached — without needing to build a real
        // component tree.
        // Regular draw opcodes are appended straight to [opcodes] the moment they're parsed, not
        // buffered per-scope — so a [ScopeFrame] only tracks (a) [startIndex], the [opcodes] size
        // at the moment this scope was pushed (everything from there to the current size when this
        // scope's CONTAINER_END fires is "this container's content", used by MODIFIER_BACKGROUND's
        // bounds inference and MODIFIER_COLUMN/ROW's child-arrangement below), and (b)
        // [cleanupOpcodes], ops queued by attachToTopScope to be appended *after* that content once
        // the scope closes (MODIFIER_OFFSET/VISIBILITY/GRAPHICS_LAYER's MatrixRestore).
        //
        // [parent] is whichever frame was on top of this stack when this frame was pushed — it's
        // how LAYOUT_COLUMN/LAYOUT_ROW discover their *direct* children for real arrangement: a
        // LAYOUT_CONTENT frame's own [layoutAxis]/[spacedBy] are set (from [pendingLayoutAxis]) only
        // when it directly follows a LAYOUT_COLUMN/LAYOUT_ROW open; then every frame whose [parent]
        // is that LAYOUT_CONTENT frame — i.e. every direct child container, since a bare draw call
        // never pushes a frame at all — registers its own finished [startIndex, opcodes.size) range
        // into the LAYOUT_CONTENT frame's [childRanges] as it closes. A child that's itself a
        // container two levels deep (e.g. a Box's own outer scope, whose *inner* LAYOUT_CONTENT
        // frame is what real grandchildren attach to) still registers correctly, because [parent]
        // is captured at push time — the outer Box frame's parent is the Column's LAYOUT_CONTENT
        // frame, while the Box's *inner* content frame's parent is the Box's own outer frame, not
        // the Column's. This naturally recurses: a nested Column's own CONTAINER_END arranges its
        // own children (rewriting their coordinates in place) before its enclosing Box (and in turn
        // that Box's enclosing Column) ever inspects its bounding box.
        class ScopeFrame(val startIndex: Int, val parent: ScopeFrame?) {
            val cleanupOpcodes = mutableListOf<Opcode>()
            var backgroundColor: Color? = null
            // Set by OP_MODIFIER_BACKGROUND's real (javap-confirmed) trailing shapeType int:
            // 0=RECTANGLE (default), 1=CIRCLE — same two values and same DrawOval-vs-DrawRect
            // choice OP_MODIFIER_BORDER's borderShapeType already gets at OP_CONTAINER_END.
            var backgroundShapeType: Int = 0
            var layoutAxis: Char? = null // 'V' (LAYOUT_COLUMN) or 'H' (LAYOUT_ROW); null otherwise
            var spacedBy: Float = 0f
            // RowLayout/ColumnLayout.{START,CENTER,END,TOP,BOTTOM,SPACE_BETWEEN,SPACE_EVENLY,
            // SPACE_AROUND} ordinals (see arrangeChildren's KDoc) — only meaningful when
            // layoutAxis != null; default POS_START matches this parser's original always-packed
            // behavior when a document doesn't set these explicitly.
            var horizontalPositioning: Int = POS_START
            var verticalPositioning: Int = POS_START
            // Set (from pendingFlowMaxItemsPerLine) only for a LAYOUT_FLOW content frame; null for
            // plain LAYOUT_COLUMN/LAYOUT_ROW/LAYOUT_COLLAPSIBLE_* content frames, which always pack
            // onto a single line regardless of child count.
            var flowMaxItemsPerLine: Int? = null
            // Set (from pendingFlowMaxLines) only for a LAYOUT_FLOW content frame; null everywhere
            // else. arrangeChildren hides (Component.Visibility.GONE, via an empty ClipRect — the
            // same mechanism OP_MODIFIER_VISIBILITY uses) every child whose computed row index
            // would reach this line count, matching the real FlowLayout's own measure logic.
            var flowMaxLines: Int? = null
            // Set (from pendingIsCollapsible) only for a LAYOUT_COLLAPSIBLE_COLUMN/ROW content
            // frame; false for plain LAYOUT_COLUMN/LAYOUT_ROW/LAYOUT_FLOW content frames.
            // arrangeChildren hides children by ascending MODIFIER_COLLAPSIBLE_PRIORITY (lowest
            // priority collapses first) once their cumulative main-axis size would exceed this
            // frame's own parent's declared extent — matching CollapsibleRowLayout/
            // CollapsibleColumnLayout's real computeVisibleChildren logic (source-confirmed via
            // javap), only possible when that extent is actually known (an explicit width()/
            // height() on the CollapsibleColumn/Row itself), the same "no measure pass" gate
            // MODIFIER_WIDTH_IN/HEIGHT_IN's real effect already needs.
            var isCollapsible: Boolean = false
            // Set (from pendingIsStateLayout) only for a LAYOUT_STATE content frame; propagated
            // independently of layoutAxis/pendingLayoutAxis, since LAYOUT_STATE needs no position-
            // arrangement handoff at all (unlike Column/Row/Flow/Collapsible, its children keep
            // their own document-authored position — only their *visibility* is real). Real
            // StateLayout (source-confirmed via javap on the real class): `currentLayoutIndex`
            // defaults to `0` and `inflate()` immediately calls `hideLayoutsOtherThan(0)`, so the
            // real, honest default render (before any runtime state-change event this parser has
            // no live state to evaluate) shows only the *first* child, hiding every other one the
            // same way [Component.Visibility.GONE] already does elsewhere.
            var isStateLayout: Boolean = false
            // Set (from pendingIsBoxAlignment) only for a LAYOUT_BOX/LAYOUT_FIT_BOX content frame.
            // arrangeChildren's per-child 2D-alignment pass (real BoxLayout.internalLayoutMeasure
            // behavior, source-confirmed via javap — see OP_LAYOUT_BOX's own KDoc) only runs when
            // this is set, so a Custom/Canvas/Root/State content frame (also axis == null, also
            // registers children for MODIFIER_ZINDEX) never gets it by accident.
            var isBoxAlignment: Boolean = false
            // Set (from pendingIsTextLayout et al.) only for a LAYOUT_TEXT content frame — the
            // inner, always-empty frame OP_LAYOUT_CONTENT pushes for it (see OP_LAYOUT_TEXT's own
            // KDoc); consumed at this same frame's own OP_CONTAINER_END to synthesize the one real
            // Opcode.DrawText this leaf renders as, since it carries no children opcodes of its own
            // to react to the way every other content frame here does.
            var isTextLayout: Boolean = false
            var textId: Int = 0
            var textColorArgb: Int = 0
            var textFontSize: Float = DEFAULT_TEXT_SIZE_SP
            var textAlign: Int = 1 // TEXT_ALIGN_LEFT
            val childRanges = mutableListOf<IntArray>() // only populated/consumed when layoutAxis != null
            // Parallel to childRanges (same index correspondence) — each entry is the
            // corresponding child's own OP_MODIFIER_ZINDEX value (default 0f), read by
            // arrangeChildren to reorder sibling paint order after positioning.
            val childZIndices = mutableListOf<Float>()
            // Parallel to childRanges — each entry is the corresponding child's own
            // MODIFIER_COLLAPSIBLE_PRIORITY value already resolved against *this* (the parent
            // container's) axis (Float.MAX_VALUE, meaning "never collapse", when the child either
            // carries no such modifier or one whose own orientation doesn't match this container's
            // axis — see CollapsiblePriority.getPriority's own orientation filter, source-confirmed
            // via javap). Only consulted by arrangeChildren when [isCollapsible].
            val childCollapsiblePriorities = mutableListOf<Float>()
            // Parallel to childRanges — each entry is the corresponding child's own real
            // Modifier.weight() value (null if it has none), already resolved against *this*
            // container's own axis (a widthWeight only applies in a 'H' — Row-axis — parent, a
            // heightWeight only in a 'V' one, mirroring how childCollapsiblePriorities resolves
            // orientation). Consulted by arrangeChildren to distribute this container's own
            // remaining main-axis space (only known when its declared extent is) proportionally,
            // the same real `Modifier.weight()` concept real Compose's own Row/Column give a
            // weighted child.
            val childWeights = mutableListOf<Float?>()
            // Set by OP_MODIFIER_ZINDEX on this frame itself; read when *this* frame registers
            // into its own parent's childZIndices at OP_CONTAINER_END.
            var zIndex: Float = 0f
            // Set by OP_MODIFIER_COLLAPSIBLE_PRIORITY on this frame itself (its own priority/
            // orientation, not a child's) — read the same way [zIndex] is, when *this* frame
            // registers into its own parent's childCollapsiblePriorities at OP_CONTAINER_END.
            var collapsiblePriority: Float = Float.MAX_VALUE
            var collapsiblePriorityOrientation: Int? = null
            // Set by OP_MODIFIER_WIDTH/OP_MODIFIER_HEIGHT with a WEIGHT mode directly on *this*
            // frame (its own weight, not a child's) — read the same way [zIndex] is, when *this*
            // frame registers into its own parent's childWeights at OP_CONTAINER_END.
            var widthWeight: Float? = null
            var heightWeight: Float? = null
            // Captured from a MODIFIER_WIDTH/MODIFIER_HEIGHT with an EXACT(_DP) mode directly on
            // *this* frame (i.e. this container's own declared size, not a child's) — read by
            // arrangeChildren on the LAYOUT_CONTENT frame this one is the parent of, since only a
            // real declared container extent (not just "however much space the children take up")
            // makes CENTER/END/SPACE_* along the main axis mean anything.
            var explicitWidthPx: Float? = null
            var explicitHeightPx: Float? = null
            // Set by OP_MODIFIER_GRAPHICS_LAYER when this container's attribute list carries a
            // SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/TRANSLATION_Y entry; consumed at this
            // frame's own OP_CONTAINER_END, once contentBounds() can resolve a real pivot for
            // scale/rotation from this container's now-finished children.
            var glScaleX: Float? = null
            var glScaleY: Float? = null
            var glRotationZ: Float? = null
            var glTranslationX: Float? = null
            var glTranslationY: Float? = null
            // Set by OP_MODIFIER_GRAPHICS_LAYER's TRANSFORM_ORIGIN_X/_Y — a fraction (0f..1f) of
            // this layer's own size, real Compose's standard `GraphicsLayerScope.transformOrigin`
            // semantics; null (unset) means the default `TransformOrigin.Center` fraction (0.5f),
            // the exact pivot every scale/rotation use before this was hardcoded to.
            var glTransformOriginX: Float? = null
            var glTransformOriginY: Float? = null
            // Set by OP_MODIFIER_GRAPHICS_LAYER's SHAPE/SHAPE_RADIUS. null (or SHAPE_RECT/0)
            // means no real clip; SHAPE_ROUND_RECT(1)/SHAPE_CIRCLE(2) clip this layer's own
            // content at OP_CONTAINER_END to a roundedRectPath built from this box's own inferred
            // bounds, nested innermost of the SCALE/ROTATION_Z/TRANSLATION transform-wrap.
            var glShapeType: Int? = null
            var glShapeRadius: Float = 0f
            // Set by OP_LAYOUT_IMAGE on the frame it pushes for itself (a leaf, so this frame
            // never gets any content of its own before its own OP_CONTAINER_END) — consumed there
            // together with explicitWidthPx/explicitHeightPx from a MODIFIER_WIDTH/HEIGHT on the
            // same modifier, since this opcode carries no position/size fields of its own.
            var imageBitmapId: Int? = null
            var imageAlpha: Float = 1f
            // Set by OP_LAYOUT_IMAGE; SCALE_FIT(4)/SCALE_CROP(5) get a real letterbox/overscan
            // effect at OP_CONTAINER_END (see imageScaleDstRect), every other value falls back to
            // the original stretch-to-fill-the-box behavior.
            var imageScaleType: Int = 6 // SCALE_FILL_BOUNDS default: matches this parser's original stretch behavior
            // Set by OP_MODIFIER_PADDING; consumed by OP_CONTAINER_END's MODIFIER_BACKGROUND
            // handling to expand the inferred background rect back out to cover this container's
            // full (un-padded) box — contentBounds() alone would only ever measure the *inset*
            // children, since their own draw calls already carry the padding's runtime translate.
            var paddingLeft: Float = 0f
            var paddingTop: Float = 0f
            var paddingRight: Float = 0f
            var paddingBottom: Float = 0f
            // Set by OP_MODIFIER_BORDER when the color is a literal r/g/b/a (not a color-pool
            // reference this parser doesn't resolve); consumed at OP_CONTAINER_END to draw a real
            // stroked outline around this container's own (padding-expanded) inferred bounds.
            var borderColor: Color? = null
            var borderWidth: Float = 0f
            var borderRoundedCorner: Float = 0f
            var borderShapeType: Int = 0
            // Set by OP_MODIFIER_CLIP_RECT/OP_MODIFIER_ROUNDED_CLIP_RECT — neither carries its own
            // rect bounds on the wire at all (real Compose always clips to this container's own
            // measured box), so a real effect is only possible when this frame also has an
            // explicit MODIFIER_WIDTH/HEIGHT smaller than its natural content — see
            // OP_CONTAINER_END's clip handling.
            var hasClipRect: Boolean = false
            // Set by OP_MODIFIER_ROUNDED_CLIP_RECT (LTR corner names, source-confirmed via javap
            // on the real RoundedRectShape constructor): non-zero only when this frame's clip
            // came from a RoundedRectShape rather than a plain RectShape, telling
            // OP_CONTAINER_END's clip-wrap to build a quadratic-corner rounded rect ClipPath
            // instead of a sharp-cornered ClipRect.
            var cornerTopStart: Float = 0f
            var cornerTopEnd: Float = 0f
            var cornerBottomStart: Float = 0f
            var cornerBottomEnd: Float = 0f
            // Set by OP_MODIFIER_WIDTH_IN/OP_MODIFIER_HEIGHT_IN; resolved against this frame's own
            // contentBounds() at OP_CONTAINER_END (real Compose constrains to a *measured* size
            // this parser doesn't have) into explicitWidthPx/explicitHeightPx when natural content
            // actually falls outside the range — narrower than [min, max] raises it, wider clips
            // it (widthIn/heightIn imply their own clip, no separate MODIFIER_CLIP_RECT needed).
            var widthInMin: Float? = null
            var widthInMax: Float? = null
            var heightInMin: Float? = null
            var heightInMax: Float? = null
            // Set directly by OP_LOOP_START on the frame it pushes for itself (no modifiers ever
            // come between it and its own children — unlike a Component-based container, real
            // LoopOperation isn't a Component/ModifierOperation host at all — so no pending-var
            // handoff to a later LAYOUT_CONTENT is needed the way OP_LAYOUT_BOX's isBoxAlignment
            // needs one). Non-null (real, static, non-NaN-tagged) from/step/until only when this
            // loop's own bounds are literal values, not variable references this parser has no
            // expression evaluator to resolve — consumed at this frame's own OP_CONTAINER_END to
            // literally repeat its one authored copy of the loop body real Compose's own
            // `RemoteContext`-driven runtime loop would otherwise re-`apply()` N times, the same
            // "real effect via static unrolling, honest fallback otherwise" approach
            // MODIFIER_COLLAPSIBLE_PRIORITY's declared-size gate already established.
            var isLoop: Boolean = false
            var loopFrom: Float? = null
            var loopStep: Float? = null
            var loopUntil: Float? = null
        }
        val scopeStack = mutableListOf<ScopeFrame>()
        fun pushScope() {
            scopeStack.add(ScopeFrame(opcodes.size, scopeStack.lastOrNull()))
        }
        fun attachToTopScope(op: Opcode) {
            scopeStack.lastOrNull()?.cleanupOpcodes?.add(op)
        }
        // Set by OP_LAYOUT_COLUMN/OP_LAYOUT_ROW, consumed by the very next OP_LAYOUT_CONTENT (the
        // real byte stream always writes a container's own modifiers — none of which open a
        // LAYOUT_CONTENT themselves — between the two, so nothing else can consume this first).
        var pendingLayoutAxis: Char? = null
        var pendingSpacedBy = 0f
        var pendingHorizontalPositioning = POS_START
        var pendingVerticalPositioning = POS_START
        // Set by OP_LAYOUT_FLOW alongside pendingLayoutAxis ('H' — FlowLayout extends RowLayout,
        // source-confirmed via javap); non-null tells arrangeChildren to wrap into multiple lines
        // instead of packing every child onto one, capping each line at this many children.
        var pendingFlowMaxItemsPerLine: Int? = null
        // Set by OP_LAYOUT_FLOW alongside pendingFlowMaxItemsPerLine; non-null caps how many rows
        // arrangeChildren actually shows, hiding (not just leaving unpositioned) every child past
        // that row count — see ScopeFrame.flowMaxLines's KDoc.
        var pendingFlowMaxLines: Int? = null
        // Set by OP_LAYOUT_COLLAPSIBLE_COLUMN/ROW alongside pendingLayoutAxis; tells the very next
        // OP_LAYOUT_CONTENT frame to set ScopeFrame.isCollapsible, same handoff shape as
        // pendingFlowMaxItemsPerLine/pendingFlowMaxLines.
        var pendingIsCollapsible = false
        // Set by OP_LAYOUT_STATE; tells the very next OP_LAYOUT_CONTENT frame to set
        // ScopeFrame.isStateLayout. Propagated independently of pendingLayoutAxis (LAYOUT_STATE
        // never sets that — see ScopeFrame.isStateLayout's KDoc).
        var pendingIsStateLayout = false
        // Set by OP_LAYOUT_BOX/OP_LAYOUT_FIT_BOX; tells the very next OP_LAYOUT_CONTENT frame to
        // set ScopeFrame.isBoxAlignment. Propagated independently of pendingLayoutAxis (Box has no
        // main/cross axis — see OP_LAYOUT_BOX's own KDoc) — kept distinct from other axis-less
        // containers (Custom/Canvas/Root/State) so arrangeChildren's Box-alignment pass only
        // applies to a real Box/FitBox, never accidentally to one of those.
        var pendingIsBoxAlignment = false
        // Set by OP_LAYOUT_TEXT on the outer (modifier-carrying) frame it pushes for itself;
        // consumed by the very next OP_LAYOUT_CONTENT, same handoff shape as pendingIsBoxAlignment
        // — this leaf's own content frame (not the outer one) is where OP_CONTAINER_END actually
        // synthesizes the real Opcode.DrawText, so these need to reach that inner frame.
        var pendingIsTextLayout = false
        var pendingTextId = 0
        var pendingTextColorArgb = 0
        var pendingTextFontSize = DEFAULT_TEXT_SIZE_SP
        var pendingTextAlign = 1 // TEXT_ALIGN_LEFT

        /**
         * This renderer has no measure/layout pass, so a container's "bounds" for
         * [OP_MODIFIER_BACKGROUND] are inferred as the tight bounding box of every draw call
         * inside it — an approximation that ignores padding/insets around the content, but a real
         * visual improvement over not drawing a background at all. Returns null if [ops] contains
         * no boundable draw opcode.
         */
        fun contentBounds(ops: List<Opcode>): FloatArray? {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            fun expand(l: Float, t: Float, r: Float, b: Float) {
                if (l < left) left = l
                if (t < top) top = t
                if (r > right) right = r
                if (b > bottom) bottom = b
            }
            fun expandPoint(x: Float, y: Float) = expand(x, y, x, y)
            // A draw opcode's own fields are always in *local* coordinates — arrangeChildren (and
            // MODIFIER_OFFSET before it) move content by wrapping it in MatrixSave/Translate/.../
            // MatrixRestore rather than rewriting those fields, so a range being measured here can
            // easily contain an already-arranged nested Column/Row whose children only look right
            // once that accumulated translation is added back in. Track it with a plain offset
            // stack — SaveLayerAlpha pushes one same as MatrixSave, since both are popped by a
            // MatrixRestore; Scale/Rotate/ClipRect don't affect a translation-only offset and are
            // deliberately left unhandled (this parser's own arrangement code never emits them).
            var offsetX = 0f
            var offsetY = 0f
            val offsetStack = mutableListOf<FloatArray>()
            for (op in ops) {
                when (op) {
                    Opcode.MatrixSave, is Opcode.SaveLayerAlpha -> offsetStack.add(floatArrayOf(offsetX, offsetY))
                    Opcode.MatrixRestore -> offsetStack.removeLastOrNull()?.let {
                        offsetX = it[0]
                        offsetY = it[1]
                    }
                    is Opcode.Translate -> {
                        offsetX += op.dx
                        offsetY += op.dy
                    }
                    is Opcode.DrawRect -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawRoundRect -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawOval -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawArc -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawBitmap -> expand(
                        op.left + offsetX, op.top + offsetY, op.right + offsetX, op.bottom + offsetY,
                    )
                    is Opcode.DrawCircle -> expand(
                        op.centerX - op.radius + offsetX, op.centerY - op.radius + offsetY,
                        op.centerX + op.radius + offsetX, op.centerY + op.radius + offsetY,
                    )
                    is Opcode.DrawLine -> expand(
                        minOf(op.x1, op.x2) + offsetX, minOf(op.y1, op.y2) + offsetY,
                        maxOf(op.x1, op.x2) + offsetX, maxOf(op.y1, op.y2) + offsetY,
                    )
                    is Opcode.DrawPath -> for (command in op.commands) when (command) {
                        is PathCommand.MoveTo -> expandPoint(command.x + offsetX, command.y + offsetY)
                        is PathCommand.LineTo -> expandPoint(command.x + offsetX, command.y + offsetY)
                        is PathCommand.QuadraticTo -> {
                            expandPoint(command.x1 + offsetX, command.y1 + offsetY)
                            expandPoint(command.x2 + offsetX, command.y2 + offsetY)
                        }
                        is PathCommand.CubicTo -> {
                            expandPoint(command.x1 + offsetX, command.y1 + offsetY)
                            expandPoint(command.x2 + offsetX, command.y2 + offsetY)
                            expandPoint(command.x3 + offsetX, command.y3 + offsetY)
                        }
                        PathCommand.Close -> Unit
                    }
                    is Opcode.DrawText -> {
                        // No real glyph metrics are available at parse time (text measurement
                        // needs a platform font resolver this parser doesn't have), so width is a
                        // rough average-character-advance estimate — good enough for a child to
                        // participate in real Column/Row arrangement without being ignored
                        // entirely, not a claim of pixel-accurate text bounds.
                        val text = textPool[op.stringIndex] ?: ""
                        val estimatedWidth = text.length * op.fontSize * 0.55f
                        val estimatedHeight = op.fontSize * 1.2f
                        expand(
                            op.x + offsetX, op.y + offsetY,
                            op.x + estimatedWidth + offsetX, op.y + estimatedHeight + offsetY,
                        )
                    }
                    else -> Unit
                }
            }
            return if (left.isFinite()) floatArrayOf(left, top, right, bottom) else null
        }

        /**
         * Returns a copy of [op] with every absolute coordinate shifted by ([dx], [dy]) — used by
         * [arrangeChildren] as the reliable alternative to wrapping a child's range in a
         * MatrixSave/Translate/MatrixRestore triple when that range contains [Opcode.DrawText] (see
         * that call site's KDoc for why). Path/list-shaped payloads ([Opcode.DrawPath]) are shifted
         * point-by-point; opcodes with no absolute position (transform/clip control, [Opcode.Unknown])
         * pass through unchanged, since a relative op's own delta stays correct under an outer shift.
         */
        fun shiftOpcode(op: Opcode, dx: Float, dy: Float): Opcode = when (op) {
            is Opcode.DrawRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawRoundRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawOval -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawArc -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawBitmap -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawCircle -> op.copy(centerX = op.centerX + dx, centerY = op.centerY + dy)
            is Opcode.DrawLine -> op.copy(x1 = op.x1 + dx, y1 = op.y1 + dy, x2 = op.x2 + dx, y2 = op.y2 + dy)
            is Opcode.ClipRect -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.ActionClick -> op.copy(
                left = op.left + dx, top = op.top + dy, right = op.right + dx, bottom = op.bottom + dy,
            )
            is Opcode.DrawText -> op.copy(x = op.x + dx, y = op.y + dy)
            is Opcode.DrawPath -> op.copy(commands = op.commands.map { command ->
                when (command) {
                    is PathCommand.MoveTo -> command.copy(x = command.x + dx, y = command.y + dy)
                    is PathCommand.LineTo -> command.copy(x = command.x + dx, y = command.y + dy)
                    is PathCommand.QuadraticTo -> command.copy(
                        x1 = command.x1 + dx, y1 = command.y1 + dy, x2 = command.x2 + dx, y2 = command.y2 + dy,
                    )
                    is PathCommand.CubicTo -> command.copy(
                        x1 = command.x1 + dx, y1 = command.y1 + dy,
                        x2 = command.x2 + dx, y2 = command.y2 + dy,
                        x3 = command.x3 + dx, y3 = command.y3 + dy,
                    )
                    PathCommand.Close -> command
                }
            })
            else -> op
        }

        /**
         * Returns `(leadingGap, betweenGap)` for [mode] given [extraSpace] (container main-axis
         * extent minus the children's own packed-together size; 0 or negative when the container
         * has no known extent bigger than its content, in which case every mode below correctly
         * degenerates to plain start-packing since there's no slack to distribute) and [count]
         * children. [POS_TOP]/[POS_BOTTOM] have no main-axis meaning and fall back to start/end.
         */
        fun mainAxisGaps(mode: Int, extraSpace: Float, count: Int): FloatArray {
            val extra = extraSpace.coerceAtLeast(0f)
            return when (mode) {
                POS_CENTER -> floatArrayOf(extra / 2f, 0f)
                POS_END, POS_BOTTOM -> floatArrayOf(extra, 0f)
                POS_SPACE_BETWEEN -> floatArrayOf(0f, if (count > 1) extra / (count - 1) else 0f)
                POS_SPACE_EVENLY -> (extra / (count + 1)).let { floatArrayOf(it, it) }
                POS_SPACE_AROUND -> (extra / count).let { floatArrayOf(it / 2f, it) }
                else -> floatArrayOf(0f, 0f) // POS_START/POS_TOP, or unrecognized
            }
        }

        /** Cross-axis offset of a child of [childExtent] within a container of [containerExtent]. */
        fun crossAxisOffset(mode: Int, containerExtent: Float, childExtent: Float): Float = when (mode) {
            POS_CENTER -> (containerExtent - childExtent) / 2f
            POS_END, POS_BOTTOM -> containerExtent - childExtent
            else -> 0f // POS_START/POS_TOP, or unrecognized
        }

        /**
         * Real arrangement for a LAYOUT_COLUMN/LAYOUT_ROW's direct children, run once — from
         * [OP_CONTAINER_END] — when [frame] (a LAYOUT_CONTENT frame carrying [ScopeFrame.layoutAxis])
         * closes and every entry in [ScopeFrame.childRanges] is final. Each child's natural size
         * comes from [contentBounds] over its own already-authored (absolute-coordinate) content —
         * there's still no real measure pass computing a size *before* a child is drawn.
         *
         * Both axes are real, within an honest limit: this parser has no notion of the container's
         * own extent unless the document explicitly gave it one via a MODIFIER_WIDTH/HEIGHT with an
         * EXACT(_DP) mode (captured as [ScopeFrame.explicitWidthPx]/[explicitHeightPx] on [frame]'s
         * *parent*, the container's own outer scope — see that field's KDoc). Without one, this
         * falls back to real "wrap content" semantics — the container's extent is exactly what its
         * children need — under which [POS_CENTER]/[POS_END]/the `SPACE_*` modes have no slack to
         * work with and correctly collapse to plain start-packing, same as real Compose would do.
         * Cross-axis alignment always has a meaningful reference even with no declared size: the
         * *tallest* (Row) or *widest* (Column) child, exactly how Compose sizes an unconstrained
         * Row/Column's cross axis by default.
         *
         * A child is moved into place the same way [OP_MODIFIER_OFFSET] moves content: a
         * MatrixSave/Translate pair spliced immediately before its content, MatrixRestore right
         * after. Splicing is done in *reverse* document order specifically so that an earlier
         * child's still-unprocessed [start, end) range is never shifted by a later child's
         * insertions (every insertion for child K happens at or after K's own start index, which is
         * always ≥ any not-yet-processed, earlier child's end index).
         */
        fun arrangeChildren(frame: ScopeFrame) {
            val axis = frame.layoutAxis
            // Captured before sorting: childZIndices is parallel to frame.childRanges' original
            // (document) order, keyed here by each child's unique start index since sortedBy below
            // produces a new list that no longer corresponds positionally to childZIndices.
            val zIndexByStart = frame.childRanges.indices.associate { i ->
                frame.childRanges[i][0] to frame.childZIndices.getOrElse(i) { 0f }
            }
            val children = frame.childRanges.sortedBy { it[0] }
            if (children.isEmpty()) return
            // Position-arrangement only applies to a real LAYOUT_COLUMN/LAYOUT_ROW content frame
            // (axis != null); a Box's own content frame has no axis, so its children keep their
            // own document-authored position — but MODIFIER_ZINDEX's paint-order reordering below
            // still applies to *any* frame with registered children, Box included.
            if (axis != null) {
                val naturalBounds = children.map { range -> contentBounds(opcodes.subList(range[0], range[1])) }
                val anchorIndex = naturalBounds.indexOfFirst { it != null }
                if (anchorIndex != -1) {
                    val anchor = naturalBounds[anchorIndex]!!
                    val anchorMainStart = if (axis == 'V') anchor[1] else anchor[0] // top (Column) or left (Row)
                    val crossAnchor = if (axis == 'V') anchor[0] else anchor[1] // left (Column) or top (Row)

                    val mainMode = if (axis == 'V') frame.verticalPositioning else frame.horizontalPositioning
                    val crossMode = if (axis == 'V') frame.horizontalPositioning else frame.verticalPositioning

                    val mainSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[3] - it[1] else it[2] - it[0] } ?: 0f }.toMutableList()
                    val crossSizes = naturalBounds.map { b -> b?.let { if (axis == 'V') it[2] - it[0] else it[3] - it[1] } ?: 0f }
                    val declaredMainExtent = if (axis == 'V') frame.parent?.explicitHeightPx else frame.parent?.explicitWidthPx
                    val declaredCrossExtent = if (axis == 'V') frame.parent?.explicitWidthPx else frame.parent?.explicitHeightPx

                    // Real Modifier.weight() effect: only possible once this container's own
                    // declared main-axis extent is known (same "no measure pass" gate every other
                    // real-but-approximate effect here needs) and at least one child actually
                    // carries a weight. Real Compose distributes the *remaining* space (this
                    // extent minus every non-weighted child's own natural size and every
                    // in-between spacedBy gap) proportionally among the weighted children — mirrors
                    // real Row/Column's own weight semantics, not a from-scratch approximation.
                    // weightScaleFactors is consumed at the very end, after position-shifting, to
                    // stretch a weighted child's own already-shifted content from its natural size
                    // up (or down) to its real weighted share via a Scale wrap pivoted at its own
                    // now-final leading edge — the same MatrixSave/Scale/MatrixRestore mechanism
                    // MODIFIER_GRAPHICS_LAYER's SCALE_X/Y already uses, just per-child here instead
                    // of around a whole container's content.
                    val weightScaleFactors = arrayOfNulls<Float>(children.size)
                    if (declaredMainExtent != null) {
                        val weights = children.indices.map { i -> frame.childWeights.getOrElse(i) { null } }
                        val totalWeight = weights.filterNotNull().sum()
                        if (totalWeight > 0f) {
                            val fixedSize = children.indices.filter { weights[it] == null }.sumOf { mainSizes[it].toDouble() }.toFloat()
                            val totalGaps = frame.spacedBy * (children.size - 1).coerceAtLeast(0)
                            val remaining = (declaredMainExtent - fixedSize - totalGaps).coerceAtLeast(0f)
                            for (i in children.indices) {
                                val w = weights[i] ?: continue
                                val weightedSize = remaining * (w / totalWeight)
                                val naturalSize = mainSizes[i]
                                if (naturalSize > 0f) weightScaleFactors[i] = weightedSize / naturalSize
                                mainSizes[i] = weightedSize
                            }
                        }
                    }

                    // LAYOUT_FLOW (flowMaxItemsPerLine != null) wraps into multiple "lines" of at
                    // most that many children each, each line packed/aligned exactly the way a
                    // plain Row's single line already was, then stacked along the cross axis with
                    // spacedBy between them. Every other container is always exactly one line
                    // (perLineCap == children.size), so this loop runs its body once with the
                    // *same* packedMainSize/crossExtent the original single-line code computed —
                    // behavior-preserving for Column/Row/CollapsibleColumn/Row.
                    val perLineCap = frame.flowMaxItemsPerLine ?: children.size
                    val deltas = arrayOfNulls<FloatArray>(children.size)
                    // LAYOUT_FLOW's maxLinesInCrossAxis (flowMaxLines != null) or
                    // isCollapsible's real priority-based collapsing: every index added here is
                    // skipped from packing/positioning below and wrapped in an empty ClipRect at
                    // the very end, matching real Compose's Component.Visibility.GONE (no space
                    // reserved) for both mechanisms.
                    val hiddenChildIndices = mutableSetOf<Int>()
                    // LAYOUT_COLLAPSIBLE_COLUMN/ROW's real collapsing (source-confirmed via javap
                    // on CollapsibleRowLayout/CollapsibleColumnLayout's computeVisibleChildren):
                    // only possible once this frame's own parent's declared main-axis extent is
                    // known — real Compose's own available-space constraint this parser otherwise
                    // has no measure pass to provide. Children are visited highest-priority-first
                    // (childCollapsiblePriorities' Float.MAX_VALUE default sorts first, meaning
                    // "no modifier" never collapses); each is kept only if its own size still fits
                    // the *remaining* budget — real Compose's own per-child comparison ignores
                    // spacedBy entirely here (confirmed via javap: it compares running-total plus
                    // this child's raw width against the available extent, with no gap term), so
                    // this mirrors that exactly rather than a more "correct"-looking accounting.
                    if (frame.isCollapsible && declaredMainExtent != null) {
                        val priorities = children.indices.map { i -> frame.childCollapsiblePriorities.getOrElse(i) { Float.MAX_VALUE } }
                        val priorityOrder = children.indices.sortedByDescending { priorities[it] }
                        var used = 0f
                        for (i in priorityOrder) {
                            val size = mainSizes[i]
                            if (used + size > declaredMainExtent) {
                                hiddenChildIndices.add(i)
                            } else {
                                used += size
                            }
                        }
                    }
                    var lineCrossCursor = crossAnchor
                    var lineStart = 0
                    var lineIndex = 0
                    while (lineStart < children.size) {
                        val lineEnd = (lineStart + perLineCap).coerceAtMost(children.size)
                        val lineIndices = (lineStart until lineEnd).filter { it !in hiddenChildIndices }
                        val lineCount = lineIndices.size
                        val maxLines = frame.flowMaxLines
                        if (maxLines != null && lineIndex >= maxLines) hiddenChildIndices.addAll(lineIndices)
                        val lineMainSizes = lineIndices.map { mainSizes[it] }
                        val lineCrossSizes = lineIndices.map { crossSizes[it] }
                        val packedMainSize = lineMainSizes.sum() + frame.spacedBy * (lineCount - 1).coerceAtLeast(0)
                        val mainExtent = declaredMainExtent ?: packedMainSize
                        val crossExtent = declaredCrossExtent ?: (lineCrossSizes.maxOrNull() ?: 0f)

                        val (leadingGap, betweenGap) = mainAxisGaps(mainMode, mainExtent - packedMainSize, lineCount)
                        var cursorMain = anchorMainStart + leadingGap
                        for (i in lineIndices) {
                            val bounds = naturalBounds[i] ?: continue
                            val crossOffset = crossAxisOffset(crossMode, crossExtent, crossSizes[i])
                            val dx: Float
                            val dy: Float
                            if (axis == 'V') {
                                dx = (lineCrossCursor + crossOffset) - bounds[0]
                                dy = cursorMain - bounds[1]
                            } else {
                                dx = cursorMain - bounds[0]
                                dy = (lineCrossCursor + crossOffset) - bounds[1]
                            }
                            deltas[i] = floatArrayOf(dx, dy)
                            cursorMain += mainSizes[i] + frame.spacedBy + betweenGap
                        }
                        lineCrossCursor += (lineCrossSizes.maxOrNull() ?: 0f) + frame.spacedBy
                        lineStart = lineEnd
                        lineIndex++
                    }
                    for (i in children.indices.reversed()) {
                        val range = children[i]
                        if (i in hiddenChildIndices) {
                            // Same empty-ClipRect GONE mechanism OP_MODIFIER_VISIBILITY uses —
                            // this child's own position doesn't matter once it renders nothing.
                            opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                            opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.ClipRect(0f, 0f, 0f, 0f)))
                            continue
                        }
                        val delta = deltas[i]
                        if (delta != null && (delta[0] != 0f || delta[1] != 0f)) {
                            // Rewrite every opcode's own coordinates directly instead of wrapping the range in
                            // MatrixSave/Translate/MatrixRestore. A real on-device Compose Canvas target was
                            // confirmed (via a minimal, isolated repro) to corrupt DrawText positioning — even
                            // *unwrapped* DrawText elsewhere in the same render — after two or more repeated
                            // canvas.save()/translate()/restore() cycles from sibling arranged children, the
                            // exact shape every stat card's icon-then-value-then-label triplet has. Since a
                            // shape's own position is just as easy to rewrite directly as text's, arrangeChildren
                            // never emits real Matrix ops at all — sidestepping the bug at its root rather than
                            // only where it was first noticed. Nested Translate/MatrixSave/MatrixRestore inside
                            // this range (from an inner, already-arranged nested Column/Row) pass through
                            // shiftOpcode unchanged, since a relative delta stays correct under an outer shift.
                            for (j in range[0] until range[1]) {
                                opcodes[j] = shiftOpcode(opcodes[j], delta[0], delta[1])
                            }
                        }
                        // Real Modifier.weight(): stretch this now-repositioned child's content
                        // from its natural main-axis size up (or down) to its real weighted share,
                        // via a Scale wrap pivoted at its own now-final leading edge (so that edge
                        // stays put and only the trailing edge moves) — cross-axis scale stays 1
                        // (unaffected), matching real Compose's weight only ever redistributing
                        // the *main*-axis extent.
                        val scaleFactor = weightScaleFactors[i]
                        if (scaleFactor != null && delta != null) {
                            val bounds = naturalBounds[i]!!
                            val pivotX = bounds[0] + delta[0]
                            val pivotY = bounds[1] + delta[1]
                            val sx = if (axis == 'V') 1f else scaleFactor
                            val sy = if (axis == 'V') scaleFactor else 1f
                            opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                            opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.Scale(sx, sy, pivotX, pivotY)))
                        }
                    }
                }
            }

            // LAYOUT_BOX/LAYOUT_FIT_BOX's real per-child 2D alignment (source-confirmed via javap
            // on BoxLayout.internalLayoutMeasure — see OP_LAYOUT_BOX's own KDoc): unlike Column/
            // Row's sequential main-axis stacking, every child is independently positioned within
            // the box's own bounds (the union of every child's own natural bounds, or this box's
            // own declared width/height when explicit — same anchor-at-natural-bounds,
            // extent-from-declared-size split Column/Row's own arrangement already uses) via the
            // same crossAxisOffset helper, reused verbatim for *both* axes here since Box's
            // horizontalPositioning/verticalPositioning share Column/Row's own ordinals exactly.
            if (frame.isBoxAlignment) {
                contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { boxBounds ->
                    val boxWidth = frame.parent?.explicitWidthPx ?: (boxBounds[2] - boxBounds[0])
                    val boxHeight = frame.parent?.explicitHeightPx ?: (boxBounds[3] - boxBounds[1])
                    for (i in children.indices.reversed()) {
                        val range = children[i]
                        val childBounds = contentBounds(opcodes.subList(range[0], range[1])) ?: continue
                        val childWidth = childBounds[2] - childBounds[0]
                        val childHeight = childBounds[3] - childBounds[1]
                        val dx = (boxBounds[0] + crossAxisOffset(frame.horizontalPositioning, boxWidth, childWidth)) - childBounds[0]
                        val dy = (boxBounds[1] + crossAxisOffset(frame.verticalPositioning, boxHeight, childHeight)) - childBounds[1]
                        if (dx == 0f && dy == 0f) continue
                        for (j in range[0] until range[1]) {
                            opcodes[j] = shiftOpcode(opcodes[j], dx, dy)
                        }
                    }
                }
            }

            // LAYOUT_STATE's real default: only the first child (index 0, matching the real
            // StateLayout's own currentLayoutIndex default) stays visible; every other child is
            // hidden via the same empty-ClipRect GONE mechanism used above, in place at its own
            // document-authored position (StateLayout has no axis, so no repositioning applies).
            if (frame.isStateLayout) {
                for (i in children.indices.reversed()) {
                    if (i == 0) continue
                    val range = children[i]
                    opcodes.addAll(range[1], listOf(Opcode.MatrixRestore))
                    opcodes.addAll(range[0], listOf(Opcode.MatrixSave, Opcode.ClipRect(0f, 0f, 0f, 0f)))
                }
            }

            // MODIFIER_ZINDEX: reorder sibling *paint* order (not position, already fixed above)
            // by z-index — a stable sort, so same-z-index siblings keep their original relative
            // (document) order, matching real Compose's tie-breaking. Only safe to do as a blind
            // remove-and-reinsert over [overallStart, overallEnd) when every child range *exactly
            // tiles* that span with no gaps — guards against a bare, unwrapped draw call between
            // children (which pushes no scope, so never registers in childRanges) silently
            // getting dropped; skips the reorder entirely rather than risk losing content.
            val zIndices = children.map { zIndexByStart[it[0]] ?: 0f }
            if (zIndices.any { it != 0f }) {
                val overallStart = children.minOf { it[0] }
                val overallEnd = children.maxOf { it[1] }
                val totalChildLength = children.sumOf { it[1] - it[0] }
                if (totalChildLength == overallEnd - overallStart) {
                    val paintOrder = children.indices.sortedBy { zIndices[it] }
                    val blocks = children.map { range -> opcodes.subList(range[0], range[1]).toList() }
                    val reordered = paintOrder.flatMap { blocks[it] }
                    for (j in overallEnd - 1 downTo overallStart) opcodes.removeAt(j)
                    opcodes.addAll(overallStart, reordered)
                }
            }
        }

        while (reader.hasRemaining()) {
            when (val opId = reader.readU8()) {
                OP_HEADER -> {
                    reader.readS32() // major version
                    reader.readS32() // minor version
                    reader.readS32() // patch version
                    width = reader.readS32()
                    height = reader.readS32()
                    reader.readS64() // capabilities bitmask — unused by this minimal renderer
                }

                OP_DATA_TEXT -> {
                    val id = reader.readS32()
                    val length = reader.readS32()
                    textPool[id] = reader.readUtf8(length)
                }

                OP_TEXT_SUBTEXT -> {
                    val textId = reader.readS32()
                    val srcId = reader.readS32()
                    val start = resolveFloat(reader.readFloat32())
                    val len = resolveFloat(reader.readFloat32())
                    substringOf(textPool[srcId], start, len)?.let { textPool[textId] = it }
                }

                OP_TEXT_TRANSFORM -> {
                    val textId = reader.readS32()
                    val srcId = reader.readS32()
                    val start = resolveFloat(reader.readFloat32())
                    val len = resolveFloat(reader.readFloat32())
                    val operation = reader.readS32()
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

                OP_ROOT_CONTENT_DESCRIPTION -> {
                    reader.readS32() // text-pool id reference — not needed for drawing
                }

                OP_PAINT_BUNDLE -> {
                    val wordCount = reader.readS32()
                    var lastWord = 0
                    repeat(wordCount) { lastWord = reader.readS32() }
                    if (wordCount >= 2) currentColor = Color(lastWord)
                }

                OP_DRAW_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.DrawRect(
                        left, top, right, bottom,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_CIRCLE -> {
                    val centerX = reader.readFloat32()
                    val centerY = reader.readFloat32()
                    val radius = reader.readFloat32()
                    opcodes += Opcode.DrawCircle(
                        centerX, centerY, radius,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_ROUND_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val radiusX = reader.readFloat32()
                    val radiusY = reader.readFloat32()
                    opcodes += Opcode.DrawRoundRect(
                        left, top, right, bottom, radiusX, radiusY,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_TEXT_RUN -> {
                    val textId = reader.readS32()
                    val start = reader.readS32()
                    val end = reader.readS32()
                    reader.readS32() // contextStart — bidi/shaping context only, not modeled
                    reader.readS32() // contextEnd
                    val x = reader.readFloat32()
                    val y = reader.readFloat32()
                    reader.readU8() // rtl — no bidi reordering to give it
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = x,
                        y = y,
                        fontSize = DEFAULT_TEXT_SIZE_SP,
                        colorArgb = currentColor.toArgb(),
                        substringStart = start,
                        substringEnd = end,
                    )
                }

                OP_DRAW_TEXT_ANCHORED -> {
                    val textId = reader.readS32()
                    val x = resolveFloat(reader.readFloat32())
                    val y = resolveFloat(reader.readFloat32())
                    val panX = resolveFloat(reader.readFloat32())
                    val panY = resolveFloat(reader.readFloat32())
                    reader.readS32() // flags
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = x,
                        y = y,
                        fontSize = DEFAULT_TEXT_SIZE_SP,
                        colorArgb = currentColor.toArgb(),
                        panX = panX,
                        panY = panY,
                    )
                }

                OP_DRAW_TEXT_ON_CIRCLE -> {
                    val textId = reader.readS32()
                    val centerX = reader.readFloat32()
                    val centerY = reader.readFloat32()
                    val radius = reader.readFloat32()
                    val startAngleDegrees = reader.readFloat32()
                    reader.readFloat32() // warpRadiusOffset — only affects per-letter curvature
                    reader.readU8() // alignment — only affects per-letter curvature
                    reader.readU8() // placement — only affects per-letter curvature
                    val startAngleRadians = startAngleDegrees * (PI.toFloat() / 180f)
                    opcodes += Opcode.DrawText(
                        stringIndex = textId,
                        x = centerX + radius * cos(startAngleRadians),
                        y = centerY + radius * sin(startAngleRadians),
                        fontSize = DEFAULT_TEXT_SIZE_SP,
                        colorArgb = currentColor.toArgb(),
                    )
                }

                OP_DRAW_TEXT_ON_PATH -> {
                    val textId = reader.readS32()
                    val pathId = reader.readS32()
                    val vOffset = reader.readFloat32() // written before hOffset — see KDoc above
                    val hOffset = reader.readFloat32()
                    val anchor = pathPool[pathId]?.filterIsInstance<PathCommand.MoveTo>()?.firstOrNull()
                    if (anchor != null) {
                        opcodes += Opcode.DrawText(
                            stringIndex = textId,
                            x = anchor.x + hOffset,
                            y = anchor.y + vOffset,
                            fontSize = DEFAULT_TEXT_SIZE_SP,
                            colorArgb = currentColor.toArgb(),
                        )
                    }
                }

                OP_DRAW_LINE -> {
                    val x1 = reader.readFloat32()
                    val y1 = reader.readFloat32()
                    val x2 = reader.readFloat32()
                    val y2 = reader.readFloat32()
                    opcodes += Opcode.DrawLine(
                        x1, y1, x2, y2,
                        PaintStyle(currentColor, PaintStyleKind.STROKE),
                    )
                }

                OP_DRAW_OVAL -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.DrawOval(
                        left, top, right, bottom,
                        PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DRAW_ARC, OP_DRAW_SECTOR -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val startAngle = reader.readFloat32()
                    val sweepAngle = reader.readFloat32()
                    opcodes += Opcode.DrawArc(
                        left, top, right, bottom, startAngle, sweepAngle,
                        useCenter = opId == OP_DRAW_SECTOR,
                        paint = PaintStyle(currentColor, PaintStyleKind.FILL),
                    )
                }

                OP_DATA_PATH -> {
                    val pathId = reader.readS32()
                    val floatCount = reader.readS32()
                    pathPool[pathId] = decodePathArray(reader, floatCount)
                }

                OP_PATH_CREATE -> {
                    val pathId = reader.readS32()
                    val startX = resolveFloat(reader.readFloat32())
                    val startY = resolveFloat(reader.readFloat32())
                    pathPool[pathId] = listOf(PathCommand.MoveTo(startX, startY))
                }

                OP_PATH_ADD -> {
                    val pathId = reader.readS32()
                    val floatCount = reader.readS32()
                    val appended = decodePathArray(reader, floatCount)
                    pathPool[pathId] = (pathPool[pathId] ?: emptyList()) + appended
                }

                OP_PATH_TWEEN -> {
                    val outId = reader.readS32()
                    val pathId1 = reader.readS32()
                    val pathId2 = reader.readS32()
                    val tween = resolveFloat(reader.readFloat32())
                    lerpPath(pathPool[pathId1], pathPool[pathId2], tween)?.let { pathPool[outId] = it }
                }

                OP_MATRIX_FROM_PATH -> {
                    val pathId = reader.readS32()
                    val fraction = resolveFloat(reader.readFloat32())
                    val vOffset = resolveFloat(reader.readFloat32())
                    val flags = reader.readS32()
                    val posTan = pathPool[pathId]?.let { pointAndTangentAlongPath(it, fraction) }
                    if (posTan != null) {
                        val (px, py, tx, ty) = posTan
                        val len = sqrt(tx * tx + ty * ty).takeIf { it > 0f } ?: 1f
                        val perpX = -ty / len * vOffset
                        val perpY = tx / len * vOffset
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.Translate(px + perpX, py + perpY)
                        if (flags and 2 != 0) { // TANGENT_MATRIX_FLAG
                            opcodes += Opcode.Rotate(atan2(ty, tx) * 180f / PI.toFloat(), 0f, 0f)
                        }
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_DRAW_TWEEN_PATH -> {
                    val path1Id = reader.readS32()
                    val path2Id = reader.readS32()
                    val tween = resolveFloat(reader.readFloat32())
                    val start = resolveFloat(reader.readFloat32())
                    val stop = resolveFloat(reader.readFloat32())
                    val lerped = lerpPath(pathPool[path1Id], pathPool[path2Id], tween)
                    if (lerped != null) {
                        val trimmed = trimPath(lerped, start, stop)
                        if (trimmed.isNotEmpty()) {
                            opcodes += Opcode.DrawPath(trimmed, PaintStyle(currentColor, PaintStyleKind.FILL))
                        }
                    }
                }

                OP_PATH_COMBINE -> {
                    val outId = reader.readS32()
                    val pathId1 = reader.readS32()
                    val pathId2 = reader.readS32()
                    val operation = reader.readS8()
                    val path1 = pathPool[pathId1]
                    val path2 = pathPool[pathId2]
                    if (operation == 1 && path1 != null && path2 != null) { // OP_INTERSECT only
                        val subject = flattenPathSegments(path1).map { floatArrayOf(it[0], it[1]) }
                        val clip = flattenPathSegments(path2).map { floatArrayOf(it[0], it[1]) }
                        val result = sutherlandHodgmanIntersect(subject, clip)
                        if (result.size >= 3) {
                            pathPool[outId] = listOf(PathCommand.MoveTo(result[0][0], result[0][1])) +
                                result.drop(1).map { PathCommand.LineTo(it[0], it[1]) } +
                                listOf(PathCommand.Close)
                        }
                    }
                }

                OP_DRAW_PATH -> {
                    val pathId = reader.readS32()
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "DrawPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.DrawPath(commands, PaintStyle(currentColor, PaintStyleKind.FILL))
                }

                OP_CLIP_PATH -> {
                    val pathId = reader.readS32()
                    val commands = pathPool[pathId] ?: throw RemoteComposeParseException(
                        "ClipPath references path id $pathId which no prior DataPath defined",
                    )
                    opcodes += Opcode.ClipPath(commands)
                }

                OP_DATA_BITMAP -> {
                    val bitmapId = reader.readS32()
                    reader.readS32() // width — BitmapPool/decodeImageBitmap reads it back out of the PNG itself
                    reader.readS32() // height
                    val pngLength = reader.readS32()
                    bitmapPool[bitmapId] = reader.readBytes(pngLength)
                }

                OP_DRAW_BITMAP -> {
                    val bitmapId = reader.readS32()
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    reader.readS32() // content-description text-pool id — not needed for drawing
                    opcodes += Opcode.DrawBitmap(bitmapId, left, top, right, bottom)
                }

                OP_DRAW_BITMAP_INT -> {
                    val bitmapId = reader.readS32()
                    val srcLeft = reader.readS32().toFloat()
                    val srcTop = reader.readS32().toFloat()
                    val srcRight = reader.readS32().toFloat()
                    val srcBottom = reader.readS32().toFloat()
                    val dstLeft = reader.readS32().toFloat()
                    val dstTop = reader.readS32().toFloat()
                    val dstRight = reader.readS32().toFloat()
                    val dstBottom = reader.readS32().toFloat()
                    reader.readS32() // content-description text-pool id — not needed for drawing
                    opcodes += Opcode.DrawBitmap(
                        bitmapId, dstLeft, dstTop, dstRight, dstBottom,
                        srcLeft, srcTop, srcRight, srcBottom,
                    )
                }

                OP_DRAW_BITMAP_SCALED -> {
                    val bitmapId = reader.readS32()
                    val srcLeft = resolveFloat(reader.readFloat32())
                    val srcTop = resolveFloat(reader.readFloat32())
                    val srcRight = resolveFloat(reader.readFloat32())
                    val srcBottom = resolveFloat(reader.readFloat32())
                    val dstLeft = resolveFloat(reader.readFloat32())
                    val dstTop = resolveFloat(reader.readFloat32())
                    val dstRight = resolveFloat(reader.readFloat32())
                    val dstBottom = resolveFloat(reader.readFloat32())
                    val scaleType = reader.readS32()
                    resolveFloat(reader.readFloat32()) // scaleFactor — SCALE_FIXED_SCALE not modeled
                    reader.readS32() // content-description text-pool id — not needed for drawing
                    val srcWidth = (srcRight - srcLeft).toInt()
                    val srcHeight = (srcBottom - srcTop).toInt()
                    val realScaleTypes = scaleType == 0 || scaleType == 1 || scaleType == 4 || scaleType == 5
                    val dst = if (realScaleTypes) {
                        imageScaleDstRect(scaleType, srcWidth, srcHeight, dstLeft, dstTop, dstRight, dstBottom)
                    } else {
                        floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
                    }
                    opcodes += Opcode.MatrixSave
                    opcodes += Opcode.ClipRect(dstLeft, dstTop, dstRight, dstBottom)
                    opcodes += Opcode.DrawBitmap(
                        bitmapId, dst[0], dst[1], dst[2], dst[3],
                        srcLeft, srcTop, srcRight, srcBottom,
                    )
                    opcodes += Opcode.MatrixRestore
                }

                OP_CLICK_AREA -> {
                    val actionId = reader.readS32()
                    reader.readS32() // content-description text-pool id — not needed for hit-testing
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    val metadataTextId = reader.readS32()
                    opcodes += Opcode.ActionClick(actionId, metadataTextId, left, top, right, bottom)
                }

                OP_LAYOUT_COLUMN, OP_LAYOUT_ROW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val horizontalPositioning = reader.readS32()
                    val verticalPositioning = reader.readS32()
                    val spacedBy = reader.readFloat32()
                    pushScope() // this container's own scope — closed by its outermost CONTAINER_END
                    // Consumed by this container's own LAYOUT_CONTENT next, so its content frame
                    // (where the real children live) knows to arrange them — see arrangeChildren.
                    pendingLayoutAxis = if (opId == OP_LAYOUT_COLUMN) 'V' else 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                }

                OP_LAYOUT_COLLAPSIBLE_COLUMN, OP_LAYOUT_COLLAPSIBLE_ROW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val horizontalPositioning = reader.readS32()
                    val verticalPositioning = reader.readS32()
                    val spacedBy = reader.readFloat32()
                    pushScope() // this container's own scope — closed by its outermost CONTAINER_END
                    // Identical shape to OP_LAYOUT_COLUMN/OP_LAYOUT_ROW's own fields, and — since
                    // OP_LAYOUT_CONTENT is a single generic children-marker shared by every
                    // container type, not a Column/Row-specific one — the exact same
                    // pendingLayoutAxis handoff gives these real arrangement too, with no changes
                    // needed to arrangeChildren or OP_LAYOUT_CONTENT itself.
                    pendingLayoutAxis = if (opId == OP_LAYOUT_COLLAPSIBLE_COLUMN) 'V' else 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingIsCollapsible = true
                }

                OP_LAYOUT_FLOW -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val horizontalPositioning = reader.readS32()
                    val verticalPositioning = reader.readS32()
                    val spacedBy = reader.readFloat32()
                    val maxItemsInMainAxis = reader.readS32()
                    val maxLinesInCrossAxis = reader.readS32()
                    pushScope()
                    // FlowLayout extends RowLayout (source-confirmed via javap): the main axis is
                    // always horizontal, wrapping to a new line after maxItemsInMainAxis children —
                    // see arrangeChildren's flowMaxItemsPerLine handling for the actual wrapping.
                    pendingLayoutAxis = 'H'
                    pendingSpacedBy = spacedBy
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingFlowMaxItemsPerLine = maxItemsInMainAxis.takeIf { it > 0 && it < Int.MAX_VALUE }
                    // Real effect (javap-confirmed on FlowLayout's own measure logic): once a
                    // child's row index reaches maxLinesInCrossAxis, real Compose marks it
                    // Component.Visibility.GONE (no space reserved) rather than adding another
                    // row — see arrangeChildren's flowMaxLines handling for the hide mechanism.
                    pendingFlowMaxLines = maxLinesInCrossAxis.takeIf { it > 0 && it < Int.MAX_VALUE }
                }

                OP_LAYOUT_BOX, OP_LAYOUT_FIT_BOX -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val horizontalPositioning = reader.readS32()
                    val verticalPositioning = reader.readS32()
                    pushScope()
                    // Consumed by this container's own LAYOUT_CONTENT next, same handoff shape as
                    // LAYOUT_COLUMN/LAYOUT_ROW's own positioning — but Box has no main/cross axis
                    // (pendingLayoutAxis is deliberately left null), so arrangeChildren's *separate*
                    // per-child 2D-alignment pass applies these instead of the sequential-stacking
                    // packing loop Column/Row use — see its own KDoc for BoxLayout's real algorithm
                    // (source-confirmed via javap).
                    pendingHorizontalPositioning = horizontalPositioning
                    pendingVerticalPositioning = verticalPositioning
                    pendingIsBoxAlignment = true
                }

                OP_LAYOUT_TEXT -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val textId = reader.readS32()
                    val color = reader.readS32()
                    val fontSize = resolveFloat(reader.readFloat32())
                    reader.readS32() // fontStyle — byte-consumed only, no italic support
                    reader.readFloat32() // fontWeight — byte-consumed only, no bold support
                    reader.readS32() // fontFamilyId — byte-consumed only, no font-family support
                    val textAlign = reader.readS32() and 0xFFFF // packed; see OP_LAYOUT_TEXT's KDoc
                    reader.readS32() // overflow — byte-consumed only, no ellipsis/clip support
                    reader.readS32() // maxLines — byte-consumed only, no wrapping support
                    pushScope()
                    pendingIsTextLayout = true
                    pendingTextId = textId
                    pendingTextColorArgb = color
                    pendingTextFontSize = fontSize
                    pendingTextAlign = textAlign
                }

                OP_LAYOUT_ROOT -> {
                    reader.readS32() // componentId — no LAYOUT_CONTENT marker follows
                    pushScope() // closed by this container's single CONTAINER_END
                }

                OP_CANVAS_OPERATIONS -> pushScope() // no payload — closed by a single CONTAINER_END

                OP_SKIP -> {
                    val conditionType = reader.readS32()
                    val value = reader.readS32()
                    val skipLength = reader.readS32()
                    // This parser's own reported library API level — see OP_SKIP's own KDoc for
                    // why Int.MAX_VALUE ("assume the newest client") is the honest default.
                    val ourApiLevel = Int.MAX_VALUE
                    val needsToSkip = when (conditionType) {
                        1 -> ourApiLevel < value // SKIP_IF_API_LESS_THAN
                        2 -> ourApiLevel > value // SKIP_IF_API_GREATER_THAN
                        3 -> ourApiLevel == value // SKIP_IF_API_EQUAL_TO
                        4 -> ourApiLevel != value // SKIP_IF_API_NOT_EQUAL_TO
                        else -> false // SKIP_IF_PROFILE_INCLUDES/EXCLUDES — no profile concept here
                    }
                    if (needsToSkip) reader.seek(reader.position + skipLength)
                }

                OP_REM -> {
                    val length = reader.readS32()
                    reader.readUtf8(length) // a source comment — no visual effect to reproduce
                }

                OP_TEXT_LENGTH -> {
                    val lengthId = reader.readS32()
                    val textId = reader.readS32()
                    floatPool[lengthId] = (textPool[textId]?.length ?: 0).toFloat()
                }

                OP_ID_LIST -> {
                    val id = reader.readS32()
                    val count = reader.readS32()
                    idListPool[id] = List(count) { reader.readS32() }
                }

                OP_TEXT_LOOKUP -> {
                    val textId = reader.readS32()
                    val dataSetId = reader.readS32()
                    val index = resolveFloat(reader.readFloat32())
                    if (!index.isNaN()) {
                        idListPool[dataSetId]?.getOrNull(index.toInt())?.let { srcId ->
                            textPool[srcId]?.let { textPool[textId] = it }
                        }
                    }
                }

                OP_TEXT_LOOKUP_INT -> {
                    val textId = reader.readS32()
                    val dataSetId = reader.readS32()
                    val indexRefId = reader.readS32()
                    intPool[indexRefId]?.let { index ->
                        idListPool[dataSetId]?.getOrNull(index)?.let { srcId ->
                            textPool[srcId]?.let { textPool[textId] = it }
                        }
                    }
                }

                OP_TEXT_MERGE -> {
                    val textId = reader.readS32()
                    val srcId1 = reader.readS32()
                    val srcId2 = reader.readS32()
                    val left = textPool[srcId1] ?: ""
                    val right = textPool[srcId2] ?: ""
                    textPool[textId] = left + right
                }

                OP_COLOR_EXPRESSIONS -> {
                    val id = reader.readS32()
                    val modeAlpha = reader.readS32()
                    val mode = modeAlpha and 0xFF
                    val alpha = (modeAlpha ushr 16) and 0xFF
                    val word1 = reader.readS32()
                    val word2 = reader.readS32()
                    val word3 = reader.readS32()
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

                OP_ID_LOOKUP -> {
                    val intId = reader.readS32()
                    val dataSetId = reader.readS32()
                    val index = resolveFloat(reader.readFloat32())
                    if (!index.isNaN()) {
                        idListPool[dataSetId]?.getOrNull(index.toInt())?.let { intPool[intId] = it }
                    }
                }

                OP_INTEGER_EXPRESSION -> {
                    val id = reader.readS32()
                    val mask = reader.readS32()
                    val count = reader.readS32()
                    val values = IntArray(count) { reader.readS32() }
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

                OP_TEXT_FROM_FLOAT -> {
                    val textId = reader.readS32()
                    val value = resolveFloat(reader.readFloat32())
                    reader.readS32() // packed (digitsBefore shl 16) or digitsAfter — unused by FULL_FORMAT
                    val flags = reader.readS32()
                    if (!value.isNaN() && flags and 0x1000 != 0) {
                        textPool[textId] = value.toString()
                    }
                }

                OP_LOOP_START -> {
                    reader.readS32() // indexVariableId — no expression evaluator to feed it
                    val from = resolveFloat(reader.readFloat32())
                    val step = resolveFloat(reader.readFloat32())
                    val until = resolveFloat(reader.readFloat32())
                    pushScope() // no LAYOUT_CONTENT marker — closed by a single CONTAINER_END
                    scopeStack.last().isLoop = true
                    scopeStack.last().loopFrom = from.takeUnless { it.isNaN() }
                    scopeStack.last().loopStep = step.takeUnless { it.isNaN() }
                    scopeStack.last().loopUntil = until.takeUnless { it.isNaN() }
                }

                OP_LAYOUT_STATE -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // horizontalPositioning
                    reader.readS32() // verticalPositioning
                    reader.readS32() // stateIndex — a remote-variable id driving runtime state
                    // changes this parser has no live state to evaluate; the real default render
                    // (currentLayoutIndex=0, see ScopeFrame.isStateLayout's KDoc) needs none.
                    pushScope()
                    pendingIsStateLayout = true
                }

                OP_LAYOUT_CONTENT, OP_LAYOUT_CANVAS_CONTENT -> {
                    reader.readS32() // componentId
                    pushScope() // the children scope itself — this is where real children attach
                    // Assigned unconditionally (not gated on axis != null): a Box's own
                    // horizontalPositioning/verticalPositioning (from OP_LAYOUT_BOX) need to reach
                    // this frame too, for arrangeChildren's per-child 2D-alignment pass — see
                    // OP_LAYOUT_BOX's own KDoc.
                    scopeStack.last().horizontalPositioning = pendingHorizontalPositioning
                    scopeStack.last().verticalPositioning = pendingVerticalPositioning
                    scopeStack.last().isBoxAlignment = pendingIsBoxAlignment
                    pendingHorizontalPositioning = POS_START
                    pendingVerticalPositioning = POS_START
                    pendingIsBoxAlignment = false
                    scopeStack.last().isTextLayout = pendingIsTextLayout
                    scopeStack.last().textId = pendingTextId
                    scopeStack.last().textColorArgb = pendingTextColorArgb
                    scopeStack.last().textFontSize = pendingTextFontSize
                    scopeStack.last().textAlign = pendingTextAlign
                    pendingIsTextLayout = false
                    val axis = pendingLayoutAxis
                    if (axis != null) {
                        scopeStack.last().layoutAxis = axis
                        scopeStack.last().spacedBy = pendingSpacedBy
                        scopeStack.last().flowMaxItemsPerLine = pendingFlowMaxItemsPerLine
                        scopeStack.last().flowMaxLines = pendingFlowMaxLines
                        scopeStack.last().isCollapsible = pendingIsCollapsible
                        pendingLayoutAxis = null
                        pendingFlowMaxItemsPerLine = null
                        pendingFlowMaxLines = null
                        pendingIsCollapsible = false
                    }
                    scopeStack.last().isStateLayout = pendingIsStateLayout
                    pendingIsStateLayout = false
                }

                OP_LAYOUT_CANVAS -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    pushScope()
                }

                OP_LAYOUT_CUSTOM -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    reader.readS32() // nameTextId
                    val propertyCount = reader.readS32()
                    repeat(propertyCount) {
                        reader.readU16() // type
                        reader.readU16() // dataType
                        reader.readS32() // value (int or float bit pattern)
                    }
                    pushScope()
                }

                OP_LAYOUT_IMAGE -> {
                    reader.readS32() // componentId
                    reader.readS32() // animationId
                    val bitmapId = reader.readS32()
                    val scaleType = reader.readS32()
                    val alpha = reader.readFloat32()
                    pushScope() // a leaf — no LAYOUT_CONTENT, just its own single CONTAINER_END
                    scopeStack.last().imageBitmapId = bitmapId
                    scopeStack.last().imageAlpha = alpha
                    scopeStack.last().imageScaleType = scaleType
                }

                OP_HAPTIC_FEEDBACK -> reader.readS32() // hapticId

                OP_THEME -> reader.readS32() // theme

                OP_ROOT_CONTENT_BEHAVIOR -> repeat(4) { reader.readS32() }

                OP_DEBUG_MESSAGE -> {
                    reader.readS32() // textId — no visual effect to reproduce
                    reader.readFloat32() // floatValue
                    reader.readS32() // flags
                }

                OP_ANIMATION_SPEC -> {
                    reader.readS32() // animationId
                    reader.readFloat32() // motionDuration
                    reader.readS32() // motionEasingType
                    reader.readFloat32() // visibilityDuration
                    reader.readS32() // visibilityEasingType
                    reader.readS32() // enterAnimation
                    reader.readS32() // exitAnimation
                }

                OP_MODIFIER_WIDTH, OP_MODIFIER_HEIGHT -> {
                    val mode = reader.readS32()
                    val value = resolveFloat(reader.readFloat32())
                    val frame = scopeStack.lastOrNull()
                    // Only a real target size (not a sizing *strategy* like FILL/WRAP this parser
                    // has no layout pass to resolve) is useful to arrangeChildren's main-axis
                    // CENTER/END/SPACE_* modes — see ScopeFrame.explicitWidthPx's KDoc.
                    if (mode == DIMENSION_MODE_EXACT || mode == DIMENSION_MODE_EXACT_DP) {
                        if (opId == OP_MODIFIER_WIDTH) frame?.explicitWidthPx = value
                        else frame?.explicitHeightPx = value
                    } else if (mode == DIMENSION_MODE_WEIGHT) {
                        // A real weight value (not a sizing strategy) — see ScopeFrame.widthWeight
                        // /heightWeight's KDoc and arrangeChildren's real effect for it.
                        if (opId == OP_MODIFIER_WIDTH) frame?.widthWeight = value
                        else frame?.heightWeight = value
                    }
                }

                OP_CONTAINER_END -> {
                    // Close this scope: if it carried a MODIFIER_BACKGROUND, insert an inferred
                    // background DrawRect *before* this container's own content (everything
                    // appended to [opcodes] since [ScopeFrame.startIndex]); if it's a LAYOUT_COLUMN/
                    // LAYOUT_ROW content frame, arrange its now-final children in place; then append
                    // this scope's deferred cleanup (e.g. a MatrixRestore queued by MODIFIER_OFFSET/
                    // MODIFIER_VISIBILITY/MODIFIER_GRAPHICS_LAYER) right after that content, closing
                    // whatever that modifier opened around it; and finally, if this scope's own
                    // parent is itself a LAYOUT_COLUMN/LAYOUT_ROW content frame, register this now-
                    // finished [startIndex, opcodes.size) range as one of *its* children.
                    val frame = scopeStack.removeLastOrNull()
                    if (frame != null) {
                        val bg = frame.backgroundColor
                        if (bg != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val left = bounds[0] - frame.paddingLeft
                                val top = bounds[1] - frame.paddingTop
                                val right = bounds[2] + frame.paddingRight
                                val bottom = bounds[3] + frame.paddingBottom
                                val paint = PaintStyle(bg, PaintStyleKind.FILL)
                                opcodes.add(
                                    frame.startIndex,
                                    // shapeType 1 (CIRCLE, javap-confirmed on the real
                                    // BackgroundModifierOperation.paint()) draws a filled oval
                                    // inscribed in the box instead of a rect — the same
                                    // shapeType-gated shape choice OP_MODIFIER_BORDER's stroke
                                    // already makes below.
                                    if (frame.backgroundShapeType == 1) {
                                        Opcode.DrawOval(left, top, right, bottom, paint)
                                    } else {
                                        Opcode.DrawRect(left, top, right, bottom, paint)
                                    },
                                )
                            }
                        }
                        // Always called (not gated on frame.layoutAxis): a Box's own inner
                        // content frame has no layoutAxis, so position-arrangement inside it is a
                        // no-op, but its registered children still need MODIFIER_ZINDEX's
                        // paint-order reordering — see arrangeChildren's own gating on axis.
                        arrangeChildren(frame)
                        // MODIFIER_BORDER: a real stroked-outline effect, drawn *on top of* this
                        // container's now-finished content (appended, not inserted at
                        // frame.startIndex like the background fill above) around the same
                        // contentBounds()-inferred, padding-expanded box the background uses.
                        // shapeType 1 (CIRCLE) draws a stroked oval inscribed in that box instead
                        // of a rect/round-rect.
                        val border = frame.borderColor
                        if (border != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val left = bounds[0] - frame.paddingLeft
                                val top = bounds[1] - frame.paddingTop
                                val right = bounds[2] + frame.paddingRight
                                val bottom = bounds[3] + frame.paddingBottom
                                val paint = PaintStyle(border, PaintStyleKind.STROKE, frame.borderWidth)
                                opcodes += if (frame.borderShapeType == 1) {
                                    Opcode.DrawOval(left, top, right, bottom, paint)
                                } else if (frame.borderRoundedCorner > 0f) {
                                    Opcode.DrawRoundRect(
                                        left, top, right, bottom,
                                        frame.borderRoundedCorner, frame.borderRoundedCorner,
                                        paint,
                                    )
                                } else {
                                    Opcode.DrawRect(left, top, right, bottom, paint)
                                }
                            }
                        }
                        // LAYOUT_IMAGE carries no position/size of its own, so real rendering only
                        // happens when an explicit MODIFIER_WIDTH/HEIGHT on the same modifier gave
                        // this frame a real box to draw into — the same real-vs-byte-consumed-only
                        // gate MODIFIER_GRAPHICS_LAYER's scale/rotation use below, for the same
                        // "no measure pass" reason. Drawn at this frame's own local origin (0,0),
                        // same as every other leaf here that has no absolute position of its own
                        // to inherit from anywhere but an enclosing MODIFIER_OFFSET/Translate.
                        val imageBitmapId = frame.imageBitmapId
                        val imageWidth = frame.explicitWidthPx
                        val imageHeight = frame.explicitHeightPx
                        if (imageBitmapId != null && imageWidth != null && imageHeight != null) {
                            val wrapAlpha = frame.imageAlpha < 1f
                            if (wrapAlpha) opcodes += Opcode.SaveLayerAlpha(frame.imageAlpha)
                            val naturalSize = bitmapPool[imageBitmapId]?.let { pngNaturalSize(it) }
                            var clipWrappedImage = false
                            val realScaleTypes = frame.imageScaleType == 0 || frame.imageScaleType == 1 ||
                                frame.imageScaleType == 4 || frame.imageScaleType == 5
                            if (naturalSize != null && realScaleTypes) {
                                val dst = imageScaleDstRect(
                                    frame.imageScaleType, naturalSize[0], naturalSize[1],
                                    0f, 0f, imageWidth, imageHeight,
                                )
                                if (frame.imageScaleType == 0 || frame.imageScaleType == 5) {
                                    // SCALE_NONE (an oversized natural bitmap) / SCALE_CROP (one
                                    // axis always) can overflow this box — real Compose's own
                                    // Image/Modifier.paint clips automatically whenever a
                                    // mismatched contentScale overflows the layout box. Harmless
                                    // (a no-op clip) when SCALE_NONE's natural size already fits.
                                    opcodes += Opcode.MatrixSave
                                    opcodes += Opcode.ClipRect(0f, 0f, imageWidth, imageHeight)
                                    clipWrappedImage = true
                                }
                                opcodes += Opcode.DrawBitmap(imageBitmapId, dst[0], dst[1], dst[2], dst[3])
                            } else {
                                opcodes += Opcode.DrawBitmap(imageBitmapId, 0f, 0f, imageWidth, imageHeight)
                            }
                            if (clipWrappedImage) opcodes += Opcode.MatrixRestore
                            if (wrapAlpha) opcodes += Opcode.MatrixRestore
                        }
                        // LAYOUT_TEXT: this leaf's own content frame is always empty (see
                        // OP_LAYOUT_TEXT's own KDoc), so the one real Opcode.DrawText it renders as
                        // is synthesized here rather than reacting to already-emitted children.
                        // textAlign only gets a real horizontal-alignment effect when this leaf's
                        // *outer* frame (frame.parent — the one OP_LAYOUT_TEXT itself pushed, which
                        // any MODIFIER_WIDTH/MODIFIER_DIMENSION_CONSTRAINTS on this same component
                        // sets explicitWidthPx on, same as OP_LAYOUT_BOX's own boxWidth lookup)
                        // actually declares a width to align within; otherwise it's real byte-
                        // coverage only, same honest gate MODIFIER_WIDTH_IN's own effect needs.
                        if (frame.isTextLayout) {
                            val text = textPool[frame.textId] ?: ""
                            val estimatedWidth = text.length * frame.textFontSize * 0.55f
                            val boxWidth = frame.parent?.explicitWidthPx
                            val alignOffsetX = if (boxWidth != null) {
                                val posMode = when (frame.textAlign) {
                                    3 -> POS_CENTER // TEXT_ALIGN_CENTER
                                    2, 6 -> POS_END // TEXT_ALIGN_RIGHT/END
                                    else -> POS_START // LEFT/START/JUSTIFY (no wrap algorithm here)
                                }
                                crossAxisOffset(posMode, boxWidth, estimatedWidth)
                            } else {
                                0f
                            }
                            opcodes += Opcode.DrawText(
                                stringIndex = frame.textId,
                                x = alignOffsetX,
                                y = 0f,
                                fontSize = frame.textFontSize,
                                colorArgb = frame.textColorArgb,
                            )
                        }
                        // MODIFIER_GRAPHICS_LAYER's SHAPE/SHAPE_RADIUS: a real clip, nested
                        // *innermost* (inserted at frame.startIndex first, so the transform-wrap
                        // below — inserted at the same index afterward — ends up outside it),
                        // matching real Compose's own order: a layer clips its own local content
                        // before the whole clipped result is scaled/rotated/translated as a unit.
                        var shapeClipWrapped = false
                        val shapeType = frame.glShapeType
                        if (shapeType != null && shapeType != 0) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val radius = if (shapeType == 2) {
                                    minOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) / 2f
                                } else {
                                    frame.glShapeRadius
                                }
                                opcodes.addAll(
                                    frame.startIndex,
                                    listOf(
                                        Opcode.MatrixSave,
                                        Opcode.ClipPath(
                                            roundedRectPath(
                                                bounds[0], bounds[1], bounds[2], bounds[3],
                                                radius, radius, radius, radius,
                                            ),
                                        ),
                                    ),
                                )
                                shapeClipWrapped = true
                            }
                        }
                        if (shapeClipWrapped) opcodes += Opcode.MatrixRestore
                        // MODIFIER_GRAPHICS_LAYER's SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/
                        // TRANSLATION_Y: wrap this frame's now-finished content (background
                        // included, since a real graphicsLayer transform applies to the whole
                        // composable box) in MatrixSave/Translate/Rotate/Scale/MatrixRestore,
                        // pivoting scale/rotation at a fraction (TRANSFORM_ORIGIN_X/_Y, default
                        // 0.5f — real Compose's own TransformOrigin.Center) of the inferred
                        // content-bounds box — the same bounds approximation MODIFIER_BACKGROUND
                        // uses, since this renderer has no measure pass to get a real layout box
                        // from instead. The MatrixRestore is appended *after* [frame.cleanupOpcodes]
                        // below (not here) so it closes outermost, keeping this the outermost
                        // save/restore pair around any MODIFIER_OFFSET/VISIBILITY/
                        // GRAPHICS_LAYER-ALPHA opened earlier inside this same frame.
                        var transformWrapped = false
                        val sx = frame.glScaleX
                        val sy = frame.glScaleY
                        val rz = frame.glRotationZ
                        val tx = frame.glTranslationX
                        val ty = frame.glTranslationY
                        if (sx != null || sy != null || rz != null || tx != null || ty != null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val originXFraction = frame.glTransformOriginX ?: 0.5f
                                val originYFraction = frame.glTransformOriginY ?: 0.5f
                                val pivotX = bounds[0] + originXFraction * (bounds[2] - bounds[0])
                                val pivotY = bounds[1] + originYFraction * (bounds[3] - bounds[1])
                                val wrap = mutableListOf<Opcode>(Opcode.MatrixSave)
                                if (tx != null || ty != null) wrap += Opcode.Translate(tx ?: 0f, ty ?: 0f)
                                if (rz != null) wrap += Opcode.Rotate(rz, pivotX, pivotY)
                                if (sx != null || sy != null) wrap += Opcode.Scale(sx ?: 1f, sy ?: 1f, pivotX, pivotY)
                                opcodes.addAll(frame.startIndex, wrap)
                                transformWrapped = true
                            }
                        }
                        opcodes.addAll(frame.cleanupOpcodes)
                        if (transformWrapped) opcodes += Opcode.MatrixRestore
                        // MODIFIER_WIDTH_IN/MODIFIER_HEIGHT_IN: resolve against this frame's own
                        // inferred content bounds now that its content is finished — narrower than
                        // min raises the effective declared size (visible to a parent Row/Column's
                        // arrangement below, the same as an explicit width()/height() would be);
                        // wider than max clips it, the same real "cut off the overflow" effect
                        // MODIFIER_CLIP_RECT gets, without a document needing a separate clip(...)
                        // call — real Compose's widthIn/heightIn imply their own clip. Only applied
                        // when no exact width()/height() already set explicitWidthPx/HeightPx,
                        // since that's a stronger, unambiguous declaration.
                        var clipImpliedByRangeConstraint = false
                        val widthInMin = frame.widthInMin
                        val widthInMax = frame.widthInMax
                        if ((widthInMin != null || widthInMax != null) && frame.explicitWidthPx == null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val naturalWidth = bounds[2] - bounds[0]
                                val clamped = naturalWidth.coerceIn(widthInMin ?: 0f, widthInMax ?: Float.MAX_VALUE)
                                if (clamped != naturalWidth) {
                                    frame.explicitWidthPx = clamped
                                    if (widthInMax != null && naturalWidth > widthInMax) clipImpliedByRangeConstraint = true
                                }
                            }
                        }
                        val heightInMin = frame.heightInMin
                        val heightInMax = frame.heightInMax
                        if ((heightInMin != null || heightInMax != null) && frame.explicitHeightPx == null) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val naturalHeight = bounds[3] - bounds[1]
                                val clamped = naturalHeight.coerceIn(heightInMin ?: 0f, heightInMax ?: Float.MAX_VALUE)
                                if (clamped != naturalHeight) {
                                    frame.explicitHeightPx = clamped
                                    if (heightInMax != null && naturalHeight > heightInMax) clipImpliedByRangeConstraint = true
                                }
                            }
                        }
                        // MODIFIER_CLIP_RECT/MODIFIER_ROUNDED_CLIP_RECT: real only when this frame
                        // also has an explicit width/height smaller than its natural content —
                        // clipping to the *inferred* (natural) bounds alone would be a no-op, since
                        // by construction nothing in the content extends past its own bounding box.
                        // The outermost wrap (inserted after the graphics-layer transform above,
                        // so it applies in this container's own *un-transformed* layout space, the
                        // same space its declared width/height is measured in) — a real "cut off
                        // the overflow" effect, not just a byte-skip.
                        var clipWrapped = false
                        if ((frame.hasClipRect || clipImpliedByRangeConstraint) &&
                            (frame.explicitWidthPx != null || frame.explicitHeightPx != null)
                        ) {
                            contentBounds(opcodes.subList(frame.startIndex, opcodes.size))?.let { bounds ->
                                val clipRight = frame.explicitWidthPx?.let { bounds[0] + it } ?: bounds[2]
                                val clipBottom = frame.explicitHeightPx?.let { bounds[1] + it } ?: bounds[3]
                                val clipShape = if (
                                    frame.cornerTopStart > 0f || frame.cornerTopEnd > 0f ||
                                    frame.cornerBottomStart > 0f || frame.cornerBottomEnd > 0f
                                ) {
                                    Opcode.ClipPath(
                                        roundedRectPath(
                                            bounds[0], bounds[1], clipRight, clipBottom,
                                            frame.cornerTopStart, frame.cornerTopEnd,
                                            frame.cornerBottomStart, frame.cornerBottomEnd,
                                        ),
                                    )
                                } else {
                                    Opcode.ClipRect(bounds[0], bounds[1], clipRight, clipBottom)
                                }
                                opcodes.addAll(
                                    frame.startIndex,
                                    listOf(Opcode.MatrixSave, clipShape),
                                )
                                clipWrapped = true
                            }
                        }
                        if (clipWrapped) opcodes += Opcode.MatrixRestore
                        val parent = frame.parent
                        if (parent != null) {
                            // Real LOOP_START unrolling (see its own KDoc): a static, non-empty
                            // range clones this frame's one authored body that many times, each
                            // clone registered as its own independent sibling below — real
                            // Compose's own per-iteration re-apply(), just unrolled at parse time
                            // instead of re-walked live. Every other frame (loopFrom == null) — and
                            // a variable-driven loop, honestly falling back to rendering once —
                            // takes repeatCount == 1, reducing to plain single registration, the
                            // exact behavior every non-loop frame already had.
                            val loopFrom = frame.loopFrom
                            val loopStep = frame.loopStep
                            val loopUntil = frame.loopUntil
                            val repeatCount = if (
                                frame.isLoop && loopFrom != null && loopStep != null && loopUntil != null &&
                                loopStep > 0f && loopFrom < loopUntil
                            ) {
                                ceil((loopUntil - loopFrom) / loopStep).toInt().coerceIn(0, 64)
                            } else {
                                1
                            }
                            val bodyStart = frame.startIndex
                            val bodyTemplate = opcodes.subList(bodyStart, opcodes.size).toList()
                            if (repeatCount <= 0) {
                                // A real "loop never runs" range (from >= until): this frame's one
                                // authored body copy was never actually meant to render at all.
                                while (opcodes.size > bodyStart) opcodes.removeAt(opcodes.size - 1)
                            } else {
                                val expectedOrientation = if (parent.layoutAxis == 'V') 1 else 0
                                fun registerChild(range: IntArray) {
                                    // Registered regardless of parent.layoutAxis: a Box's children
                                    // need this too, just for MODIFIER_ZINDEX's paint-order
                                    // reordering below rather than arrangeChildren's position-
                                    // arrangement (Box already paints children at their own
                                    // document-authored position).
                                    parent.childRanges.add(range)
                                    parent.childZIndices.add(frame.zIndex)
                                    // Resolved against *this* registration's own parent axis now,
                                    // while both this frame's own collapsiblePriorityOrientation
                                    // and the parent's layoutAxis are known —
                                    // CollapsiblePriority.getPriority's real orientation filter
                                    // (javap-confirmed): a priority whose own orientation doesn't
                                    // match is treated as Float.MAX_VALUE (never collapse), same
                                    // as no modifier at all.
                                    parent.childCollapsiblePriorities.add(
                                        if (frame.collapsiblePriorityOrientation == expectedOrientation) {
                                            frame.collapsiblePriority
                                        } else {
                                            Float.MAX_VALUE
                                        },
                                    )
                                    // A widthWeight only applies in an 'H' (Row-axis) parent, a
                                    // heightWeight only in a 'V' one — same axis-matching rationale
                                    // as collapsiblePriority's own orientation filter above.
                                    parent.childWeights.add(
                                        if (parent.layoutAxis == 'V') frame.heightWeight else frame.widthWeight,
                                    )
                                }
                                registerChild(intArrayOf(bodyStart, opcodes.size))
                                repeat(repeatCount - 1) {
                                    val cloneStart = opcodes.size
                                    opcodes.addAll(bodyTemplate)
                                    registerChild(intArrayOf(cloneStart, opcodes.size))
                                }
                            }
                        }
                    }
                }

                OP_MODIFIER_CLICK -> pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                OP_HOST_ACTION -> reader.readS32() // actionId

                OP_MODIFIER_PADDING -> {
                    // A real semantic effect (not just a byte-skip): translates every subsequent
                    // draw belonging to this container's children inward, undone at this
                    // container's own closing CONTAINER_END — the same mechanism MODIFIER_OFFSET
                    // uses. right/bottom are stashed too (not applied as a translate themselves —
                    // padding only insets from the top-left, the same as MODIFIER_OFFSET's own
                    // two-float shape has no separate "how much smaller" concept) so
                    // OP_CONTAINER_END's MODIFIER_BACKGROUND handling can expand the inferred
                    // background rect back out to cover the full un-padded box, matching the
                    // classic "colored margin around padded content" look real Compose gives
                    // `Modifier.background(color).padding(...)`.
                    val left = resolveFloat(reader.readFloat32())
                    val top = resolveFloat(reader.readFloat32())
                    val right = resolveFloat(reader.readFloat32())
                    val bottom = resolveFloat(reader.readFloat32())
                    val frame = scopeStack.lastOrNull()
                    frame?.paddingLeft = left
                    frame?.paddingTop = top
                    frame?.paddingRight = right
                    frame?.paddingBottom = bottom
                    if (left != 0f || top != 0f) {
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.Translate(left, top)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_MODIFIER_BACKGROUND -> {
                    repeat(4) { reader.readS32() } // [colorIdFlag][colorId][0][0] — dynamic-color-by-id path, unused here
                    val r = reader.readFloat32()
                    val g = reader.readFloat32()
                    val b = reader.readFloat32()
                    val a = reader.readFloat32()
                    val shapeType = reader.readS32() // 0=RECTANGLE, 1=CIRCLE
                    val frame = scopeStack.lastOrNull()
                    frame?.backgroundColor = Color(r, g, b, a)
                    frame?.backgroundShapeType = shapeType
                }

                OP_MODIFIER_VISIBILITY -> {
                    // Component.Visibility: GONE=0, VISIBLE=1, INVISIBLE=2. Anything but VISIBLE
                    // is rendered here as an empty clip around this container's children (the
                    // executor already implements OP_CLIP_RECT via Skia's real clip stack, and an
                    // empty rect makes every subsequent draw inside it a no-op) — a real semantic
                    // effect, not just a byte-skip, though it doesn't distinguish GONE (no space
                    // reserved) from INVISIBLE (space reserved) since this renderer has no layout
                    // pass to reserve space with.
                    val visibility = reader.readS32()
                    if (visibility != 1) {
                        opcodes += Opcode.MatrixSave
                        opcodes += Opcode.ClipRect(0f, 0f, 0f, 0f)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_MODIFIER_OFFSET -> {
                    // A real semantic effect (not just a byte-skip): translates every subsequent
                    // draw belonging to this container's children, undone at this container's own
                    // closing CONTAINER_END via the scope stack above.
                    val x = reader.readFloat32()
                    val y = reader.readFloat32()
                    opcodes += Opcode.MatrixSave
                    opcodes += Opcode.Translate(x, y)
                    attachToTopScope(Opcode.MatrixRestore)
                }

                OP_MODIFIER_BORDER -> {
                    val colorRefFlag = reader.readS32()
                    val colorId = reader.readS32() // only meaningful when colorRefFlag == 2
                    reader.readS32() // legacy flag
                    reader.readS32() // reserved — always a literal 0 on the wire
                    val borderWidth = reader.readFloat32()
                    val roundedCorner = reader.readFloat32()
                    val r = reader.readFloat32()
                    val g = reader.readFloat32()
                    val b = reader.readFloat32()
                    val a = reader.readFloat32()
                    val shapeType = reader.readS32()
                    val resolvedColor = if (colorRefFlag == 2) colorPool[colorId] else Color(r, g, b, a)
                    if (resolvedColor != null) {
                        val frame = scopeStack.lastOrNull()
                        frame?.borderColor = resolvedColor
                        frame?.borderWidth = borderWidth
                        frame?.borderRoundedCorner = roundedCorner
                        frame?.borderShapeType = shapeType
                    }
                }

                OP_DATA_FLOAT -> {
                    val id = reader.readS32()
                    val value = reader.readFloat32()
                    floatPool[id] = value
                }

                OP_DATA_INT -> {
                    val id = reader.readS32()
                    val value = reader.readS32()
                    intPool[id] = value
                }

                OP_DATA_BOOLEAN -> {
                    val id = reader.readS32()
                    val value = reader.readU8() != 0
                    booleanPool[id] = value
                }

                OP_DATA_LONG -> {
                    val id = reader.readS32()
                    val value = reader.readS64()
                    longPool[id] = value
                }

                OP_COLOR_CONSTANT -> {
                    val colorId = reader.readS32()
                    val colorArgb = reader.readS32()
                    colorPool[colorId] = Color(colorArgb)
                }

                OP_MODIFIER_CLIP_RECT -> scopeStack.lastOrNull()?.hasClipRect = true

                OP_MODIFIER_ROUNDED_CLIP_RECT -> {
                    val topStart = resolveFloat(reader.readFloat32())
                    val topEnd = resolveFloat(reader.readFloat32())
                    val bottomStart = resolveFloat(reader.readFloat32())
                    val bottomEnd = resolveFloat(reader.readFloat32())
                    val frame = scopeStack.lastOrNull()
                    frame?.hasClipRect = true
                    frame?.cornerTopStart = topStart
                    frame?.cornerTopEnd = topEnd
                    frame?.cornerBottomStart = bottomStart
                    frame?.cornerBottomEnd = bottomEnd
                }

                OP_MODIFIER_MULTI_CLICK -> {
                    reader.readS32() // clickType
                    pushScope() // opens a nested action list, closed by its own CONTAINER_END
                }

                OP_MODIFIER_TOUCH_DOWN, OP_MODIFIER_TOUCH_UP, OP_MODIFIER_TOUCH_CANCEL ->
                    pushScope() // no payload — opens a nested action list, closed by its own CONTAINER_END

                OP_MODIFIER_WIDTH_IN, OP_MODIFIER_HEIGHT_IN -> {
                    val min = reader.readFloat32()
                    val max = reader.readFloat32()
                    val frame = scopeStack.lastOrNull()
                    if (opId == OP_MODIFIER_WIDTH_IN) {
                        frame?.widthInMin = min
                        frame?.widthInMax = max
                    } else {
                        frame?.heightInMin = min
                        frame?.heightInMax = max
                    }
                }

                OP_MODIFIER_COLLAPSIBLE_PRIORITY -> {
                    val orientation = reader.readS32()
                    val priority = resolveFloat(reader.readFloat32())
                    val frame = scopeStack.lastOrNull()
                    frame?.collapsiblePriority = priority
                    frame?.collapsiblePriorityOrientation = orientation
                }

                OP_MODIFIER_ALIGN_BY -> {
                    reader.readFloat32() // line (NaN-tagged baseline-kind constant)
                    reader.readS32() // flag
                }

                OP_MODIFIER_ZINDEX -> {
                    val zIndex = reader.readFloat32()
                    scopeStack.lastOrNull()?.zIndex = zIndex
                }

                OP_MODIFIER_RIPPLE -> Unit // no payload

                OP_MODIFIER_DRAW_CONTENT -> Unit // no payload

                OP_MODIFIER_MARQUEE -> {
                    reader.readS32() // iterations
                    reader.readS32() // animationMode
                    repeat(4) { reader.readFloat32() } // repeatDelay, initialDelay, spacing, velocity
                }

                OP_MODIFIER_SCROLL -> {
                    reader.readS32() // direction — 0=VERTICAL, 1=HORIZONTAL
                    reader.readFloat32() // positionExpression — a live touch-driven runtime
                    reader.readFloat32() // max — variable this parser has no state/expression
                    reader.readFloat32() // notchMax — system to evaluate; see KDoc above
                }

                OP_TOUCH_EXPRESSION -> {
                    reader.readS32() // id
                    reader.readFloat32() // defValue
                    reader.readFloat32() // min
                    reader.readFloat32() // max
                    reader.readFloat32() // velocity
                    reader.readS32() // flags
                    val srcExpLength = reader.readS32()
                    repeat(srcExpLength) { reader.readFloat32() }
                    val packed = reader.readS32()
                    val tapExpLength = packed and 0xFFFF
                    repeat(tapExpLength) { reader.readFloat32() }
                    val tapExpFloatsLength = reader.readS32()
                    repeat(tapExpFloatsLength) { reader.readFloat32() }
                }

                OP_MODIFIER_GRAPHICS_LAYER -> {
                    // Real semantic effect for ALPHA (opens a real compositing layer around this
                    // container's children, closed by a MatrixRestore queued on this container's
                    // own scope — the same mechanism MODIFIER_OFFSET/MODIFIER_VISIBILITY use),
                    // SCALE_X/SCALE_Y/ROTATION_Z/TRANSLATION_X/TRANSLATION_Y, TRANSFORM_ORIGIN_X/_Y,
                    // and SHAPE/SHAPE_RADIUS (all stashed on this container's own [ScopeFrame],
                    // applied at its OP_CONTAINER_END once real bounds/a real pivot can be inferred
                    // — see the frame's `glScaleX`/`glTransformOriginX`/`glShapeType` etc. KDoc).
                    // Every other attribute (shadow/blur/camera distance/etc.) is still just
                    // byte-consumed, since those have no equivalent among this renderer's Opcodes.
                    val count = reader.readS32()
                    var alpha: Float? = null
                    repeat(count) {
                        val tag = reader.readS32() // attribute key, OR'd with 0x400 if float-valued
                        val rawValue = reader.readS32() // int or float bit pattern
                        when (tag) {
                            GRAPHICS_LAYER_ALPHA_TAG -> alpha = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SCALE_X_TAG -> scopeStack.lastOrNull()?.glScaleX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SCALE_Y_TAG -> scopeStack.lastOrNull()?.glScaleY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_ROTATION_Z_TAG -> scopeStack.lastOrNull()?.glRotationZ = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSLATION_X_TAG -> scopeStack.lastOrNull()?.glTranslationX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSLATION_Y_TAG -> scopeStack.lastOrNull()?.glTranslationY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSFORM_ORIGIN_X_TAG -> scopeStack.lastOrNull()?.glTransformOriginX = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_TRANSFORM_ORIGIN_Y_TAG -> scopeStack.lastOrNull()?.glTransformOriginY = Float.fromBits(rawValue)
                            GRAPHICS_LAYER_SHAPE_TAG -> scopeStack.lastOrNull()?.glShapeType = rawValue // int, not bit-reinterpreted
                            GRAPHICS_LAYER_SHAPE_RADIUS_TAG -> scopeStack.lastOrNull()?.glShapeRadius = Float.fromBits(rawValue)
                        }
                    }
                    alpha?.let {
                        opcodes += Opcode.SaveLayerAlpha(it)
                        attachToTopScope(Opcode.MatrixRestore)
                    }
                }

                OP_MODIFIER_DIMENSION_CONSTRAINTS -> {
                    val type = reader.readS8()
                    val min = resolveFloat(reader.readFloat32())
                    val max = resolveFloat(reader.readFloat32())
                    val frame = scopeStack.lastOrNull()
                    when (type) {
                        // HORIZONTAL_CONSTRAINTS(0)/REQUIRED_HORIZONTAL_CONSTRAINTS(2): same real
                        // effect OP_MODIFIER_WIDTH_IN already gets — see ScopeFrame.widthInMin's
                        // KDoc and OP_CONTAINER_END's clamp/clip handling for it.
                        0, 2 -> {
                            frame?.widthInMin = min
                            frame?.widthInMax = max
                        }
                        // VERTICAL_CONSTRAINTS(1)/REQUIRED_VERTICAL_CONSTRAINTS(3): same real
                        // effect OP_MODIFIER_HEIGHT_IN already gets.
                        1, 3 -> {
                            frame?.heightInMin = min
                            frame?.heightInMax = max
                        }
                    }
                }

                OP_VALUE_INTEGER_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // value
                }

                OP_VALUE_STRING_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // stringId (text-pool reference)
                }

                OP_VALUE_FLOAT_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readFloat32() // value
                }

                OP_VALUE_INTEGER_EXPRESSION_CHANGE -> {
                    reader.readS64() // valueId
                    reader.readS64() // value
                }

                OP_VALUE_FLOAT_EXPRESSION_CHANGE -> {
                    reader.readS32() // valueId
                    reader.readS32() // value (expression/id reference)
                }

                OP_MATRIX_SAVE -> opcodes += Opcode.MatrixSave

                OP_MATRIX_RESTORE -> opcodes += Opcode.MatrixRestore

                OP_MATRIX_TRANSLATE -> {
                    val dx = reader.readFloat32()
                    val dy = reader.readFloat32()
                    opcodes += Opcode.Translate(dx, dy)
                }

                OP_MATRIX_SCALE -> {
                    val sx = reader.readFloat32()
                    val sy = reader.readFloat32()
                    val pivotX = reader.readFloat32()
                    val pivotY = reader.readFloat32()
                    opcodes += Opcode.Scale(sx, sy, pivotX, pivotY)
                }

                OP_MATRIX_ROTATE -> {
                    val degrees = reader.readFloat32()
                    val pivotX = reader.readFloat32()
                    val pivotY = reader.readFloat32()
                    opcodes += Opcode.Rotate(degrees, pivotX, pivotY)
                }

                OP_MATRIX_SKEW -> {
                    val skewX = reader.readFloat32()
                    val skewY = reader.readFloat32()
                    opcodes += Opcode.Skew(skewX, skewY)
                }

                OP_CLIP_RECT -> {
                    val left = reader.readFloat32()
                    val top = reader.readFloat32()
                    val right = reader.readFloat32()
                    val bottom = reader.readFloat32()
                    opcodes += Opcode.ClipRect(left, top, right, bottom)
                }

                else -> throw RemoteComposeParseException(
                    "Real opcode $opId is outside the minimal subset this demo parser supports " +
                        "(Header/DataText/RootContentDescription/PaintBundle/DrawRect/DrawCircle/" +
                        "DrawRoundRect/DrawTextAnchored/DrawLine/DrawOval/DrawArc/DrawSector/" +
                        "DataPath/DrawPath/DataBitmap/DrawBitmap/ClickArea/LayoutColumn/LayoutRow/" +
                        "LayoutCollapsibleColumn/LayoutCollapsibleRow/LayoutFlow/LayoutFitBox/" +
                        "LayoutRoot/LayoutState/LayoutCanvas/LayoutCanvasContent/LayoutCustom/" +
                        "LayoutImage/HapticFeedback/Theme/RootContentBehavior/AnimationSpec/" +
                        "LayoutBox/LayoutContent/ContainerEnd/ModifierWidth/ModifierHeight/" +
                        "ModifierClick/HostAction/ModifierPadding/ModifierBackground/" +
                        "ModifierVisibility/ModifierOffset/ModifierBorder/ModifierClipRect/" +
                        "ModifierRoundedClipRect/ModifierMultiClick/ModifierTouchDown/" +
                        "ModifierTouchUp/ModifierTouchCancel/ModifierWidthIn/ModifierHeightIn/" +
                        "ModifierCollapsiblePriority/ModifierAlignBy/ModifierZIndex/ModifierRipple/" +
                        "ModifierDrawContent/ModifierMarquee/ModifierGraphicsLayer/" +
                        "ModifierDimensionConstraints/ValueIntegerChange/ValueStringChange/" +
                        "ValueFloatChange/ValueIntegerExpressionChange/ValueFloatExpressionChange/" +
                        "MatrixSave/MatrixRestore/MatrixTranslate/MatrixScale/MatrixRotate/ClipRect/" +
                        "ClipPath/DrawBitmapInt/DrawTextOnCircle/MatrixSkew/DrawTextRun/" +
                        "DrawTextOnPath/ColorConstant/ModifierScroll/TouchExpression/DataFloat/" +
                        "DataInt/DataBoolean/DataLong)",
                )
            }
        }

        return RemoteDocument(
            header = Header(
                versionMajor = 1,
                versionMinor = 0,
                width = width,
                height = height,
                backgroundColor = Color.Transparent,
                capabilities = 0L,
            ),
            strings = StringPool.fromEntries(textPool),
            variables = VariablePool.EMPTY,
            bitmaps = BitmapPool.fromEntries(bitmapPool),
            opcodes = opcodes,
        )
    }

    /**
     * Decodes a `RemotePathBase` flat command array of [floatCount] raw i32 words (read directly
     * as bits, not as [BufferReader.readFloat32], since a command tag is a specific NaN bit
     * pattern that must be tested before deciding whether a word is a tag or real float data).
     *
     * @throws RemoteComposeParseException on [PATH_CMD_CONIC] — declared from source but not yet
     *   byte-verified (see [OP_DATA_PATH]).
     */
    /**
     * A quadratic-corner approximation of a rounded rect, for [OP_MODIFIER_ROUNDED_CLIP_RECT]'s
     * real clip effect: this parser has no dedicated round-rect clip primitive (unlike
     * [Opcode.DrawRoundRect], which real Compose's own Skia backend draws natively), so each
     * corner is instead approximated by a quadratic Bézier whose control point sits at the
     * corner's own sharp vertex — visually close to, but not bit-identical with, a true circular
     * arc, the same kind of documented approximation [OP_DRAW_TEXT_ON_CIRCLE]'s straight-line
     * fallback already makes elsewhere in this parser. Each radius is independently clamped to
     * half this rect's smaller dimension so opposite corners can never overlap. Corner naming
     * (`topStart`/`topEnd`/`bottomStart`/`bottomEnd`) assumes LTR, matching every other
     * `Component.Positioning`-based measurement in this parser (which has no bidi/RTL support).
     */
    private fun roundedRectPath(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topStart: Float,
        topEnd: Float,
        bottomStart: Float,
        bottomEnd: Float,
    ): List<PathCommand> {
        val maxRadius = minOf(right - left, bottom - top) / 2f
        val rTopStart = topStart.coerceIn(0f, maxRadius)
        val rTopEnd = topEnd.coerceIn(0f, maxRadius)
        val rBottomStart = bottomStart.coerceIn(0f, maxRadius)
        val rBottomEnd = bottomEnd.coerceIn(0f, maxRadius)
        return listOf(
            PathCommand.MoveTo(left + rTopStart, top),
            PathCommand.LineTo(right - rTopEnd, top),
            PathCommand.QuadraticTo(right, top, right, top + rTopEnd),
            PathCommand.LineTo(right, bottom - rBottomEnd),
            PathCommand.QuadraticTo(right, bottom, right - rBottomEnd, bottom),
            PathCommand.LineTo(left + rBottomStart, bottom),
            PathCommand.QuadraticTo(left, bottom, left, bottom - rBottomStart),
            PathCommand.LineTo(left, top + rTopStart),
            PathCommand.QuadraticTo(left, top, left + rTopStart, top),
            PathCommand.Close,
        )
    }

    /**
     * A PNG's natural pixel size, read directly from its `IHDR` chunk (always the first chunk,
     * immediately after the 8-byte signature: 4-byte length, 4-byte `"IHDR"` tag, then a 4-byte
     * big-endian width and a 4-byte big-endian height) — exact for any real PNG, so no platform
     * image-decoding is needed just to answer "how big is this bitmap really", which this
     * common-code parser has no access to anyway. [bitmapPool]'s entries are always real PNG bytes
     * (the real writer's own `storeBitmap`/`addBitmap` always encodes one), so this never needs a
     * fallback. Returns `null` if `bytes` is too short to hold an `IHDR` chunk at all.
     */
    private fun pngNaturalSize(bytes: ByteArray): IntArray? {
        if (bytes.size < 24) return null
        fun beInt(offset: Int) =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        return intArrayOf(beInt(16), beInt(20))
    }

    /**
     * `Operations.LAYOUT_IMAGE`'s real `scaleType` effect for `SCALE_FIT`(`4`)/`SCALE_CROP`(`5`) —
     * a faithful port of the real `ImageScaling.adjustDrawToType()`'s own integer arithmetic
     * (javap-confirmed), not a from-scratch reimplementation, so it matches real Compose's pixel
     * rounding too. Both scale types compare `srcW*dstH` against `dstW*srcH` to decide which axis
     * needs adjusting, but with the comparison flipped between them: `FIT` shrinks whichever axis
     * would otherwise overflow (letterboxing — the result always fits *inside* [dstLeft, dstTop,
     * dstRight, dstBottom]); `CROP` grows whichever axis would otherwise leave a gap (the result
     * can extend *outside* those bounds on one axis, so the caller must clip to them — real
     * Compose's own `Image`/`Modifier.paint` does exactly that whenever a mismatched
     * `contentScale` overflows the layout box). Every other `scaleType` returns the box unchanged
     * (this parser's original stretch-to-fill behavior, identical to what `SCALE_FILL_BOUNDS`
     * itself really means).
     */
    private fun imageScaleDstRect(
        scaleType: Int,
        naturalWidth: Int,
        naturalHeight: Int,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
    ): FloatArray {
        if (naturalWidth <= 0 || naturalHeight <= 0 ||
            (scaleType != 0 && scaleType != 1 && scaleType != 4 && scaleType != 5)
        ) {
            return floatArrayOf(dstLeft, dstTop, dstRight, dstBottom)
        }
        val dstW = (dstRight - dstLeft).toInt()
        val dstH = (dstBottom - dstTop).toInt()
        var leftOffset = 0
        var rightOffset = dstW
        var topOffset = 0
        var bottomOffset = dstH
        // SCALE_NONE: natural size, centered — no scaling at all (javap-confirmed: real
        // ImageScaling.adjustDrawToType()'s case 0 uses srcW/srcH directly, unadjusted).
        fun centerAtNaturalSize() {
            leftOffset = (dstW - naturalWidth) / 2
            rightOffset = naturalWidth + leftOffset
            topOffset = (dstH - naturalHeight) / 2
            bottomOffset = naturalHeight + topOffset
        }
        // Shared FIT/CROP/SCALE_INSIDE's-shrink-branch math: compare srcW*dstH against dstW*srcH
        // to decide which axis to adjust, with the branch flipped between FIT (shrink whichever
        // axis would overflow) and CROP (grow whichever axis would leave a gap) — see
        // imageScaleDstRect's own original KDoc above [OP_LAYOUT_IMAGE] for the full rationale.
        fun shrinkOrGrowToFit(shrinkHeightWhenSrcWider: Boolean) {
            val srcWiderThanDst = naturalWidth * dstH > dstW * naturalHeight
            val shrinkHeight = if (shrinkHeightWhenSrcWider) srcWiderThanDst else !srcWiderThanDst
            if (shrinkHeight) {
                val adjustedHeight = dstW * naturalHeight / naturalWidth
                topOffset = (dstH - adjustedHeight) / 2
                bottomOffset = adjustedHeight + topOffset
            } else {
                val adjustedWidth = dstH * naturalWidth / naturalHeight
                leftOffset = (dstW - adjustedWidth) / 2
                rightOffset = adjustedWidth + leftOffset
            }
        }
        when (scaleType) {
            0 -> centerAtNaturalSize()
            // SCALE_INSIDE: keep natural size (centered, never scaled up) when it already fits
            // both dimensions of the box, otherwise shrink exactly like SCALE_FIT (javap-confirmed
            // on the real case 1: an early-exit when dst is at least as large as src in both
            // dimensions, else it falls through to the identical shrink-to-fit branch case 4 uses).
            1 -> if (dstW >= naturalWidth && dstH >= naturalHeight) centerAtNaturalSize() else shrinkOrGrowToFit(true)
            4 -> shrinkOrGrowToFit(true) // SCALE_FIT
            5 -> shrinkOrGrowToFit(false) // SCALE_CROP
        }
        return floatArrayOf(dstLeft + leftOffset, dstTop + topOffset, dstLeft + rightOffset, dstTop + bottomOffset)
    }

    private fun decodePathArray(reader: BufferReader, floatCount: Int): List<PathCommand> {
        val words = IntArray(floatCount) { reader.readS32() }
        val commands = mutableListOf<PathCommand>()
        var i = 0
        while (i < words.size) {
            val tagWord = words[i]
            require((tagWord and NAN_TAG_MASK) == NAN_TAG_MASK) {
                "Expected a path command tag at float index $i, got a non-tag word"
            }
            when (val tag = tagWord and 0x7FFFFF) {
                PATH_CMD_MOVE -> {
                    commands += PathCommand.MoveTo(Float.fromBits(words[i + 1]), Float.fromBits(words[i + 2]))
                    i += 3
                }
                PATH_CMD_LINE -> {
                    // Real encoder bug: 2 garbage words between the tag and the real (x, y) — see
                    // OP_DATA_PATH's KDoc.
                    commands += PathCommand.LineTo(Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]))
                    i += 5
                }
                PATH_CMD_QUADRATIC -> {
                    // Same 2-word padding bug as LINE, ahead of 4 real floats (x1, y1, x2, y2).
                    commands += PathCommand.QuadraticTo(
                        Float.fromBits(words[i + 3]), Float.fromBits(words[i + 4]),
                        Float.fromBits(words[i + 5]), Float.fromBits(words[i + 6]),
                    )
                    i += 7
                }
                PATH_CMD_CUBIC -> {
                    // Same 2-word padding bug, ahead of 6 real floats (x1, y1, x2, y2, x3, y3).
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
                    "Real path command tag $tag at float index $i is not yet supported " +
                        "(MOVE/LINE/QUADRATIC/CUBIC/CLOSE verified against real output; CONIC is not)",
                )
            }
        }
        return commands
    }

    // OP_PATH_TWEEN's real semantic (matching real android.graphics.Path.interpolate()'s own
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

    // OP_COLOR_EXPRESSIONS's real gamma-2.2-corrected color interpolation (source-confirmed via
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

    // OP_COLOR_EXPRESSIONS's real HSV-to-RGB conversion (source-confirmed via javap on the real
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

    // OP_INTEGER_EXPRESSION's real RPN stack-machine step (source-confirmed via javap on the real
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

    // OP_MATRIX_FROM_PATH's real position-along-path semantic (matching Android's own
    // PathMeasure.getPosTan()): flattens Quadratic/CubicTo into short line segments (a standard,
    // real curve-length technique) to build one continuous polyline, walks it by cumulative arc
    // length to the target fraction, and returns [x, y, tangentDx, tangentDy] at that point — the
    // tangent an un-normalized direction vector (only its angle matters to the caller). null for
    // an empty/degenerate (zero-length) path.
    // Shared by [pointAndTangentAlongPath]/[trimPath]: flattens Quadratic/CubicTo segments into
    // 16 short line samples each (a standard, real curve-length technique — see
    // OP_MATRIX_FROM_PATH's own KDoc) into one continuous list of [x1, y1, x2, y2] line segments.
    private fun flattenPathSegments(commands: List<PathCommand>): List<FloatArray> {
        val segments = mutableListOf<FloatArray>()
        var curX = 0f; var curY = 0f
        var subpathStartX = 0f; var subpathStartY = 0f
        val curveSamples = 16
        fun quadPoint(t: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
            val u = 1f - t
            return floatArrayOf(
                u * u * x0 + 2f * u * t * x1 + t * t * x2,
                u * u * y0 + 2f * u * t * y1 + t * t * y2,
            )
        }
        fun cubicPoint(
            t: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        ): FloatArray {
            val u = 1f - t
            return floatArrayOf(
                u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3,
            )
        }
        for (command in commands) {
            when (command) {
                is PathCommand.MoveTo -> {
                    curX = command.x; curY = command.y
                    subpathStartX = curX; subpathStartY = curY
                }
                is PathCommand.LineTo -> {
                    segments += floatArrayOf(curX, curY, command.x, command.y)
                    curX = command.x; curY = command.y
                }
                is PathCommand.QuadraticTo -> {
                    var prevX = curX; var prevY = curY
                    for (i in 1..curveSamples) {
                        val p = quadPoint(
                            i / curveSamples.toFloat(), curX, curY,
                            command.x1, command.y1, command.x2, command.y2,
                        )
                        segments += floatArrayOf(prevX, prevY, p[0], p[1])
                        prevX = p[0]; prevY = p[1]
                    }
                    curX = command.x2; curY = command.y2
                }
                is PathCommand.CubicTo -> {
                    var prevX = curX; var prevY = curY
                    for (i in 1..curveSamples) {
                        val p = cubicPoint(
                            i / curveSamples.toFloat(), curX, curY,
                            command.x1, command.y1, command.x2, command.y2, command.x3, command.y3,
                        )
                        segments += floatArrayOf(prevX, prevY, p[0], p[1])
                        prevX = p[0]; prevY = p[1]
                    }
                    curX = command.x3; curY = command.y3
                }
                PathCommand.Close -> {
                    segments += floatArrayOf(curX, curY, subpathStartX, subpathStartY)
                    curX = subpathStartX; curY = subpathStartY
                }
            }
        }
        return segments
    }

    private fun pointAndTangentAlongPath(commands: List<PathCommand>, fraction: Float): FloatArray? {
        val segments = flattenPathSegments(commands)
        val lengths = segments.map { sqrt((it[2] - it[0]) * (it[2] - it[0]) + (it[3] - it[1]) * (it[3] - it[1])) }
        val totalLength = lengths.sum()
        if (totalLength <= 0f) return null
        val targetDist = fraction.coerceIn(0f, 1f) * totalLength
        var accumulated = 0f
        for (i in segments.indices) {
            val segLen = lengths[i]
            if (accumulated + segLen >= targetDist || i == segments.lastIndex) {
                val localT = if (segLen > 0f) ((targetDist - accumulated) / segLen).coerceIn(0f, 1f) else 0f
                val seg = segments[i]
                return floatArrayOf(
                    seg[0] + (seg[2] - seg[0]) * localT,
                    seg[1] + (seg[3] - seg[1]) * localT,
                    seg[2] - seg[0],
                    seg[3] - seg[1],
                )
            }
            accumulated += segLen
        }
        return null
    }

    // OP_DRAW_TWEEN_PATH's real start/stop trim (matching Android's own well-documented
    // PathMeasure.getSegment()): keeps only the [start, stop) fraction of the path's own total
    // arc length, rebuilt as a polyline (MoveTo + LineTo per flattened vertex, the same
    // curve-flattening [flattenPathSegments] already performs elsewhere) — start == 0f && stop ==
    // 1f (no real trim) returns the original commands unchanged, so untrimmed callers keep their
    // own exact Quadratic/CubicTo curves instead of an unnecessarily-flattened approximation.
    private fun trimPath(commands: List<PathCommand>, start: Float, stop: Float): List<PathCommand> {
        if (start <= 0f && stop >= 1f) return commands
        val segments = flattenPathSegments(commands)
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

    // OP_PATH_COMBINE's OP_INTERSECT: the textbook Sutherland-Hodgman polygon-clipping algorithm
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
