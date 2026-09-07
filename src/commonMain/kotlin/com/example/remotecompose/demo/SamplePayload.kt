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
 * `TRANSFORM_ORIGIN_X`/`_Y` now really move the pivot instead of staying byte-consumed only, a
 * solid brown 16x16 square carrying a
 * `then(GraphicsLayerModifier().apply { setIntAttribute(SHAPE, SHAPE_CIRCLE) })` modifier — this
 * reduced wire API has no separate boolean "clip" flag the real Compose `GraphicsLayerScope` API
 * does, so `SHAPE` alone is trusted to mean "clip this layer's own content to it" — should render
 * as a circle inscribed in the square, corners visibly cut away against whatever's behind, the
 * same real effect `MODIFIER_BACKGROUND`'s own `shapeType`=CIRCLE gets, reached through a
 * completely different modifier this time, and a dark-indigo rect carrying a
 * `then(WidthInModifier(type, min, max))` modifier — its `type` (`VERTICAL_CONSTRAINTS`) and
 * natural height already sit within its declared range, so this carries no visible effect — a
 * teal rect drawn oversized (30x10) inside a `startBox`/`endBox` carrying
 * `then(WidthInModifier(0, 5f, 15f))` this time (`type`=`HORIZONTAL_CONSTRAINTS`) — should clip
 * down to exactly the declared max (15) the same real "cut off the overflow" effect
 * `MODIFIER_WIDTH_IN` itself gets, but reached through `MODIFIER_DIMENSION_CONSTRAINTS` — a wire
 * opcode entirely of its own — proving that opcode's `type`/`min`/`max` now really constrain this
 * axis instead of staying byte-consumed only, and a teal rect
 * carrying an `onClick(ValueIntegerChange(3, 7))` modifier, a burnt-orange rect carrying an
 * `onClick(ValueFloatChange(4, 2.5f))` modifier, and a dark-blue rect carrying an
 * `onClick(ValueStringChange(5, hi))` modifier, and a dark-olive rect carrying an
 * `onClick(ValueIntegerExpressionChange(6L, 42L))` modifier, and a brown rect carrying an
 * `onClick(ValueFloatExpressionChange(7, 9))` modifier, and a dark-blue-grey/dark-pink pair of
 * boxes wrapped in a `startCollapsibleColumn`/`endCollapsibleColumn` and a
 * `startCollapsibleRow`/`endCollapsibleRow` respectively, a `startCollapsibleRow` carrying an
 * explicit `width(30f)` this time — real Compose's own available-width constraint, known here
 * only because it's explicit — wrapping 3 Box children each drawing an identical raw
 * `(72, 52)-(87, 62)` 15x10 rect: a teal one with no `collapsiblePriority` modifier at all
 * (`Float.MAX_VALUE` default, so it never collapses), a purple one carrying
 * `collapsiblePriority(0, 2f)`, and an orange one carrying `collapsiblePriority(0, 1f)` — real
 * `CollapsibleRowLayout` visits children highest-priority-first (source-confirmed via javap), so
 * at `15+15=30` the teal and purple children exactly fill the declared width and the orange one
 * (lowest priority) is the one that collapses. A render showing only the teal and purple rects
 * packed side by side with no gap where the orange one would have been (not three overlapping
 * rects, and not a gap left for the hidden one) proves `MODIFIER_COLLAPSIBLE_PRIORITY` now really
 * collapses by priority instead of staying byte-consumed only, and a teal rect wrapped in a
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
 * `endStateLayout`, a `startStateLayout` wrapping 3 `startBox`/`endBox` children this time — each
 * drawing an identical raw `(110, 80)-(125, 90)` rect (deliberately overlapping) — pink, indigo,
 * and lime respectively: real `StateLayout` defaults `currentLayoutIndex` to `0` and hides every
 * other child immediately (source-confirmed via javap: `inflate()` calls
 * `hideLayoutsOtherThan(0)`), before any runtime state-change event this parser has no live state
 * to evaluate, so a render showing only the pink child — not three overlapping rects — proves
 * `LAYOUT_STATE`'s `stateIndex` now really drives this default-state visibility instead of staying
 * byte-consumed only, a `startRow` declaring an explicit `width(60f)` wrapping a teal 10-wide fixed
 * `startBox`/`endBox` child and a magenta one carrying
 * `then(WidthModifier(DimensionModifierOperation.Type.WEIGHT, 1f))` whose own natural content is
 * a small 8-wide placeholder rect: real `Row`/`Column` weight semantics give the weighted child
 * the *remaining* space (`60-10=50`, the only weight so it gets all of it) instead of its own
 * natural size — approximated here (no true measure pass) by stretching that already-positioned
 * placeholder via a real `Scale` wrap pivoted at its own leading edge, so it should render 50 wide
 * (from the fixed child's own right edge all the way to the row's declared right edge) rather than
 * staying 8 wide, proving `WEIGHT` now gets a real proportional-space-distribution effect instead
 * of staying byte-consumed only, an olive rect wrapped in a `startCanvas`/`endCanvas`, and a dark-brown rect
 * wrapped in a `startCustom`/`endCustom`, a `writer.image(RecordingModifier().width(16f)
 * .height(16f), bitmapId, IMAGE_SCALE_FIT, 0.8f)` leaf component drawing a small teal/white
 * checkerboard bitmap at 80% opacity into that explicit 16x16 box (this renderer has no measure
 * pass to size an image from its own bitmap/scaleType otherwise, so `LAYOUT_IMAGE` only renders
 * when an explicit `width()`/`height()` is present — a real-bytes hex-diff of this same call also
 * caught `bitmapId` and `scaleType` swapped from an earlier pass's assumed field order) — the same
 * solid-green 8x4 bitmap (a real, deliberately non-square 2:1 aspect ratio, read straight out of
 * its own PNG `IHDR` bytes rather than needing any platform image-decoding) drawn into two more
 * 16x16 boxes with `IMAGE_SCALE_FIT` and `IMAGE_SCALE_CROP` respectively: `FIT` should letterbox
 * it to a 16-wide x 8-tall strip vertically centered in the box (its own top/bottom margins
 * showing the red background behind it, not green), while `CROP` should instead cover the whole
 * box edge-to-edge with solid green and no red margin visible anywhere (its scaled width
 * overflows the box and gets clipped back to it — the same auto-clip real Compose's own
 * `Image`/`Modifier.paint` applies whenever a mismatched `contentScale` overflows the layout box)
 * — proving `scaleType` now gets a real letterbox/overscan effect for these two values instead of
 * staying byte-consumed only, the same bitmap again into a 16x16 box with `IMAGE_SCALE_NONE` —
 * never scales at all, just centers it at its own natural 8x4 size, so only a narrow patch in the
 * middle should show green, with margins on *all four* sides (unlike `FIT`'s wider letterboxed
 * strip) — and into a 4x8 box (smaller than the bitmap's own natural width) with
 * `IMAGE_SCALE_INSIDE` — shrinks like `FIT` whenever natural size doesn't already fit (unlike
 * `NONE`, which would just overflow/clip without shrinking), so a narrow 4-wide x2-tall strip
 * vertically centered in the box, not the old stretch-to-fill default, a `startBox` declaring
 * `CENTER`/`CENTER` alignment (`2, 2`) wrapping two more `startBox`/`endBox` children that both
 * draw their own rect at the *identical* raw top-left-anchored `(0, 0)` position — a brown 20x20
 * one and an amber 8x8 one, the small one deliberately drawn at the same corner as the big one,
 * not pre-centered by the document itself: real `BoxLayout` positions every child independently
 * within the box's own bounds (here, the big child's own 20x20 union), so the small child should
 * end up centered within it rather than still tucked in its documented top-left corner, proving
 * `LAYOUT_BOX`'s own `horizontalPositioning`/`verticalPositioning` now really align each child
 * instead of staying byte-consumed only, the same "Centered" string drawn twice with
 * `drawTextAnchored` at the identical `x=100` anchor — once with `panX=-1f` (left-align: `x`
 * should be the text's own left edge) and once with `panX=1f` (right-align: `x` should be the
 * text's own right edge) — real `DrawTextAnchored.getHorizontalOffset()` (source-confirmed via
 * javap) means these two renders should differ by roughly the text's own full measured width
 * (confirmed via exact pixel sampling: the left-aligned string's own left edge sits almost exactly
 * at `x=100`, the right-aligned string's own right edge sits almost exactly at the same `x=100`),
 * not sit at the identical position the old (`x` always the literal left edge, `panX`
 * byte-consumed) behavior would have given both — the *first* `drawTextAnchored` earlier in this
 * same document (the plain "Hi" one) also carries a real `panX=0f` (center) from the same real
 * writer call, so its own rendered position shifts slightly too, now genuinely centered on its `x`
 * instead of left-anchored there — and a teal rect wrapped in
 * a `startBox`/`endBox` carrying a
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
 * shapeType-gated shape choice `MODIFIER_BORDER`'s stroke already gets, and the same "Tall"
 * string drawn twice with `drawTextAnchored` at the identical `y=100` anchor — once with
 * `panY=-1f` (top-align: `y` should be the text's own top edge) and once with `panY=1f`
 * (bottom-align: `y` should be the text's own bottom edge) — this renderer's own topLeft-based
 * `drawText` means these two renders differ by roughly the text's own full measured height
 * (confirmed via pixel sampling: the top-aligned string sits visibly lower/below `y=100`, the
 * bottom-aligned string sits visibly higher/above it, ending almost exactly at the same `y=100`),
 * not the identical position the old (`y` always the literal top edge, `panY` byte-consumed)
 * behavior would have given both, and a real `LAYOUT_TEXT` component (`RemoteComposeWriter
 * .startTextComponent(...)`/`.endTextComponent()` — previously a completely unhandled opcode that
 * would throw a parse exception) rendering its own real `textId`/`color`/`fontSize`, drawn twice
 * with an identical `width(60f)` modifier — once `TEXT_ALIGN_LEFT` (should render flush with this
 * leaf's own left edge) and once `TEXT_ALIGN_CENTER` (should shift right by roughly half the
 * declared box's own leftover space, confirmed via pixel sampling: the left-aligned "Hi"'s own
 * left edge sits close to its box's own left edge, the centered one's left edge sits visibly
 * further right by close to the hand-computed shift) — proving `LAYOUT_TEXT` is both really
 * parsed at all and `textAlign` really anchors it within a declared width, not just newly byte-
 * consumed, and a real `DRAW_BITMAP_SCALED` (`RemoteComposeWriter.drawScaledBitmap(...)` —
 * previously a completely unhandled opcode) call sampling only the top *half* of the existing
 * solid-green 8x4 `wideBitmap` (an 8x2, 4:1-aspect source sub-rect, unlike the full bitmap's own
 * 2:1) into a 20x20 square box with `SCALE_FIT` — real `ImageScaling` math driven by this
 * *declared* source rect's own aspect (confirmed via the parsed opcode dump: a 20-wide x 5-tall
 * letterboxed strip, half as tall as the full-bitmap 20x10 strip [OP_LAYOUT_IMAGE]'s own FIT
 * proof produces elsewhere in this same document) proves this opcode's source sub-rect really
 * drives its scaling math, not just being byte-consumed, and a real `LOOP_START`
 * (`startLoop(0, 0f, 1f, 3f)`/`endLoop()` — previously a completely unhandled opcode) wrapping a
 * single authored 6x6 rect, nested in a `startRow`/`endRow` — real `LoopOperation.paint()` would
 * re-apply that one authored body in a live `for (i = 0; i < 3; i += 1)` loop; since every bound
 * here is a literal (not a variable reference), this parser statically unrolls it into three
 * separate rects instead, each registered as its own independent sibling for the enclosing row's
 * own real packing to arrange — confirmed via the parsed opcode dump: three 6x6 rects at local
 * `x=0`/`6`/`12` (not the single rect the old unhandled behavior could never have rendered at
 * all), and a real `TEXT_SUBTEXT` (`writer.textSubtext(srcId, 6f, -1f)` — previously a completely
 * unhandled opcode) computing "World" from a registered "Hello World" string (chars `[6, end)`,
 * `len=-1f` meaning "rest of the string") and registering it as a *new* text-pool entry, drawn via
 * `drawTextAnchored`'s own int-`textId` overload right below the full "Hello World" source string
 * for comparison — proving this op's `start`/`len` really compute a real substring instead of
 * staying byte-consumed only, and a real `TEXT_TRANSFORM` (`writer.textTransform(srcId, 0f, -1f,
 * TEXT_CAPITALIZE)` — previously a completely unhandled opcode) computing "Hello World" from a
 * registered "hello world" string — the real `capitalizeWords()` algorithm (ported verbatim from
 * `TextTransform.apply()`'s own bytecode) title-cases only the first letter of *every* word,
 * leaving the rest of each word's own case untouched — drawn via the same int-`textId`
 * `drawTextAnchored` overload right above the untransformed "hello world" source string for
 * comparison, and a real `PATH_CREATE`/`PATH_ADD` triangle (`writer.pathCreate(2f, 150f)` then
 * `pathAppendLineTo`/`pathAppendClose` — both previously completely unhandled opcodes) drawn via
 * `drawPath` — the *incremental*, multi-opcode path-building protocol (distinct from this
 * document's own already-handled single-opcode `DATA_PATH` paths elsewhere), confirmed via the
 * parsed opcode dump: a real `MoveTo`/`LineTo`/`LineTo`/`Close` triangle, not the single point the
 * old unhandled behavior could never have rendered at all, and a real `PATH_TWEEN`
 * (`writer.pathTween(pathA, pathB, 0.5f)` — previously a completely unhandled opcode) computing a
 * new path exactly halfway between two structurally-identical triangles (one at `y=[2, 20]`, one
 * shifted straight down by `30` to `y=[32, 50]`) — real per-coordinate linear interpolation
 * (matching real `android.graphics.Path.interpolate()`'s own well-known contract) confirmed via
 * the parsed opcode dump: the tweened path's own `MoveTo`/`LineTo`/`LineTo` land at exactly
 * `y=[17, 35]`, drawn in amber alongside both original gray triangles for comparison, and a real
 * `DEBUG_MESSAGE` (`writer.addDebugMessage("debug", 1f, 0)` — previously a completely unhandled
 * opcode) followed immediately by a plain `drawRect` — real `DebugMessage` has no rendering effect
 * of any kind (a pure developer-tools log message, source-confirmed via javap), so the only real
 * thing to prove is that this parser consumes exactly its own `[textId][floatValue][flags]` fields
 * and stays correctly byte-aligned for what follows, confirmed by that `drawRect` landing at its
 * own exact documented position rather than shifted by a misaligned read, and two real
 * `MATRIX_FROM_PATH` (`writer.matrixFromPath(pathId, 0.5f, 0f, flags)` — previously a completely
 * unhandled opcode) proofs: a teal square positioned at the exact midpoint of a horizontal 40-wide
 * line with `POSITION_MATRIX_FLAG` only (confirmed via the parsed opcode dump: `Translate(dx=20,
 * dy=0)`, no rotation), and an orange bar positioned at the midpoint of a 45-degree diagonal line
 * with `POSITION_MATRIX_FLAG | TANGENT_MATRIX_FLAG` — confirmed via the parsed opcode dump
 * (`Translate(dx=14, dy=14)` then `Rotate(degrees=45, pivotX=0, pivotY=0)`) and visually (a clean
 * diagonal bar following the line's own tangent, not the axis-aligned rect the position-only proof
 * renders), proving this op's real arc-length position/tangent computation instead of staying
 * unsupported entirely, and a real `DRAW_TWEEN_PATH` (`writer.drawTweenPath(squareA, squareB, 0.5f,
 * 0f, 0.5f)` — previously a completely unhandled opcode) tweening two structurally-identical 20x20
 * squares (one at `y=[0, 20]`, one shifted straight down by `30` to `y=[30, 50]`) to `y=[15, 35]`
 * (the same real per-coordinate lerp `PATH_TWEEN` already proves), then real-trimming to only the
 * first *half* of that square's own perimeter — confirmed via the parsed opcode dump: an exact
 * `MoveTo(0, 15)`/`LineTo(20, 15)`/`LineTo(20, 35)` "L" shape (the square's first two sides), which
 * renders (this parser's own `drawTweenPath`, like `drawPath`, always fills) as a clean right
 * triangle — the top-left half of the square, not the full untrimmed square the old unhandled
 * behavior could never have computed at all, and a real `PATH_COMBINE` `OP_INTERSECT`
 * (`writer.pathCombine(squareC, squareD, OP_INTERSECT)` — previously a completely unhandled
 * opcode) intersecting two overlapping 20x20 squares — one at `(0, 0)-(20, 20)`, one at
 * `(10, 10)-(30, 30)` — via this parser's own real Sutherland-Hodgman polygon-clipping algorithm,
 * confirmed via the parsed opcode dump to compute exactly their real geometric overlap, a 10x10
 * square at `(10, 10)-(20, 20)` (hand-verifiable min/max arithmetic), not the unresolved reference
 * the old unhandled behavior could never have computed at all, and a real `CANVAS_OPERATIONS`
 * (`startCanvasOperations()`/`endCanvasOperations()` — previously a completely unhandled opcode)
 * wrapping a single `drawRect` — real `CanvasOperations` writes no fields at all and its own real
 * `paint()` just applies its children directly, confirmed by that `drawRect` landing at its own
 * exact documented position, not the parse exception the old unhandled behavior would have thrown,
 * and a real `SKIP` (`writer.beginSkip`/`endSkip` — previously a completely unhandled opcode)
 * proof: a `SKIP_IF_API_GREATER_THAN(2)` block (value `0`) wrapping a bright red rect — since this
 * parser reports its own library API level as `Int.MAX_VALUE`, that condition is real (`MAX_VALUE
 * > 0`), so real `Skip.read()` itself jumps clean past that whole span unparsed and the red rect
 * never renders at all — followed by a `SKIP_IF_API_LESS_THAN(1)` block (value `Int.MAX_VALUE`,
 * so `MAX_VALUE < MAX_VALUE` is false) wrapping a green rect that *does* render normally right
 * after, confirmed via the parsed opcode dump (no red rect anywhere; the green one lands at its
 * own exact documented position), proving the reader stays correctly aligned whether or not the
 * preceding block was actually skipped, and a real `REM` (`writer.rem("...")` — previously a
 * completely unhandled opcode) followed immediately by a plain `drawRect` — real `Rem` has no
 * rendering effect of any kind (a pure source comment), so the only real thing to prove is
 * correct byte alignment, confirmed by that `drawRect` landing at its own exact documented
 * position rather than shifted by a misaligned read, and a real `TEXT_LENGTH`
 * (`writer.textLength(srcId)` — previously a completely unhandled opcode) computing the real
 * character count (`5`) of a registered "Hello" string and using the resulting NaN-tagged float
 * directly as a `width()` value on a box wrapping an oversized (30x10) rect clipped to its own
 * bounds — confirmed via the parsed opcode dump: the box's own real declared width lands at
 * exactly `5` (`ClipRect(left=96, top=185, right=101, bottom=195)`), not the unresolved reference
 * the old completely-unhandled behavior could never have computed at all, since this op loads its
 * result into the exact same value pool `resolveFloat` already resolves every other NaN-tagged
 * field against, and a real `ID_LIST`/`TEXT_LOOKUP` (`writer.addList(intArrayOf(...))`/
 * `writer.textLookup(dataSet, index)` — both previously completely unhandled opcodes) proof:
 * three registered strings ("Alpha", "Beta", "Gamma") collected into a real `ID_LIST`, then
 * looked up at index `1` — real `TextLookup.apply()` resolves the id at that index within the
 * real collection, then that id's own real string, computing "Beta" (confirmed both via the
 * parsed document's own string pool and visually — a clean crop reads "Beta" directly), not
 * "Alpha"/"Gamma"/an unresolved reference the old unhandled behavior could never have computed at
 * all, and a real `TEXT_LOOKUP_INT` (`writer.textLookup(dataSet, indexRefId: Int)` fed a real
 * `writer.addInteger(2).toInt()` registered int reference — previously a completely unhandled
 * opcode) proof reusing that same `ID_LIST`: `TextLookupInt.apply()` resolves its index
 * unconditionally via `RemoteContext.getInteger(mIndex)` (unlike `TextLookup`'s NaN-conditional
 * float index), so looking up index `2` computes "Gamma" (confirmed via the parsed document's own
 * string pool), not "Alpha"/"Beta"/an unresolved reference, and a real `TEXT_MERGE`
 * (`writer.textMerge(srcId1, srcId2): Int` — previously a completely unhandled opcode) proof:
 * real `TextMerge.apply()` (source-confirmed via javap) does a plain, separator-less
 * `getText(srcId1) + getText(srcId2)` concatenation into a newly allocated text-pool slot the
 * real writer method itself returns — merging registered "Merged" and "Text" strings computes
 * "MergedText" exactly (confirmed via the parsed document's own string pool), not "Merged"/"Text"
 * individually or an unresolved reference, and a real `COLOR_EXPRESSIONS`
 * (`writer.addColorExpression(...): Short` — previously a completely unhandled opcode) proof
 * reusing the same [colorPool]-consuming `dynamicBorder` path already proven for a literal
 * `DATA_COLOR`: one box's border color comes from a real `COLOR_COLOR_INTERPOLATE` (pure red
 * interpolated with pure blue at `tween=0.5`) — real `Utils.interpolateColor()` does a
 * gamma-2.2-corrected per-channel lerp, not a naive linear RGB average, computing a brighter
 * `(186, 0, 186)` rather than the naive `(128, 0, 128)` (confirmed via the parsed opcode dump's
 * own border-stroke color) — and another box's border color comes from a real `HSV_MODE`
 * (`hue=1/3`, full saturation/value), real `Utils.hsvToRgb()`'s standard hexagon conversion
 * computing pure green `(0, 255, 0)` exactly, and a real `ID_LOOKUP`
 * (`writer.idLookup(dataSet, index): Int` — previously a completely unhandled opcode) proof:
 * real `IdLookup.apply()` (source-confirmed via javap) retrieves the id at a literal index within
 * an `ID_LIST` and stores it into a plain int-pool slot (despite its real class's misleadingly
 * named `mTextId` field) — since this parser has no other consumer that treats an arbitrary
 * retrieved id as meaningful on its own, its real effect is only observable by chaining that same
 * int-pool slot into `TEXT_LOOKUP_INT`'s own index: a literal-index `ID_LIST` `[0, 1, 2]`,
 * `ID_LOOKUP` fetches "2" at index 2, and `TEXT_LOOKUP_INT` uses that computed (not merely
 * registered) value to resolve the Alpha/Beta/Gamma collection's index 2, "Gamma" (confirmed via
 * the parsed document's own string pool), and a real `INTEGER_EXPRESSION`
 * (`writer.integerExpression(vararg Long): Long` — previously a completely unhandled opcode)
 * proof: a genuine RPN stack machine (source-confirmed via javap on the real
 * `IntegerExpressionEvaluator`) computes `17 MOD 3` (not a naive pass-through of either operand),
 * and that computed `2` feeds `TEXT_LOOKUP_INT`'s own index the same way the `ID_LOOKUP` proof
 * above does, again resolving the Alpha/Beta/Gamma collection's index 2, "Gamma", and a real
 * `TEXT_FROM_FLOAT` (`writer.createTextFromFloat(value, digitsBefore, digitsAfter, flags): Int` —
 * previously a completely unhandled opcode) proof: with the `FULL_FORMAT` flag, real `apply()`
 * (source-confirmed via javap) does a plain `Float.toString(value)` — `42.75f` computes `"42.75"`
 * exactly (confirmed via the parsed document's own string pool), not `"42"`/`"0.75"`/an unresolved
 * reference, and a real `ID_MAP`/`DATA_MAP_LOOKUP` (`writer.addDataMap(...)`/
 * `writer.mapLookup(dataMapId, key): Int` — both previously completely unhandled opcodes) proof:
 * real `DataMapIds.apply()` (source-confirmed via javap) just registers a named lookup table, and
 * real `DataMapLookup.apply()` looks a key up in it and resolves the matching entry's real value
 * by its own real type — looking up `"banana"` in a real two-entry (`apple`/`banana`)
 * string-valued map computes `"Yellow Banana"` exactly (confirmed via the parsed document's own
 * string pool), not `"Red Apple"`/an empty string/an unresolved reference. Shared by every
 * platform demo entry
 * point (iOS, Android) so they
 * render byte-identical input — the point of the cross-platform comparison is to catch *rendering*
 * differences, not to accidentally compare two different payloads.
 */
@OptIn(ExperimentalEncodingApi::class)
val SAMPLE_RC_BYTES: ByteArray by lazy {
    Base64.decode(
            "AAAAAAEAAAABAAAAAAAAAMgAAADIAAAAAAAAAABmAAAAKgAAAARkZW1vZwAAACooAAAAAgAAAAT/5Tk1KkGgAABBoAAAQzQAAEM0AAAoAAAAAgAAAAT/Hojl" +
            "LkJwAABDDAAAQfAAACgAAAACAAAABP9DoEczQtwAAELcAABDPgAAQz4AAEFAAABBQAAAKAAAAAIAAAAE/wAAAGYAAAArAAAAAkhphQAAACtCyAAAQaAAAAAA" +
            "AAAAAAAAAAAAACgAAAACAAAABP+OJKovQSAAAENDAABDPgAAQ0MAACgAAAACAAAABP/7jAA4QwwAAEHwAABDQwAAQnAAACgAAAACAAAABP8ArMGYQAAAAEAA" +
            "AABCIAAAQiAAAAAAAABCtAAAKAAAAAIAAAAE/9gbYDRDDAAAQwwAAENGAABDRgAAQ0gAAELIAAAoAAAAAgAAAAT/OUmrewAAACwAAAAO/4AACkMbAABDMgAA" +
            "/4AACwAAAAAAAAAAQ0YAAEMyAAD/gAALAAAAAAAAAABDMAAAQ0cAAP+AAA98AAAALGUAAAAtAAAACAAAAAgAAABWiVBORw0KGgoAAAANSUhEUgAAAAgAAAAI" +
            "CAYAAADED76LAAAAHUlEQVR4XmP4DwSKioogCivNgE0QmWbAJjjkTAAAa5Crwb4olnUAAAAASUVORK5CYIJmAAAALgAAAAdjaGVja2VyLAAAAC1CjAAAQoIA" +
            "AELcAABC0gAAAAAALmUAAAAvAAAAAgAAAAIAAABUiVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAG0lEQVR4XmN4amn633mB+38GuY6n///e" +
            "MP0PAFK0Cg/xwFIOAAAAAElFTkSuQmCCQgAAAC8AAAAAAAAAAAAAAAEAAAABAAAAlgAAAAIAAAC+AAAAKgAAAABmAAAAMAAAAAp0YXAgdGFyZ2V0ZgAAADEA" +
            "AAAaaHR0cHM6Ly9leGFtcGxlLmNvbS90YXBwZWRAAAAABwAAADBBoAAAQaAAAEM0AABDNAAAAAAAMSgAAAACAAAABP8Ag497AAAAMgAAABT/gAAKQAAAAEMW" +
            "AAD/gAAMAAAAAAAAAABBkAAAQxYAAEGQAABDKgAA/4AADgAAAAAAAAAAQZAAAEM5AABBIAAAQ0MAAEAAAABDQwAA/4AAD3wAAAAyzP////7/////AAAAAAAA" +
            "AAAAAAAAyf////0oAAAAAgAAAAT//dg1KkKqAABDFgAAQtIAAEMlAAAoAAAAAgAAAAT/bUxBLkK+AABDLwAAQQAAANbWy/////z/////AAAAAAAAAAAAAAAA" +
            "yf////soAAAAAgAAAAT/AGlcKkLmAABDFgAAQwIAAEMlAAAoAAAAAgAAAAT/8GKSLkMMAABDHQAAQOAAANbWyv////r/////AAAAAAAAAADJ////+SgAAAAC" +
            "AAAABP+enSQqQxYAAEMUAABDJQAAQyMAANbWyv////j/////AAAAAAAAAAAQAAAAAEGgAABDAAAAAEEgAADJ////9ygAAAACAAAABP9dQDcqQyoAAEMUAABD" +
            "PgAAQyMAANbWyv////b/////AAAAAAAAAAA70QAAAAnWyf////UoAAAAAgAAAAT/7EB6KkM+AABAAAAAQ0cAAEEwAADW1sr////0/////wAAAAAAAAAAOkAA" +
            "AABAwAAAQAAAAEAAAAA3AAAAAAAAAAAAAAAAAAAAAD5c3N0+jo6PPp6enz+AAAAAAAAAyf////MoAAAAAgAAAAT/ex+iKkI0AABAAAAAQnAAAEFAAADW1sr/" +
            "///y/////wAAAAAAAAAA0wAAAADJ////8SgAAAACAAAABP84jjwqQoIAAEAAAABCoAAAQUAAANbWyv////D/////AAAAAAAAAADdQKAAAECgAADJ////7ygA" +
            "AAACAAAABP//VyIqQqoAAEAAAABCyAAAQUAAANbWyv///+7/////AAAAAAAAAABrAAAAAAAAAAAAAAAAAAAAAEAAAABAgAAAAAAAAAAAAAAAAAAAP4AAAAAA" +
            "AADJ////7SgAAAACAAAABP8AlogqQtIAAEAAAABC8AAAQUAAANbWyv///+z/////AAAAAAAAAAAQAAAAAEFwAABDAAAAAEEQAABs4gAAAABCSAAA/4AAM/+A" +
            "ADSdAAgAAAAAAAAAAAAA/4AAMwAAAAAAAAADAAAAA/+AAA6/gAAA/7EAAwAAAAAAAAAA1sn////rKAAAAAIAAAAE/zNpHioAAAAAAAAAAEFwAABBcAAA1taK" +
            "AAAANf/VAPnK////6v////8AAAAAAAAAAGsAAAACAAAANQAAAAAAAAAAQAAAAECAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMn////pKAAAAAIAAAAE/zdHTypC" +
            "9gAAQAAAAEMKAABBQAAA1tbK////6P////8AAAAAAAAAAGzJ////5ygAAAACAAAABP9eNbEqQvoAAEAAAABDDAAAQUAAANbWyv///+b/////AAAAAAAAAAA2" +
            "QIAAAECAAABAgAAAQIAAAMn////lKAAAAAIAAAAE/8DKMypDEQAAQAAAAEMgAABBQAAA1tbK////5P////8AAAAAAAAAABAAAAAAQXAAAEMAAAAAQSAAAGzJ" +
            "////4ygAAAACAAAABP//jwAqQzUAAEFwAABDUwAAQgwAANbWyv///+L/////AAAAAAAAAAAQAAAAAEFwAABDAAAAAEEgAAA2QIAAAECAAABAgAAAQIAAAMn/" +
            "///hKAAAAAIAAAAE/+kejypDEQAAQyoAAEMvAABDPgAA1tbK////4P////8AAAAAAAAAAFMAAAAB0QAAAAnWyf///98oAAAAAgAAAAT/AIOPKkMlAABAAAAA" +
            "QzQAAEFAAADW1sr////e/////wAAAAAAAAAA29EAAAAJ1sn////dKAAAAAIAAAAE/41uYypAAAAAQXAAAEGIAABByAAA1tbK////3P////8AAAAAAAAAANzR" +
            "AAAACdbJ////2ygAAAACAAAABP8mppoqQaAAAEFwAABCDAAAQcgAANbWyv///9r/////AAAAAAAAAADh0QAAAAnWyf///9koAAAAAgAAAAT/71NQKkIYAABB" +
            "cAAAQlQAAEHIAADW1sr////Y/////wAAAAAAAAAA50EgAABBoAAAyf///9coAAAAAgAAAAT/eYbLKkJgAABBcAAAQo4AAEHIAADW1sr////W/////wAAAAAA" +
            "AAAA6EBAAABBIAAAyf///9UoAAAAAgAAAAT//7MAKkKUAABBcAAAQrIAAEHIAADW1sr////U/////wAAAAAAAAAA50CgAABBcAAAyf///9MoAAAAAgAAAAT/" +
            "gncXKkK+AABCtAAAQvoAAELIAADW1sr////S/////wAAAAAAAAAA6wAAAABAAAAAyf///9EoAAAAAgAAAAT/VG56KkK4AABBcAAAQtYAAEHIAADW1sr////Q" +
            "/////wAAAAAAAAAA7f+AAAEAAAAAyf///88oAAAAAgAAAAT/jiSqKkLcAABBcAAAQvoAAEHIAADW1sr////O/////wAAAAAAAAAA30BAAADJ////zSgAAAAC" +
            "AAAABP8+JyMqQwAAAEFwAABDDwAAQcgAANbWyv///8z/////AAAAAAAAAADJ////y8r////K/////wAAAAAAAAAA30CgAADJ////ySgAAAACAAAABP/TLy8q" +
            "QxYAAEKCAABDLwAAQqAAANbWyv///8j/////AAAAAAAAAADfP4AAAMn////HKAAAAAIAAAAE/xl20ipDFgAAQoIAAEMvAABCoAAA1tbW1sr////G/////wAA" +
            "AAAAAAAA5cn////FKAAAAAIAAAAE/xl20ipDEgAAQXAAAEMhAABByAAA1tbK////xP////8AAAAAAAAAAK7J////wygAAAACAAAABP+tFFcqQyQAAEFwAABD" +
            "MwAAQcgAANbWyv///8L/////AAAAAAAAAADkAAAAAQAAAABEegAAQ/oAAEEAAABB8AAAyf///8EoAAAAAgAAAAT/gncXKkAAAABB4AAAQYgAAEIYAADW1sr/" +
            "///A/////wAAAAAAAAAA4AAAAAEAAAQLPwAAAMn///+/KAAAAAIAAAAE/2obmipBoAAAQeAAAEIMAABCGAAA1tbK////vv////8AAAAAAAAAAOAAAAACAAAE" +
            "AEAAAAAAAAQBQAAAAMn///+9KAAAAAIAAAAE//3YNSpCNAAAQnQAAEJUAABCigAA1tbK////vP////8AAAAAAAAAAOAAAAABAAAEBEI0AADJ////uygAAAAC" +
            "AAAABP8eiOUqQnAAAEJ0AABCmAAAQooAANbWyv///7r/////AAAAAAAAAADgAAAAAwAABARCNAAAAAAEBQAAAAAAAAQGAAAAAMn///+5KAAAAAIAAAAE/wCs" +
            "wSpCqgAAQnQAAEK6AABCigAA1ta/QKAAAL9AAAAAyv///7j/////AAAAAAAAAADgAAAAAQAABAJCcAAAyf///7coAAAAAgAAAAT/72wAKkLIAABCdAAAQugA" +
            "AEKaAADW1sr///+2/////wAAAAAAAAAA4AAAAAEAAAQDQnAAAMn///+1KAAAAAIAAAAE/y59MipC8AAAQnQAAEMIAABCmgAA1tbK////tP////8AAAAAAAAA" +
            "AOAAAAABAAAAFAAAAALJ////sygAAAACAAAABP9dQDcqQAAAAEMqAABBkAAAQzoAANbWyv///7L/////AAAAAAAAAADzAUCgAABCIAAAyf///7EoAAAAAgAA" +
            "AAT/RSegKkIYAABB4AAAQlQAAEIYAADW1sr///+w/////wAAAAAAAAAA8wBAoAAAQXAAAMn///+vKAAAAAIAAAAE/wBpXCpC6AAAQuAAAEMSAABC9AAA1tbK" +
            "////rv////8AAAAAAAAAADvUAAAAAwAAAAfWyf///60oAAAAAgAAAAT/AGlcKkJgAABB4AAAQo4AAEIYAADW1sr///+s/////wAAAAAAAAAAO94AAAAEQCAA" +
            "ANbJ////qygAAAACAAAABP/YQxUqQpQAAEHgAABCsgAAQhgAANbWyv///6r/////AAAAAAAAAAA7ZgAAADYAAAACaGnVAAAABQAAADbWyf///6koAAAAAgAA" +
            "AAT/KDWTKkK4AABB4AAAQtYAAEIYAADW1sr///+o/////wAAAAAAAAAAO9oAAAAAAAAABgAAAAAAAAAq1sn///+nKAAAAAIAAAAE/zNpHipC3AAAQeAAAEL6" +
            "AABCGAAA1tbK////pv////8AAAAAAAAAADvjAAAABwAAAAnWyf///6UoAAAAAgAAAAT/bUxBKkMAAABB4AAAQw8AAEIYAADW1un///+k/////wAAAAAAAAAA" +
            "AAAAAMn///+jKAAAAAIAAAAE/zdHTypDEgAAQeAAAEMhAABCGAAA1tbm////ov////8AAAAAAAAAAAAAAADJ////oSgAAAACAAAABP+IDk8qQyQAAEHgAABD" +
            "MwAAQhgAANbW5v///6D/////AAAAAAAAAAAAAAAAEAAAAABB8AAAyf///5/K////nv////8AAAAAAAAAAMn///+dKAAAAAIAAAAE/wCDjypCkAAAQlAAAEKu" +
            "AABCeAAA1tbK////nP////8AAAAAAAAAAOsAAAAAQAAAAMn///+bKAAAAAIAAAAE/2obmipCkAAAQlAAAEKuAABCeAAA1tbK////mv////8AAAAAAAAAAOsA" +
            "AAAAP4AAAMn///+ZKAAAAAIAAAAE/+9sACpCkAAAQlAAAEKuAABCeAAA1tbW1vD///+Y/////wAAAAAAAAAAAAAAAH////9/////yf///5coAAAAAgAAAAT/" +
            "AIOPKkAAAABCJAAAQYgAAEJMAADW1vD///+W/////wAAAAAAAAAAQEAAAAAAAAJ/////yf///5XK////lP////8AAAAAAAAAAMn///+TKAAAAAIAAAAE/9hD" +
            "FSpCggAAQrQAAEKSAABCxAAA1tbK////kv////8AAAAAAAAAAMn///+RKAAAAAIAAAAE/2obmipCggAAQrQAAEKSAABCxAAA1tbK////kP////8AAAAAAAAA" +
            "AMn///+PKAAAAAIAAAAE/y59MipCggAAQrQAAEKSAABCxAAA1tbK////jv////8AAAAAAAAAAMn///+NKAAAAAIAAAAE/xVlwCpCggAAQrQAAEKSAABCxAAA" +
            "1tbW1vD///+M/////wAAAAAAAAAAQEAAAAAAAAIAAAAByf///4vK////iv////8AAAAAAAAAAMn///+JKAAAAAIAAAAE/+9sACpDKgAAQyoAAEMwAABDMAAA" +
            "1tbK////iP////8AAAAAAAAAAMn///+HKAAAAAIAAAAE/wBpXCpDKgAAQyoAAEMwAABDMAAA1tbK////hv////8AAAAAAAAAAMn///+FKAAAAAIAAAAE/60U" +
            "VypDKgAAQyoAAEMwAABDMAAA1tbK////hP////8AAAAAAAAAAMn///+DKAAAAAIAAAAE/yg1kypDKgAAQyoAAEMwAABDMAAA1tbW1rD///+C/////wAAAAAA" +
            "AAAAyf///4EoAAAAAgAAAAT/5koZKkGgAABCJAAAQgwAAEJMAADW1sj///+AKAAAAAIAAAAE/xojfipCGAAAQiQAAEJUAABCTAAA1tn///9//////wAAAAAA" +
            "AAAAAAAAAMn///9+KAAAAAIAAAAE/60UVypCYAAAQiQAAEKOAABCTAAA1tbZ////ff////8AAAAAAAAAAAAAAADJ////fMr///97/////wAAAAAAAAAAyf//" +
            "/3ooAAAAAgAAAAT/6R5jKkLcAABCoAAAQvoAAEK0AADW1sr///95/////wAAAAAAAAAAyf///3goAAAAAgAAAAT/P1G1KkLcAABCoAAAQvoAAEK0AADW1sr/" +
            "//93/////wAAAAAAAAAAyf///3YoAAAAAgAAAAT/zdw5KkLcAABCoAAAQvoAAEK0AADW1tbWy////3X/////AAAAAAAAAAAAAAAAEAAAAABCcAAAyf///3TK" +
            "////c/////8AAAAAAAAAAMn///9yKAAAAAIAAAAE/wBpXCpC3AAAQrQAAELwAABCxAAA1tbK////cf////8AAAAAAAAAABAAAAADP4AAAMn///9wKAAAAAIA" +
            "AAAE/60UVypC8AAAQrQAAEMAAABCxAAA1tbW1s3///9v/////8n///9uz////20oAAAAAgAAAAT/VYsvKkKUAABCJAAAQrIAAEJMAADW1tZmAAAANwAAAAht" +
            "eUN1c3RvbV3//////////wAAADcAAAAAyf///2woAAAAAgAAAAT/TjQuKkK4AABCJAAAQtYAAEJMAADW1mUAAAA4AAAABAAAAAQAAABQiVBORw0KGgoAAAAN" +
            "SUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAF0lEQVR4XmNg2L/0PwjAaRQOkGYgqAIAugIy+dakDXUAAAAASUVORK5CYILq////a/////8AAAA4AAAABD9MzM0Q" +
            "AAAAAEGAAABDAAAAAEGAAADWZQAAADkAAAAIAAAABAAAAE+JUE5HDQoaCgAAAA1JSERSAAAACAAAAAQIBgAAALPNfvAAAAAWSURBVHheY9CrNfqPDzOgC6Bj" +
            "yhUAADXlO4GFQLwAAAAAAElFTkSuQmCCyv///2r/////AAAAAAAAAADdQtwAAEJgAADJ////aer///9o/////wAAADkAAAAEP4AAABAAAAAAQYAAAEMAAAAA" +
            "QYAAANbW1sr///9n/////wAAAAAAAAAA3UMCAABCYAAAyf///2bq////Zf////8AAAA5AAAABT+AAAAQAAAAAEGAAABDAAAAAEGAAADW1tZQAAAAOkDAAADK" +
            "////ZP////8AAAAAAAAAADr/gAA6AAAAAAAAAAAAAAAAyf///2MoAAAAAgAAAAT/AIl7KkIgAABCtAAAQlwAAELIAADW1owAAAA7AAAAB48AAAA8AZQAAAA9" +
            "AAAAHL6ZGhSxAAAABD/////9KAAAAAIAAAAE///BBypCwAAAQAAAAELQAABAwAAAP/////9BAAAAAQAAAAIAAAADAAAABMr///9i/////wAAAAAAAAAADgAA" +
            "AANDlgAAAAAAAUOWAAAAAAABAAAAAAAAAAHJ////YSgAAAACAAAABP9dQDcqQtwAAEIkAABC+gAAQkwAANbWgn9DAgAAQiQAACgAAAACAAAABP8ArMEqAAAA" +
            "AAAAAABBcAAAQSAAAIOCfkAAAABAAAAAQxEAAEI4AAAoAAAAAgAAAAT/9X8XKkMRAABCJAAAQxgAAEI4AACDgoFCNAAAQy8AAEI4AAAoAAAAAgAAAAT/ahua" +
            "KkMoAABCJAAAQzcAAEJMAACDgidDPgAAQiQAAENNAABCTAAAKAAAAAIAAAAE/6oA/ypDOQAAQhAAAENSAABCYAAAg4KAPwAAAAAAAAAoAAAAAgAAAAT/2EMV" +
            "KkAAAABDAgAAQYgAAEMRAACD6f///2D/////AAAAAAAAAABAQAAAyf///1/K////Xv////8AAAAAAAAAAMn///9dKAAAAAIAAAAE/8IYWypBsAAAQwIAAEIA" +
            "AABDDAAA1tbK////XP////8AAAAAAAAAAMn///9bKAAAAAIAAAAE/3sfoipBsAAAQwIAAEIAAABDDAAA1tbK////Wv////8AAAAAAAAAAMn///9ZKAAAAAIA" +
            "AAAE/zA/nypBsAAAQwIAAEIAAABDDAAA1tbW1igAAAACAAAABP8hISFmAAAAPgAAAAtIZWxsbyBXb3JsZCsAAAA+AAAABgAAAAsAAAAAAAAAC0IQAABDCAAA" +
            "AHsAAAA/AAAACP+AAApC0gAAQ0MAAP+AAAsAAAAAAAAAAEL6AABDQwAAKAAAAAIAAAAE/wBpXGYAAABAAAAABFBhdGg1AAAAQAAAAD/AgAAAAAAAAMr///9Y" +
            "/////wAAAAAAAAAANwAAAAAAAAAAAAAAAAAAAAA/gAAAPt7e3wAAAAA/gAAAAAAAAMn///9XKAAAAAIAAAAE/xVlwCpAAAAAQnQAAEFAAABCjgAAKAAAAAIA" +
            "AAAE/y59Mi5CDAAAQpwAAEDAAADW1sz///9W/////wAAAAAAAAAAQEAAAMn///9Vyv///1T/////AAAAAAAAAADJ////UygAAAACAAAABP/TLy8qQAAAAEK0" +
            "AABBQAAAQsgAANbWyv///1L/////AAAAAAAAAADJ////USgAAAACAAAABP8ZdtIqQAAAAEK0AABBQAAAQsgAANbWyv///1D/////AAAAAAAAAADJ////TygA" +
            "AAACAAAABP84jjwqQAAAAEK0AABBQAAAQsgAANbW1tbL////Tv////8AAAAGAAAAAgAAAAAQAAAAAEKgAADJ////Tcr///9M/////wAAAAAAAAAAyf///0so" +
            "AAAAAgAAAAT/xigoKkGwAABDFgAAQfAAAEMeAADW1sr///9K/////wAAAAAAAAAAyf///0koAAAAAgAAAAT/+aglKkGwAABDFgAAQfAAAEMmAADW1sr///9I" +
            "/////wAAAAAAAAAAyf///0coAAAAAgAAAAT/AIOPKkGwAABDFgAAQfAAAEMeAADW1tbWgnsAAABBAAAADv+AAApC0gAAQygAAP+AAAsAAAAAAAAAAEL6AABD" +
            "FgAA/4AACwAAAAAAAAAAQvoAAEMoAAD/gAAPJgAAAEEoAAAAAgAAAAT/ahuaKkLSAABDFgAAQwIAAEMqAACDKAAAAAIAAAAE/yEhIWYAAABCAAAABkN1cnZl" +
            "ZDkAAABCQsgAAEM5AABBIAAAQ4cAAAAAAAABAMr///9G/////wAAAAAAAAAANwAAAAAAAAAAAAAAAAAAAAA/GZmaPkzMzQAAAAA/gAAAAAAAAcn///9FKAAA" +
            "AAIAAAAE/////ypDFgAAQtwAAEMXAABC3gAAKkM9AABDAQAAQz4AAEMCAADW1sr///9E/////wAAAAAAAAAA3UFAAABCdAAAyf///0Pq////Qv////8AAAA5" +
            "AAAAAD+AAAAQAAAAAEGAAABDAAAAAEGAAADW1tbK////Qf////8AAAAAAAAAAN1AAAAAQrQAAMn///9A6v///z//////AAAAOQAAAAE/gAAAEAAAAABAgAAA" +
            "QwAAAABBAAAA1tbWyv///z7/////AAAAAgAAAALdQAAAAEMWAADJ////Pcr///88/////wAAAAAAAAAAyf///zsoAAAAAgAAAAT/XUA3KgAAAAAAAAAAQaAA" +
            "AEGgAADW1sr///86/////wAAAAAAAAAAyf///zkoAAAAAgAAAAT//7MAKgAAAAAAAAAAQQAAAEEAAADW1tbWKAAAAAIAAAAE/xVlwGYAAABDAAAACENlbnRl" +
            "cmVkhQAAAENCyAAAQyAAAL+AAAAAAAAAAAAAACgAAAACAAAABP9qG5qFAAAAQ0LIAABDNAAAP4AAAAAAAAAAAAAAKAAAAAIAAAAE/y59MmYAAABEAAAABFRh" +
            "bGyFAAAAREMMAABCyAAAv4AAAL+AAAAAAAAAKAAAAAIAAAAE/8YoKIUAAABEQyoAAELIAAC/gAAAP4AAAAAAAABmAAAARQAAAADQ////OP////8AAAAr/wBp" +
            "XEGAAAAAAAAAQ8gAAAAAAEUAAAABAAAAAQAAAAEQAAAAAEJwAADdQaAAAEMWAADJ////N9bW0P///zb/////AAAAK/+tFFdBgAAAAAAAAEPIAAAAAABFAAAA" +
            "AwAAAAEAAAABEAAAAABCcAAA3ULIAABDFgAAyf///zXW1mYAAABGAAAABnNjYWxlZJUAAAA5AAAAAAAAAABBAAAAQAAAAEAAAABAAAAAQbAAAEGwAAAAAAAE" +
            "P4AAAAAAAEbL////NP////8AAAAAAAAAAAAAAADdQAAAAEMqAADJ////M9cAAAAAAAAAAD+AAABAQAAAKAAAAAIAAAAE/21MQSoAAAAAAAAAAEDAAABAwAAA" +
            "1tbWtgAAAEcAAAA+QMAAAL+AAAAoAAAAAgAAAAT/AIOPhQAAAEdDGwAAQ0QAAL+AAAA/gAAAAAAAACgAAAACAAAABP9dQDeFAAAAPkJwAABDRAAAv4AAAD+A" +
            "AAAAAAAAZgAAAEgAAAALaGVsbG8gd29ybGTHAAAASQAAAEgAAAAAv4AAAAAAAAQoAAAAAgAAAAT/RSeghQAAAElAAAAAQUAAAL+AAAC/gAAAAAAAACgAAAAC" +
            "AAAABP8AaVyFAAAASEAAAABB8AAAv4AAAL+AAAAAAAAAnwAAAEpAAAAAQxYAAKAAAABKAAAABf+AAAsAAAAAAAAAAEGQAABDFgAAoAAAAEoAAAAF/4AACwAA" +
            "AAAAAAAAQSAAAEMlAACgAAAASgAAAAH/gAAPKAAAAAIAAAAE/8IYW3wAAABKnwAAAEtDDAAAQAAAAKAAAABLAAAABf+AAAsAAAAAAAAAAEMeAABAAAAAoAAA" +
            "AEsAAAAF/4AACwAAAAAAAAAAQxUAAEGgAACgAAAASwAAAAH/gAAPnwAAAExDDAAAQgAAAKAAAABMAAAABf+AAAsAAAAAAAAAAEMeAABCAAAAoAAAAEwAAAAF" +
            "/4AACwAAAAAAAAAAQxUAAEJIAACgAAAATAAAAAH/gAAPngAAAE0AAABLAAAATD8AAAAoAAAAAgAAAAT/np6efAAAAEsoAAAAAgAAAAT/YWFhfAAAAEwoAAAA" +
            "AgAAAAT//8EHfAAAAE1mAAAATgAAAAVkZWJ1Z7MAAABOP4AAAAAAAAAoAAAAAgAAAAT/GiN+KkMgAABDFgAAQzAAAEMmAADK////Mv////8AAAAAAAAAAN1C" +
            "cAAAQowAAMn///8xnwAAAE8AAAAAAAAAAKAAAABPAAAABf+AAAsAAAAAAAAAAEIgAAAAAAAAtQAAAE8/AAAAAAAAAAAAAAEoAAAAAgAAAAT/AIOPKgAAAAAA" +
            "AAAAQMAAAEDAAADW1sr///8w/////wAAAAAAAAAA3UJwAABCtAAAyf///y+fAAAAUAAAAAAAAAAAoAAAAFAAAAAF/4AACwAAAAAAAAAAQeAAAEHgAAC1AAAA" +
            "UD8AAAAAAAAAAAAAAygAAAACAAAABP/YQxUqwQAAAL+AAABBAAAAP4AAANbWyv///y7/////AAAAAAAAAADdQAAAAEJwAADJ////LZ8AAABRAAAAAAAAAACg" +
            "AAAAUQAAAAX/gAALAAAAAAAAAABBoAAAAAAAAKAAAABRAAAABf+AAAsAAAAAAAAAAEGgAABBoAAAoAAAAFEAAAAF/4AACwAAAAAAAAAAAAAAAEGgAACgAAAA" +
            "UQAAAAH/gAAPnwAAAFIAAAAAQfAAAKAAAABSAAAABf+AAAsAAAAAAAAAAEGgAABB8AAAoAAAAFIAAAAF/4AACwAAAAAAAAAAQaAAAEJIAACgAAAAUgAAAAX/" +
            "gAALAAAAAAAAAAAAAAAAQkgAAKAAAABSAAAAAf+AAA8oAAAAAgAAAAT/ahuafQAAAFEAAABSPwAAAAAAAAA/AAAA1tbK////LP////8AAAAAAAAAAN1B8AAA" +
            "QnAAAMn///8rnwAAAFMAAAAAAAAAAKAAAABTAAAABf+AAAsAAAAAAAAAAEGgAAAAAAAAoAAAAFMAAAAF/4AACwAAAAAAAAAAQaAAAEGgAACgAAAAUwAAAAX/" +
            "gAALAAAAAAAAAAAAAAAAQaAAAKAAAABTAAAAAf+AAA+fAAAAVEEgAABBIAAAoAAAAFQAAAAF/4AACwAAAAAAAAAAQfAAAEEgAACgAAAAVAAAAAX/gAALAAAA" +
            "AAAAAABB8AAAQfAAAKAAAABUAAAABf+AAAsAAAAAAAAAAEEgAABB8AAAoAAAAFQAAAAB/4AAD68AAABVAAAAUwAAAFQBKAAAAAIAAAAE/y59MnwAAABV1tat" +
            "KAAAAAIAAAAE/+9sACpCyAAAQzkAAELoAABDRwAA1vEAAAACAAAAAAAAAB4oAAAAAgAAAAT/1QAAKkAAAABDOQAAQfAAAENHAADxAAAAAX////8AAAAeKAAA" +
            "AAIAAAAE/ziOPCpCDAAAQzkAAEJ8AABDRwAAuQAAACd0aGlzIGlzIGEgY29tbWVudCwgbm90IGEgcmVhbCBVSSBvcGNvZGUoAAAAAgAAAAT/AGlcKkKEAABD" +
            "OQAAQrwAAENHAABmAAAAVgAAAAVIZWxsb5wAAABXAAAAVsr///8q/////wAAAAAAAAAA3ULAAABDOQAAEAAAAAD/gABXQwAAAABBIAAAbMn///8pKAAAAAIA" +
            "AAAE/21MQSoAAAAAAAAAAEHwAABBIAAA1tZmAAAAWAAAAAVBbHBoYWYAAABZAAAABEJldGFmAAAAWgAAAAVHYW1tYZIAIAAqAAAAAwAAAFgAAABZAAAAWpcA" +
            "AABbACAAKj+AAAAoAAAAAgAAAAT/RSeghQAAAFtDAgAAQyoAAL+AAAC/gAAAAAAAAIwAAABcAAAAApkAAABdACAAKgAAAFwoAAAAAgAAAAT/AGlchQAAAF1D" +
            "AgAAQz4AAL+AAAC/gAAAAAAAAGYAAABeAAAABk1lcmdlZGYAAABfAAAABFRleHSIAAAAYAAAAF4AAABfKAAAAAIAAAAE/9hDFYUAAABgQKAAAEEAAAC/gAAA" +
            "v4AAAAAAAACGAAAAYQAAAAD//wAA/wAA/z8AAADK////KP////8AAAAAAAAAAGsAAAACAAAAYQAAAAAAAAAAQAAAAECAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AMn///8nKAAAAAIAAAAE/zdHTypDDwAAQAAAAEMeAABBQAAA1taGAAAAYgD/AAQ+qqqrP4AAAD+AAADK////Jv////8AAAAAAAAAAGsAAAACAAAAYgAAAAAA" +
            "AAAAQAAAAECAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMn///8lKAAAAAIAAAAE/zdHTypDIwAAQAAAAEMyAABBQAAA1taSACAAKwAAAAMAAAAAAAAAAQAAAALA" +
            "AAAAYwAgACtAAAAAmQAAAGQAIAAqAAAAYygAAAACAAAABP9qG5qFAAAAZECgAABCNAAAv4AAAL+AAAAAAAAAkAAAAGUAAAAEAAAAAwAAABEAAAADAAEABZkA" +
            "AABmACAAKgAAAGUoAAAAAgAAAAT/AIOPhQAAAGZAoAAAQoIAAL+AAAC/gAAAAAAAAIcAAABnQisAAAAAAAAAABAAKAAAAAIAAAAE/60UV4UAAABnQKAAAEKq" +
            "AAC/gAAAv4AAAAAAAABmAAAAaAAAAAlSZWQgQXBwbGVmAAAAaQAAAA1ZZWxsb3cgQmFuYW5hkQAgACwAAAACAAAABWFwcGxlAAAAAGgAAAAGYmFuYW5hAAAA" +
            "AGlmAAAAagAAAAZiYW5hbmGaAAAAawAgACwAAABqKAAAAAIAAAAE/zNpHoUAAABrQKAAAELSAAC/gAAAv4AAAAAAAAA="
    )
}
