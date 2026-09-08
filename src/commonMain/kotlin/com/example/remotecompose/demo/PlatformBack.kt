package com.example.remotecompose.demo

import androidx.compose.runtime.Composable

/**
 * Wraps [content] in the platform's own way of going back, for as long as [enabled] holds.
 *
 * Android has one and it is the system back gesture. iOS has one for a view controller inside a
 * navigation stack, which a bare Compose view controller is not, so there it is the same
 * left-edge swipe, detected by hand. Desktop has none.
 *
 * Compose Multiplatform grew a common `BackHandler` in 1.8; this project is on 1.7.
 */
@Composable
expect fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit)
