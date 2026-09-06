package com.example.remotecompose.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift (`ContentView.swift`) to host the round-trip demo in a UIKit view
 * controller. [initialPage] selects the payload shown first; see [DemoScreen].
 */
fun MainViewController(initialPage: Int = 0): UIViewController = ComposeUIViewController {
    DemoScreen(initialPage = initialPage)
}
