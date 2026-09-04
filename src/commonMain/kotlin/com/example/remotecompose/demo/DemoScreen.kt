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
 * The actual app entry point on both platforms (see `MainActivity.kt` / `MainViewController.kt`):
 * owns which of the two real payloads [RealPayloadDemoScreen] is currently rendering, and a plain
 * tap anywhere on the screen swaps between them — [SAMPLE_RC_BYTES] (the dense opcode-coverage
 * probe grid this loop's byte-coverage iterations verify against) and [SHOWCASE_RC_BYTES] (the
 * "dashboard card" this loop's layout-engine iterations verify against, meant to actually look
 * like something). Both are real `.rc` payloads from the official writer, decoded and rendered
 * through the exact same [RealPayloadDemoScreen] path — this composable adds nothing to that,
 * it's purely a demo-host switcher.
 */
@Composable
fun DemoScreen() {
    var showcase by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { showcase = !showcase } },
    ) {
        RealPayloadDemoScreen(if (showcase) SHOWCASE_RC_BYTES else SAMPLE_RC_BYTES)
        BasicText(
            text = if (showcase) "Showcase — tap to switch" else "Coverage — tap to switch",
            style = TextStyle(color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(Color(0x66000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
