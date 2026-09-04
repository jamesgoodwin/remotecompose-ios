package com.example.remotecompose.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.remotecompose.demo.RealPayloadDemoScreen
import com.example.remotecompose.demo.SAMPLE_RC_BYTES

/**
 * Hosts the same [RealPayloadDemoScreen] + [SAMPLE_RC_BYTES] the iOS demo
 * (`MainViewController.kt`) uses, so the two platforms render byte-identical input through the
 * shared `OpcodeExecutor` — the point being a direct visual cross-check, not two different demos.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RealPayloadDemoScreen(SAMPLE_RC_BYTES)
        }
    }
}
