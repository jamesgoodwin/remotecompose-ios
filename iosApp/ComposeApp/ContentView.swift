import SwiftUI
import RemoteComposeShared

/// Hosts the Kotlin-side `DemoScreen` (real `androidx.compose.remote` payloads, parsed by our own
/// `RealRemoteComposeParser` and rendered by our own `OpcodeExecutor`) in a UIKit view controller
/// built by Kotlin's `ComposeUIViewController`.
///
/// `RC_PAGE` in the process environment picks the payload shown first, so scripted screenshots can
/// launch straight onto a page: `SIMCTL_CHILD_RC_PAGE=2 xcrun simctl launch <udid> <bundle id>`.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let page = Int32(ProcessInfo.processInfo.environment["RC_PAGE"] ?? "0") ?? 0
        return MainViewControllerKt.MainViewController(initialPage: page)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
