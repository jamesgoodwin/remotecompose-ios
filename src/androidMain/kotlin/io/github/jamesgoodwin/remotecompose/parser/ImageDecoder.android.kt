package io.github.jamesgoodwin.remotecompose.parser

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Android actual: decodes via the platform's native `BitmapFactory`. */
internal actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("BitmapFactory could not decode ${bytes.size} byte(s) as an image")
    return bitmap.asImageBitmap()
}
