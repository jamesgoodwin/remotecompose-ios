package com.example.remotecompose.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point called from Swift (`ComposeApp.swift`) to host the round-trip demo in a UIKit view controller. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    RealPayloadDemoScreen(SAMPLE_RC_BYTES)
}
