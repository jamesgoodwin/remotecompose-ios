# remotecompose-ios

**RemoteCompose documents, rendered on iOS.**

[`androidx.compose.remote`](https://developer.android.com/jetpack/androidx/releases/compose-remote)
is an AndroidX library. A `.rc` file it produces is a self-contained interactive UI: it carries its
own arithmetic, so a watch face follows the clock and a list scrolls under a finger with no round
trip to a server. The official player runs on Android.

This project reads the same wire format and draws it on iOS, from the bytes the official writer
produces. It ships as a Swift package; see "Using it".

It builds for Android and desktop as well, and those are how the iOS output gets checked. The pixel
harness renders a document on both platforms and compares the results, and `docs/PERFORMANCE.md`
runs the same code on both runtimes. The Android player is the reference implementation. Where the
two disagree, the assumption is that this one is wrong.

A document is a list of operations, not a picture: pools of text, colour and bitmaps, expressions
over them, components to lay out, and draws. A panel re-lays itself out when the host sets a named
value, and a tap runs an action the document declared.

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

## Status

**Experimental.** It renders every document in `tools/rc-writer/` on iOS, Android and desktop, and
the test suite runs on all three. It has not been used in anything that ships, and its API is not
stable.

The library builds with Kotlin's `explicitApi()`. Anything that is not part of the API is
`internal`, which keeps the public surface to 29 declarations and keeps the iOS framework's
Objective-C header to roughly what a caller needs.

154 of the format's 172 opcodes are decoded, of which 132 are acted on in full. `docs/OPCODES.md`
lists every one and says which of three things it is:

- **supported** — decoded and acted on, with a fixture and a test behind it
- **partial** — the common path is acted on and the note says what is not
- **decoded only** — read so the byte stream stays aligned, with no effect on the render

Anything not listed is not decoded, and a document containing one **throws**
`RemoteComposeParseException` at the byte where it appears. The format has no generic length prefix,
so an unknown record cannot be skipped safely. Five of the eighteen are not implemented upstream
either.

### Known limitations

- **Text is measured by the platform, not by Android.** Line breaking, `TEXT_MEASURE` and
  anything sized off a string differ from an Android render by a few pixels.
- **No shader compilation.** `DATA_SHADER` decodes its uniforms; drawing with one needs a runtime
  shader compiler this does not have.
- **No blur, shadow or perspective.** The graphics-layer attributes that need them are decoded
  and ignored; `ROTATION_X`/`ROTATION_Y` foreshorten without a vanishing point.
- **No sound.**
- **Targets 1.0.0-alpha18 only.** There is no version negotiation.

## Using it

An XCFramework behind a Swift package, so an iOS app can render `.rc` documents without a Kotlin
toolchain in its build:

```swift
// The package is RemoteCompose; the repository it comes from is not.
dependencies: [
    .package(url: "https://github.com/jamesgoodwin/remotecompose-ios", from: "0.1.0"),
],
targets: [
    .target(name: "YourApp", dependencies: [
        .product(name: "RemoteCompose", package: "remotecompose-ios"),
    ]),
]
```

That resolves against a tagged release with the XCFramework attached to it, and against nothing
else — the binary is not in the repository. See "Releasing it" for cutting one.

```swift
import RemoteCompose

struct WatchFace: View {
    let document: Data   // the bytes of a .rc file

    var body: some View {
        RemoteComposeView(data: document)
            .ignoresSafeArea()
    }
}
```

Gestures, animation and the frame loop are handled inside, the same as on the Kotlin side: a
document that scrolls follows a finger, one that animates keeps its own time, one that does
neither is drawn once. A document carries a palette for each of light and dark, and the view
follows the environment's `colorScheme`.

For a document whose values the host fills in, hold a `RemoteComposeDocument` and pass that
instead. Values set before it is on screen are applied when it gets there, so there is no ordering
to arrange:

```swift
@StateObject private var flight = RemoteComposeDocument(data: document)

var body: some View {
    RemoteComposeView(document: flight)
        .onAppear {
            flight.setString("route", "Bristol to Palma")
            flight.setFloat("minutes", 23)
            flight.onAction = { action in print(action.id, action.url as Any) }
        }
}
```

`setString`/`setFloat`/`setInteger`/`setLong`/`setColor` return `false` when the document is on
screen and named no such value, or named it as a different kind, so a host pushing a value that
has nowhere to go finds out. UIKit callers can take
`document.makeViewController()` and skip the SwiftUI wrapper.

The framework is iOS 14 and up, which is Compose Multiplatform's floor and what its binaries
declare.

One entry is worth adding to the app's own `Info.plist`:

```xml
<key>CADisableMinimumFrameDurationOnPhone</key><true/>
```

Without it iOS caps Compose at 60Hz on a display that can do 120, and an animating document is
exactly where that shows. The entry has to be in the app's own plist, so no library can set it for
you. This one logs a line about it once; Compose Multiplatform's default is to refuse to start.

## Trying it

The demo app opens on a list of the documents in `tools/rc-writer/` and shows one full screen
once it is tapped. Every one is generated by the official writer, not written by hand; see
"Fixtures" below.

```bash
# ios: open iosApp/RemoteComposeDemo.xcodeproj and Run, or
xcrun simctl launch booted io.github.jamesgoodwin.remotecompose.demo

# android, to put the same document beside it
./gradlew :androidApp:installDebug
adb shell am start -n io.github.jamesgoodwin.remotecompose.androidapp/.MainActivity

# either one, straight onto a document rather than the list, named by its fixture
SIMCTL_CHILD_RC_DEMO=watch xcrun simctl launch booted io.github.jamesgoodwin.remotecompose.demo
adb shell am start -n io.github.jamesgoodwin.remotecompose.androidapp/.MainActivity --es demo watch

# desktop, which renders headlessly to a PNG — this is the reference the pixel harness diffs
# the two devices against, not a platform anyone is asked to ship on
./gradlew runDesktopDemo --args="tools/rc-writer/watch.rc out.png"
./gradlew runDesktopDemo --args="tools/rc-writer/coffee.rc out.png 2.5 150:180"
```

## Using it from Kotlin

The same renderer is a Compose Multiplatform composable, for an Android or desktop host, or for
the Android side of a Kotlin Multiplatform app that uses the Swift package on iOS. It is not
published to Maven yet, so this means depending on the module in a build of your own.

```kotlin
RemoteComposeCanvas(
    bytes = documentBytes,
    modifier = Modifier.fillMaxSize(),
    dark = isSystemInDarkTheme(),
    onAction = { action -> /* HOST_ACTION reaches you here */ },
)
```

Gestures, animation and the frame loop are handled inside. For a document whose values a host
fills in by name, keep the `RemoteComposeDocument`:

```kotlin
RemoteComposeCanvas(
    bytes = documentBytes,
    onDocument = { document ->
        document.namedValues          // what this document expects to be told
        document.setNamedString("route", "Bristol to Palma")
        document.setNamedFloat("minutes", 23f)
    },
)
```

Below the composable, `RemoteComposeParser.load(bytes)` gives a `RemoteComposeDocument` whose
`frame(nowMillis)` returns the draw list for one frame, and whose `click`/`touchDown`/`touchDrag`/
`touchUp` deliver gestures. `nextRepaintDelayMillis()` says how long a host may wait before
drawing again, and returns `-1` when nothing has asked for another frame.

## Fixtures

`tools/rc-writer` is a JVM tool that writes the `.rc` files through
`androidx.compose.remote:remote-creation`, the official writer, pulled from Maven. Nothing in this
repository hand-rolls the bytes, so the fixtures are the format as that writer emits it.

```bash
./gradlew :tools:rc-writer:run --args="watch"     # writes tools/rc-writer/watch.rc
```

Each fixture is carried into `fixtures/` as Base64 so the tests can run on every target, and
`PayloadDriftTest` fails if the two ever disagree.

The repository is four Gradle modules, and the split is the one the published framework needs:

| | |
| --- | --- |
| `:` | the renderer, and nothing else — this is what the Swift package ships |
| `:fixtures` | the writer's documents as Base64, for the tests and the demo to share |
| `:demo` | the demo app's screens, and its own `RemoteComposeDemoShared` framework for `iosApp` |
| `:androidApp` | the Android host for `:demo` |

`:fixtures` exists because the library's tests and the demo need the same bytes and neither can
reach the other: putting them in `:demo` would make the library's tests depend on the demo, which
depends on the library.

## Tests

```bash
./gradlew desktopTest
./gradlew iosSimulatorArm64Test       # the same suite, on Kotlin/Native
./gradlew connectedAndroidTest        # the same suite again, on ART, on a device
```

`tools/capture-screens.sh <fixture>...` is a third kind of check: it drives a document onto the
Android emulator and the iOS Simulator, screenshots both, renders the same document headlessly and
compares them.
The comparison allows for two rasterizers disagreeing: a channel range within a small radius, and
glyph pixels counted as a proportion instead of matched one to one. Every threshold in it was
measured on renders that already agreed, not picked by hand.

## Releasing it

`Package.swift` names a release asset and a checksum, so the package resolves against a GitHub
release and nothing else. The zip is 38MB and `build/` is ignored, so it is never committed — it is
built and uploaded:

```bash
tools/release-xcframework.sh v0.1.0     # assembles, zips, writes the url and checksum
git commit -am "Point the package at v0.1.0"
git push
gh release create v0.1.0 build/XCFrameworks/RemoteComposeShared.xcframework.zip --title v0.1.0
```

Upload the exact file that script produced. Kotlin/Native does not link reproducibly, so building
again gives a different zip and the checksum just committed no longer matches.

Give each release a new version; do not replace one. SwiftPM defends against a published tag being
repointed, in two ways that both look like a broken build to whoever hits them:

- it records version to revision the first time it resolves a package, in
  `~/Library/org.swift.swiftpm/security/fingerprints`, and refuses afterwards with *does not match
  previously recorded value*
- it caches the binary artifact under the download URL, in
  `~/Library/Caches/org.swift.swiftpm/artifacts`, so the same URL with new bytes hands back the old
  ones and fails the checksum

Neither affects a machine that has never resolved the package, which is why replacing v0.1.0 before
anyone depended on it was safe. It is not safe afterwards, and nothing about the failure tells the
person hitting it to delete those two directories.

The repository has to be public before SwiftPM can fetch the asset; a private release asset needs
credentials SwiftPM will not send.

## Provenance

This is an independent reimplementation. It is **not affiliated with or endorsed by Google or the
AndroidX team**.

The format was worked out by disassembling the published `androidx.compose.remote` jars with
`javap` and transcribing what the classes do. That library is Apache 2.0 and its source is public;
this repository contains none of its code, and the runtime here depends only on Compose
Multiplatform. The Google artifacts are a build-time dependency of the fixture writer alone.

The code names the class and method it is following at each decision, and says so when it departs
from one. `docs/OPCODES.md` summarises where it does both.

`docs/PERFORMANCE.md` measures the shared pipeline on Kotlin/Native and on ART, and says which
differences between the two platforms are real and which are an artefact of how the app was built.

Photographs in the demo documents are CC0 from Wikimedia Commons; `tools/rc-writer/photos/CREDITS.md`
says who took each one.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
