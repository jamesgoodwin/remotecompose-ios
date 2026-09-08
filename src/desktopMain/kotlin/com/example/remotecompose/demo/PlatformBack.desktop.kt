package com.example.remotecompose.demo

import androidx.compose.runtime.Composable

/** Desktop has no back gesture, and the demo is not run there as an app. */
@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    content()
}
