package com.example.remotecompose.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app entry point on both platforms (see `MainActivity.kt` / `MainViewController.kt`): shows
 * one real `.rc` payload at a time and switches between them. Taps go to the document first, so
 * an interactive document handles its own buttons; a tap it does not claim moves to the next page.
 *
 * @param initialPage Which payload to show first (0 coverage, 1 showcase, 2 paint, 3 anim,
 *   4 actions, 5 text paths, 6 generated). Lets the device hosts launch straight onto a page for scripted screenshots:
 *   Android reads an `--ei page N` intent extra, iOS an `RC_PAGE` environment variable.
 */
@Composable
fun DemoScreen(initialPage: Int = 0) {
    var page by remember { mutableStateOf(initialPage.coerceIn(0, 6)) }
    val pages = listOf(
        "Coverage" to SAMPLE_RC_BYTES,
        "Showcase" to SHOWCASE_RC_BYTES,
        "Paint" to PAINT_RC_BYTES,
        "Anim" to ANIM_RC_BYTES,
        "Actions" to ACTIONS_RC_BYTES,
        "Text paths" to TEXTPATH_RC_BYTES,
        "Generated" to ADVANCED_RC_BYTES,
    )
    val (label, bytes) = pages[page]
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // A tap the document itself does not handle switches to the next page.
        RealPayloadDemoScreen(bytes) { page = (page + 1) % pages.size }
        BasicText(
            text = "$label — tap to switch",
            style = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
