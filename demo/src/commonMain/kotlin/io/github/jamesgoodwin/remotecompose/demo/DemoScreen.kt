package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jamesgoodwin.remotecompose.fixtures.ACTIONS_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ADVANCED_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ANIM_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.ARTICLE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.CAROUSEL_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.COFFEE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.FLIGHT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LAYOUT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LAZYLIST_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.LIST_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.MARQUEE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.MATERIAL_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.NOTCHES_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PAINT_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PARALLAX_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.PATTERN_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.REFERENCED_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.RUNACTION_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SAMPLE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.SHOWCASE_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.TEXTPATH_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.WATCH_RC_BYTES
import io.github.jamesgoodwin.remotecompose.fixtures.WRAP_RC_BYTES
import io.github.jamesgoodwin.remotecompose.model.RemoteAction
import io.github.jamesgoodwin.remotecompose.parser.RemoteComposeDocument
import io.github.jamesgoodwin.remotecompose.ui.RemoteComposeCanvas
import kotlinx.coroutines.delay

/**
 * One document in the demo: what to call it, what it is for, and how it wants to be shown.
 *
 * [listed] is false for a document that exists to be compared rather than looked at. It keeps its
 * place in the list so that the indices the hosts and the pixel harness use do not move, and it
 * is reachable by one, but nothing offers it.
 */
private class Demo(
    val id: String,
    val name: String,
    val about: String,
    val bytes: ByteArray,
    /**
     * The ground the document sits on, which the page fills the screen with.
     *
     * A document is scaled to fit rather than cropped, so on a screen of a different shape it
     * does not reach two of the edges. Painting the ground its own corner is painted in is what
     * makes the page look like a page rather than a card floating on whatever is behind it —
     * which is what a back gesture drags away and shows.
     */
    val background: Color,
    /** The same, in the dark, for a document that carries both palettes. */
    val backgroundDark: Color = background,
    val followsSystemTheme: Boolean = false,
    val listed: Boolean = true,
    val feed: (suspend (RemoteComposeDocument) -> Unit)? = null,
)

private val DEMOS = listOf(
    Demo("watch", "Watch", "Hands and a date from the clock alone, in two palettes", WATCH_RC_BYTES, background = Color(0xFFF7F2FA), backgroundDark = Color(0xFF121016), followsSystemTheme = true),
    Demo("carousel", "Carousel", "Cards that fling, shrinking and dimming away from the middle", CAROUSEL_RC_BYTES, background = Color(0xFF12101A)),
    Demo("coffee", "Coffee", "A shop: themed colours, a scrolling menu, rows that expand", COFFEE_RC_BYTES, background = Color(0xFFFDF8F3), backgroundDark = Color(0xFF1B1614), followsSystemTheme = true),
    Demo("flight", "Flight", "Every value fed by name from outside, and eased on the way in", FLIGHT_RC_BYTES, background = Color(0xFFFFFBFE), feed = ::runFlightFeed),
    Demo("lazylist", "Lazy list", "Five hundred rows, of which only the ones in view are built", LAZYLIST_RC_BYTES, background = Color(0xFF12101A)),
    Demo("material", "Material", "A Material screen: a stepper, a snackbar, real tokens", MATERIAL_RC_BYTES, background = Color(0xFFFEF7FF)),
    Demo("wrap", "Wrapping", "Line breaking, ellipsis and justification, and text that expands", WRAP_RC_BYTES, background = Color(0xFF12101A)),
    Demo("notches", "Snap", "The four ways a released scroll can be told where to stop", NOTCHES_RC_BYTES, background = Color(0xFF12101A)),
    Demo("layout", "Layout", "A document working out its own size, place and measurements", LAYOUT_RC_BYTES, background = Color(0xFF12101A)),
    Demo("parallax", "Parallax", "A photograph moving slower than the words over it", PARALLAX_RC_BYTES, background = Color(0xFF12101A)),
    Demo("advanced", "Generated", "Functions, path expressions, particles and matrices", ADVANCED_RC_BYTES, background = Color.White),
    Demo("article", "Article", "A progress bar the document works out from its own scroll", ARTICLE_RC_BYTES, background = Color(0xFFFFFBFE)),
    Demo("textpath", "Text paths", "Glyphs placed along a curve, one at a time", TEXTPATH_RC_BYTES, background = Color.White),
    Demo("runaction", "Run action", "Actions that run because a component was painted", RUNACTION_RC_BYTES, background = Color(0xFF12101A)),
    Demo("marquee", "Marquee", "Text too long for its box, slid rather than left clipped", MARQUEE_RC_BYTES, background = Color(0xFF12101A)),
    Demo("pattern", "Pattern", "A card written once and called three times, each with its own contents", PATTERN_RC_BYTES, background = Color.White),
    Demo("referenced", "Referenced", "One block of operations, drawn on three cards", REFERENCED_RC_BYTES, background = Color(0xFF12101A)),
    Demo("list", "List", "One row body over a list, with its bars read from a float list", LIST_RC_BYTES, background = Color.White),
    Demo("anim", "Anim", "Values that move with the clock, and the curves they move on", ANIM_RC_BYTES, background = Color.White),
    Demo("actions", "Actions", "Buttons that write the document's own values", ACTIONS_RC_BYTES, background = Color.White),
    Demo("paint", "Paint", "Every paint attribute: strokes, caps, joins, gradients", PAINT_RC_BYTES, background = Color.White),
    Demo("showcase", "Showcase", "The format's drawing and layout on one page", SHOWCASE_RC_BYTES, background = Color.White),
    // Not offered: one drawing per opcode, overlapping, which is a fixture for the golden test
    // and the pixel harness rather than anything to look at.
    Demo("sample", "Coverage", "One drawing per opcode", SAMPLE_RC_BYTES, background = Color.White, listed = false),
)

/**
 * The app entry point on both platforms (see `MainActivity.kt` / `ContentView.swift`): a list of
 * the documents, and one of them filling the screen once it is tapped.
 *
 * Nothing else navigates, and there is nothing drawn over a page to navigate by: the way back is
 * the platform's own, which on Android is the system gesture and on iOS the swipe in from the
 * left edge. Everything else on the screen belongs to the document, which is the only way an
 * interactive one is usable.
 *
 * @param initialDemo Opens straight onto the document of that name — the name of its fixture in
 *   `tools/rc-writer`, so `watch` is `watch.rc` — for scripted screenshots: Android reads an
 *   `--es demo <name>` intent extra, iOS an `RC_DEMO` environment variable. A name that is not
 *   one of them, which is what both hosts pass when nothing was asked for, shows the list.
 * @param oneToOne Draws the document at its own size in the middle of the screen instead of
 *   filling it. Only the pixel harness asks for this: it finds a document in a screenshot by
 *   expecting it unscaled, since comparing a resampled render to a resampled screenshot would
 *   compare the resampling. It applies to [initialDemo] and to nothing else — an activity keeps
 *   the intent it was started with, so a flag that applied to whatever came next would outlive
 *   the page it was meant for.
 */
@Composable
fun DemoScreen(initialDemo: String? = null, oneToOne: Boolean = false) {
    val launched = remember(initialDemo) { DEMOS.firstOrNull { it.id == initialDemo } }
    var open by remember { mutableStateOf(launched) }
    // One scroll position for the list, so the copy of it behind a page being dragged away is
    // where the real one is rather than back at the top.
    val listScroll = rememberScrollState()
    val demo = open
    val unscaled = oneToOne && demo != null && demo === launched
    HideSystemBars(alsoNavigation = unscaled)
    if (demo == null) {
        DemoList(scroll = listScroll, onOpen = { open = it })
    } else {
        BackGesture(enabled = true, onBack = { open = null }) { progress ->
            Box(modifier = Modifier.fillMaxSize()) {
                // What the gesture is going back to, so that going back is something you watch
                // rather than something that happens when you let go. Not tappable while it is
                // behind: the page over it is what the finger is on.
                if (progress > 0f) DemoList(scroll = listScroll, onOpen = {})
                DemoPage(
                    demo = demo,
                    oneToOne = oneToOne && demo === launched,
                    modifier = Modifier.graphicsLayer {
                        // The page draws back from the edge the gesture came from and shrinks a
                        // little, which is the shape of both platforms' own back.
                        translationX = size.width * 0.28f * progress
                        val shrink = 1f - 0.09f * progress
                        scaleX = shrink
                        scaleY = shrink
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        if (progress > 0f) {
                            clip = true
                            shape = RoundedCornerShape(28.dp * progress)
                            shadowElevation = 24.dp.toPx() * progress
                        }
                    },
                )
            }
        }
    }
}

/** The documents, by name and by what each is for. */
@Composable
private fun DemoList(scroll: ScrollState, onOpen: (Demo) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .verticalScroll(scroll)
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
        for (demo in DEMOS) {
            if (!demo.listed) continue
            DemoRow(demo, onClick = { onOpen(demo) })
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
 * One document, with the screen to itself: scaled up until it fits, and nothing drawn over it
 * except what a host action briefly says.
 */
@Composable
private fun DemoPage(demo: Demo, oneToOne: Boolean, modifier: Modifier = Modifier) {
    var document by remember(demo) { mutableStateOf<RemoteComposeDocument?>(null) }
    // Where a `HOST_ACTION` lands, and only for as long as it takes to read: it used to turn the
    // page, which made a document with a button on it unusable — pressing the button left.
    var heard by remember(demo) { mutableStateOf<String?>(null) }
    LaunchedEffect(heard) {
        if (heard != null) {
            delay(2200)
            heard = null
        }
    }
    if (demo.feed != null) {
        LaunchedEffect(document) { document?.let { demo.feed.invoke(it) } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (oneToOne) {
            RealPayloadDemoScreen(demo.bytes)
        } else {
            val dark = demo.followsSystemTheme && isSystemInDarkTheme()
            RemoteComposeCanvas(
                bytes = demo.bytes,
                modifier = Modifier.fillMaxSize()
                    .background(if (dark) demo.backgroundDark else demo.background),
                dark = dark,
                onDocument = { document = it },
                onAction = { action ->
                    heard = when (action) {
                        is RemoteAction.Click -> "host action ${action.actionId}"
                        is RemoteAction.Custom -> action.identifier
                    }
                },
            )
        }
        heard?.let { label ->
            BasicText(
                text = label,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6202A2F))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
        }
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
