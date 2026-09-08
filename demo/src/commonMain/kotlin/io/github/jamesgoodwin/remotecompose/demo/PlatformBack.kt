package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.runtime.Composable

/**
 * The platform's back gesture, with the progress of one in flight.
 *
 * [content] is given 0 when nothing is happening and rises towards 1 as the gesture goes on, so
 * the screen can move under the finger and show where it is going rather than snapping when the
 * finger lifts. [onBack] runs when the gesture completes; when it is abandoned the progress falls
 * back to 0 and nothing else happens.
 *
 * Android has a system gesture that reports this, through `PredictiveBackHandler`. iOS has one
 * for a view controller inside a navigation stack, which a bare Compose view controller is not,
 * so there it is the same left-edge swipe measured by hand. Desktop has none and stays at 0.
 *
 * Compose Multiplatform grew a common `BackHandler` in 1.8; this project is on 1.7.
 */
@Composable
expect fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable (Float) -> Unit)
