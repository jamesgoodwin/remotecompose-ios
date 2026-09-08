package com.example.remotecompose.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift (`ContentView.swift`) to host the round-trip demo in a UIKit view
 * controller. [initialPage] opens straight onto a payload and [oneToOne] draws it unscaled; see
 * [DemoScreen].
 */
fun MainViewController(initialPage: Int = -1, oneToOne: Boolean = false): UIViewController =
    ComposeUIViewController {
        DemoScreen(initialPage = initialPage, oneToOne = oneToOne)
    }
