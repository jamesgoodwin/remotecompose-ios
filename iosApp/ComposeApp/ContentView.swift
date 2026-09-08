import SwiftUI
import RemoteComposeShared

/// Hosts the Kotlin-side `DemoScreen` (real `androidx.compose.remote` payloads, parsed by our own
/// `RealRemoteComposeParser` and rendered by our own `OpcodeExecutor`) in a UIKit view controller
/// built by Kotlin's `ComposeUIViewController`.
///
/// `RC_PAGE` in the process environment opens straight onto a payload, so scripted screenshots can
/// skip the list: `SIMCTL_CHILD_RC_PAGE=2 xcrun simctl launch <udid> <bundle id>`. Without it the
/// app opens on the list, which is what -1 asks for.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let page = Int32(ProcessInfo.processInfo.environment["RC_PAGE"] ?? "-1") ?? -1
        return MainViewControllerKt.MainViewController(initialPage: page)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
