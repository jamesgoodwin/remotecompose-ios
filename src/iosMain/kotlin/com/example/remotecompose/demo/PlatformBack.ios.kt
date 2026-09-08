package com.example.remotecompose.demo

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The interactive-pop gesture, by hand.
 *
 * A view controller inside a `UINavigationController` gets this from UIKit; a bare Compose one
 * does not, and hosting the demo in a navigation stack would mean writing its list twice — once
 * in SwiftUI for iOS and once in Compose for everything else. So the strip down the left edge
 * watches for the same movement UIKit would: a drag that starts within a thumb's width of the
 * edge and travels far enough right.
 *
 * The strip is over the page, so a gesture beginning inside it does not reach the document —
 * which is what the system gesture does too.
 */
@Composable
actual fun BackGesture(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (enabled) {
            val threshold = with(LocalDensity.current) { 64.dp.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(EDGE_WIDTH)
                    .fillMaxHeight()
                    .pointerInput(onBack) {
                        var travelled = 0f
                        var done = false
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f; done = false },
                            onDragEnd = { travelled = 0f },
                        ) { _, delta ->
                            travelled += delta
                            if (!done && travelled > threshold) {
                                done = true
                                onBack()
                            }
                        }
                    },
            )
        }
    }
}

/** As wide as the region UIKit listens on, which is about a thumb. */
private val EDGE_WIDTH = 20.dp
