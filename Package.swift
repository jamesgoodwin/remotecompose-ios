// swift-tools-version: 5.9
import PackageDescription

// The Kotlin framework is a binary artifact, not source: it is Compose Multiplatform and Skia
// compiled ahead of time by Kotlin/Native, and there is no way for SwiftPM to build it. It is
// attached to the matching GitHub release by tools/release-xcframework.sh, which also writes the
// two lines below. `RemoteCompose` is the Swift API over it; the framework's own Objective-C
// surface is Kotlin-shaped and is not what you are meant to hold.
//
// iOS 14 is what the framework's binaries declare (`otool -l` → LC_BUILD_VERSION minos), which is
// Compose Multiplatform's floor, not a choice made here.
let package = Package(
    name: "RemoteCompose",
    platforms: [
        .iOS(.v14),
    ],
    products: [
        .library(name: "RemoteCompose", targets: ["RemoteCompose"]),
    ],
    targets: [
        .target(
            name: "RemoteCompose",
            dependencies: ["RemoteComposeShared"]
        ),
        .binaryTarget(
            name: "RemoteComposeShared",
            url: "https://github.com/jamesgoodwin/apple-remote-compose/releases/download/v0.1.0/RemoteComposeShared.xcframework.zip",
            checksum: "7f20603288cdfef1dcab3ce8e83626bafeacf3c37a7def526f5adcf441d7cae2"
        ),
    ]
)
