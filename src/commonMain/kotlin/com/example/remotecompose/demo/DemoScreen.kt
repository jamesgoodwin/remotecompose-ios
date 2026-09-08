package com.example.remotecompose.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import com.example.remotecompose.model.RemoteAction
import com.example.remotecompose.parser.RemoteComposeDocument
import com.example.remotecompose.ui.RemoteComposeCanvas
import kotlinx.coroutines.delay

/**
 * One document in the demo: what to call it, what it is for, and how it wants to be shown.
 *
 * [oneToOne] draws the document at its own size in the middle of the screen rather than scaling
 * it to fill. That is what the pixel harness compares against, so the documents it covers are
 * shown that way.
 */
private class Demo(
    val name: String,
    val about: String,
    val bytes: ByteArray,
    val background: Color? = null,
    val followsSystemTheme: Boolean = false,
    val oneToOne: Boolean = false,
    val feed: (suspend (RemoteComposeDocument) -> Unit)? = null,
)

private val DEMOS = listOf(
    Demo("Coverage", "One drawing per opcode, which the golden test pins", SAMPLE_RC_BYTES, oneToOne = true),
    Demo("Showcase", "The format's drawing and layout on one page", SHOWCASE_RC_BYTES, oneToOne = true),
    Demo("Paint", "Every paint attribute: strokes, caps, joins, gradients", PAINT_RC_BYTES, oneToOne = true),
    Demo("Anim", "Values that move with the clock, and the curves they move on", ANIM_RC_BYTES, oneToOne = true),
    Demo("Actions", "Buttons that write the document's own values", ACTIONS_RC_BYTES, oneToOne = true),
    Demo("Text paths", "Glyphs placed along a curve, one at a time", TEXTPATH_RC_BYTES, oneToOne = true),
    Demo("Generated", "Functions, path expressions, particles and matrices", ADVANCED_RC_BYTES, oneToOne = true),
    Demo("Material", "A Material screen, scaled to the window it is shown in", MATERIAL_RC_BYTES, background = Color(0xFFFEF7FF)),
    Demo("List", "One row body over a list, with its bars read from a float list", LIST_RC_BYTES, oneToOne = true),
    Demo("Pattern", "A card written once and called three times, each with its own contents", PATTERN_RC_BYTES, oneToOne = true),
    Demo("Coffee", "A shop: themed colours, a scrolling menu, rows that expand", COFFEE_RC_BYTES, followsSystemTheme = true),
    Demo("Article", "A progress bar the document works out from its own scroll", ARTICLE_RC_BYTES, background = Color(0xFFFFFBFE)),
    Demo("Flight", "Every value fed by name from outside, and eased on the way in", FLIGHT_RC_BYTES, background = Color(0xFFFFFBFE), feed = ::runFlightFeed),
    Demo("Watch", "Hands and a date from the clock alone, in two palettes", WATCH_RC_BYTES, followsSystemTheme = true),
    Demo("Parallax", "A photograph moving slower than the words over it", PARALLAX_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Carousel", "Cards that fling, shrinking and dimming away from the middle", CAROUSEL_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Lazy list", "Five hundred rows, of which only the ones in view are built", LAZYLIST_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Snap", "The four ways a released scroll can be told where to stop", NOTCHES_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Referenced", "One block of operations, drawn on three cards", REFERENCED_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Wrapping", "Line breaking, ellipsis and justification, and text that expands", WRAP_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Layout", "A document working out its own size, place and measurements", LAYOUT_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Marquee", "Text too long for its box, slid rather than left clipped", MARQUEE_RC_BYTES, background = Color(0xFF12101A)),
    Demo("Run action", "Actions that run because a component was painted", RUNACTION_RC_BYTES, background = Color(0xFF12101A)),
)

/**
 * The app entry point on both platforms (see `MainActivity.kt` / `ContentView.swift`): a list of
 * the documents, and one of them full screen once it is tapped.
 *
 * Nothing else navigates. Taps and drags inside a document belong to the document, which is the
 * only way an interactive one is usable, so the way back is a bar floating over the page rather
 * than anything the document could swallow.
 *
 * @param initialPage Opens straight onto that document, for scripted screenshots: Android reads
 *   an `--ei page N` intent extra, iOS an `RC_PAGE` environment variable. Anything outside the
 *   list — which is what both hosts pass when nothing was asked for — shows the list instead.
 */
@Composable
fun DemoScreen(initialPage: Int = -1) {
    var open by remember { mutableStateOf(initialPage.takeIf { it in DEMOS.indices }) }
    val index = open
    if (index == null) {
        DemoList(onOpen = { open = it })
    } else {
        DemoPage(demo = DEMOS[index], onBack = { open = null })
    }
}

/** The documents, by name and by what each is for. */
@Composable
private fun DemoList(onOpen: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        BasicText(
            text = "RemoteCompose",
            style = TextStyle(color = Color(0xFFF2F3F8), fontSize = 28.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = "Documents written by the Android writer, drawn here",
            style = TextStyle(color = Color(0xFF8A90A6), fontSize = 13.sp),
        )
        Spacer(Modifier.height(20.dp))
        for ((index, demo) in DEMOS.withIndex()) {
            DemoRow(demo, onClick = { onOpen(index) })
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun DemoRow(demo: Demo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1F27))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        BasicText(
            text = demo.name,
            style = TextStyle(color = Color(0xFFF2F3F8), fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(3.dp))
        BasicText(
            text = demo.about,
            style = TextStyle(color = Color(0xFF8A90A6), fontSize = 12.sp),
        )
    }
}

/**
 * One document, with the whole screen to itself and a bar floating over the bottom of it.
 *
 * The bar takes no room away from the document: the pixel harness finds a page in a screenshot by
 * expecting it centred at its own size, and one laid out around a bar would not be.
 */
@Composable
private fun DemoPage(demo: Demo, onBack: () -> Unit) {
    var document by remember(demo) { mutableStateOf<RemoteComposeDocument?>(null) }
    // Where a `HOST_ACTION` lands. It used to turn the page, which made a document with a button
    // on it unusable — pressing the button left.
    var heard by remember(demo) { mutableStateOf<String?>(null) }
    if (demo.feed != null) {
        LaunchedEffect(document) { document?.let { demo.feed.invoke(it) } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (demo.oneToOne) {
            RealPayloadDemoScreen(demo.bytes)
        } else {
            RemoteComposeCanvas(
                bytes = demo.bytes,
                modifier = Modifier.fillMaxSize()
                    .let { if (demo.background != null) it.background(demo.background) else it },
                dark = if (demo.followsSystemTheme) isSystemInDarkTheme() else false,
                onDocument = { document = it },
                onAction = { action ->
                    heard = when (action) {
                        is RemoteAction.Click -> "host action ${action.actionId}"
                        is RemoteAction.Custom -> action.identifier
                    }
                },
            )
        }
        BackBar(
            label = heard ?: demo.name,
            onBack = onBack,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}

/** The way back, and which document is showing. */
@Composable
private fun BackBar(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE6202A2F))
            .clickable(onClick = onBack)
            .padding(end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // A tap target wide enough for a thumb, whatever the glyph inside measures.
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            BasicText(
                text = "‹",
                style = TextStyle(color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center),
            )
        }
        BasicText(
            text = label,
            style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
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
