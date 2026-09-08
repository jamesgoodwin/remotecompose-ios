package com.example.remotecompose.demo

import androidx.compose.runtime.Composable

/** Desktop has no system bars, and the demo is not run there as an app. */
@Composable
actual fun HideSystemBars(alsoNavigation: Boolean) = Unit
