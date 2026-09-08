package com.example.remotecompose.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.remotecompose.demo.DemoScreen

/**
 * Hosts the same [DemoScreen] the iOS demo (`MainViewController.kt`) uses, so the two platforms
 * render byte-identical input through the shared `OpcodeExecutor` — the point being a direct
 * visual cross-check, not two different demos.
 *
 * The pixel harness hides the system bars so that a screenshot compares the rendered content
 * directly, without a status or navigation bar over it on one platform and not the other. Nothing
 * else does: hidden bars come with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and that spends the
 * first swipe in from an edge on showing the bars rather than on going back.
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
        if (oneToOne) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        setContent {
            DemoScreen(initialDemo = initialDemo, oneToOne = oneToOne)
        }
    }
}
