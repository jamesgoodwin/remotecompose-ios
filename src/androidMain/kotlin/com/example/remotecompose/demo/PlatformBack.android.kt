package com.example.remotecompose.demo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** The system back gesture, or the button where a device still has one. */
@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
    content()
}
