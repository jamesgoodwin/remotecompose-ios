package io.github.jamesgoodwin.remotecompose.engine

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/** Skia reads the font file itself, on iOS and on desktop alike. */
internal actual fun embeddedFontFamily(bytes: ByteArray): FontFamily? = try {
    val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
    typeface?.let { FontFamily(Typeface(it)) }
} catch (_: Exception) {
    null
}
