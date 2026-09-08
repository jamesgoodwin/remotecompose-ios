package io.github.jamesgoodwin.remotecompose.demo

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException

/**
 * The system back gesture, and the progress it reports while the finger is still down.
 *
 * The flow the handler hands over is the gesture: each event carries how far through it is, it
 * completes when the finger lifts past the point of no return, and it is cancelled when the
 * gesture is abandoned. `android:enableOnBackInvokedCallback` in the manifest is what makes the
 * system send the progress at all; without it the flow completes at once and this is an ordinary
 * back.
 */
@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable (Float) -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progress = it.progress }
            progress = 0f
            onBack()
        } catch (cancelled: CancellationException) {
            progress = 0f
            throw cancelled
        }
    }
    content(progress)
}
