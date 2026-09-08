# remotecompose-ios

**Draws a complete, interactive screen that arrived as a file.**

A `.rc` document describes a screen: its layout, its text and images, its animation, and the
arithmetic behind all of it. Hand one to this library and it measures it, draws it, runs the
animation and handles the gestures. The app around it does not need to know what is inside, so
changing the screen means sending a different file instead of shipping a new build.

The documents are not pictures. A watch face follows the clock, a list scrolls under a finger, and
a panel re-measures itself when a value is set in it, all from expressions the file carries. None
of it needs a round trip to a server, and none of it is code the host has to run.

The format is RemoteCompose, part of
[`androidx.compose.remote`](https://developer.android.com/jetpack/androidx/releases/compose-remote),
whose official player runs on Android. This project reads the same bytes and draws them on iOS,
shipped as a Swift package.

It builds for Android and desktop too, which is how the iOS output gets checked: the pixel harness
renders a document on both platforms and compares the results. The Android player is the reference
implementation, and where the two disagree the assumption is that this one is wrong.

## Using it

An XCFramework behind a Swift package, so an iOS app can render `.rc` documents without a Kotlin
toolchain in its build. iOS 14 and up.

```swift
// The package is RemoteCompose; the repository it comes from is not.
.package(url: "https://github.com/jamesgoodwin/remotecompose-ios", from: "0.1.0"),
.product(name: "RemoteCompose", package: "remotecompose-ios"),
```

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

Gestures, animation and the frame loop are handled inside: a document that scrolls follows a
finger, one that animates keeps its own time, one that does neither is drawn once. Documents carry
a palette for each of light and dark, and the view follows the environment's `colorScheme`.

For a document whose values the host fills in, hold a `RemoteComposeDocument` and pass that
instead. Values set before it is on screen are applied when it gets there.

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

The `setString`/`setFloat`/`setInteger`/`setLong`/`setColor` calls return `false` if the document
named no such value, so a host pushing one that has nowhere to go finds out. UIKit callers can take
`document.makeViewController()` and skip the SwiftUI wrapper.

Worth adding to the app's own `Info.plist`, since no library can set it for you:

```xml
<key>CADisableMinimumFrameDurationOnPhone</key><true/>
```

Without it iOS caps Compose at 60Hz on a display that can do 120, which an animating document will
show. This library logs a line about it once; Compose Multiplatform's own default is to refuse to
start.

## Status

**Experimental.** It has not been used in anything that ships, and its API is not stable.

**154 of the format's 172 opcodes** are decoded, 132 of them acted on in full.
[`docs/OPCODES.md`](docs/OPCODES.md) lists every one. Anything not listed is not decoded, and a
document containing one throws `RemoteComposeParseException` at the byte where it appears: the
format has no generic length prefix, so an unknown record cannot be skipped safely.

Known gaps:

- **Text is measured by the platform, not by Android**, so line breaking and anything sized off a
  string differ from an Android render by a few pixels.
- **No shader compilation.** `DATA_SHADER` decodes its uniforms; drawing with one needs a runtime
  shader compiler this does not have.
- **No blur, shadow or perspective**, and `ROTATION_X`/`ROTATION_Y` foreshorten without a
  vanishing point.
- **No sound.**
- **Targets 1.0.0-alpha18 only.** There is no version negotiation.

## Using it from Kotlin

The same renderer is a Compose Multiplatform composable, for an Android or desktop host, or for the
Android side of a KMP app whose iOS side uses the Swift package. It is not on Maven yet, so this
means depending on the module in a build of your own.

```kotlin
RemoteComposeCanvas(
    bytes = documentBytes,
    modifier = Modifier.fillMaxSize(),
    dark = isSystemInDarkTheme(),
    onAction = { action -> /* HOST_ACTION reaches you here */ },
    onDocument = { it.setNamedString("route", "Bristol to Palma") },
)
```

Below the composable, `RemoteComposeParser.load(bytes)` gives a `RemoteComposeDocument` whose
`frame(nowMillis)` returns the draw list for one frame, and whose `click`/`touchDown`/`touchDrag`/
`touchUp` deliver gestures.

## Trying it

The demo app opens on a list of the documents in `tools/rc-writer/` and shows one full screen once
it is tapped.

```bash
# ios: open iosApp/RemoteComposeDemo.xcodeproj and Run, or
xcrun simctl launch booted io.github.jamesgoodwin.remotecompose.demo

# android, to put the same document beside it
./gradlew :androidApp:installDebug
adb shell am start -n io.github.jamesgoodwin.remotecompose.androidapp/.MainActivity

# straight onto one document, named by its fixture
SIMCTL_CHILD_RC_DEMO=watch xcrun simctl launch booted io.github.jamesgoodwin.remotecompose.demo
adb shell am start -n io.github.jamesgoodwin.remotecompose.androidapp/.MainActivity --es demo watch
```

## More

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - the two-phase pipeline, the module split, how
  fixtures are generated, and how the test suites and pixel harness check them
- [`docs/OPCODES.md`](docs/OPCODES.md) - every opcode and what this renderer does with it
- [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) - the same code measured on Kotlin/Native and ART
- [`docs/RELEASING.md`](docs/RELEASING.md) - cutting a release of the Swift package

## Provenance

This is an independent reimplementation. It is **not affiliated with or endorsed by Google or the
AndroidX team**.

The format was worked out by disassembling the published `androidx.compose.remote` jars with
`javap` and transcribing what the classes do. That library is Apache 2.0 and its source is public;
this repository contains none of its code, and the runtime here depends only on Compose
Multiplatform. The Google artifacts are a build-time dependency of the fixture writer alone.

Photographs in the demo documents are CC0 from Wikimedia Commons;
`tools/rc-writer/photos/CREDITS.md` says who took each one.

## Licence

Apache License 2.0 - see [LICENSE](LICENSE).
