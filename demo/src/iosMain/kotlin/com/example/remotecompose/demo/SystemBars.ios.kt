package com.example.remotecompose.demo

import androidx.compose.runtime.Composable

/** iOS hides its status bar through `UIStatusBarHidden` in `project.yml`, once and for all. */
@Composable
actual fun HideSystemBars(alsoNavigation: Boolean) = Unit
