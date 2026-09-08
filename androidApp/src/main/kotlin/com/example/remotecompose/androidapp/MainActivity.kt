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
 * System bars are hidden so cross-platform screenshots compare the rendered content directly,
 * without a status bar/nav bar overlapping it on one platform but not the other.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // `adb shell am start -n <package>/.MainActivity --ei page N` launches straight onto a
        // payload page for scripted screenshots; see DemoScreen. Without it the app opens on the
        // list, which is what -1 asks for.
        val initialPage = intent.getIntExtra("page", -1)
        setContent {
            DemoScreen(initialPage = initialPage)
        }
    }
}
