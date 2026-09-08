package io.github.jamesgoodwin.remotecompose.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import io.github.jamesgoodwin.remotecompose.demo.DemoScreen

/**
 * Hosts the same [DemoScreen] the iOS demo (`MainViewController.kt`) uses, so the two platforms
 * render byte-identical input through the shared `OpcodeExecutor` — the point being a direct
 * visual cross-check, not two different demos.
 *
 * Which system bars are hidden is [HideSystemBars]'s to decide, from what is on screen rather
 * than from the intent this was started with — an activity keeps that intent for as long as it
 * lives, so a flag read once here outlives the page it was meant for.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // `adb shell am start -n <package>/.MainActivity --es demo watch` launches straight onto
        // a payload for scripted screenshots; see DemoScreen. The name is the fixture's, so it
        // survives the demo list being reordered. Without it the app opens on the list.
        // `--ez oneToOne true` draws that page unscaled, which is what the pixel harness
        // compares against.
        val initialDemo = intent.getStringExtra("demo")
        val oneToOne = intent.getBooleanExtra("oneToOne", false)
        setContent {
            DemoScreen(initialDemo = initialDemo, oneToOne = oneToOne)
        }
    }
}
