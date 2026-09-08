package io.github.jamesgoodwin.remotecompose.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** iOS actual: decodes via Skia (Skiko), which backs Compose Multiplatform's iOS renderer. */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap =
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
