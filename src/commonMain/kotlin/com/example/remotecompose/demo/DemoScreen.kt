package com.example.remotecompose.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remotecompose.parser.RemoteComposeDocument
import kotlinx.coroutines.delay
import com.example.remotecompose.ui.RemoteComposeCanvas

/**
 * The app entry point on both platforms (see `MainActivity.kt` / `MainViewController.kt`): shows
 * one real `.rc` payload at a time, with a bar along the bottom to move between them.
 *
 * Nothing else navigates. Taps and drags inside a page belong to the document, which is the only
 * way an interactive one is usable: the pages with buttons to press and lists to drag were
 * unreachable while any tap the document did not claim moved to the next page.
 *
 * @param initialPage Which payload to show first (0 coverage, 1 showcase, 2 paint, 3 anim,
 *   4 actions, 5 text paths, 6 generated, 7 material, 8 list, 9 pattern, 10 coffee, 11 article,
 *   12 flight, 13 watch, 14 parallax, 15 carousel, 16 lazy list, 17 snap). Lets the
 *   device hosts launch straight onto a page for scripted screenshots: Android reads an
 *   `--ei page N` intent extra, iOS an `RC_PAGE` environment variable.
 */
@Composable
fun DemoScreen(initialPage: Int = 0) {
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
        "Article" to ARTICLE_RC_BYTES,
        "Flight" to FLIGHT_RC_BYTES,
        "Watch" to WATCH_RC_BYTES,
        "Parallax" to PARALLAX_RC_BYTES,
        "Carousel" to CAROUSEL_RC_BYTES,
        "Lazy list" to LAZYLIST_RC_BYTES,
        "Snap" to NOTCHES_RC_BYTES,
    )
    var page by remember { mutableStateOf(initialPage.coerceIn(0, pages.lastIndex)) }
    val (label, bytes) = pages[page]
    val go = { step: Int -> page = (page + step + pages.size) % pages.size }

    Box(modifier = Modifier.fillMaxSize()) {
        // The document keeps the whole screen and stays centred in it, which is what the pixel
        // harness relies on to find it in a screenshot; the bar floats over the backdrop below.
        when (label) {
            // Both are dragged, and both are dark of their own accord.
            "Parallax", "Carousel", "Lazy list", "Snap" -> RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().background(Color(0xFF12101A)).padding(bottom = BAR_SPACE),
                onAction = { go(1) },
            )
            // Runs on the clock alone, and has a palette for each mode.
            "Watch" -> RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().padding(bottom = BAR_SPACE),
                dark = isSystemInDarkTheme(),
                onAction = { go(1) },
            )
            // The one page where the host has something to say: the document names its values
            // and this feeds them, the way a real one would from a network.
            "Flight" -> {
                var document by remember { mutableStateOf<RemoteComposeDocument?>(null) }
                LaunchedEffect(document) { document?.let { runFlightFeed(it) } }
                RemoteComposeCanvas(
                    bytes = bytes,
                    modifier = Modifier.fillMaxSize().background(Color(0xFFFFFBFE)).padding(bottom = BAR_SPACE),
                    onDocument = { document = it },
                    onAction = { go(1) },
                )
            }
            // Scrolls under the finger, so it goes through the interactive host like the others.
            "Article" -> RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().background(Color(0xFFFFFBFE)).padding(bottom = BAR_SPACE),
                onAction = { go(1) },
            )
            "Coffee" -> RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().padding(bottom = BAR_SPACE),
                dark = isSystemInDarkTheme(),
                onAction = { go(1) },
            )
            // Shown through the public composable rather than the 1:1 screenshot host, so the
            // document is scaled to the screen and its buttons are the size a finger expects.
            "Material" -> RemoteComposeCanvas(
                bytes = bytes,
                modifier = Modifier.fillMaxSize().background(Color(0xFFFEF7FF)).padding(bottom = BAR_SPACE),
                onAction = { go(1) },
            )
            else -> RealPayloadDemoScreen(bytes)
        }
        NavigationBar(
            label = label,
            position = "${page + 1}/${pages.size}",
            onBack = { go(-1) },
            onForward = { go(1) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}

/** How much room to leave a full-screen page so the bar does not sit over its content. */
private val BAR_SPACE = 76.dp

/** Which page is showing, and the two ways to leave it. */
@Composable
private fun NavigationBar(
    label: String,
    position: String,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE6202A2F)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow("‹", onBack)
        Box(modifier = Modifier.width(150.dp), contentAlignment = Alignment.Center) {
            BasicText(
                text = "$label  ·  $position",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Arrow("›", onForward)
    }
}

/** A tap target wide enough for a thumb, whatever the glyph inside measures. */
@Composable
private fun Arrow(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center),
        )
    }
}

/**
 * Stands in for whatever would really be telling this document about the flight: a feed that
 * pushes values by name and lets the document decide what they look like.
 *
 * Everything here is a `setNamed*` call. The panel eases its countdown, moves its bar and opens
 * its delay line on its own — none of that is arranged from out here.
 */
private suspend fun runFlightFeed(document: RemoteComposeDocument) {
    document.setNamedString("flight", "BA 2490")
    document.setNamedString("route", "Bristol to Palma")
    document.setNamedString("status", "Scheduled")
    document.setNamedColor("statusColor", 0xFF5F5A66.toInt())
    document.setNamedString("gate", "—")
    document.setNamedFloat("minutes", 48f)
    delay(2000)

    document.setNamedString("status", "Gate open")
    document.setNamedColor("statusColor", 0xFF2E7D32.toInt())
    document.setNamedString("gate", "B12")
    document.setNamedFloat("minutes", 32f)
    delay(2500)

    // Boarding, and the bar fills as it goes.
    document.setNamedString("status", "Boarding")
    for (step in 1..6) {
        document.setNamedFloat("boarded", step / 6f)
        document.setNamedFloat("minutes", (24 - step * 4).toFloat())
        delay(1200)
    }

    delay(1500)
    document.setNamedString("status", "Delayed")
    document.setNamedColor("statusColor", 0xFFB3261E.toInt())
    document.setNamedFloat("delayed", 1f)
    document.setNamedString("delayNote", "Delayed 25 min · new gate B31")
    document.setNamedString("gate", "B31")
}
