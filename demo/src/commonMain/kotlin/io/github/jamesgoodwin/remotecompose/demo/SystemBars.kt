package io.github.jamesgoodwin.remotecompose.demo

import androidx.compose.runtime.Composable

/**
 * Hides the status bar, and the navigation bars as well when [alsoNavigation].
 *
 * A document is meant to have the screen, so the status bar goes. The navigation bars stay:
 * hidden bars come with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, which spends the first swipe in
 * from an edge on showing them rather than on going back. Only the pixel harness asks for both,
 * so that a screenshot compares the rendered content without a bar over it on one platform and
 * not the other, and nothing navigates in that mode.
 *
 * This follows what is on screen rather than how the app was launched. An activity keeps the
 * intent it was started with for as long as it lives, so a flag read once in `onCreate` outlives
 * the page it was meant for — which is how the app came to draw everything the way the harness
 * had asked for one document.
 *
 * iOS hides its status bar through `UIStatusBarHidden` in `project.yml` and has no equivalent of
 * the rest; desktop has neither.
 */
@Composable
expect fun HideSystemBars(alsoNavigation: Boolean)
