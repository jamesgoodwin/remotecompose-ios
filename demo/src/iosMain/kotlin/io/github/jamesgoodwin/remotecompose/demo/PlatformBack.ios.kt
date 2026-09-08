package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The interactive-pop gesture, by hand.
 *
 * A view controller inside a `UINavigationController` gets this from UIKit; a bare Compose one
 * does not, and hosting the demo in a navigation stack would mean writing its list twice — once
 * in SwiftUI for iOS and once in Compose for everything else. So the strip down the left edge
 * watches for the same movement UIKit would, and reports how far through it is: the screen
 * follows the finger, completes past the halfway point, and eases back if it does not get there.
 *
 * The strip is over the page, so a gesture beginning inside it does not reach the document —
 * which is what the system gesture does too.
 */
@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable (Float) -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // How far the gesture would have to travel to be a whole one, which UIKit takes as the width
    // of the thing being pushed off. The strip the gesture is detected in is a thumb wide, so it
    // cannot be asked.
    var span by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(enabled) { if (!enabled) progress.snapTo(0f) }

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { span = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        content(progress.value)
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(EDGE_WIDTH)
                    .fillMaxHeight()
                    .pointerInput(onBack) {
                        var travelled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = {
                                if (progress.value >= COMPLETE_AT) {
                                    onBack()
                                    scope.launch { progress.snapTo(0f) }
                                } else {
                                    scope.launch { progress.animateTo(0f) }
                                }
                            },
                            onDragCancel = { scope.launch { progress.animateTo(0f) } },
                        ) { _, delta ->
                            travelled = (travelled + delta).coerceIn(0f, span)
                            scope.launch { progress.snapTo(travelled / span) }
                        }
                    },
            )
        }
    }
}

/** As wide as the region UIKit listens on, which is about a thumb. */
private val EDGE_WIDTH = 20.dp

/** Where UIKit's own interactive pop commits rather than springing back. */
private const val COMPLETE_AT = 0.5f
