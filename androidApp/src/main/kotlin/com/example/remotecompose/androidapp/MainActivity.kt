package com.example.remotecompose.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.remotecompose.demo.RealPayloadDemoScreen
import com.example.remotecompose.demo.SAMPLE_RC_BYTES

/**
 * Hosts the same [RealPayloadDemoScreen] + [SAMPLE_RC_BYTES] the iOS demo
 * (`MainViewController.kt`) uses, so the two platforms render byte-identical input through the
 * shared `OpcodeExecutor` — the point being a direct visual cross-check, not two different demos.
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
        setContent {
            RealPayloadDemoScreen(SAMPLE_RC_BYTES)
        }
    }
}
