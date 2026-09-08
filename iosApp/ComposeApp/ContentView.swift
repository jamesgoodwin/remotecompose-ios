import SwiftUI
import RemoteComposeDemoShared

/// Hosts the Kotlin-side `DemoScreen` (real `androidx.compose.remote` payloads, parsed by our own
/// `RealRemoteComposeParser` and rendered by our own `OpcodeExecutor`) in a UIKit view controller
/// built by Kotlin's `ComposeUIViewController`.
///
/// `RC_DEMO` in the process environment opens straight onto the payload of that name, so scripted
/// screenshots can skip the list: `SIMCTL_CHILD_RC_DEMO=watch xcrun simctl launch <udid> <bundle
/// id>`. The name is the fixture's, so it survives the demo list being reordered. Without it the
/// app opens on the list. `RC_ONE_TO_ONE=1` draws that payload unscaled, which is what the pixel
/// harness compares against.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let demo = ProcessInfo.processInfo.environment["RC_DEMO"]
        let oneToOne = ProcessInfo.processInfo.environment["RC_ONE_TO_ONE"] == "1"
        return MainViewControllerKt.MainViewController(initialDemo: demo, oneToOne: oneToOne)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
