# How it is put together

Two phases. `OperationReader.readAll(bytes)` decodes the byte stream into one `Operation` per
record with no evaluation. `RemoteComposeParser.build` then walks those operations once per frame,
evaluating every value-producing one into `RemoteContext`'s pools, collecting the components into a
tree, measuring and laying that tree out, and flattening the result into draw opcodes.

```
bytes ─► OperationReader ─► List<Operation>       decode, no evaluation
                                 │
                                 ▼
                           RemoteContext          per frame: time, density, touch, pools
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
        value operations    component tree      draw operations
       (into the pools)   (measure and lay out)  (into an opcode list)
                                 │
                                 ▼
                         Compose DrawScope        iOS · Android · desktop
```

The structure follows the official player's (`Operation`, `RemoteContext`, `PaintContext`, the
`*Layout` managers), because the format is defined by what that player does with the bytes.

## Modules

The repository is four Gradle modules, and the split is the one the published framework needs:

| | |
| --- | --- |
| `:` | the renderer, and nothing else. This is what the Swift package ships |
| `:fixtures` | the writer's documents as Base64, for the tests and the demo to share |
| `:demo` | the demo app's screens, and its own `RemoteComposeDemoShared` framework for `iosApp` |
| `:androidApp` | the Android host for `:demo` |

`:fixtures` exists because the library's tests and the demo need the same bytes and neither can
reach the other: putting them in `:demo` would make the library's tests depend on the demo, which
depends on the library.

## Accessibility

A document is drawn, not laid out: everything ends up as draw calls on one canvas, so there are no
composables for a screen reader to walk. What a reader is told comes from the document itself —
`ACCESSIBILITY_SEMANTICS` on a component, and `ROOT_CONTENT_DESCRIPTION` for the whole thing — and
a document that carries neither says nothing, here or in the official player.

`RemoteComposeCanvas` puts an empty box over the canvas for each labelled component, at the place
and size that component was laid out to, carrying nothing but Compose semantics. The boxes take no
pointer input, so gestures still reach the canvas underneath, and nothing about the drawing
changes. A host that draws a document itself can do the same with `RemoteComposeSemantics`, or read
`RemoteComposeDocument.semantics` and build its own.

`MERGE` and `CLEAR_AND_SET` are resolved into a single label in common code rather than left to
Compose's own descendant merging, which reaches a screen reader on some platforms and arrives
empty on others: a control a reader focuses and then says nothing about is worse than one it skips.

## Fixtures

`tools/rc-writer` is a JVM tool that writes the `.rc` files through
`androidx.compose.remote:remote-creation`, the official writer, pulled from Maven. Nothing in this
repository hand-rolls the bytes, so the fixtures are the format as that writer emits it.

```bash
./gradlew :tools:rc-writer:run --args="watch"     # writes tools/rc-writer/watch.rc
```

Each fixture is carried into `fixtures/` as Base64 so the tests can run on every target, and
`PayloadDriftTest` fails if the two ever disagree.

## Tests

```bash
./gradlew desktopTest
./gradlew iosSimulatorArm64Test       # the same suite, on Kotlin/Native
./gradlew connectedAndroidTest        # the same suite again, on ART, on a device
```

`tools/capture-screens.sh <fixture>...` is a third kind of check: it drives a document onto the
Android emulator and the iOS Simulator, screenshots both, renders the same document headlessly and
compares them. The comparison allows for two rasterizers disagreeing: a channel range within a
small radius, and glyph pixels counted as a proportion instead of matched one to one. Every
threshold in it was measured on renders that already agreed, not picked by hand.
