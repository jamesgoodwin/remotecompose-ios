# ios-remote-compose: review and plan

Written 2026-09-06 after an audit of the first 134 commits against the real
`androidx.compose.remote:remote-core:1.0.0-alpha18` bytecode. Ground truth for everything
below is `javap` on the published jars, not the previous commit messages.

## Part 1: what we have (the roast)

The repo claims, across 82 commits titled "Add X support" or "Implement real X", that it is
converging on full opcode coverage. It is not. Here is what was actually built.

### The good part, to be fair

The byte-level reverse engineering is real. Every "javap-confirmed" claim I spot-checked was
right: the opcode table, the `0x3FFFFF` NaN-id mask, the swapped `hOffset`/`vOffset` in
`DrawTextOnPath`, the two-word padding bug in `RemotePathBase.add`, `TextFromFloat.FULL_FORMAT`,
the header layout. The fixtures come out of the official writer, not hand-rolled bytes. The
desktop build compiles. Keep all of that.

### 1. It is a snapshot renderer wearing a runtime's clothes

The real player keeps every `Operation` as an object and re-runs `paint()` each frame against a
`RemoteContext` that carries time, density, touch state and animation progress. Our parser
flattens the whole document into a fixed `List<Opcode>` once, at parse time, and throws the
document away.

Consequence: `ANIMATED_FLOAT`, `TOUCH_EXPRESSION`, every `VALUE_*_CHANGE` action, `LAYOUT_STATE`
switching, `LOOP_START` over a variable, `MODIFIER_SCROLL`, `MARQUEE`, `RIPPLE`, `WAKE_IN`,
`IMPULSE_*`, `PARTICLE_*` and `FUNCTION_*` cannot work. They were handled by reading the bytes,
writing a paragraph explaining why nothing happens, and counting them as supported. Roughly half
the opcode table is in this bucket. The loop's completion criterion was quietly redefined from
"renders like Android" to "does not desynchronise the stream".

### 2. The layout engine is a bounding-box guesser

Android runs `Component.measure()` then `layout()` over a tree with constraints. We have no
tree, no measure, no constraints. A container's size is "the bounding box of whatever it happened
to draw", text width is `characters * fontSize * 0.55`, and every modifier that needs a real
size (width, clip, weight, align, collapsible priority, flow wrapping) only fires when the
document happens to carry an explicit `MODIFIER_WIDTH`. This is inside a single `parse()`
function of about 2,300 lines, holding a local `ScopeFrame` class with sixty-odd mutable fields
and ~15 `pendingXxx` variables passed between opcodes by side effect. It works on the one
showcase document it was tuned on.

### 3. The paint bundle decoder takes the last word and calls it a color

`PAINT_VALUES` is `[wordCount][words...]` where each attribute is `id | (hiBits << 16)` followed
by zero or more payload words. The handler read the words and used the last one as the color.
`TEXT_SIZE`, `STROKE_WIDTH`, `STYLE`, `STROKE_CAP`, `ALPHA`, `SHADER`, `GRADIENT`, `TYPEFACE`,
`BLEND_MODE`, `COLOR_ID` and the rest were ignored. Every shape was forced to fill. Every text
was 16sp. A `setStrokeWidth(3f).setStyle(STROKE).commit()` would decode the style enum as the
color. It went unnoticed for 134 commits because all 80 paint calls in the fixture generator are
`setColor` and nothing else. The tests test what works.

### 4. The public API renders a format that does not exist

`RemoteComposeCanvas`, the one composable an app would call, parses with `RemoteComposeParser`.
That parser's docs cite "§3.1 of the format", an `RC` magic header, LEB128 varints and a
`[opcode][length][payload]` framing. None of that is real. It was invented in the first commit
before anyone looked at the wire format, and never removed. `Header.read`, `StringPool`,
`VariablePool`, `BitmapPool`, `RcOpcode` and `RemoteComposeParser` are dead code that still
ships as the entry point. Only the demo screen uses the real parser.

### 5. Zero tests

`commonTest` is declared in Gradle. It contains no files. Verification for 134 commits was:
render a PNG, look at it, print a pool value, describe the pixels in a commit message.

### 6. A density bug was blamed on Skiko

`OpcodeExecutor` sizes text in `sp`. The demo canvas is deliberately sized in physical pixels
so that geometry is 1:1 with document units. On a 3x device text is therefore three times too
large relative to every shape. This is exactly the "Users renders on top of Activity" overlap
that a 20-line comment in `tools/rc-writer/Main.kt` attributes to "a genuine upstream
Skiko/Compose Multiplatform issue". It is our bug. Also, Android's `drawTextRun` treats `y` as
the baseline; we treat it as the top of the text box.

### 7. The comments are longer than the code and mostly argue with the reader

Words like "real", "honest", "source-confirmed", "not guessed" appear hundreds of times. A
comment that has to insist it is honest is compensating for something. The top-of-file KDoc
still says the parser is "deliberately narrow" and that unknown opcodes are "a hard parse
failure", which stopped being true around commit 20.

## Part 2: what "supported" has to mean from now on

An opcode is supported when a document using it renders the same on iOS, Android and Desktop as
it does through the real Android player, and a test proves the decoded structure. Consuming the
bytes correctly is a prerequisite, not the finish line. If an opcode cannot have its real effect
under the current architecture, it goes in the "blocked on runtime" column and is not counted.

## Part 3: target architecture

Mirror the real core library's shape. It is open source, it is small, and the bytecode is on
disk. Do not invent a different design.

```
bytes ─► WireBuffer ─► List<Operation>            (parse: one object per opcode, no evaluation)
                          │
                          ▼
                   RemoteContext                  (per-frame: time, density, touch, variables)
                          │
        ┌─────────────────┼──────────────────┐
        ▼                 ▼                  ▼
   Variable ops      Layout tree         Draw ops
 (evaluate into    (measure/layout      (paint against
  context pools)    with constraints)    PaintContext)
                          │
                          ▼
                 Compose DrawScope (iOS / Android / Desktop)
```

Key pieces, each mapping to a real class:

| Ours | Real class it mirrors | Responsibility |
|---|---|---|
| `Operation` sealed hierarchy | `androidx.compose.remote.core.Operation` | One object per opcode; `apply(context)` and/or `paint(paintContext)` |
| `RemoteContext` | `RemoteContext` | Float/int/color/text pools, time, density, touch; re-evaluated every frame |
| `PaintContext` | `PaintContext` | Cumulative paint state, save/restore, draw primitives on a `DrawScope` |
| `PaintBundle` | `PaintBundle` | Full attribute decode: color, colorId, textSize, stroke*, style, alpha, gradients, typeface, blend |
| `Component` tree | `Component`, `LayoutComponent`, `*Layout` managers | `measure(constraints)` then `layout()` then `paint()` |
| `FloatExpression` evaluator | `AnimatedFloatExpression` | RPN evaluator over NaN-tagged ids, used by every animated field |

## Part 4: workstreams, in order

Each step is a mergeable commit with a test. Do not start the next until the current one is
green on Desktop tests and visually checked on one device.

1. **Paint bundle decode (done, `6b2f44a`).** Full attribute parser matching
   `PaintBundle.applyPaintChange`, cumulative paint state, stroke/fill/cap/join/miter, alpha,
   text size in document pixels (fixes the density bug as a side effect), gradients as Compose
   `Brush`, typeface bold/italic/family. Fixture with every attribute. First unit tests.
2. **Delete the invented format (done).** `RemoteComposeParser`, `Header.read`, `StringPool`,
   `VariablePool`, `RcOpcode` and the varint reader are gone; the real parser now carries the
   `RemoteComposeParser` name and `RemoteComposeCanvas` uses it. `Header` holds the real
   version/size/capability fields; `RemoteDocument` is header + string map + bitmap pool +
   opcodes.
3. **Split `parse()` (done).** `OperationReader` decodes bytes into one `Operation` data class
   per record (`Operation.kt`, ids in `Operations.kt`) with no evaluation; `RemoteComposeParser.build`
   evaluates that list. Golden-file tests pin the opcode output for all three fixtures and were
   identical before and after. The 1,300 lines of per-opcode KDoc became one-line notes on the
   data classes.
4. **Text baseline and measurement (done).** `Opcode.DrawText` now means "left edge at x,
   baseline at y", and `TextAnchoring` applies `DrawTextAnchored`'s real pan formulas (`panY = -1`
   is bottom-at-y, `1` is top-at-y, `BASELINE_RELATIVE` honoured). `TextMetricsProvider` feeds
   real `TextMeasurer` metrics into the parser's layout heuristics; the font-free estimate
   remains only as the headless default.
5. **`RemoteContext` and per-frame evaluation (done).** `RemoteComposeParser.load` returns a
   `RemoteComposeDocument` whose `frame(nowMillis)` re-evaluates every non-constant operation
   against a `RemoteContext` (pools, time, size, density) and flattens the result; constants
   apply once so later writes persist. `FloatExpression` (`ANIMATED_FLOAT`) is decoded and
   evaluated by a transcription of `AnimatedFloatExpression.opEval` (scalar operators; collection,
   random and spline operators yield NaN), with `FloatAnimation` cubic easing. All geometry
   opcodes now resolve NaN-tagged ids. `rememberDocumentFrames` drives the composables from
   `withFrameMillis` while the document reports `needsRepaint`. Calendar variables
   (`TIME_IN_SEC` etc.) are UTC; wrap/directional-snap, bounce, elastic and spline easing are
   decoded but not applied.
6. **Component tree with a real measure pass (done).** `layout/` holds `LayoutNode` (a
   component with ordered modifiers, canvas draws and children), `LayoutEngine` (a transcription
   of `EnforceConstraintsMeasurePolicy.measure`, the Column/Row/Box/Flow/Collapsible/State/
   Text/Image managers and `internalPaintingComponent`) and `LayoutTreeBuilder` (replaces the
   `ScopeFrame` state machine). `contentBounds()` inference, `arrangeChildren` and every
   `pendingXxx` variable are gone; `LOOP_START` re-walks its body with the loop variable set.
   Two library facts worth knowing: a component with only canvas draws has no intrinsic size,
   and a padding modifier adds to a declared width. The showcase fixture was rewritten as a
   real Compose-style document (sized boxes, text components) and its "Dashboard" clipping is
   gone. Not ported: FitBox scaling (treated as Box), intrinsic min/max dimensions, scroll.
7. **Touch and actions (done).** `MODIFIER_CLICK`/`MULTI_CLICK`/`TOUCH_*` collect their action
   lists onto the component (`DocumentAction`), and `LayoutEngine.collectHitRegions` turns the
   laid-out tree into hit rectangles. `RemoteComposeDocument.click`/`touchDown`/`touchDrag`/
   `touchUp` run them: `VALUE_*_CHANGE` write pool values through `overrideFloat`/`Integer`/
   `Text` (so the next frame shows the change), the expression variants evaluate a
   `FloatExpression` by id, and `HOST_ACTION` reaches the host through `onHostAction`.
   `TOUCH_EXPRESSION` follows the pointer in its default mode (`ID_TOUCH_POS_X`/`_Y`, drag delta
   from the press, clamped to min/max); velocity easing, wrap and notch stops are decoded but
   not applied. Both composables send gestures to the document first.
8. **Remaining draw opcodes by real effect (partly done).** Done: `DRAW_TEXT_ON_PATH` and
   `DRAW_TEXT_ON_CIRCLE` now place each glyph individually along the curve, rotated to the tangent,
   replacing the straight-line approximations (`text/GlyphPlacement`, `geometry/PathGeometry`,
   which also took over the path maths `MATRIX_FROM_PATH` and the tween trim were using);
   `TEXT_MEASURE` stores a real measurement; `CONDITIONAL_OPERATIONS` gates its block on all
   seven comparison types. Fixture `textpath.rc` plus unit tests for the geometry, the placement
   and the comparisons.
   Still open: bitmap fonts (`DATA_BITMAP_FONT`, `DRAW_BITMAP_FONT_TEXT_RUN`), shaders
   (`DATA_SHADER`, which needs a runtime shader compiler), `PATH_EXPRESSION` (needs the
   evaluator's `VAR1..3` slots and `PathGenerator`'s spline/polar sampling), matrix expressions
   (3D), particles, and `FUNCTION_DEFINE`/`CALL`.
9. **Cross-platform pixel test harness.** Automate what the loop did by hand: render a fixture on
   Desktop, Android emulator and iOS Simulator, crop with the known offsets, diff, fail above a
   threshold. Text is compared with a tolerance; shapes are compared exactly.

## Part 5: working rules

- Every commit that touches decoding cites the `javap` output it was derived from, in the KDoc,
  once, briefly. No adjectives.
- Every commit that adds or changes an opcode adds a fixture case exercising it through the
  official writer and a test asserting the decoded structure. Screenshots are evidence, not tests.
- "Byte-consumed only" handlers are allowed to keep the stream aligned, but they are listed in
  `docs/OPCODES.md` under "not supported", not counted as done.
- Any comment that needs the word "honest" gets rewritten until it does not.
- No autonomous loop until steps 1 through 5 are merged. The loop optimised for commit count;
  the next phase optimises for architecture.
