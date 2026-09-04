import SwiftUI
import RemoteComposeShared

/// Hosts the Kotlin-side `RealPayloadDemoScreen` (a real `androidx.compose.remote` payload,
/// parsed by our own `RealRemoteComposeParser` and rendered by our own `OpcodeExecutor`) in a
/// UIKit view controller built by Kotlin's `ComposeUIViewController`.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
