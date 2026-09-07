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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.sp
import com.example.remotecompose.ui.RemoteComposeCanvas

/**
 * The app entry point on both platforms (see `MainActivity.kt` / `MainViewController.kt`): shows
 * one real `.rc` payload at a time and switches between them. Taps go to the document first, so
 * an interactive document handles its own buttons; a tap it does not claim moves to the next page.
 *
 * @param initialPage Which payload to show first (0 coverage, 1 showcase, 2 paint, 3 anim,
 *   4 actions, 5 text paths, 6 generated, 7 material, 8 list, 9 pattern, 10 coffee). Lets the device hosts launch straight onto
 *   a page for scripted screenshots: Android reads an `--ei page N` intent extra, iOS an
 *   `RC_PAGE` environment variable.
 */
@Composable
fun DemoScreen(initialPage: Int = 0) {
    var page by remember { mutableStateOf(initialPage.coerceIn(0, 10)) }
    val pages = listOf(
        "Coverage" to SAMPLE_RC_BYTES,
        "Showcase" to SHOWCASE_RC_BYTES,
        "Paint" to PAINT_RC_BYTES,
        "Anim" to ANIM_RC_BYTES,
        "Actions" to ACTIONS_RC_BYTES,
        "Text paths" to TEXTPATH_RC_BYTES,
        "Generated" to ADVANCED_RC_BYTES,
        "Material" to MATERIAL_RC_BYTES,
        "List" to LIST_RC_BYTES,
        "Pattern" to PATTERN_RC_BYTES,
        "Coffee" to COFFEE_RC_BYTES,
    )
    val (label, bytes) = pages[page]
    val next = { page = (page + 1) % pages.size }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (label == "Coffee") {
            // The one page shown at the size a phone would show it: it scrolls and it has a dark
            // palette, so it is given the whole screen and told which mode the system is in.
            RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize(),
                dark = isSystemInDarkTheme(),
                onAction = { next() },
            )
        } else if (label == "Material") {
            // The one page shown through the public composable rather than the 1:1 screenshot
            // host: it scales the document to the screen, so the buttons are the size a finger
            // expects. Its own "Next demo" button asks the host to move on through a host action.
            RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().background(Color(0xFFFEF7FF)),
                onAction = { next() },
            )
        } else {
            // A tap the document itself does not handle switches to the next page.
            RealPayloadDemoScreen(bytes) { next() }
        }
        // The Material and Coffee pages carry their own chrome and their own way out, so the
        // overlay label would only cover what they draw at the bottom.
        if (label != "Material" && label != "Coffee") BasicText(
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
