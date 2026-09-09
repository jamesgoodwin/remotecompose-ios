package io.github.jamesgoodwin.remotecompose.engine

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import java.nio.ByteBuffer
import android.graphics.fonts.FontFamily as PlatformFontFamily

/**
 * The same three steps the official player takes: a `Font` over the bytes, a family of that one
 * font, and a typeface that falls back to `sans-serif` for anything the font has no glyph for.
 * All of that arrived in API 29, so anything older keeps the family the paint named instead.
 *
 * The API-29 half sits in its own method so that loading this one does not verify against classes
 * an older device has never heard of.
 */
internal actual fun embeddedFontFamily(bytes: ByteArray): FontFamily? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        FontFamily(platformTypeface(bytes))
    } catch (_: Exception) {
        null
    }
}

private fun platformTypeface(bytes: ByteArray): Typeface {
    val buffer = ByteBuffer.allocateDirect(bytes.size)
    buffer.put(bytes)
    buffer.rewind()
    val font = Font.Builder(buffer).build()
    return Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(font).build())
        .setSystemFallback("sans-serif")
        .build()
}
