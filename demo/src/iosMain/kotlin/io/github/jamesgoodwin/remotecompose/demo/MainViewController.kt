package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift (`ContentView.swift`) to host the round-trip demo in a UIKit view
 * controller. [initialDemo] opens straight onto the payload of that name and [oneToOne] draws it
 * unscaled; see [DemoScreen].
 */
fun MainViewController(initialDemo: String? = null, oneToOne: Boolean = false): UIViewController =
    ComposeUIViewController {
        DemoScreen(initialDemo = initialDemo, oneToOne = oneToOne)
    }
